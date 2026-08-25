package com.letsblog.content.messaging;

import com.letsblog.content.domain.ProcessedEvent;
import com.letsblog.content.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessedEventStoreImplTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Test
    void markIfNotProcessed_未処理ならtrueを返しevent_idを保存する() {
        when(processedEventRepository.existsById("evt-1")).thenReturn(false);
        ProcessedEventStoreImpl store = new ProcessedEventStoreImpl(processedEventRepository);

        boolean result = store.markIfNotProcessed("evt-1", "project.deleted");

        assertTrue(result);
        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(captor.capture());
        assertEquals("evt-1", captor.getValue().getEventId());
        assertEquals("project.deleted", captor.getValue().getEventType());
    }

    @Test
    void markIfNotProcessed_処理済みならfalseを返しsaveしない() {
        when(processedEventRepository.existsById("evt-2")).thenReturn(true);
        ProcessedEventStoreImpl store = new ProcessedEventStoreImpl(processedEventRepository);

        boolean result = store.markIfNotProcessed("evt-2", "project.deleted");

        assertFalse(result);
        verify(processedEventRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
