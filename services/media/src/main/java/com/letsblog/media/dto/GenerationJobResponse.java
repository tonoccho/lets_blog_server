package com.letsblog.media.dto;

import com.letsblog.common.client.GenerationJobSummary;
import java.time.Instant;

public record GenerationJobResponse(
        Long id,
        String type,
        String status,
        Instant createdAt,
        Instant updatedAt
) {

    /** ai-service のジョブ要約から公開レスポンスを作る。日時は UTC の Z 終端 RFC 3339 で返す(issue #1539)。 */
    public static GenerationJobResponse from(GenerationJobSummary job) {
        return new GenerationJobResponse(
                job.id(), job.type(), job.status(),
                UtcDateTimes.toInstant(job.createdAt()), UtcDateTimes.toInstant(job.updatedAt()));
    }
}
