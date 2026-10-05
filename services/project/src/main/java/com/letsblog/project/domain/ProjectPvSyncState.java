package com.letsblog.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * PV 達成ルールと GA4 の認証情報を、最後に本番サイトのプラグインへ送った結果(issue #1578)。
 * {@code state}は {@code SENT} か {@code FAILED}。失敗の理由には GA4 の認証情報を入れない。
 */
@Entity
@Table(name = "project_pv_sync_state")
@Getter
@Setter
@NoArgsConstructor
public class ProjectPvSyncState {

    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "state", nullable = false, length = 10)
    private String state;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Convert(converter = UtcInstantConverter.class)
    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;
}
