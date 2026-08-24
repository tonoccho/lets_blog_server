package com.letsblog.project.dto;

public record TagDesignPresetResponse(
        String id,
        String label,
        String backgroundColor,
        String textColor,
        String accentColor) {
}
