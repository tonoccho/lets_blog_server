package com.letsblog.project.dto;

import java.util.List;

public record TagDesignSettingsOverviewResponse(
        List<TagDesignPresetResponse> presets,
        List<TagDesignSettingResponse> settings) {
}
