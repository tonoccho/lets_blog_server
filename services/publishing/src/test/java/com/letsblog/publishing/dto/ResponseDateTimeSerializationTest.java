package com.letsblog.publishing.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.letsblog.publishing.domain.BulkOperationLog;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * issue #1540: openapi/publishing.json が format: date-time(RFC 3339、オフセット必須)と宣言する
 * 公開レスポンスの日時が、UTC の Z 終端で出力されること。
 *
 * <p>API の文字列形式は画面から観測できないため、Gherkin ではなくサービスレベルの
 * シリアライズテストで表現する。DB / エンティティは UTC の壁時計 LocalDateTime のまま。
 */
class ResponseDateTimeSerializationTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode json(LocalDateTime createdAt) {
        BulkOperationLog log = new BulkOperationLog();
        log.setCreatedAt(createdAt);
        return mapper.valueToTree(BulkOperationLogResponse.from(log));
    }

    @Test
    void bulkOperationLogCreatedAtIsZTerminatedRfc3339() {
        assertEquals("2026-09-08T20:03:35Z", json(LocalDateTime.of(2026, 9, 8, 20, 3, 35)).get("createdAt").asString());
    }

    @Test
    void bulkOperationLogNullCreatedAtStaysNull() {
        assertTrue(json(null).get("createdAt").isNull());
    }
}
