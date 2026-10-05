package com.letsblog.project.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 認可の途中経過(クライアントの秘密・PKCE の検証子)をメモリにだけ短時間持つ入れ物(issue #1574)。
 * DB には書かない。1回取り出したら消え、期限が切れたものは取り出せない。
 */
class XAuthorizationStoreTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-04T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final XAuthorizationStore store = new XAuthorizationStore(clock, Duration.ofMinutes(10));

    @Test
    void 作った認可は一度だけ取り出せる() {
        XAuthorizationStore.Pending pending = store.create(7L, 3L, "sub-1", "cid", "csecret", "https://l/cb");

        Optional<XAuthorizationStore.Pending> first = store.take(pending.state());
        Optional<XAuthorizationStore.Pending> second = store.take(pending.state());

        assertTrue(first.isPresent());
        assertEquals(7L, first.get().projectId());
        assertEquals(3L, first.get().siteId());
        assertEquals("sub-1", first.get().actorSub());
        assertEquals("cid", first.get().clientId());
        assertEquals("csecret", first.get().clientSecret());
        assertEquals("https://l/cb", first.get().redirectUri());
        assertTrue(second.isEmpty());
    }

    @Test
    void stateはプロジェクトIDで始まり_毎回異なる() {
        XAuthorizationStore.Pending a = store.create(7L, 3L, "s", "c", "x", "r");
        XAuthorizationStore.Pending b = store.create(7L, 3L, "s", "c", "x", "r");

        assertTrue(a.state().startsWith("7."));
        assertNotEquals(a.state(), b.state());
        assertNotEquals(a.codeVerifier(), b.codeVerifier());
    }

    @Test
    void 検証子は43文字以上で_チャレンジはRFC7636の例と一致する() {
        XAuthorizationStore.Pending pending = store.create(7L, 3L, "s", "c", "x", "r");
        assertTrue(pending.codeVerifier().length() >= 43);

        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                XAuthorizationStore.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assertEquals(XAuthorizationStore.codeChallenge(pending.codeVerifier()), pending.codeChallenge());
    }

    @Test
    void 期限が切れた認可は取り出せない() {
        XAuthorizationStore.Pending pending = store.create(7L, 3L, "s", "c", "x", "r");

        clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        assertTrue(store.take(pending.state()).isEmpty());
    }

    @Test
    void 期限内なら取り出せる() {
        XAuthorizationStore.Pending pending = store.create(7L, 3L, "s", "c", "x", "r");

        clock.advance(Duration.ofMinutes(9));

        assertTrue(store.take(pending.state()).isPresent());
    }

    @Test
    void 知らないstateとnullは空() {
        assertTrue(store.take("nope").isEmpty());
        assertTrue(store.take(null).isEmpty());
    }

    @Test
    void 新しく作るとき期限切れの認可は掃除される() {
        XAuthorizationStore.Pending old = store.create(7L, 3L, "s", "c", "x", "r");
        clock.advance(Duration.ofMinutes(11));

        store.create(8L, 4L, "s", "c", "x", "r");

        assertEquals(1, store.size());
        assertTrue(store.take(old.state()).isEmpty());
    }

    @Test
    void 既定のコンストラクタで使える() {
        XAuthorizationStore defaults = new XAuthorizationStore();

        XAuthorizationStore.Pending pending = defaults.create(1L, 2L, "s", "c", "x", "r");

        assertTrue(defaults.take(pending.state()).isPresent());
    }
}
