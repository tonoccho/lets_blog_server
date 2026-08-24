package com.letsblog.media.client;

/**
 * identity-serviceの{@code GET /api/identity/me}(UserProfileResponse)から必要な項目のみを
 * 取り出したもの(#572)。未知のJSONフィールドは無視する(Spring Bootの既定のJackson設定は
 * FAIL_ON_UNKNOWN_PROPERTIESが無効)。
 */
public record ActorProfile(Long id, String role) {

    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
