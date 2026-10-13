package com.letsblog.publishing.dto;

import com.letsblog.common.client.GenerationJobSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** issue #1702: ジョブ受理の公開レスポンス。日時はUTCのZ終端(#1539と同じ)で返す。 */
class GenerationJobResponseTest {

    @Test
    void ai_serviceのジョブ要約をUTCのInstantへ写す() {
        LocalDateTime created = LocalDateTime.of(2026, 10, 13, 1, 2, 3);
        LocalDateTime updated = LocalDateTime.of(2026, 10, 13, 1, 2, 4);

        GenerationJobResponse response =
                GenerationJobResponse.from(new GenerationJobSummary(7L, "bulk_slug_install", "running", created, updated));

        assertEquals(7L, response.id());
        assertEquals("bulk_slug_install", response.type());
        assertEquals("running", response.status());
        assertEquals(Instant.parse("2026-10-13T01:02:03Z"), response.createdAt());
        assertEquals(Instant.parse("2026-10-13T01:02:04Z"), response.updatedAt());
    }

    @Test
    void 日時が無ければnullのまま() {
        GenerationJobResponse response =
                GenerationJobResponse.from(new GenerationJobSummary(7L, "t", "running", null, null));

        assertNull(response.createdAt());
        assertNull(response.updatedAt());
    }
}
