package com.letsblog.ai.messaging;

import com.letsblog.ai.domain.ProcessedEvent;
import com.letsblog.ai.repository.ProcessedEventRepository;
import com.letsblog.ai.repository.ProjectAiSettingsRepository;
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
    private ProjectAiSettingsRepository projectAiSettingsRepository;

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
        listener = new EventMessageListener(processedEventStore, projectAiSettingsRepository);
    }

    @Test
    void onProjectDeleted_同一eventIdの重複配信ではdeleteByProjectIdが1回しか呼ばれない() {
        ProjectDeletedEvent event = new ProjectDeletedEvent("evt-project-deleted-1", Instant.now(), 42L);

        listener.onProjectDeleted(event);
        listener.onProjectDeleted(event);
        listener.onProjectDeleted(event);

        verify(projectAiSettingsRepository, times(1)).deleteByProjectId(42L);
    }

    @Test
    void onProjectDeleted_eventIdが異なれば別イベントとして処理される() {
        listener.onProjectDeleted(new ProjectDeletedEvent("evt-a", Instant.now(), 1L));
        listener.onProjectDeleted(new ProjectDeletedEvent("evt-b", Instant.now(), 2L));

        verify(projectAiSettingsRepository).deleteByProjectId(1L);
        verify(projectAiSettingsRepository).deleteByProjectId(2L);
    }
}
