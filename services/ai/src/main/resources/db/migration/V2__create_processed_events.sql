-- letsblog.eventsドメインイベントの冪等な受信を保証するための処理済みevent_id記録テーブル
-- (issue #580)。RabbitMQはat-least-once配送のため、同一イベントが複数回配送されうる。
-- event_idにUNIQUE制約を持たせ、コンシューマー(EventMessageListener)はINSERT成功可否で
-- 「初回配信か再配信か」を判定する(com.letsblog.common.messaging.ProcessedEventStoreの実装契約)。
CREATE TABLE processed_events (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY COMMENT 'ドメインイベントのeventId(UUID)',
    event_type VARCHAR(100) NOT NULL COMMENT 'ルーティングキー(例: project.deleted)',
    processed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_processed_events_event_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
