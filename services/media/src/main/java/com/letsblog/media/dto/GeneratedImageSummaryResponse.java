package com.letsblog.media.dto;

import java.time.Instant;
import java.util.List;

public record GeneratedImageSummaryResponse(
        Long id,
        Long projectId,
        String prompt,
        String checkpoint,
        Instant createdAt,
        List<String> tags,
        String provider,
        /** 所属フォルダ(issue #1493)。nullは未分類。 */
        Long folderId
) {
}
