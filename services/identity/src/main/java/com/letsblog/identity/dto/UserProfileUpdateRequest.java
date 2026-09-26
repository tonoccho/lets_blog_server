package com.letsblog.identity.dto;

import com.letsblog.identity.domain.CustomLink;
import com.letsblog.identity.domain.SocialLinks;

import java.util.List;

/**
 * @param email 変更後のメールアドレス(#1192)。null/空白は「変更しない」。変更はadmin限定
 *              ({@code UserController#updateProfile}が認可する)
 */
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
        List<CustomLink> customLinks,
        String email
) {
}
