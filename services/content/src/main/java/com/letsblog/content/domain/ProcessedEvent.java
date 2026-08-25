package com.letsblog.content.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * letsblog.eventsドメインイベント(issue #580)の冪等な受信を保証するための処理済みevent_id記録。
 * {@code event_id}にUNIQUE制約(PK)を持たせ、EventMessageListenerが
 * {@link com.letsblog.common.messaging.ProcessedEventStore}実装(ProcessedEventStoreImpl)経由で
 * 「初回配信か再配信か」を判定する。
 */
@Entity
@Table(name = "processed_events")
@Getter
@Setter
@NoArgsConstructor
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public ProcessedEvent(String eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
    }

    @PrePersist
    void onCreate() {
        this.processedAt = LocalDateTime.now();
    }
}
