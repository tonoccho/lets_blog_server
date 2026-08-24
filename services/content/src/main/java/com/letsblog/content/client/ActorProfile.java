package com.letsblog.content.client;

/**
 * identity-serviceの{@code GET /api/identity/me}(UserProfileResponse)から必要な項目のみを
 * 取り出したもの(media-service/ai-serviceのActorProfileと同じ形)。未知のJSONフィールドは
 * 無視する(Spring Bootの既定のJackson設定はFAIL_ON_UNKNOWN_PROPERTIESが無効)。
 */
public record ActorProfile(Long id, String role) {

    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
