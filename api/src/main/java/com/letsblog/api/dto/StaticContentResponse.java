package com.letsblog.api.dto;

import com.letsblog.api.domain.StaticContent;
import com.letsblog.api.domain.StaticContentType;

import java.time.LocalDateTime;

public record StaticContentResponse(
    Long id,
    Long siteId,
    StaticContentType contentType,
    String body,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static StaticContentResponse from(StaticContent entity) {
        return new StaticContentResponse(
            entity.getId(),
            entity.getSiteId(),
            entity.getContentType(),
            entity.getBody(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
