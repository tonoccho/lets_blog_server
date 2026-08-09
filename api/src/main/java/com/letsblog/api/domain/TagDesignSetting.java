package com.letsblog.api.domain;

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
 * プロジェクトごとの組み込みタグ([toc]/[blogcard]/[amazon])のデザイン設定。
 * プロジェクト×タグ種別の組み合わせごとに高々1件(project_id, tag_typeでユニーク制約)。
 * 未保存のプロジェクト/タグ種別はDesignPreset.DEFAULTの色をそのまま使う。
 */
@Entity
@Table(name = "tag_design_settings")
@Getter
@Setter
@NoArgsConstructor
public class TagDesignSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tag_type", nullable = false, length = 20)
    private EmbedTagType tagType;

    /** 最後に選択されたプリセットID。レンダリングには使わない(色は下記3列が常に確定値)、UI表示用。 */
    @Column(name = "preset_id", nullable = false, length = 50)
    private String presetId;

    @Column(name = "background_color", nullable = false, length = 7)
    private String backgroundColor;

    @Column(name = "text_color", nullable = false, length = 7)
    private String textColor;

    @Column(name = "accent_color", nullable = false, length = 7)
    private String accentColor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
