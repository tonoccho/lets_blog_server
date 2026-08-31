package com.letsblog.identity.dto;

public record ProjectUserSummaryResponse(
        Long projectId,
        Long userId,
        String wpRole
) {
}
