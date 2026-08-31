package com.letsblog.media.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * プロジェクト単位の画像生成(media)関連設定(issue #571)。projects god-tableの分割で、mediaサービスが
 * 概念上所有する設定を切り出したもの。projectIdは外部キーではなく参照キーとして保持する
 * (将来mediaサービスが物理分離された際にクロスDB外部キーにならないようにするため)。
 */
@Entity
@Table(name = "project_image_settings")
@Getter
@Setter
@NoArgsConstructor
public class ProjectImageSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, unique = true)
    private Long projectId;

    /**
     * プロジェクト単位の画像生成AI既定値(COMFYUI/CHATGPT、issue #531)。未設定時はCOMFYUIとして扱う
     * (ImageModelService#getSelectedProvider、既存の動作を維持するため)。
     */
    @Column(name = "image_provider", length = 20)
    private String imageProvider;

    @Column(name = "comfyui_checkpoint", length = 255)
    private String comfyuiCheckpoint;

    /**
     * 画像生成時に既定で使うnegative prompt/画質プロンプト(issue #293)。未設定時はアプリ全体の
     * デフォルト(application.yml)にフォールバックする(ProjectImageSettingsService#resolveDefaultNegativePrompt等)。
     */
    @Column(name = "default_negative_prompt", length = 1000)
    private String defaultNegativePrompt;

    @Column(name = "default_quality_prompt", length = 500)
    private String defaultQualityPrompt;

    /**
     * 画像生成時に既定で使う生成サイズ(issue #292)。未設定時はアプリ全体のデフォルト(1920x1080)に
     * フォールバックする(ProjectImageSettingsService#resolveDefaultGeneratedImageWidth等)。
     */
    @Column(name = "default_generated_image_width")
    private Integer defaultGeneratedImageWidth;

    @Column(name = "default_generated_image_height")
    private Integer defaultGeneratedImageHeight;

    /**
     * 記事投稿時に本文/アイキャッチ画像をリサイズする長編の目標px(issue #291)。未設定時は
     * アプリ全体のデフォルト(既定1300)にフォールバックする(ProjectImageSettingsService#resolveArticleImageLongEdgePx)。
     */
    @Column(name = "default_article_image_long_edge_px")
    private Integer defaultArticleImageLongEdgePx;

    /**
     * 画像生成時の不適切コンテンツフィルタ設定(issue #532)。カテゴリごとに生成を禁止するかどうかを保持する。
     * 未設定(null)時はアプリ全体のデフォルト(application.yml、既定は全カテゴリ禁止=true)にフォールバックする
     * (ProjectImageSettingsService#resolveBlockSexualContent等)。
     */
    @Column(name = "block_sexual_content")
    private Boolean blockSexualContent;

    @Column(name = "block_violent_content")
    private Boolean blockViolentContent;

    @Column(name = "block_discriminatory_content")
    private Boolean blockDiscriminatoryContent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ProjectImageSettings(Long projectId) {
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
