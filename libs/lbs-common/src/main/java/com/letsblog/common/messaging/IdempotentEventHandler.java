package com.letsblog.common.messaging;

/**
 * ドメインイベント(issue #580)の冪等処理を行うための共通ヘルパー。RabbitMQはat-least-once配送のため
 * (リトライ・再接続・コンシューマー再起動等で同一メッセージが複数回届きうる)、コンシューマー側の
 * {@code @RabbitListener}メソッドはこのヘルパー経由でビジネスロジックを実行し、同一{@code eventId}の
 * 再配信では二重処理しないようにする。
 *
 * <p>使い方の例:
 * <pre>{@code
 * @RabbitListener(queues = "project-deleted.queue")
 * @Transactional
 * public void onProjectDeleted(ProjectDeletedEvent event) {
 *     IdempotentEventHandler.handle(processedEventStore, event, "project.deleted",
 *             () -> projectContentSettingsRepository.deleteByProjectId(event.projectId()));
 * }
 * }</pre>
 *
 * <p>{@code markIfNotProcessed}とビジネスロジックの実行は、呼び出し元のリスナーメソッドが
 * {@code @Transactional}であることを前提に同一トランザクション内で行われる想定。ビジネスロジックが
 * 例外を投げた場合はトランザクション全体がロールバックされ(processed_eventsへの記録も含む)、
 * メッセージはRabbitMQ側の再配送・リトライ(spring.rabbitmq.listener.simple.retry)に委ねられる。
 */
public final class IdempotentEventHandler {

    private IdempotentEventHandler() {
    }

    /**
     * イベントを冪等に処理する。{@code eventId}が既に処理済みであればビジネスロジックを実行せず
     * スキップする(二重処理防止)。
     */
    public static void handle(ProcessedEventStore store, DomainEvent event, String eventType, Runnable businessLogic) {
        boolean firstDelivery = store.markIfNotProcessed(event.eventId(), eventType);
        if (!firstDelivery) {
            return;
        }
        businessLogic.run();
    }
}
