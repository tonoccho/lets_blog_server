package com.letsblog.api.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

/**
 * リクエストヘッダー(X-Actor-Id / X-Actor-Role)から操作者情報を取得する。
 * このAPIサーバーはユーザー単位の認証機構を持たず、Web BFF(Next.js)が
 * NextAuthセッションの内容をヘッダーとして転送してくる前提で動作する
 * (ApiKeyAuthFilterと同様、BFFを信頼するモデル)。
 */
@Service
public class CurrentActorService {

    private static final String ACTOR_ID_HEADER = "X-Actor-Id";
    private static final String ACTOR_ROLE_HEADER = "X-Actor-Role";

    private final HttpServletRequest request;

    public CurrentActorService(HttpServletRequest request) {
        this.request = request;
    }

    public Long getCurrentActorId() {
        String header = request.getHeader(ACTOR_ID_HEADER);
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String getCurrentActorRole() {
        return request.getHeader(ACTOR_ROLE_HEADER);
    }

    public boolean isAdmin() {
        return "admin".equals(getCurrentActorRole());
    }

    public String getRemoteIp() {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor;
        }
        return request.getRemoteAddr();
    }

    public String getUserAgent() {
        return request.getHeader("User-Agent");
    }
}
