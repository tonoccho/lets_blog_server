package com.letsblog.logwriter.dto;

import com.letsblog.logwriter.domain.AuditLog;
import com.letsblog.logwriter.testsupport.LogWriterIntegrationTestBase;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * issue #1538: openapi/log-writer.json が format: date-time(RFC 3339、オフセット必須)と宣言する
 * 公開レスポンスの日時が、UTC の Z 終端で出力されること。
 *
 * <p>API の文字列形式は画面から観測できないため、Gherkin ではなくサービスレベルのシリアライズテストで表現する。
 * issue #1829で、サービス・コントローラのモックをやめ、実 MySQL に入れた行を実物のコントローラ・サービスから
 * MockMvc 越しに読み出し、実際の HTTP 応答の JSON を検証する形にした。DB / エンティティは UTC の壁時計
 * LocalDateTime のまま、DTO への変換時に UTC を付けるだけ。
 */
class ResponseDateTimeSerializationTest extends LogWriterIntegrationTestBase {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 9, 8, 20, 3, 35);
    private static final LocalDateTime TIMESTAMP = LocalDateTime.of(2026, 9, 9, 1, 2, 3);
    private static final String CREATED_Z = "2026-09-08T20:03:35Z";
    private static final String TIMESTAMP_Z = "2026-09-09T01:02:03Z";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void users() {
        login("admin", "sub-admin", 2L, "admin");
        HTTP.json("GET", "/api/generation-jobs",
                "[{\"id\":7,\"type\":\"draft\",\"status\":\"done\",\"createdAt\":\"2026-09-08T20:03:35\",\"updatedAt\":null}]");
    }

    private JsonNode json(String path) throws Exception {
        String body = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer admin"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private void seedAudit() {
        AuditLog log = new AuditLog();
        log.setUserId(2L);
        log.setActorKeycloakSub("sub-1");
        log.setAction("LOGIN");
        log.setResourceType("SITE");
        log.setResourceId(3L);
        log.setChanges("{}");
        log.setRemoteIp("127.0.0.1");
        log.setUserAgent("ua");
        log.setCreatedAt(CREATED);
        auditLogRepository.save(log);
    }

    private void seedOperation() {
        var log = saveOperation(2L, "op-1", "GET", "/api/sites", 5L, CREATED);
        log.setErrorMessage("e");
        operationLogRepository.save(log);
    }

    private void seedError() {
        var log = saveError("boom", "ERROR", "https://example.com", CREATED);
        log.setTimestamp(TIMESTAMP);
        frontendErrorLogRepository.save(log);
    }

    @Test
    void auditLog_一覧はZ終端のRFC3339で返りPage構造とフィールド名は変わらない() throws Exception {
        seedAudit();

        JsonNode page = json("/api/audit-logs");

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
        // created_at は NOT NULL 列で DB には null の行を入れられないので、DTO への変換だけを直接確かめる。
        AuditLog log = new AuditLog();
        log.setAction("LOGIN");

        JsonNode row = mapper.valueToTree(AuditLogResponse.from(log));

        assertTrue(row.get("createdAt").isNull());
    }

    @Test
    void operationLog_一覧と経路追跡はZ終端のRFC3339で返る() throws Exception {
        seedOperation();

        JsonNode listRow = json("/api/operation-logs").get("content").get(0);
        JsonNode traceRow = json("/api/operation-logs/op-1").get(0);

        for (JsonNode row : List.of(listRow, traceRow)) {
            assertEquals(CREATED_Z, row.get("createdAt").asString());
            assertEquals("op-1", row.get("operationId").asString());
            assertEquals(5, row.get("durationMs").asInt());
            assertTrue(row.get("success").asBoolean());
        }
    }

    @Test
    void frontendErrorLog_はtimestampもcreatedAtもZ終端のRFC3339で返る() throws Exception {
        seedError();

        JsonNode row = json("/api/logs/errors").get("content").get(0);

        assertEquals(TIMESTAMP_Z, row.get("timestamp").asString());
        assertEquals(CREATED_Z, row.get("createdAt").asString());
        assertEquals("boom", row.get("message").asString());
        assertEquals("ERROR", row.get("level").asString());
    }

    @Test
    void unifiedLogEntry_は3ソースともZ終端のRFC3339で返る() throws Exception {
        seedOperation();
        seedAudit();

        JsonNode page = json("/api/operation-logs/unified");

        assertEquals(3, page.get("content").size());
        for (JsonNode node : page.get("content")) {
            assertEquals(CREATED_Z, node.get("createdAt").asString());
            assertTrue(node.has("sourceType"));
            assertTrue(node.has("actorKeycloakSub"));
        }
    }

    @Test
    void 全ての絞り込み経路でもZ終端のRFC3339で返る() throws Exception {
        seedAudit();
        seedError();
        String period = "startDate=2026-01-01T00:00:00&endDate=2026-12-31T00:00:00";

        for (String path : List.of(
                "/api/audit-logs?userId=2", "/api/audit-logs?action=LOGIN", "/api/audit-logs?" + period)) {
            assertEquals(CREATED_Z, json(path).get("content").get(0).get("createdAt").asString(), path);
        }
        for (String path : List.of(
                "/api/logs/errors?level=error", "/api/logs/errors?" + period, "/api/logs/errors?url=https://example.com")) {
            assertEquals(TIMESTAMP_Z, json(path).get("content").get(0).get("timestamp").asString(), path);
        }
    }

    @Test
    void operationLog_adminの経路追跡もZ終端のRFC3339で返る() throws Exception {
        saveOperation(5L, "op-1", "GET", "/api/sites", 5L, CREATED);

        assertEquals(CREATED_Z, json("/api/operation-logs/op-1").get(0).get("createdAt").asString());
    }
}
