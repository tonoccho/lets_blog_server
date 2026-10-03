package com.letsblog.logwriter.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.logwriter.controller.AuditLogController;
import com.letsblog.logwriter.controller.FrontendErrorLogController;
import com.letsblog.logwriter.controller.OperationLogController;
import com.letsblog.logwriter.domain.AuditLog;
import com.letsblog.logwriter.domain.FrontendErrorLog;
import com.letsblog.logwriter.domain.OperationLog;
import com.letsblog.logwriter.repository.AuditLogRepository;
import com.letsblog.logwriter.repository.OperationLogRepository;
import com.letsblog.logwriter.service.AdminAuthorizationService;
import com.letsblog.logwriter.service.AuditLogService;
import com.letsblog.logwriter.service.CurrentActorService;
import com.letsblog.logwriter.service.FrontendErrorLogService;
import com.letsblog.logwriter.service.OperationLogService;
import com.letsblog.logwriter.service.UnifiedOperationLogService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * issue #1538: openapi/log-writer.json が format: date-time(RFC 3339、オフセット必須)と宣言する
 * 公開レスポンスの日時が、UTC の Z 終端で出力されること。
 *
 * <p>API の文字列形式は画面から観測できないため、Gherkin ではなくサービスレベルの
 * シリアライズテストで表現する。コントローラ / サービスの既存の組み立て経路を通して作った
 * 戻り値を、Spring MVC の既定コンバータと同じ Jackson 3 の JsonMapper で JSON にする。
 * DB / エンティティは UTC の壁時計 LocalDateTime のまま、DTO への変換時に UTC を付けるだけ。
 */
class ResponseDateTimeSerializationTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 9, 8, 20, 3, 35);
    private static final LocalDateTime TIMESTAMP = LocalDateTime.of(2026, 9, 9, 1, 2, 3);
    private static final String CREATED_Z = "2026-09-08T20:03:35Z";
    private static final String TIMESTAMP_Z = "2026-09-09T01:02:03Z";
    private static final Pageable PAGE = PageRequest.of(0, 20);

    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode json(Object value) {
        return mapper.valueToTree(value);
    }

    private static AuditLog auditLog(LocalDateTime createdAt) {
        AuditLog log = new AuditLog();
        log.setId(1L);
        log.setUserId(2L);
        log.setActorKeycloakSub("sub-1");
        log.setAction("LOGIN");
        log.setResourceType("SITE");
        log.setResourceId(3L);
        log.setChanges("{}");
        log.setRemoteIp("127.0.0.1");
        log.setUserAgent("ua");
        log.setCreatedAt(createdAt);
        return log;
    }

    private static OperationLog operationLog(LocalDateTime createdAt) {
        OperationLog log = new OperationLog();
        log.setId(4L);
        log.setOperationId("op-1");
        log.setUserId(2L);
        log.setActorKeycloakSub("sub-1");
        log.setMethod("GET");
        log.setPath("/api/sites");
        log.setStatusCode(200);
        log.setDurationMs(5L);
        log.setSuccess(true);
        log.setErrorMessage("e");
        log.setCreatedAt(createdAt);
        return log;
    }

    private static FrontendErrorLog errorLog(LocalDateTime timestamp, LocalDateTime createdAt) {
        FrontendErrorLog log = new FrontendErrorLog();
        log.setId(5L);
        log.setMessage("boom");
        log.setLevel("ERROR");
        log.setUrl("https://example.com");
        log.setTimestamp(timestamp);
        log.setCreatedAt(createdAt);
        return log;
    }

    private AuditLogController auditController(AuditLogService service) {
        return new AuditLogController(service, mock(AdminAuthorizationService.class));
    }

    @Test
    void auditLog_一覧はZ終端のRFC3339で返りPage構造とフィールド名は変わらない() {
        AuditLogService service = mock(AuditLogService.class);
        when(service.findAll(PAGE)).thenReturn(new PageImpl<>(List.of(auditLog(CREATED)), PAGE, 1));

        JsonNode page = json(auditController(service).list(null, null, null, null, PAGE));

        assertEquals(1, page.get("totalElements").asInt());
        JsonNode row = page.get("content").get(0);
        assertEquals(CREATED_Z, row.get("createdAt").asString());
        assertEquals("LOGIN", row.get("action").asString());
        assertEquals("sub-1", row.get("actorKeycloakSub").asString());
        assertEquals(3, row.get("resourceId").asInt());
        assertEquals("127.0.0.1", row.get("remoteIp").asString());
    }

    @Test
    void auditLog_日時がnullならnullのまま返る() {
        AuditLogService service = mock(AuditLogService.class);
        when(service.findAll(PAGE)).thenReturn(new PageImpl<>(List.of(auditLog(null)), PAGE, 1));

        JsonNode row = json(auditController(service).list(null, null, null, null, PAGE)).get("content").get(0);

        assertTrue(row.get("createdAt").isNull());
    }

    @Test
    void operationLog_一覧と経路追跡はZ終端のRFC3339で返る() {
        OperationLogService service = mock(OperationLogService.class);
        CurrentActorService actor = mock(CurrentActorService.class);
        when(actor.getCurrentActorId()).thenReturn(2L);
        when(service.findByUser(2L, PAGE)).thenReturn(new PageImpl<>(List.of(operationLog(CREATED)), PAGE, 1));
        when(service.findTrace(2L, "op-1")).thenReturn(List.of(operationLog(CREATED)));
        OperationLogController controller =
                new OperationLogController(service, mock(UnifiedOperationLogService.class), actor);

        JsonNode listRow = json(controller.list(PAGE)).get("content").get(0);
        JsonNode traceRow = json(controller.trace("op-1")).get(0);

        for (JsonNode row : List.of(listRow, traceRow)) {
            assertEquals(CREATED_Z, row.get("createdAt").asString());
            assertEquals("op-1", row.get("operationId").asString());
            assertEquals(5, row.get("durationMs").asInt());
            assertTrue(row.get("success").asBoolean());
        }
    }

    @Test
    void frontendErrorLog_はtimestampもcreatedAtもZ終端のRFC3339で返る() {
        FrontendErrorLogService service = mock(FrontendErrorLogService.class);
        when(service.findAll(PAGE)).thenReturn(
                new PageImpl<>(List.of(errorLog(TIMESTAMP, CREATED)), PAGE, 1));
        FrontendErrorLogController controller =
                new FrontendErrorLogController(service, mock(AdminAuthorizationService.class));

        JsonNode row = json(controller.getErrors(null, null, null, null, PAGE)).get("content").get(0);

        assertEquals(TIMESTAMP_Z, row.get("timestamp").asString());
        assertEquals(CREATED_Z, row.get("createdAt").asString());
        assertEquals("boom", row.get("message").asString());
        assertEquals("ERROR", row.get("level").asString());
    }

    @Test
    void unifiedLogEntry_は3ソースともZ終端のRFC3339で返る() {
        OperationLogRepository operations = mock(OperationLogRepository.class);
        GenerationJobClient jobs = mock(GenerationJobClient.class);
        AuditLogRepository audits = mock(AuditLogRepository.class);
        when(operations.findByUserIdOrderByCreatedAtDesc(eq(10L), any()))
                .thenReturn(new PageImpl<>(List.of(operationLog(CREATED))));
        when(jobs.listRecent("Bearer t")).thenReturn(
                List.of(new GenerationJobSummary(7L, "draft", "done", CREATED, null)));
        when(audits.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(new PageImpl<>(List.of(auditLog(CREATED))));

        List<UnifiedLogEntryResponse> entries = new UnifiedOperationLogService(operations, jobs, audits)
                .list(10L, true, null, null, null, null, PAGE, "Bearer t").getContent();

        assertEquals(3, entries.size());
        for (UnifiedLogEntryResponse entry : entries) {
            JsonNode node = json(entry);
            assertEquals(CREATED_Z, node.get("createdAt").asString());
            assertTrue(node.has("sourceType"));
            assertTrue(node.has("actorKeycloakSub"));
        }
    }

    @Test
    void 全ての絞り込み経路でもZ終端のRFC3339で返る() {
        AuditLogService audits = mock(AuditLogService.class);
        FrontendErrorLogService errors = mock(FrontendErrorLogService.class);
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 12, 31, 0, 0);
        when(audits.findByUserId(2L, PAGE)).thenReturn(new PageImpl<>(List.of(auditLog(CREATED)), PAGE, 1));
        when(audits.findByAction("LOGIN", PAGE)).thenReturn(new PageImpl<>(List.of(auditLog(CREATED)), PAGE, 1));
        when(audits.findByDateRange(from, to, PAGE)).thenReturn(new PageImpl<>(List.of(auditLog(CREATED)), PAGE, 1));
        when(errors.findByLevel("ERROR", PAGE)).thenReturn(new PageImpl<>(List.of(errorLog(TIMESTAMP, CREATED)), PAGE, 1));
        when(errors.findByDateRange(from, to, PAGE)).thenReturn(new PageImpl<>(List.of(errorLog(TIMESTAMP, CREATED)), PAGE, 1));
        when(errors.findByUrl("u", PAGE)).thenReturn(new PageImpl<>(List.of(errorLog(TIMESTAMP, CREATED)), PAGE, 1));
        AuditLogController auditController = auditController(audits);
        FrontendErrorLogController errorController =
                new FrontendErrorLogController(errors, mock(AdminAuthorizationService.class));

        for (Object page : List.of(
                auditController.list(2L, null, null, null, PAGE),
                auditController.list(null, "LOGIN", null, null, PAGE),
                auditController.list(null, null, from, to, PAGE))) {
            assertEquals(CREATED_Z, json(page).get("content").get(0).get("createdAt").asString());
        }
        for (Object page : List.of(
                errorController.getErrors("error", null, null, null, PAGE),
                errorController.getErrors(null, from, to, null, PAGE),
                errorController.getErrors(null, null, null, "u", PAGE))) {
            assertEquals(TIMESTAMP_Z, json(page).get("content").get(0).get("timestamp").asString());
        }
    }

    @Test
    void operationLog_adminの経路追跡もZ終端のRFC3339で返る() {
        OperationLogService service = mock(OperationLogService.class);
        CurrentActorService actor = mock(CurrentActorService.class);
        when(actor.getCurrentActorId()).thenReturn(2L);
        when(actor.isAdmin()).thenReturn(true);
        when(service.findTraceAsAdmin("op-1")).thenReturn(List.of(operationLog(CREATED)));
        OperationLogController controller =
                new OperationLogController(service, mock(UnifiedOperationLogService.class), actor);

        assertEquals(CREATED_Z, json(controller.trace("op-1")).get(0).get("createdAt").asString());
    }
}
