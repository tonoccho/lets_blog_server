package com.letsblog.project.messaging;

import com.letsblog.common.messaging.EventExchanges;
import com.letsblog.common.messaging.ProjectEnvironmentBoundEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** issue #1324: project.environment-boundの発行。 */
@ExtendWith(MockitoExtension.class)
class DomainEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Test
    void publishProjectEnvironmentBound_project_environment_boundのroutingKeyでprojectIdとsiteIdを発行する() {
        new DomainEventPublisher(rabbitTemplate).publishProjectEnvironmentBound(7L, 30L);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(
                eq(EventExchanges.EVENTS_EXCHANGE),
                eq(EventExchanges.PROJECT_ENVIRONMENT_BOUND_ROUTING_KEY),
                payload.capture());
        ProjectEnvironmentBoundEvent event = (ProjectEnvironmentBoundEvent) payload.getValue();
        assertEquals(7L, event.projectId());
        assertEquals(30L, event.siteId());
        assertNotNull(event.eventId());
        assertNotNull(event.occurredAt());
    }

    @Test
    void publishProjectEnvironmentBound_ブローカーに繋がらなくても例外を投げない() {
        doThrow(new AmqpConnectException(new RuntimeException("down")))
                .when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));

        assertDoesNotThrow(() -> new DomainEventPublisher(rabbitTemplate).publishProjectEnvironmentBound(7L, 30L));
    }

    // コミット前に発行すると、identity-serviceが紐付け前のproject-serviceの状態を読んで補填に失敗する。
    // トランザクション内で呼ばれたときは、コミット後にだけ発行する。

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishProjectEnvironmentBound_トランザクション内ではコミットされるまで発行しない() {
        TransactionSynchronizationManager.initSynchronization();

        new DomainEventPublisher(rabbitTemplate).publishProjectEnvironmentBound(7L, 30L);

        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void publishProjectEnvironmentBound_コミット後に発行する() {
        TransactionSynchronizationManager.initSynchronization();
        new DomainEventPublisher(rabbitTemplate).publishProjectEnvironmentBound(7L, 30L);

        List<TransactionSynchronization> synchronizations =
                new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        synchronizations.forEach(TransactionSynchronization::afterCommit);

        verify(rabbitTemplate).convertAndSend(
                eq(EventExchanges.EVENTS_EXCHANGE),
                eq(EventExchanges.PROJECT_ENVIRONMENT_BOUND_ROUTING_KEY),
                any(Object.class));
    }

    @Test
    void publishProjectEnvironmentBound_ロールバックされたら発行しない() {
        TransactionSynchronizationManager.initSynchronization();
        new DomainEventPublisher(rabbitTemplate).publishProjectEnvironmentBound(7L, 30L);

        List<TransactionSynchronization> synchronizations =
                new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }
}
