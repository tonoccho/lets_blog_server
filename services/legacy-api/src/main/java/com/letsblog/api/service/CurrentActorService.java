package com.letsblog.api.service;

import com.letsblog.api.domain.User;
import com.letsblog.api.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * 操作者(actor)情報を取得する。
 *
 * <p>issue #566で、Web BFF(Next.js)がNextAuthセッションの内容を転送していた旧ヘッダベースの
 * フォールバックを撤去し、KeycloakのJWT(Bearerトークン。SecurityConfigの
 * oauth2ResourceServer().jwt()により、ここへ到達する時点で署名・有効期限・issuerは検証済み)のみを
 * 情報源とする実装へ一本化した(#563で追加したJWT優先ロジック自体はそのまま)。SecurityContextに
 * 検証済みJWTが存在する場合、そのsubクレームでローカルUser(#562のKeycloakユーザー同期で
 * keycloakSubが設定される)を引き当てる。identity-service自身のUserRepositoryを直接参照するため、
 * この解決は他サービスへのHTTP呼び出しを伴わず循環参照にならない。
 *
 * <p>JWTが提示されているのに対応するローカルUserが見つからない場合、およびJWTが提示されていない
 * 場合は、いずれも「操作者なし」を返す。
 *
 * <p>JWTからの解決結果は1リクエストにつき最大1回のDB問い合わせになるよう、
 * リクエストスコープ(HttpServletRequestの属性)でキャッシュする。
 */
@Service
public class CurrentActorService {

    private static final String JWT_ACTOR_CACHE_ATTR = CurrentActorService.class.getName() + ".jwtActor";

    private final HttpServletRequest request;
    private final UserRepository userRepository;

    public CurrentActorService(HttpServletRequest request, UserRepository userRepository) {
        this.request = request;
        this.userRepository = userRepository;
    }

    public Long getCurrentActorId() {
        return resolveJwtActor().map(User::getId).orElse(null);
    }

    /**
     * 監査ログ(issue #569)のために、JWTのsubクレームを生の文字列のまま返す。
     *
     * <p>{@link #getCurrentActorId()}はsubをローカルUserへ解決できた場合のみ値を返す(解決できなければ
     * nullになる)ため、ローカルUser未同期・削除済みなどapp内部のuserIdが失われるケースでは、
     * 監査証跡から「誰が」の手がかりが消えてしまう。この値は ローカルUser解決の成否と切り離して、
     * JWTが提示されている限り常にKeycloak側のsubクレームをそのまま保持することで、監査ログの
     * 追跡性をローカルUser同期状況に依存させないために用いる。
     *
     * <p>JWTが提示されていない場合はnullを返す。
     */
    public String getCurrentActorKeycloakSub() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            return null;
        }
        String subject = token.getToken().getSubject();
        return (subject == null || subject.isBlank()) ? null : subject;
    }

    public String getCurrentActorRole() {
        return resolveJwtActor().map(User::getRole).orElse(null);
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
            // "IS NULL"として扱うため、keycloak_subが未設定(NULL)のローカルユーザーへ
            // 誤って解決されてしまう。実機検証(#563)でこの誤解決が発生することを確認したため、
            // 明示的に空扱いにする。
            return Optional.empty();
        }
        return userRepository.findByKeycloakSub(subject).filter(CurrentActorService::isUsable);
    }

    /**
     * 無効化されたユーザーを操作者として解決しない(issue #816)。
     *
     * <p>identity-serviceの{@code CurrentActorService}と同じ判定。無効化しても発行済みの
     * アクセストークンは失効しない(Keycloakが止めるのは新規発行だけ)ため、判定を入れないと
     * {@code accessTokenLifespan}(既定300秒)の間、無効化されたユーザーがAPIを通せてしまう。
     *
     * <p><b>legacy-apiだけ個別に必要な理由</b>: 他の8サービスは
     * {@code GET /api/identity/me}への同期呼び出しで操作者を解決するため、identity-service側の
     * 修正だけで塞がる(ただし見え方は403ではなく502。#829参照)。legacy-apiは共有スキーマの
     * {@code users}テーブルを自前で参照しており(#786参照)、その経路を通らない。
     */
    private static boolean isUsable(User user) {
        return user.isEnabled();
    }
}
