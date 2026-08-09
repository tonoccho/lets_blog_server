package com.letsblog.api.dto;

import com.letsblog.api.domain.EmbedTagType;

public record TagDesignSettingResponse(
        EmbedTagType tagType,
        String presetId,
        String backgroundColor,
        String textColor,
        String accentColor) {
}
