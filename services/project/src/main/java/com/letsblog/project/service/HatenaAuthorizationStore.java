package com.letsblog.project.service;

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
 * はてなの OAuth 1.0a の認可の途中経過(consumer secret・リクエストトークンとその秘密・開始した操作者)をメモリにだけ
 * 短時間持つ(issue #1582。X の {@link XAuthorizationStore} と同じ方針)。DB・ファイル・cookie には書かない。
 * コールバックで一度取り出したら消え、期限(既定10分)を過ぎたものは取り出せない。
 *
 * <p>state は {@code <projectId>.<乱数>}。OAuth 1.0a には state が無いので、コールバック URL に state を載せて
 * 往復させる(はてなは callback の URL をそのまま使って戻す)。プロジェクトIDを前に置くのは、コールバックを受ける側が
 * 対象プロジェクトのAPIを呼べるようにするため。
 */
@Component
public class HatenaAuthorizationStore {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final Map<String, Pending> pendings = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    @Autowired
    public HatenaAuthorizationStore() {
        this(Clock.systemUTC(), DEFAULT_TTL);
    }

    HatenaAuthorizationStore(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    /** 新しい state。リクエストトークンの取得(callback に state を載せる)の前に要るので、保存とは別に作る。 */
    public String newState(Long projectId) {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        return projectId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public Pending create(String state, Long projectId, Long siteId, String actorSub, String consumerKey,
            String consumerSecret, String requestToken, String requestTokenSecret) {
        Instant now = clock.instant();
        pendings.values().removeIf(pending -> pending.expiresAt().isBefore(now));
        Pending pending = new Pending(state, projectId, siteId, actorSub, consumerKey, consumerSecret, requestToken,
                requestTokenSecret, now.plus(ttl));
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

    /** 認可の途中経過。秘密を持つので、値は表示しない。 */
    public record Pending(
            String state,
            Long projectId,
            Long siteId,
            String actorSub,
            String consumerKey,
            String consumerSecret,
            String requestToken,
            String requestTokenSecret,
            Instant expiresAt) {

        @Override
        public String toString() {
            return "Pending[state=" + state + ", projectId=" + projectId + "]";
        }
    }
}
