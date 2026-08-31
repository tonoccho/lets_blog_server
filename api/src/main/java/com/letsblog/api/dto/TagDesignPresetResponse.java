package com.letsblog.api.dto;

public record TagDesignPresetResponse(
        String id,
        String label,
        String backgroundColor,
        String textColor,
        String accentColor) {
}
