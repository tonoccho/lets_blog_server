package com.letsblog.content.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * issue #1542: {@code PostUpsertRequest.publishScheduledAt} は openapi/content.json で
 * format: date-time(オフセット付き)と宣言されている。オフセット付き入力は UTC 壁時計へ換算し、
 * オフセットなしは従来どおり UTC として受理する。
 *
 * <p>入力 DTO の内部ブリッジ契約で、画面からオフセットを選んで送る操作は無いため、
 * Gherkin ではなくサービスレベルのデシリアライズテストで表現する。
 */
class PostUpsertRequestDateTimeDeserializationTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private LocalDateTime parse(String value) {
        String json = "{\"siteId\":1,\"wpPostId\":\"42\",\"publishScheduledAt\":"
                + (value == null ? "null" : "\"" + value + "\"") + "}";
        return mapper.readValue(json, PostUpsertRequest.class).publishScheduledAt();
    }

    private static final LocalDateTime EXPECTED = LocalDateTime.of(2026, 9, 8, 20, 3, 35);

    @Test
    void 正のオフセット付きはUTC壁時計へ換算される() {
        assertEquals(EXPECTED, parse("2026-09-09T05:03:35+09:00"));
    }

    @Test
    void 負のオフセット付きはUTC壁時計へ換算される() {
        assertEquals(EXPECTED, parse("2026-09-08T15:03:35-05:00"));
    }

    @Test
    void Z終端は同じ実時刻で受理される() {
        assertEquals(EXPECTED, parse("2026-09-08T20:03:35Z"));
    }

    @Test
    void オフセットなしは従来どおりUTCとして受理される() {
        assertEquals(EXPECTED, parse("2026-09-08T20:03:35"));
    }

    @Test
    void nullとabsentはnullのまま() {
        assertNull(parse(null));
        assertNull(mapper.readValue("{\"siteId\":1}", PostUpsertRequest.class).publishScheduledAt());
    }

    @Test
    void 空文字はnullになる() {
        assertNull(parse(""));
    }

    @Test
    void 不正な文字列は拒否される() {
        assertThrows(JacksonException.class, () -> parse("not-a-date"));
    }
}
