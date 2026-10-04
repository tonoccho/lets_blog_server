package com.letsblog.project.domain;

import com.letsblog.project.cms.CmsType;
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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * project-serviceが所有する{@code sites}(issue #577 stage2)。サイトのCMS認証情報(暗号化保存)は
 * ここが正となる(#577受入基準)。legacy-apiの{@code com.letsblog.api.domain.Site}は、まだこのstageでは
 * 移設しない他のlegacy-apiドメイン(publishing pipeline・analytics report等)から広く参照されているため、
 * 削除せず据え置く(詳細はPR説明を参照)。
 */
@Entity
@Table(name = "sites")
@Getter
@Setter
@NoArgsConstructor
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "site_key", nullable = false, unique = true, length = 100)
    private String siteKey;

    @Column(name = "cms_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private CmsType cmsType;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    // CMS種別を問わない汎用の暗号化認証情報(JSON化してAES-256-GCMで暗号化)
    @Column(name = "credentials_encrypted")
    private byte[] credentialsEncrypted;

    // WordPress自動プロビジョニング(常駐wordpressコンテナへのサブディレクトリ設置)で作成されたサイトかどうか。
    @Column(name = "managed_wordpress", nullable = false)
    private boolean managedWordpress = false;

    @Column(name = "wp_slug", length = 100)
    private String wpSlug;

    @Column(name = "wp_db_name", length = 100)
    private String wpDbName;

    // 管理画面パスのサイト個別の上書き。NULLはグローバル既定を使う(issue #1081)。
    @Column(name = "admin_path", length = 200)
    private String adminPath;

    // letsblogプラグインへの同期の状態(issue #1558)。statusがnullならまだ一度も同期していない。
    @Column(name = "letsblog_sync_status", length = 20)
    @Enumerated(EnumType.STRING)
    private LetsblogSyncStatus letsblogSyncStatus;

    @Column(name = "letsblog_sync_error", columnDefinition = "TEXT")
    private String letsblogSyncError;

    @Column(name = "letsblog_sync_hash", length = 64)
    private String letsblogSyncHash;

    @Column(name = "letsblog_synced_at")
    private LocalDateTime letsblogSyncedAt;

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
