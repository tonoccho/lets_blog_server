package com.letsblog.project.dto;

import com.letsblog.project.domain.EmbedTagType;

public record TagDesignSettingResponse(
        EmbedTagType tagType,
        String presetId,
        String backgroundColor,
        String textColor,
        String accentColor,
        String customCss,
        String htmlTemplate) {
}
