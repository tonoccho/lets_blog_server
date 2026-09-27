package com.letsblog.analytics.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * プロジェクト単位のGoogle Analytics/AdSense連携設定(issue #578でanalytics-serviceへ移設)。
 * projects god-tableの分割(#571)でlegacy-apiのanalytics_credentialsとして切り出され、その後
 * analytics-serviceの物理分離(本issue)でlbs_analyticsスキーマへ移った。projectIdは外部キーではなく
 * 参照キーとして保持する(project-serviceが未抽出のためlegacy-apiのprojectsテーブルはクロス
 * スキーマになり、ADR-0004によりFKを張れない)。
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
     * Google Analytics連携(issue #386、issue #1231でサービスアカウントJSONからユーザーOAuthへ移行)。
     * GA4プロパティID自体は秘匿情報ではないので平文で保持する。AdSenseと同じ3-legged OAuthで取得した
     * リフレッシュトークンとOAuthクライアントのシークレットのみ暗号化して保持する。
     */
    @Column(name = "ga_property_id", length = 64)
    private String gaPropertyId;

    /** GA用のGoogle OAuthクライアント。AdSenseの設定とは独立(issue #1231)。client_idは平文で保持する。 */
    @Column(name = "ga_oauth_client_id", length = 255)
    private String gaOauthClientId;

    @Column(name = "ga_oauth_client_secret_encrypted", columnDefinition = "VARBINARY(1024)")
    private byte[] gaOauthClientSecretEncrypted;

    @Column(name = "ga_refresh_token_encrypted", columnDefinition = "VARBINARY(1024)")
    private byte[] gaRefreshTokenEncrypted;

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

    /** Googleアカウントとの連携(リフレッシュトークンの保存)が済んでいるか。プロパティ選択の有無は問わない。 */
    public boolean hasGoogleAnalyticsConnection() {
        return gaRefreshTokenEncrypted != null && gaRefreshTokenEncrypted.length > 0;
    }

    /** ダッシュボードのレポート取得に必要な資格情報が揃っているか(連携済みかつプロパティ選択済み)。 */
    public boolean hasGoogleAnalyticsCredentials() {
        return gaPropertyId != null && !gaPropertyId.isBlank() && hasGoogleAnalyticsConnection();
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
