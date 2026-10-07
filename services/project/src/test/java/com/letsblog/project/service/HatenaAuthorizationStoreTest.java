package com.letsblog.project.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * はてなの OAuth 1.0a の認可の途中経過(consumer secret・リクエストトークンとその秘密・開始した操作者)をメモリにだけ短時間持つ入れ物
 * (issue #1582)。DB には書かない。1回取り出したら消え、期限が切れたものは取り出せない。
 */
class HatenaAuthorizationStoreTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-08T00:00:00Z");

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
    private final HatenaAuthorizationStore store = new HatenaAuthorizationStore(clock, Duration.ofMinutes(10));

    private HatenaAuthorizationStore.Pending create(String state) {
        return store.create(state, 7L, 3L, "sub-1", "ck", "consumer-secret-value", "request-token", "request-secret");
    }

    @Test
    void stateはプロジェクトIDで始まり毎回異なる() {
        String first = store.newState(7L);
        String second = store.newState(7L);

        assertTrue(first.startsWith("7."));
        assertTrue(first.length() > 10);
        assertNotEquals(first, second);
    }

    @Test
    void 作った認可は一度だけ取り出せる() {
        String state = store.newState(7L);
        create(state);

        Optional<HatenaAuthorizationStore.Pending> first = store.take(state);
        Optional<HatenaAuthorizationStore.Pending> second = store.take(state);

        assertTrue(first.isPresent());
        assertEquals(state, first.get().state());
        assertEquals(7L, first.get().projectId());
        assertEquals(3L, first.get().siteId());
        assertEquals("sub-1", first.get().actorSub());
        assertEquals("ck", first.get().consumerKey());
        assertEquals("consumer-secret-value", first.get().consumerSecret());
        assertEquals("request-token", first.get().requestToken());
        assertEquals("request-secret", first.get().requestTokenSecret());
        assertTrue(second.isEmpty());
    }

    @Test
    void 期限を過ぎた認可は取り出せない() {
        String state = store.newState(7L);
        create(state);

        clock.advance(Duration.ofMinutes(11));

        assertTrue(store.take(state).isEmpty());
    }

    @Test
    void 期限内なら取り出せる() {
        String state = store.newState(7L);
        create(state);

        clock.advance(Duration.ofMinutes(9));

        assertTrue(store.take(state).isPresent());
    }

    @Test
    void 知らないstateとnullは空() {
        assertTrue(store.take("7.unknown").isEmpty());
        assertTrue(store.take(null).isEmpty());
    }

    @Test
    void 新しく作るとき期限切れの認可を片付ける() {
        create(store.newState(7L));
        clock.advance(Duration.ofMinutes(11));
        assertEquals(1, store.size());

        create(store.newState(7L));

        assertEquals(1, store.size());
    }

    @Test
    void 文字列表現に秘密を出さない() {
        String text = create(store.newState(7L)).toString();

        assertFalse(text.contains("consumer-secret-value"));
        assertFalse(text.contains("request-token"));
        assertFalse(text.contains("request-secret"));
        assertTrue(text.contains("projectId=7"));
    }

    @Test
    void 既定の期限と時計で作れる() {
        HatenaAuthorizationStore defaults = new HatenaAuthorizationStore();
        String state = defaults.newState(1L);
        defaults.create(state, 1L, 2L, "sub", "ck", "cs", "rt", "rs");

        assertTrue(defaults.take(state).isPresent());
    }
}
