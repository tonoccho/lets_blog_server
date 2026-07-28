package com.letsblog.api.domain;

import com.letsblog.api.cms.CmsType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

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

    // 既存WordPressサイト向けの後方互換カラム(新規登録サイトはcredentialsEncryptedを使う)
    @Column(name = "wp_username")
    private String wpUsername;

    @Column(name = "wp_app_password_encrypted")
    private byte[] wpAppPasswordEncrypted;

    // CMS種別を問わない汎用の暗号化認証情報(JSON化してAES-256-GCMで暗号化)
    @Column(name = "credentials_encrypted")
    private byte[] credentialsEncrypted;

    // WordPress自動プロビジョニング(常駐wordpressコンテナへのサブディレクトリ設置)で作成されたサイトかどうか。
    // trueの場合、サイト削除時にWPインスタンス・専用DBも連動削除する。
    @Column(name = "managed_wordpress", nullable = false)
    private boolean managedWordpress = false;

    @Column(name = "wp_slug", length = 100)
    private String wpSlug;

    @Column(name = "wp_db_name", length = 100)
    private String wpDbName;

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
