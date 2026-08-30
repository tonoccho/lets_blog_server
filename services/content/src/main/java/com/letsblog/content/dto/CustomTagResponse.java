package com.letsblog.content.dto;

import com.letsblog.content.domain.CustomTag;
import com.letsblog.content.domain.CustomTagFormat;

import java.time.LocalDateTime;

public record CustomTagResponse(
        Long id,
        String tagName,
        String htmlTemplate,
        String description,
        String cssContent,
        CustomTagFormat tagFormat,
        Long projectId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
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
                tag.getCreatedAt(),
                tag.getUpdatedAt(),
                tag.getPenpotFileUrl());
    }
}
