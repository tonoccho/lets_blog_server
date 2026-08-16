package com.letsblog.api.dto;

public record SetProjectBufferSettingsRequest(
        boolean enabled,
        String profileIds,
        Integer delayMinutes,
        String messageTemplate
) {
}
