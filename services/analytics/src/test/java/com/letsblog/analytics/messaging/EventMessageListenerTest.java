package com.letsblog.analytics.messaging;

import com.letsblog.analytics.domain.ProcessedEvent;
import com.letsblog.analytics.repository.AnalyticsCredentialsRepository;
import com.letsblog.analytics.repository.ProcessedEventRepository;
import com.letsblog.common.messaging.ProjectDeletedEvent;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link EventMessageListener}の冪等性(issue #580)を検証する。content-serviceの
 * EventMessageListenerTestと同じ方針(ProcessedEventRepositoryをHashSetでフェイクし、
 * リスナーメソッドを同一イベントで複数回直接呼び出してRabbitMQ再配送を模す)。
 */
@ExtendWith(MockitoExtension.class)
class EventMessageListenerTest {

    @Mock
    private AnalyticsCredentialsRepository analyticsCredentialsRepository;

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
        listener = new EventMessageListener(processedEventStore, analyticsCredentialsRepository);
    }

    @Test
    void onProjectDeleted_同一eventIdの重複配信ではdeleteByProjectIdが1回しか呼ばれない() {
        ProjectDeletedEvent event = new ProjectDeletedEvent("evt-project-deleted-1", Instant.now(), 42L);

        listener.onProjectDeleted(event);
        listener.onProjectDeleted(event);
        listener.onProjectDeleted(event);

        verify(analyticsCredentialsRepository, times(1)).deleteByProjectId(42L);
    }

    @Test
    void onProjectDeleted_eventIdが異なれば別イベントとして処理される() {
        listener.onProjectDeleted(new ProjectDeletedEvent("evt-a", Instant.now(), 1L));
        listener.onProjectDeleted(new ProjectDeletedEvent("evt-b", Instant.now(), 2L));

        verify(analyticsCredentialsRepository).deleteByProjectId(1L);
        verify(analyticsCredentialsRepository).deleteByProjectId(2L);
    }
}
