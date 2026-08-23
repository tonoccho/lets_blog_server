package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * プロジェクト単位のコンテンツ(content)関連設定(issue #571)。projects god-tableの分割で、contentサービスが
 * 概念上所有する設定を切り出したもの。projectIdは外部キーではなく参照キーとして保持する
 * (将来contentサービスが物理分離された際にクロスDB外部キーにならないようにするため)。
 */
@Entity
@Table(name = "project_content_settings")
@Getter
@Setter
@NoArgsConstructor
public class ProjectContentSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, unique = true)
    private Long projectId;

    /**
     * カスタムタグCSSのセレクタに自動付与するプリフィックス(issue #298)。未設定時はslugを使う
     * (ProjectContentSettingsService#resolveCssSelectorPrefixで解決)。
     */
    @Column(name = "css_selector_prefix", length = 100)
    private String cssSelectorPrefix;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ProjectContentSettings(Long projectId) {
        this.projectId = projectId;
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
