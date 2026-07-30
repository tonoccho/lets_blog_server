package com.letsblog.api.dto;

public record ProjectUserSummaryResponse(
        Long projectId,
        Long userId,
        String wpRole
) {
}
