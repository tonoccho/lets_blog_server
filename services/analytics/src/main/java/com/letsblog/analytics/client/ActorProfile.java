package com.letsblog.analytics.client;

/**
 * identity-serviceの{@code GET /api/identity/me}(UserProfileResponse)から必要な項目のみを
 * 取り出したもの(media-service(#573)/ai-service(#574)のActorProfileと同じ形。issue #578)。
 * 未知のJSONフィールドは無視する(Spring Bootの既定のJackson設定はFAIL_ON_UNKNOWN_PROPERTIESが無効)。
 */
public record ActorProfile(Long id, String role) {

    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
