package com.letsblog.logwriter.dto;

import com.letsblog.logwriter.domain.FrontendErrorLog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * issue #1059 レビュー対応: {@link FrontendErrorLogRequest#toDomain()}・{@code normalizeLevel}の
 * 既存分岐(本Issueで振る舞いを変えていない)に対するカバレッジ補完。
 */
class FrontendErrorLogRequestTest {

    @Test
    void toDomain_timestampが妥当なISO8601文字列ならそのままパースする() {
        FrontendErrorLogRequest request = new FrontendErrorLogRequest(
                "boom", null, null, "error", null, null, null, "2026-01-01T00:00:00");

        FrontendErrorLog log = request.toDomain();

        assertEquals(java.time.LocalDateTime.of(2026, 1, 1, 0, 0), log.getTimestamp());
    }

    @Test
    void toDomain_timestampが不正な文字列ならnowにフォールバックする() {
        FrontendErrorLogRequest request = new FrontendErrorLogRequest(
                "boom", null, null, "error", null, null, null, "not-a-timestamp");

        FrontendErrorLog log = request.toDomain();

        assertNotNull(log.getTimestamp());
    }

    @Test
    void toDomain_timestampが無ければnowを使う() {
        FrontendErrorLogRequest request = new FrontendErrorLogRequest(
                "boom", null, null, "error", null, null, null, null);

        FrontendErrorLog log = request.toDomain();

        assertNotNull(log.getTimestamp());
    }

    @Test
    void toDomain_contextが無ければnullのまま() {
        FrontendErrorLogRequest request = new FrontendErrorLogRequest(
                "boom", null, null, "error", null, null, null, null);

        FrontendErrorLog log = request.toDomain();

        assertNull(log.getContext());
    }

    @Test
    void toDomain_contextがあればtoStringした文字列を保持する() {
        FrontendErrorLogRequest request = new FrontendErrorLogRequest(
                "boom", null, null, "error", java.util.Map.of("k", "v"), null, null, null);

        FrontendErrorLog log = request.toDomain();

        assertNotNull(log.getContext());
    }

    @Test
    void toDomain_levelは大文字小文字を無視して正規化される() {
        FrontendErrorLogRequest request = new FrontendErrorLogRequest(
                "boom", null, null, "warn", null, null, null, null);

        FrontendErrorLog log = request.toDomain();

        assertEquals("WARN", log.getLevel());
    }

    @Test
    void toDomain_levelがERRORでもWARNでもなければ例外になる() {
        FrontendErrorLogRequest request = new FrontendErrorLogRequest(
                "boom", null, null, "invalid-level", null, null, null, null);

        assertThrows(IllegalArgumentException.class, request::toDomain);
    }

    @Test
    void toDomain_levelが無ければ正規化前にnullとなり例外になる() {
        // Set.of(...)はnullを許容せずcontains(null)がNullPointerExceptionを投げるため、
        // levelがnullのときはIllegalArgumentExceptionではなくNPEになる(既存の分岐そのまま、
        // 本Issueで挙動を変えていない)。
        FrontendErrorLogRequest request = new FrontendErrorLogRequest(
                "boom", null, null, null, null, null, null, null);

        assertThrows(NullPointerException.class, request::toDomain);
    }
}
