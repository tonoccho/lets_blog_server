package com.letsblog.content.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * issue #1536: DB / エンティティの LocalDateTime は UTC の壁時計(#1257 で固定済み)。
 * 公開レスポンスでは openapi の format: date-time(RFC 3339、オフセット必須)を満たすため、
 * 実時刻を変えずに UTC の Instant(Z 終端)へ変換する。
 */
public final class UtcDateTimes {

    private UtcDateTimes() {
    }

    public static Instant toInstant(LocalDateTime utcWallClock) {
        return utcWallClock == null ? null : utcWallClock.toInstant(ZoneOffset.UTC);
    }

    /**
     * issue #1542: 入力の日時文字列(ISO 8601 / RFC 3339)を UTC の壁時計へ換算する。
     * オフセット付きは同じ実時刻の UTC に直し、オフセットなしは従来どおり UTC の壁時計として扱う。
     * 解釈できない文字列は {@link java.time.format.DateTimeParseException}。
     */
    public static LocalDateTime parseUtcWallClock(String text) {
        java.time.temporal.TemporalAccessor parsed = DateTimeFormatter.ISO_DATE_TIME.parseBest(
                text, OffsetDateTime::from, LocalDateTime::from);
        if (parsed instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        }
        return (LocalDateTime) parsed;
    }
}
