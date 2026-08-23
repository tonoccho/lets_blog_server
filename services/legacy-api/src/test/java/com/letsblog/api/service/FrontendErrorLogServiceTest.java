package com.letsblog.api.service;

import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.api.domain.FrontendErrorLog;
import com.letsblog.common.messaging.ErrorLogMessage;
import com.letsblog.api.repository.FrontendErrorLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FrontendErrorLogServiceTest {

    @Mock
    private FrontendErrorLogRepository repository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private CurrentActorService currentActorService;

    private FrontendErrorLogService service;

    private FrontendErrorLog errorLog() {
        FrontendErrorLog errorLog = new FrontendErrorLog();
        errorLog.setMessage("boom");
        errorLog.setLevel(FrontendErrorLog.ErrorLevel.ERROR);
        errorLog.setUrl("https://example.com/posts");
        return errorLog;
    }

    @Test
    void logError_キューへ発行できればDBへは直接書き込まない() {
        // CurrentActorServiceのMockitoスタブ無しデフォルト応答はLong等のラッパー型では0を返す
        // (nullではない)ため、actorなしのケースを検証するには明示的にnullをスタブする。
        service = new FrontendErrorLogService(repository, rabbitTemplate, currentActorService);
        when(currentActorService.getCurrentActorId()).thenReturn(null);
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn(null);

        service.logError(errorLog());

        ArgumentCaptor<ErrorLogMessage> captor = ArgumentCaptor.forClass(ErrorLogMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(LogExchanges.LOG_EXCHANGE), eq(LogExchanges.ERROR_LOG_ROUTING_KEY), captor.capture());
        ErrorLogMessage message = captor.getValue();
        assertEquals("boom", message.message());
        assertEquals("ERROR", message.level());
        assertEquals("https://example.com/posts", message.url());
        assertNotNull(message.createdAt());
        assertNull(message.userId());
        assertNull(message.actorKeycloakSub());
        verify(repository, never()).save(any());
    }

    @Test
    void logError_JWT由来のactorが解決できればuserIdとactorKeycloakSubを設定する() {
        service = new FrontendErrorLogService(repository, rabbitTemplate, currentActorService);
        lenient().when(currentActorService.getCurrentActorId()).thenReturn(7L);
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("keycloak-sub-1");

        service.logError(errorLog());

        ArgumentCaptor<ErrorLogMessage> captor = ArgumentCaptor.forClass(ErrorLogMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(LogExchanges.LOG_EXCHANGE), eq(LogExchanges.ERROR_LOG_ROUTING_KEY), captor.capture());
        ErrorLogMessage message = captor.getValue();
        assertEquals(7L, message.userId());
        assertEquals("keycloak-sub-1", message.actorKeycloakSub());
    }

    @Test
    void logError_キュー発行に失敗したら同期DB書き込みへフォールバックする() {
        service = new FrontendErrorLogService(repository, rabbitTemplate, currentActorService);
        when(currentActorService.getCurrentActorId()).thenReturn(null);
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn(null);
        doThrow(new AmqpException("キュー接続エラー"))
                .when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));

        service.logError(errorLog());

        ArgumentCaptor<FrontendErrorLog> captor = ArgumentCaptor.forClass(FrontendErrorLog.class);
        verify(repository, times(1)).save(captor.capture());
        FrontendErrorLog saved = captor.getValue();
        assertEquals("boom", saved.getMessage());
        assertEquals(FrontendErrorLog.ErrorLevel.ERROR, saved.getLevel());
        assertNotNull(saved.getCreatedAt());
        assertNull(saved.getUserId());
        assertNull(saved.getActorKeycloakSub());
    }
}
