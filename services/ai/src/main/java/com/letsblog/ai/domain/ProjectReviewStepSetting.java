package com.letsblog.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * プロジェクト × レビューステップ(issue #1210)ごとのAI(LLM)プロバイダー/モデル設定(issue #1211)。
 * project_ai_settings(プロジェクト既定)への行追加ではなく別テーブルにしたのは、ステップが
 * 将来増減しうるため(カラム追加だと都度マイグレーションが必要になる)。projectIdはprojectId
 * (ProjectAiSettingsと同じ理由でIDのみを参照キーとして保持、外部キーにしない。ADR-0004)。
 */
@Entity
@Table(name = "project_review_step_settings")
@Getter
@Setter
@NoArgsConstructor
public class ProjectReviewStepSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_key", nullable = false, length = 30)
    private ReviewStepKey stepKey;

    @Column(name = "llm_provider", length = 20)
    private String llmProvider;

    @Column(name = "llm_model", length = 255)
    private String llmModel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ProjectReviewStepSetting(Long projectId, ReviewStepKey stepKey) {
        this.projectId = projectId;
        this.stepKey = stepKey;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
