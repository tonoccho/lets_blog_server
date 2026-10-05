package com.letsblog.project.service;

import com.letsblog.project.client.FacebookApiClient;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 認可後に利用者が投稿先のページを選ぶまでの、ページのトークンの入れ物(issue #1580)。メモリにだけ短時間持ち、
 * 選んだら消す。期限(既定10分)を過ぎたものは取り出せない。
 */
class FacebookPageSelectionStoreTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-06T00:00:00Z");

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
    private final FacebookPageSelectionStore store = new FacebookPageSelectionStore(clock, Duration.ofMinutes(10));
    private final List<FacebookApiClient.Page> pages = List.of(new FacebookApiClient.Page("100", "ページA", "PAGE-A"));

    @Test
    void 保存した選択肢は消すまで何度でも取り出せる() {
        store.save("7.abc", 7L, 3L, "sub-1", pages);

        assertTrue(store.find("7.abc").isPresent());
        FacebookPageSelectionStore.Selection selection = store.find("7.abc").orElseThrow();
        assertEquals(7L, selection.projectId());
        assertEquals(3L, selection.siteId());
        assertEquals("sub-1", selection.actorSub());
        assertEquals(pages, selection.pages());
    }

    @Test
    void 消したら取り出せない() {
        store.save("7.abc", 7L, 3L, "sub-1", pages);

        store.remove("7.abc");

        assertTrue(store.find("7.abc").isEmpty());
    }

    @Test
    void 期限が切れたら取り出せず_次の保存で掃除される() {
        store.save("7.abc", 7L, 3L, "sub-1", pages);
        clock.advance(Duration.ofMinutes(11));

        assertTrue(store.find("7.abc").isEmpty());
        store.save("7.def", 7L, 3L, "sub-1", pages);
        assertEquals(1, store.size());
    }

    @Test
    void 知らないstateとnullは空() {
        assertTrue(store.find("nope").isEmpty());
        assertTrue(store.find(null).isEmpty());
    }

    @Test
    void 選択肢の文字列表現にトークンは出ない() {
        String text = store.save("7.abc", 7L, 3L, "sub-1", pages).toString();

        assertFalse(text.contains("PAGE-A"));
    }
}
