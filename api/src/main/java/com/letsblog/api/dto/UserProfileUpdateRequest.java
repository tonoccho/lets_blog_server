package com.letsblog.api.dto;

public record UserProfileUpdateRequest(
        String firstName,
        String lastName,
        String displayName,
        String nickname,
        String websiteUrl,
        String bio,
        String locale,
        String avatarUrl,
        String department,
        String position
) {
}
