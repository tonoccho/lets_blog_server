package com.letsblog.content.messaging;

import com.letsblog.common.messaging.ImageGeneratedEvent;
import com.letsblog.common.messaging.PostDeletedEvent;
import com.letsblog.common.messaging.PostPublishedEvent;
import com.letsblog.common.messaging.ProjectDeletedEvent;
import com.letsblog.common.messaging.SiteDeletedEvent;
import com.letsblog.common.messaging.UserDeactivatedEvent;
import com.letsblog.content.domain.ProcessedEvent;
import com.letsblog.content.repository.PostRepository;
import com.letsblog.content.repository.ProcessedEventRepository;
import com.letsblog.content.repository.ProjectContentSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link EventMessageListener}の冪等性(issue #580)を、RabbitMQ再配送を模してリスナーメソッドを
 * 同一イベントで2回直接呼び出すことで検証する。{@link ProcessedEventRepository}をHashSetで
 * フェイクした実物の{@link ProcessedEventStoreImpl}を使うことで、DB/ブローカーなしに
 * IdempotentEventHandler→ProcessedEventStoreImpl→ビジネスロジックの結線全体を確認する
 * (log-writerのLogMessageListenerTestと同じ、Mockitoのみで完結するユニットテストの方針)。
 */
@ExtendWith(MockitoExtension.class)
class EventMessageListenerTest {

    @Mock
    private ProjectContentSettingsRepository projectContentSettingsRepository;

    @Mock
    private PostRepository postRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    private EventMessageListener listener;

    @BeforeEach
    void setUp() {
        Set<String> processedEventIds = new HashSet<>();
        lenient().when(processedEventRepository.existsById(anyString()))
                .thenAnswer(invocation -> processedEventIds.contains((String) invocation.getArgument(0)));
        lenient().when(processedEventRepository.save(any(ProcessedEvent.class))).thenAnswer(invocation -> {
            ProcessedEvent saved = invocation.getArgument(0);
            processedEventIds.add(saved.getEventId());
            return saved;
        });

        ProcessedEventStoreImpl processedEventStore = new ProcessedEventStoreImpl(processedEventRepository);
        listener = new EventMessageListener(processedEventStore, projectContentSettingsRepository, postRepository);
    }

    @Test
    void onProjectDeleted_同一eventIdの重複配信ではdeleteByProjectIdが1回しか呼ばれない() {
        ProjectDeletedEvent event = new ProjectDeletedEvent("evt-project-deleted-1", Instant.now(), 42L);

        listener.onProjectDeleted(event);
        // RabbitMQの再配送(at-least-once)を模し、同じイベントをもう一度届ける。
        listener.onProjectDeleted(event);

        verify(projectContentSettingsRepository, times(1)).deleteByProjectId(42L);
    }

    @Test
    void onProjectDeleted_eventIdが異なれば別イベントとして処理される() {
        listener.onProjectDeleted(new ProjectDeletedEvent("evt-a", Instant.now(), 1L));
        listener.onProjectDeleted(new ProjectDeletedEvent("evt-b", Instant.now(), 2L));

        verify(projectContentSettingsRepository).deleteByProjectId(1L);
        verify(projectContentSettingsRepository).deleteByProjectId(2L);
    }

    @Test
    void onSiteDeleted_同一eventIdの重複配信ではdeleteBySiteIdが1回しか呼ばれない() {
        SiteDeletedEvent event = new SiteDeletedEvent("evt-site-deleted-1", Instant.now(), 7L);

        listener.onSiteDeleted(event);
        listener.onSiteDeleted(event);
        listener.onSiteDeleted(event);

        verify(postRepository, times(1)).deleteBySiteId(7L);
    }

    @Test
    void onPostPublished_重複配信でも例外にならず処理済み記録が1件だけ残る() {
        PostPublishedEvent event = new PostPublishedEvent(
                "evt-post-published-1", Instant.now(), 1L, 10L, "99", "https://example.com/?p=99", "publish");

        listener.onPostPublished(event);
        listener.onPostPublished(event);

        verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
    }

    @Test
    void onPostDeleted_重複配信でも例外にならない() {
        PostDeletedEvent event = new PostDeletedEvent("evt-post-deleted-1", Instant.now(), 1L, "99");

        listener.onPostDeleted(event);
        listener.onPostDeleted(event);

        verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
    }

    @Test
    void onImageGenerated_重複配信でも例外にならない() {
        ImageGeneratedEvent event = new ImageGeneratedEvent(
                "evt-image-generated-1", Instant.now(), 5L, 10L, "/api/generated-images/5/file");

        listener.onImageGenerated(event);
        listener.onImageGenerated(event);

        verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
    }

    @Test
    void onUserDeactivated_重複配信でも例外にならない() {
        UserDeactivatedEvent event = new UserDeactivatedEvent("evt-user-deactivated-1", Instant.now(), 3L, "kc-sub");

        listener.onUserDeactivated(event);
        listener.onUserDeactivated(event);

        verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
    }

    @Test
    void onProjectDeleted_未処理のeventIdでは1回だけ実行される() {
        listener.onProjectDeleted(new ProjectDeletedEvent("evt-fresh", Instant.now(), 99L));

        verify(projectContentSettingsRepository).deleteByProjectId(99L);
        verify(postRepository, never()).deleteBySiteId(any());
    }
}
