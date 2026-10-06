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
 * プロジェクトの告知文テンプレート(issue #1583)。正本はアプリで、本番サイトのプラグインへは公開時と PV 達成時を
 * まとめて丸ごと送って置き換える。空文字は「既定の告知文を使う」の意味。{@code syncState}は最後に送った結果
 * ({@code SENT} か {@code FAILED}。まだ送っていなければ null)。
 */
@Entity
@Table(name = "project_sns_templates")
@Getter
@Setter
@NoArgsConstructor
public class ProjectSnsTemplate {

    @Id
    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "publish_template", nullable = false, length = 1000)
    private String publishTemplate = "";

    @Column(name = "pv_template", nullable = false, length = 1000)
    private String pvTemplate = "";

    @Column(name = "sync_state", length = 10)
    private String syncState;

    @Column(name = "sync_error", columnDefinition = "TEXT")
    private String syncError;

    @Convert(converter = UtcInstantConverter.class)
    @Column(name = "sent_at")
    private Instant sentAt;
}
