package com.letsblog.publishing.client;

/**
 * identity-serviceの{@code GET /api/identity/me}(UserProfileResponse)から必要な項目のみを
 * 取り出したもの(media-service/ai-service/content-serviceのActorProfileと同じ形)。未知のJSON
 * フィールドは無視する(Spring Bootの既定のJackson設定はFAIL_ON_UNKNOWN_PROPERTIESが無効)。
 */
public record ActorProfile(Long id, String role, String email) {

    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
