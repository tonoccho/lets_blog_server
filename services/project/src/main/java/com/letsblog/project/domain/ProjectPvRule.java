package com.letsblog.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * プロジェクトの PV 達成ルール(issue #1578)。正本はアプリで、本番サイトのプラグインへはルール全件を
 * 丸ごと送って置き換える。{@code period}は {@code daily}(1日)か {@code total}(累計)。
 */
@Entity
@Table(name = "project_pv_rules")
@Getter
@Setter
@NoArgsConstructor
public class ProjectPvRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "period", nullable = false, length = 10)
    private String period;

    @Column(name = "threshold", nullable = false)
    private int threshold;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
