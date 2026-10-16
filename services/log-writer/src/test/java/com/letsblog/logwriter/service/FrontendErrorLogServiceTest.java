package com.letsblog.logwriter.service;

import com.letsblog.common.messaging.ErrorLogMessage;
import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.logwriter.domain.FrontendErrorLog;
import com.letsblog.logwriter.testsupport.LogWriterIntegrationTestBase;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * FrontendErrorLogService(issue #1829で書き直し)。CurrentActorService・リポジトリは実物(実 MySQL)で、
 * 外部との通信は RabbitMQ への発行({@code RabbitTemplate})と identity-service(HTTP のフェイク)だけ。
 */
class FrontendErrorLogServiceTest extends LogWriterIntegrationTestBase {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 1, 12, 0);

    @Autowired
    private FrontendErrorLogService service;

    private FrontendErrorLog errorLog() {
        FrontendErrorLog errorLog = new FrontendErrorLog();
        errorLog.setMessage("boom");
        errorLog.setLevel("ERROR");
        errorLog.setUrl("https://example.com/posts");
        errorLog.setTimestamp(AT);
        return errorLog;
    }

    private ErrorLogMessage publishedMessage() {
        ArgumentCaptor<ErrorLogMessage> captor = ArgumentCaptor.forClass(ErrorLogMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(LogExchanges.LOG_EXCHANGE), eq(LogExchanges.ERROR_LOG_ROUTING_KEY), captor.capture());
        return captor.getValue();
    }

    private void brokerDown() {
        doThrow(new AmqpException("キュー接続エラー"))
                .when(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void logError_キューへ発行できればDBへは直接書き込まない() {
        asBearer(null);

        service.logError(errorLog());

        ErrorLogMessage message = publishedMessage();
        assertEquals("boom", message.message());
        assertEquals("ERROR", message.level());
        assertNotNull(message.createdAt());
        assertEquals(AT.toString(), message.timestamp());
        assertNull(message.userId());
        assertNull(message.actorKeycloakSub());
        assertEquals(0, frontendErrorLogRepository.count());
    }

    @Test
    void logError_timestampが無ければメッセージのtimestampもnull() {
        asBearer(null);
        FrontendErrorLog errorLog = errorLog();
        errorLog.setTimestamp(null);

        service.logError(errorLog);

        assertNull(publishedMessage().timestamp());
    }

    @Test
    void logError_作成日時が設定済みならそのまま使う() {
        asBearer(null);
        FrontendErrorLog errorLog = errorLog();
        errorLog.setCreatedAt(AT.minusDays(1));

        service.logError(errorLog);

        assertEquals(AT.minusDays(1).toString(), publishedMessage().createdAt());
    }

    @Test
    void logError_actorが解決できればuserIdとactorKeycloakSubを設定する() {
        actor("tok", 7L, "editor");
        asBearer("tok");
        authenticateAs("keycloak-sub-1");

        service.logError(errorLog());

        ErrorLogMessage message = publishedMessage();
        assertEquals(7L, message.userId());
        assertEquals("keycloak-sub-1", message.actorKeycloakSub());
    }

    @Test
    void logError_identityServiceが落ちていてもuserIdがnullのまま発行する() {
        HTTP.status("GET", "/api/identity/me", 404, "{}");
        asBearer("tok");
        authenticateAs("keycloak-sub-1");

        service.logError(errorLog());

        ErrorLogMessage message = publishedMessage();
        assertNull(message.userId());
        assertEquals("keycloak-sub-1", message.actorKeycloakSub());
    }

    @Test
    void logError_キュー発行に失敗したら同期DB書き込みへフォールバックする() {
        actor("tok", 7L, "editor");
        asBearer("tok");
        authenticateAs("keycloak-sub-1");
        brokerDown();

        service.logError(errorLog());

        List<FrontendErrorLog> saved = frontendErrorLogRepository.findAll();
        assertEquals(1, saved.size());
        assertEquals("boom", saved.get(0).getMessage());
        assertEquals(7L, saved.get(0).getUserId());
        assertEquals("keycloak-sub-1", saved.get(0).getActorKeycloakSub());
        assertNotNull(saved.get(0).getCreatedAt());
    }

    @Test
    void 読み取り_全件_レベル_期間_URLで絞れる() {
        saveError("e1", "ERROR", "https://a.example", AT);
        saveError("e2", "WARN", "https://b.example", AT.plusDays(1));
        saveError("e3", "ERROR", "https://b.example", AT.plusDays(2));
        PageRequest page = PageRequest.of(0, 20);

        assertEquals(3, service.findAll(page).getTotalElements());
        assertEquals(2, service.findByLevel("ERROR", page).getTotalElements());
        assertEquals(List.of("e2"), service.findByDateRange(AT.plusHours(1), AT.plusDays(1).plusHours(1), page)
                .map(FrontendErrorLog::getMessage).getContent());
        assertEquals(2, service.findByUrl("https://b.example", page).getTotalElements());
    }
}
