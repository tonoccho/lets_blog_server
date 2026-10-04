package com.letsblog.project.dto;

import com.letsblog.common.client.GenerationJobSummary;
import java.time.Instant;

/** ジョブ受理の公開レスポンス(issue #1479)。media-serviceの同名DTOと同じ形。 */
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
                job.id(), job.type(), job.status(),
                UtcDateTimes.toInstant(job.createdAt()), UtcDateTimes.toInstant(job.updatedAt()));
    }
}
