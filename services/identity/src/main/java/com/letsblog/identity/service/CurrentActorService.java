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

    public String getCurrentActorRole() {
        return resolveJwtActor().map(User::getRole).orElse(null);
    }

    public boolean isAdmin() {
        return "admin".equals(getCurrentActorRole());
    }

    /**
     * JWTの{@code sub}クレーム(Keycloakのユーザー識別子)。監査ログの{@code actorKeycloakSub}に使う
     * (issue #583)。{@link #getCurrentActorId()}と違いローカルの{@code users}行を必要としないので、
     * まだ同期されていない利用者の操作でも記録が残る。
     */
    public String getCurrentActorKeycloakSub() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            return null;
        }
        return token.getToken().getSubject();
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
     * <p>{@code deactivate}はKeycloak側とローカルの{@code users.enabled}を落とすが、
     * <b>すでに発行済みのアクセストークンは失効しない</b>。Keycloakが止めるのは新規のトークン発行
     * だけで、既存トークンの署名も有効期限も変わらないためである。#816以前はこの解決処理が
     * {@code enabled}を参照していなかったので、無効化直後のユーザーは
     * {@code accessTokenLifespan}(既定300秒)の間、admin操作を含めて通常どおりAPIを通せた。
     *
     * <p>結果として、無効化されたadminが自分自身を{@code reactivate}して復帰でき、
     * 退職者や侵害されたアカウントを即時に締め出せなかった。
     *
     * <p><b>ここを直せば他サービスにも効く</b>: legacy-apiを除く8サービスの
     * {@code CurrentActorService}は、いずれも{@code GET /api/identity/me}への同期呼び出し
     * ({@code IdentityClient})で操作者を解決する。そのエンドポイントは
     * {@code requireActorId()}を通るため、無効化ユーザーでは403になる。
     * legacy-apiだけは共有スキーマの{@code users}を自前参照するため個別に修正した(#786参照)。
     *
     * <p><b>ただし他サービスでの見え方は403ではなく502になる</b>。403は呼び出し側で
     * {@code SyncServiceClientErrorException}に変換され、各サービスの{@code lookupProfile}が
     * {@code IdentityServiceUnavailableException}へ再変換し、{@code GlobalExceptionHandler}が
     * 502にマップするため。拒否はされる(fail-closed)が、無効化ユーザー起因の502と
     * サービス障害起因の502が区別できない。改善は#829で追跡している。
     *
     * <p>無効化を「解決しない」で表現しているのは、JWTの検証(署名・有効期限・issuer)自体は
     * 通っている以上401ではなく、「認証は済んでいるが操作者として扱わない」が実態に合うため。
     * {@code AdminAuthorizationService}や{@code PermissionAuthorizationService}は
     * 操作者を解決できないと既にForbiddenを投げる設計なので、そこに合流する。
     *
     * <p><b>効く範囲は actor を解決するエンドポイントに限る。</b>認可チェックを持たない
     * エンドポイント({@code docs/AUTHORIZATION_MATRIX.md}の「有効なJWTさえあれば到達できる
     * エンドポイント」の節)には効かない。
     */
    private static boolean isUsable(User user) {
        return user.isEnabled();
    }
}
