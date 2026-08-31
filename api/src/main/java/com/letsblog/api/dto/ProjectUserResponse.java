package com.letsblog.api.dto;

public record ProjectUserResponse(
        Long userId,
        String email,
        String displayName,
        String wpRole
) {
}
