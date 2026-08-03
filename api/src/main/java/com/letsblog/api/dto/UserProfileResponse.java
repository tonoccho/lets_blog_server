package com.letsblog.api.dto;

import com.letsblog.api.domain.CustomLink;
import com.letsblog.api.domain.Role;
import com.letsblog.api.domain.SocialLinks;
import com.letsblog.api.domain.User;

import java.time.LocalDateTime;
import java.util.List;

public record UserProfileResponse(
        Long id,
        String email,
        String role,
        List<String> roleNames,
        String firstName,
        String lastName,
        String displayName,
        String nickname,
        String websiteUrl,
        String bio,
        String locale,
        String timezone,
        String avatarUrl,
        String department,
        String position,
        SocialLinks socialLinks,
        List<CustomLink> customLinks,
        boolean githubTokenConfigured,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getRoles().stream().map(Role::getRoleName).sorted().toList(),
                user.getFirstName(),
                user.getLastName(),
                user.getDisplayName(),
                user.getNickname(),
                user.getWebsiteUrl(),
                user.getBio(),
                user.getLocale(),
                user.getTimezone(),
                user.getAvatarUrl(),
                user.getDepartment(),
                user.getPosition(),
                user.getSocialLinks(),
                user.getCustomLinks(),
                user.hasGithubToken(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
