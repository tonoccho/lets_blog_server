package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "local_site_id")
    private Long localSiteId;

    @Column(name = "test_site_id")
    private Long testSiteId;

    @Column(name = "production_site_id")
    private Long productionSiteId;

    /**
     * カテゴリ/タグ/プラグイン/テーマの比較テーブル(Phase12)における「正」の環境。
     * test/productionのいずれかのみを許容する(ローカルはマスターにできない)。
     */
    @Column(name = "master_environment", nullable = false, length = 20)
    private String masterEnvironment = "test";

    @Column(name = "github_repository", length = 255)
    private String githubRepository;

    /**
     * カスタムタグCSSのセレクタに自動付与するプリフィックス(issue #298)。未設定時はslugを使う
     * (CustomTagService#resolveCssSelectorPrefixで解決)。
     */
    @Column(name = "css_selector_prefix", length = 100)
    private String cssSelectorPrefix;

    /**
     * 画像生成時に既定で使うnegative prompt/画質プロンプト(issue #293)。未設定時はアプリ全体の
     * デフォルト(application.yml)にフォールバックする(ProjectService#resolveDefaultNegativePrompt等)。
     */
    @Column(name = "default_negative_prompt", length = 1000)
    private String defaultNegativePrompt;

    @Column(name = "default_quality_prompt", length = 500)
    private String defaultQualityPrompt;

    /**
     * 画像生成時に既定で使う生成サイズ(issue #292)。未設定時はアプリ全体のデフォルト(1920x1080)に
     * フォールバックする(ProjectService#resolveDefaultGeneratedImageWidth等)。
     */
    @Column(name = "default_generated_image_width")
    private Integer defaultGeneratedImageWidth;

    @Column(name = "default_generated_image_height")
    private Integer defaultGeneratedImageHeight;

    /**
     * 記事投稿時に本文/アイキャッチ画像をリサイズする長編の目標px(issue #291)。未設定時は
     * アプリ全体のデフォルト(既定1300)にフォールバックする(ProjectService#resolveArticleImageLongEdgePx)。
     */
    @Column(name = "default_article_image_long_edge_px")
    private Integer defaultArticleImageLongEdgePx;

    @Column(name = "llm_model", length = 255)
    private String llmModel;

    @Column(name = "comfyui_checkpoint", length = 255)
    private String comfyuiCheckpoint;

    @Column(name = "github_token_encrypted", columnDefinition = "VARBINARY(1024)")
    private byte[] githubTokenEncrypted;

    @Column(name = "brave_search_api_key_encrypted", columnDefinition = "VARBINARY(1024)")
    private byte[] braveSearchApiKeyEncrypted;

    /**
     * Google Analytics連携(issue #386)。GA4プロパティID自体は秘匿情報ではないので平文で保持し、
     * サービスアカウントの認証情報(JSON鍵ファイル全体)のみ暗号化して保持する。
     */
    @Column(name = "ga_property_id", length = 64)
    private String gaPropertyId;

    @Column(name = "ga_service_account_json_encrypted", columnDefinition = "VARBINARY(4096)")
    private byte[] gaServiceAccountJsonEncrypted;

    /**
     * Google AdSense連携(issue #387)。AdSense Management APIはサービスアカウント委任に対応していないため、
     * GAとは異なり3-legged OAuth(認可コード→リフレッシュトークン)で取得したリフレッシュトークンを保持する。
     * アカウントID(パブリッシャーID、例: pub-1234567890123456)自体は秘匿情報ではないので平文で保持する。
     */
    @Column(name = "adsense_account_id", length = 64)
    private String adsenseAccountId;

    @Column(name = "adsense_refresh_token_encrypted", columnDefinition = "VARBINARY(1024)")
    private byte[] adsenseRefreshTokenEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isGithubRepositoryConfigured() {
        return githubRepository != null && !githubRepository.isBlank();
    }

    public boolean hasGithubToken() {
        return githubTokenEncrypted != null && githubTokenEncrypted.length > 0;
    }

    public boolean hasBraveSearchApiKey() {
        return braveSearchApiKeyEncrypted != null && braveSearchApiKeyEncrypted.length > 0;
    }

    public boolean hasGoogleAnalyticsCredentials() {
        return gaPropertyId != null && !gaPropertyId.isBlank()
                && gaServiceAccountJsonEncrypted != null && gaServiceAccountJsonEncrypted.length > 0;
    }

    public boolean hasAdsenseCredentials() {
        return adsenseAccountId != null && !adsenseAccountId.isBlank()
                && adsenseRefreshTokenEncrypted != null && adsenseRefreshTokenEncrypted.length > 0;
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
