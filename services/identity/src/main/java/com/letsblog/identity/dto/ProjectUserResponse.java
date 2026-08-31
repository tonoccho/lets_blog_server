package com.letsblog.identity.dto;

public record ProjectUserResponse(
        Long userId,
        String email,
        String displayName,
        String wpRole
) {
}
