package com.letsblog.project.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * X の認可の途中経過(クライアントの秘密・PKCE の検証子・開始した操作者)をメモリにだけ短時間持つ(issue #1574)。
 * DB・ファイルには書かない。コールバックで一度取り出したら消え、期限(既定10分)を過ぎたものは取り出せない。
 *
 * <p>state は {@code <projectId>.<乱数>}。プロジェクトIDを前に置くのは、コールバックを受ける側が
 * 対象プロジェクトのAPIを呼べるようにするため(GA/AdSenseの state と同じ形)。
 */
@Component
public class XAuthorizationStore {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final Map<String, Pending> pendings = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    @Autowired
    public XAuthorizationStore() {
        this(Clock.systemUTC(), DEFAULT_TTL);
    }

    XAuthorizationStore(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    public Pending create(
            Long projectId, Long siteId, String actorSub, String clientId, String clientSecret, String redirectUri) {
        Instant now = clock.instant();
        pendings.values().removeIf(pending -> pending.expiresAt().isBefore(now));
        String state = projectId + "." + randomToken(24);
        Pending pending = new Pending(state, projectId, siteId, actorSub, clientId, clientSecret, redirectUri,
                randomToken(48), now.plus(ttl));
        pendings.put(state, pending);
        return pending;
    }

    /** 取り出すと消える。期限切れ・未知の state は空。 */
    public Optional<Pending> take(String state) {
        if (state == null) {
            return Optional.empty();
        }
        Pending pending = pendings.remove(state);
        if (pending == null || pending.expiresAt().isBefore(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    int size() {
        return pendings.size();
    }

    /** RFC 7636 の S256: BASE64URL(SHA256(verifier))。 */
    static String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 が使えません", e);
        }
    }

    private static String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** 認可の途中経過。クライアントの秘密を持つので、値は表示しない。 */
    public record Pending(
            String state,
            Long projectId,
            Long siteId,
            String actorSub,
            String clientId,
            String clientSecret,
            String redirectUri,
            String codeVerifier,
            Instant expiresAt) {

        public String codeChallenge() {
            return XAuthorizationStore.codeChallenge(codeVerifier);
        }

        @Override
        public String toString() {
            return "Pending[state=" + state + ", projectId=" + projectId + "]";
        }
    }
}
