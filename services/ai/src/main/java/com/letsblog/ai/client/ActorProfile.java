package com.letsblog.ai.client;

/**
 * identity-serviceの{@code GET /api/identity/me}(UserProfileResponse)から必要な項目のみを
 * 取り出したもの(media-service(#573)のActorProfileと同じ形。issue #574)。未知のJSONフィールドは
 * 無視する(Spring Bootの既定のJackson設定はFAIL_ON_UNKNOWN_PROPERTIESが無効)。
 */
public record ActorProfile(Long id, String role) {

    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
