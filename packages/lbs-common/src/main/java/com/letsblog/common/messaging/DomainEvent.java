package com.letsblog.common.messaging;

import java.time.Instant;

/**
 * ドメインイベント(issue #580)共通のペイロード規約。全イベント型(record)がこのインターフェースを
 * 実装し、{@code eventId}(冪等性キー)と{@code occurredAt}を必ず持つ。
 *
 * <p>{@code eventId}はプロデューサーが発行のたびに新規採番するUUID文字列。同一イベントが
 * RabbitMQの再配送(at-least-once配送)で複数回コンシューマーに届いても、コンシューマー側は
 * この値をキーに二重処理を防ぐ({@link ProcessedEventStore}/{@link IdempotentEventHandler}参照)。
 */
public interface DomainEvent {

    String eventId();

    Instant occurredAt();
}
