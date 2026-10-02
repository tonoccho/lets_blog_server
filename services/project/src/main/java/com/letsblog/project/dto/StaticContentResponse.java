package com.letsblog.project.dto;

import com.letsblog.project.domain.StaticContent;
import com.letsblog.project.domain.StaticContentType;
import java.time.Instant;

public record StaticContentResponse(
        Long id,
        Long siteId,
        StaticContentType contentType,
        String body,
        Instant createdAt,
        Instant updatedAt
) {
    public static StaticContentResponse from(StaticContent entity) {
        return new StaticContentResponse(
                entity.getId(),
                entity.getSiteId(),
                entity.getContentType(),
                entity.getBody(),
                UtcDateTimes.toInstant(entity.getCreatedAt()),
                UtcDateTimes.toInstant(entity.getUpdatedAt())
        );
    }
}
