package com.letsblog.logwriter.service;

import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.client.SyncServiceException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * 操作者(actor)情報を取得する(#572)。
 *
 * <p>legacy-api/identity-serviceのCurrentActorServiceと異なり、本サービス(lbs_logスキーマ)は
 * usersテーブルへクロススキーマアクセスできない(ADR-0004)ため、JWTのsubクレームからローカルで
 * userIdを解決することができない。そのため「自分自身のuserId」の解決は、常に
 * identity-serviceの{@code GET /api/identity/me}への同期呼び出し({@link IdentityClient})に
 * 委ねる(呼び出し元のBearerトークンをそのまま転送する。IdentityClientのJavadoc参照)。
 *
 * <p>gateway経由のトラフィックは既にWeb(#564)・VSCode拡張(#565)ともKeycloakトークンを
 * 送るようになっているため、legacy-api/identity-serviceが今も保持するX-Actor-Idヘッダー
 * フォールバックは持たない(log-writerは本Issueで初めて公開する新規REST APIサーフェスであり、
 * ヘッダー経由の既存クライアントが存在しないため)。
 *
 * <p>identity-serviceへの問い合わせ結果は1リクエストにつき最大1回になるよう、
 * リクエストスコープ(HttpServletRequestの属性)でキャッシュする。
 */
@Service
public class CurrentActorService {

    private static final String PROFILE_CACHE_ATTR = CurrentActorService.class.getName() + ".profile";

    private final HttpServletRequest request;
    private final IdentityClient identityClient;

    public CurrentActorService(HttpServletRequest request, IdentityClient identityClient) {
        this.request = request;
        this.identityClient = identityClient;
    }

    /**
     * JWTのsubクレームを生の文字列のまま返す(監査証跡用。identity-serviceへの問い合わせは伴わない)。
     * JWTが提示されていない場合はnullを返す。
     */
    public String getCurrentActorKeycloakSub() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            return null;
        }
        String subject = token.getToken().getSubject();
        return (subject == null || subject.isBlank()) ? null : subject;
    }

    /**
     * identity-serviceが解決したローカルUserのidを返す。JWTが提示されていない、または
     * identity-service側でユーザーを解決できない場合はnullを返す。identity-serviceへの
     * 呼び出し自体が失敗した場合は{@link IdentityServiceUnavailableException}を伝播させる
     * (読み取りAPIの権限判定・本人確認で使う想定。resolveProfileのJavadoc参照)。
     */
    public Long getCurrentActorId() {
        return resolveProfile().map(ActorProfile::id).orElse(null);
    }

    /**
     * ログ記録(書き込み)経路専用。identity-serviceが落ちていてもログ記録自体は失わせたくない
     * ため、identity-serviceへの呼び出し失敗はuserId未解決(null)へ握りつぶす
     * (actor_keycloak_subは{@link #getCurrentActorKeycloakSub()}でJWTから直接取れるため、
     * identity-service障害時もそちらは引き続き記録できる)。
     */
    public Long tryGetCurrentActorId() {
        try {
            return getCurrentActorId();
        } catch (IdentityServiceUnavailableException e) {
            return null;
        }
    }

    public boolean isAdmin() {
        return resolveProfile().map(ActorProfile::isAdmin).orElse(false);
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

    /**
     * 呼び出し元が送ってきた{@code Authorization}ヘッダーの値をそのまま返す(例:
     * {@code "Bearer xxx"}）。identity-service以外のサービス間同期呼び出し
     * ({@link com.letsblog.logwriter.client.GenerationJobClient}等)へも同じトークンを
     * 転送するために公開する。ヘッダーが無ければnull。
     */
    public String getAuthorizationHeader() {
        return request.getHeader(HttpHeaders.AUTHORIZATION);
    }

    @SuppressWarnings("unchecked")
    private Optional<ActorProfile> resolveProfile() {
        Object cached = request.getAttribute(PROFILE_CACHE_ATTR);
        if (cached == null) {
            Optional<ActorProfile> resolved = lookupProfile();
            request.setAttribute(PROFILE_CACHE_ATTR, resolved);
            return resolved;
        }
        return (Optional<ActorProfile>) cached;
    }

    /**
     * Authorizationヘッダーが無い(=未認証)場合のみ空を返す。ヘッダーが有るのに
     * identity-serviceへの問い合わせ自体が失敗した場合は、「未認証」へ握りつぶさず
     * {@link IdentityServiceUnavailableException}をそのまま呼び出し元へ伝播させる
     * (GlobalExceptionHandlerが502として扱う。identity-service障害を静かにadmin権限無しへ
     * 縮退させると、意図せず権限チェックが素通りする方向の不具合を生みかねないため)。
     */
    private Optional<ActorProfile> lookupProfile() {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken == null || bearerToken.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(identityClient.fetchProfile(bearerToken));
        } catch (SyncServiceException e) {
            throw new IdentityServiceUnavailableException(
                    "identity-serviceの/api/identity/me呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }
}
