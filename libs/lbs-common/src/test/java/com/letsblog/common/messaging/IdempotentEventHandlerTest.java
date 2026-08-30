package com.letsblog.common.messaging;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IdempotentEventHandler}の冪等性(issue #580)を、実際にRabbitMQを起動せず検証する。
 * 「同一event_idのイベントが2回配送されても、ビジネスロジックは1回しか実行されない」ことを
 * 保証するのがこのクラスの唯一の責務であり、ドメインイベントの受入基準
 * (「購読側の処理が冪等である」)の中核部分をここで確認する。
 */
class IdempotentEventHandlerTest {

    /** {@link ProcessedEventStore}のインメモリfake。実サービスのJPA実装のUNIQUE制約INSERT挙動を模す。 */
    private static final class InMemoryProcessedEventStore implements ProcessedEventStore {
        private final Set<String> processedEventIds = new HashSet<>();

        @Override
        public synchronized boolean markIfNotProcessed(String eventId, String eventType) {
            return processedEventIds.add(eventId);
        }
    }

    private static ProjectDeletedEvent event(String eventId) {
        return new ProjectDeletedEvent(eventId, Instant.now(), 42L);
    }

    @Test
    void 同一eventIdの重複配信ではビジネスロジックが1回しか実行されない() {
        InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();
        AtomicInteger invocationCount = new AtomicInteger();
        ProjectDeletedEvent duplicateEvent = event("11111111-1111-1111-1111-111111111111");

        IdempotentEventHandler.handle(store, duplicateEvent, "project.deleted", invocationCount::incrementAndGet);
        // RabbitMQの再配送(at-least-once)を模し、同じイベントをもう一度届ける。
        IdempotentEventHandler.handle(store, duplicateEvent, "project.deleted", invocationCount::incrementAndGet);
        IdempotentEventHandler.handle(store, duplicateEvent, "project.deleted", invocationCount::incrementAndGet);

        assertEquals(1, invocationCount.get(), "同一eventIdの再配信では2回目以降ビジネスロジックを実行してはならない");
    }

    @Test
    void eventIdが異なれば別イベントとして処理される() {
        InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();
        AtomicInteger invocationCount = new AtomicInteger();

        IdempotentEventHandler.handle(store, event("aaaa"), "project.deleted", invocationCount::incrementAndGet);
        IdempotentEventHandler.handle(store, event("bbbb"), "project.deleted", invocationCount::incrementAndGet);

        assertEquals(2, invocationCount.get());
    }

    @Test
    void markIfNotProcessedは初回true以降falseを返す() {
        InMemoryProcessedEventStore store = new InMemoryProcessedEventStore();

        assertTrue(store.markIfNotProcessed("event-1", "project.deleted"));
        assertFalse(store.markIfNotProcessed("event-1", "project.deleted"));
    }
}
