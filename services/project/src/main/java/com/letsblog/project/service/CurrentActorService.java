package com.letsblog.project.service;

import com.letsblog.project.client.ActorProfile;
import com.letsblog.project.client.IdentityClient;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * 操作者(actor)情報を取得する。media-service/ai-service/content-serviceのCurrentActorServiceと
 * 同じ理由で、本サービス(lbs_projectスキーマ)はusersテーブルへクロススキーマアクセスできない
 * (ADR-0004)ため、JWTのsubクレームからローカルでuserIdを解決することができない。そのため
 * 「自分自身のuserId」の解決は、常にidentity-serviceの{@code GET /api/identity/me}への同期呼び出し
 * ({@link IdentityClient}）に委ねる。
 *
 * <p>identity-serviceへの問い合わせ結果は1リクエストにつき最大1回になるよう、
 * リクエストスコープ(HttpServletRequestの属性)でキャッシュする。
 */
@Service
public class CurrentActorService {

    private static final String PROFILE_CACHE_ATTR = CurrentActorService.class.getName() + ".profile";

    /**
     * {@code @Async}のジョブのスレッドへ引き継いだ操作者(issue #1479)。束縛されている間は、
     * リクエストにもセキュリティコンテキストにも触れず、こちらの値を返す。
     */
    private static final ThreadLocal<ActorSnapshot> BOUND = new ThreadLocal<>();

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
        ActorSnapshot bound = BOUND.get();
        if (bound != null) {
            return bound.keycloakSub();
        }
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            return null;
        }
        String subject = token.getToken().getSubject();
        return (subject == null || subject.isBlank()) ? null : subject;
    }

    /**
     * identity-serviceが解決したローカルUserのidを返す。JWTが提示されていない、または
     * identity-service側でユーザーを解決できない場合はnullを返す。identity-serviceへの
     * 呼び出し自体が失敗した場合は{@link IdentityServiceUnavailableException}を伝播させる。
     */
    public Long getCurrentActorId() {
        ActorSnapshot bound = BOUND.get();
        if (bound != null) {
            return bound.userId();
        }
        return resolveProfile().map(ActorProfile::id).orElse(null);
    }

    public boolean isAdmin() {
        ActorSnapshot bound = BOUND.get();
        if (bound != null) {
            return bound.admin();
        }
        return resolveProfile().map(ActorProfile::isAdmin).orElse(false);
    }

    /**
     * サイト登録時の著者プロビジョニング(SiteService#register)向け。identity-serviceが解決した
     * ローカルUserのメールアドレスを返す。JWTが提示されていない場合はnull。
     */
    public String getCurrentActorEmail() {
        ActorSnapshot bound = BOUND.get();
        if (bound != null) {
            return bound.email();
        }
        return resolveProfile().map(ActorProfile::email).orElse(null);
    }

    /**
     * 呼び出し元が送ってきた{@code Authorization}ヘッダーの値をそのまま返す(例:
     * {@code "Bearer xxx"}）。identity-service以外のサービス間同期呼び出し
     * ({@link com.letsblog.project.client.IdentityBridgeClient}等)へも同じトークンを
     * 転送するために公開する。ヘッダーが無ければnull。
     */
    public String getAuthorizationHeader() {
        ActorSnapshot bound = BOUND.get();
        if (bound != null) {
            return bound.authorization();
        }
        return request.getHeader(HttpHeaders.AUTHORIZATION);
    }

    /** 監査ログ(AuditLogAspect)向け。X-Forwarded-Forがあればそちらを優先する。 */
    public String getRemoteIp() {
        ActorSnapshot bound = BOUND.get();
        if (bound != null) {
            return bound.remoteIp();
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor;
        }
        return request.getRemoteAddr();
    }

    /** 監査ログ(AuditLogAspect)向け。 */
    public String getUserAgent() {
        ActorSnapshot bound = BOUND.get();
        if (bound != null) {
            return bound.userAgent();
        }
        return request.getHeader("User-Agent");
    }

    /**
     * 今のリクエストの操作者を写し取る(issue #1479)。<b>リクエストスレッドで</b>呼ぶこと。
     * {@code @Async}のジョブへ渡し、ジョブ側で{@link #runAs}して使う。
     */
    public ActorSnapshot snapshot() {
        return new ActorSnapshot(
                getCurrentActorId(), getCurrentActorKeycloakSub(), getCurrentActorEmail(),
                getRemoteIp(), getUserAgent(), getAuthorizationHeader(), isAdmin());
    }

    /**
     * {@code actor}を、このスレッドの操作者として{@code action}の間だけ束縛する(issue #1479)。
     * 監査ログ(AuditLogAspect)とサイト登録の著者解決が、リクエストの無いジョブのスレッドでも
     * 従来どおり操作者を参照できる。終わったら(例外でも)必ず外す。
     */
    public <T> T runAs(ActorSnapshot actor, Supplier<T> action) {
        BOUND.set(actor);
        try {
            return action.get();
        } finally {
            BOUND.remove();
        }
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

    private Optional<ActorProfile> lookupProfile() {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken == null || bearerToken.isBlank()) {
            return Optional.empty();
        }
        // 401/403(無効化ユーザー等)は「操作者なし」。障害と区別する(issue #829)。
        return identityClient.lookupProfile(bearerToken);
    }
}
