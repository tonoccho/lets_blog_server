package com.letsblog.project.domain;

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

    /**
     * 最後に選択されたプリセットID。レンダリングには使わない、UI表示用。
     * customCssが設定されている場合、下記3色はUI上でのCSS再生成の元になる値であり、
     * レンダリングされるCSSそのものには使われない(customCssが優先される)。
     */
    @Column(name = "preset_id", nullable = false, length = 50)
    private String presetId;

    @Column(name = "background_color", nullable = false, length = 7)
    private String backgroundColor;

    @Column(name = "text_color", nullable = false, length = 7)
    private String textColor;

    @Column(name = "accent_color", nullable = false, length = 7)
    private String accentColor;

    /**
     * 任意の生CSS。設定されていれば、背景色/テキスト色/アクセントカラーから組み立てる標準CSSの
     * 代わりにこちらを丸ごと使う(完全上書き、issue #165)。未設定時のみ上記3色から生成する。
     */
    @Column(name = "custom_css", columnDefinition = "TEXT")
    private String customCss;

    /**
     * 標準のHTML構造を置き換える任意のテンプレート。プレースホルダは種別ごとに固定
     * (BLOGCARD/AMAZON: {{title}}等のデータ項目、TOC: 生成された目次全体を表す{{toc}}のみ)。
     * 未設定時は従来どおりのハードコードされたHTML構造を使う。
     */
    @Column(name = "html_template", columnDefinition = "TEXT")
    private String htmlTemplate;

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
