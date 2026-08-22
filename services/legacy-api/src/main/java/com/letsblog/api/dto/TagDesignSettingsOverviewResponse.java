package com.letsblog.api.dto;

import java.util.List;

public record TagDesignSettingsOverviewResponse(
        List<TagDesignPresetResponse> presets,
        List<TagDesignSettingResponse> settings) {
}
