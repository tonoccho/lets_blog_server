package com.letsblog.api.dto;

import com.letsblog.api.domain.CustomLink;
import com.letsblog.api.domain.SocialLinks;

import java.util.List;

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
        String position,
        SocialLinks socialLinks,
        List<CustomLink> customLinks
) {
}
