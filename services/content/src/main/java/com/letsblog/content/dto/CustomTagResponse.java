package com.letsblog.content.dto;

import com.letsblog.content.domain.CustomTag;
import com.letsblog.content.domain.CustomTagFormat;

import java.time.Instant;

public record CustomTagResponse(
        Long id,
        String tagName,
        String htmlTemplate,
        String description,
        String cssContent,
        CustomTagFormat tagFormat,
        Long projectId,
        Instant createdAt,
        Instant updatedAt,
        /** AI生成時にLLMへのリクエストを元にPenpotへ作成したデザインファイルのURL(ベストエフォート、手動作成タグではnull)。 */
        String penpotFileUrl
) {
    public static CustomTagResponse from(CustomTag tag) {
        return new CustomTagResponse(
                tag.getId(),
                tag.getTagName(),
                tag.getHtmlTemplate(),
                tag.getDescription(),
                tag.getCssContent(),
                tag.getTagFormat(),
                tag.getProjectId(),
                UtcDateTimes.toInstant(tag.getCreatedAt()),
                UtcDateTimes.toInstant(tag.getUpdatedAt()),
                tag.getPenpotFileUrl());
    }
}
