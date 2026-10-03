package com.letsblog.identity.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * issue #1537: DB / エンティティの LocalDateTime は UTC の壁時計(#1257 で固定済み)。
 * 公開レスポンスでは openapi の format: date-time(RFC 3339、オフセット必須)を満たすため、
 * 実時刻を変えずに UTC の Instant(Z 終端)へ変換する。
 */
public final class UtcDateTimes {

    private UtcDateTimes() {
    }

    public static Instant toInstant(LocalDateTime utcWallClock) {
        return utcWallClock == null ? null : utcWallClock.toInstant(ZoneOffset.UTC);
    }
}
