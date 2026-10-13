package com.letsblog.publishing.dto;

import com.letsblog.common.client.GenerationJobSummary;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** ジョブ受理の公開レスポンス(issue #1702)。project-serviceの同名DTO(#1479)と同じ形。 */
public record GenerationJobResponse(
        Long id,
        String type,
        String status,
        Instant createdAt,
        Instant updatedAt
) {

    /** ai-serviceのジョブ要約から作る。日時はUTCのZ終端RFC 3339で返す(#1539と同じ)。 */
    public static GenerationJobResponse from(GenerationJobSummary job) {
        return new GenerationJobResponse(
                job.id(), job.type(), job.status(), toInstant(job.createdAt()), toInstant(job.updatedAt()));
    }

    /** DBの{@code LocalDateTime}はUTCの壁時計(#1257)。実時刻を変えずにInstantへ変換する。 */
    private static Instant toInstant(LocalDateTime utcWallClock) {
        return utcWallClock == null ? null : utcWallClock.toInstant(ZoneOffset.UTC);
    }
}
