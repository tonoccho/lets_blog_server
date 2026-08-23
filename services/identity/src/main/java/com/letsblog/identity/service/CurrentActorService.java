package com.letsblog.identity.service;

import com.letsblog.identity.domain.User;
import com.letsblog.identity.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * 操作者(actor)情報を取得する。
 *
 * <p>従来はリクエストヘッダー(X-Actor-Id / X-Actor-Role。Web BFF(Next.js)がNextAuthセッションの
 * 内容を転送してくる前提。ApiKeyAuthFilterと同様、BFFを信頼するモデル)のみを情報源としていたが、
 * #563でKeycloak発行JWTによる解決を追加した。SecurityContextに検証済みJWT(Bearerトークン。
 * SecurityConfigのoauth2ResourceServer().jwt()により、ここへ到達する時点で署名・有効期限・issuerは
 * 検証済み)が存在する場合は、そのsubクレームでローカルUser(#562のKeycloakユーザー同期で
 * keycloakSubが設定される)を引き当てて優先する。identity-service自身のUserRepositoryを直接
 * 参照するため、この解決は他サービスへのHTTP呼び出しを伴わず循環参照にならない。
 *
 * <p>JWTが提示されているのに対応するローカルUserが見つからない場合は、ヘッダーへフォールバック
 * せず「操作者なし」を返す。ヘッダーはBFF専用の経路であり、JWT保持者に対してヘッダーによる
 * なりすまし判定の余地を与えないため。JWTが提示されていない場合(2026-08時点でWeb/VSCode拡張は
 * まだKeycloakトークンを送っていない。#564/#565が未着手)は、従来通りヘッダーへフォールバックする
 * ——これが実運用における唯一の経路であり、これを外すと唯一の実ユーザーアカウントがログインできなくなる。
 *
 * <p>JWTからの解決結果は1リクエストにつき最大1回のDB問い合わせになるよう、
 * リクエストスコープ(HttpServletRequestの属性)でキャッシュする。
 */
@Service
public class CurrentActorService {

    private static final String ACTOR_ID_HEADER = "X-Actor-Id";
    private static final String ACTOR_ROLE_HEADER = "X-Actor-Role";
    private static final String JWT_ACTOR_CACHE_ATTR = CurrentActorService.class.getName() + ".jwtActor";

    private final HttpServletRequest request;
    private final UserRepository userRepository;

    public CurrentActorService(HttpServletRequest request, UserRepository userRepository) {
        this.request = request;
        this.userRepository = userRepository;
    }

    public Long getCurrentActorId() {
        Optional<User> jwtActor = resolveJwtActor();
        if (jwtActor.isPresent()) {
            return jwtActor.get().getId();
        }
        if (hasJwtAuthentication()) {
            return null;
        }
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
        Optional<User> jwtActor = resolveJwtActor();
        if (jwtActor.isPresent()) {
            return jwtActor.get().getRole();
        }
        if (hasJwtAuthentication()) {
            return null;
        }
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

    private boolean hasJwtAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken;
    }

    @SuppressWarnings("unchecked")
    private Optional<User> resolveJwtActor() {
        Object cached = request.getAttribute(JWT_ACTOR_CACHE_ATTR);
        if (cached == null) {
            Optional<User> resolved = lookupJwtActor();
            request.setAttribute(JWT_ACTOR_CACHE_ATTR, resolved);
            return resolved;
        }
        return (Optional<User>) cached;
    }

    private Optional<User> lookupJwtActor() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            return Optional.empty();
        }
        String subject = token.getToken().getSubject();
        if (subject == null || subject.isBlank()) {
            // subクレームが無いJWT(実運用では想定しないが、クライアント設定次第では起こり得る)を
            // nullのままリポジトリへ渡すと、Spring Data JPAの派生クエリはnullパラメータを
            // "IS NULL"として扱うため、keycloak_subが未設定(NULL)のローカルユーザー
            // (実運用ではKeycloak未移行の既存ユーザーが該当し得る)へ誤って解決されてしまう。
            // 実機検証(#563)でこの誤解決が発生することを確認したため、明示的に空扱いにする。
            return Optional.empty();
        }
        return userRepository.findByKeycloakSub(subject);
    }
}
