package com.letsblog.common.client;

/**
 * identity-serviceの{@code GET /api/identity/me}(UserProfileResponse)から必要な項目のみを
 * 取り出したもの。log-writer(#572)/media-service(#573)/ai-service(#574)/content-service(#576)/
 * analytics-service(#578)がそれぞれ個別に持っていた同一形状の{@code ActorProfile}を、issue #581
 * (C12)で共通化した。未知のJSONフィールドは無視する(Spring Bootの既定のJackson設定は
 * FAIL_ON_UNKNOWN_PROPERTIESが無効)。
 */
public record ActorProfile(Long id, String role) {

    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
