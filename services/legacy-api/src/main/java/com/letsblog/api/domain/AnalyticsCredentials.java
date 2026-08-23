package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * プロジェクト単位のGoogle Analytics/AdSense連携設定(issue #571)。projects god-tableの分割で、
 * analyticsサービスが概念上所有する設定を切り出したもの。projectIdは外部キーではなく参照キーとして
 * 保持する(将来analyticsサービスが物理分離された際にクロスDB外部キーにならないようにするため)。
 */
@Entity
@Table(name = "analytics_credentials")
@Getter
@Setter
@NoArgsConstructor
public class AnalyticsCredentials {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, unique = true)
    private Long projectId;

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

    /**
     * AdSense連携用のGoogle OAuthクライアント(issue #407)。以前はアプリ全体で1つの環境変数/システム設定
     * (GOOGLE_OAUTH_CLIENT_ID/SECRET)だったが、プロジェクトごとに異なるGoogle Cloudプロジェクトを
     * 使い分けられるようプロジェクト単位に変更した。client_idは秘匿情報ではないので平文で保持する。
     */
    @Column(name = "adsense_oauth_client_id", length = 255)
    private String adsenseOauthClientId;

    @Column(name = "adsense_oauth_client_secret_encrypted", columnDefinition = "VARBINARY(1024)")
    private byte[] adsenseOauthClientSecretEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public AnalyticsCredentials(Long projectId) {
        this.projectId = projectId;
    }

    public boolean hasGoogleAnalyticsCredentials() {
        return gaPropertyId != null && !gaPropertyId.isBlank()
                && gaServiceAccountJsonEncrypted != null && gaServiceAccountJsonEncrypted.length > 0;
    }

    public boolean hasAdsenseCredentials() {
        return adsenseAccountId != null && !adsenseAccountId.isBlank()
                && adsenseRefreshTokenEncrypted != null && adsenseRefreshTokenEncrypted.length > 0;
    }

    public boolean hasAdsenseOauthClientSecret() {
        return adsenseOauthClientSecretEncrypted != null && adsenseOauthClientSecretEncrypted.length > 0;
    }

    public boolean hasAdsenseOauthClient() {
        return adsenseOauthClientId != null && !adsenseOauthClientId.isBlank() && hasAdsenseOauthClientSecret();
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
