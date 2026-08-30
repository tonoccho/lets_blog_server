package com.letsblog.platform.service;

import com.letsblog.platform.aop.AuditLog;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.platform.domain.AuditLogAction;
import com.letsblog.platform.domain.SystemSetting;
import com.letsblog.platform.repository.SystemSettingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * プロジェクト/サイトに紐付かないアプリ全体のグローバル設定(現状はBrave Search APIキーのみ)を
 * 管理する。DBに値が保存されていればそれを使い(admin向けWeb管理画面から設定可能)、
 * 未設定の場合は .env 由来の環境変数値(app.brave-search-api-key)にフォールバックする
 * (既存の環境変数のみでの運用も引き続き機能する)。値はCredentialCipherでAES-256-GCM暗号化して保存する。
 * legacy-apiから移設(issue #693)。ai-serviceのBrave Search APIキー解決はlegacy-apiの
 * PlatformServiceClient経由の内部ブリッジ(InternalPlatformSettingsController)を通じて
 * 引き続き提供する。
 */
@Service
public class SystemSettingService {

    public static final String BRAVE_SEARCH_API_KEY = "brave_search_api_key";

    private final SystemSettingRepository repository;
    private final CredentialCipher credentialCipher;
    private final AdminAuthorizationService adminAuthorizationService;
    private final String braveSearchApiKeyEnvDefault;

    public SystemSettingService(
            SystemSettingRepository repository,
            CredentialCipher credentialCipher,
            AdminAuthorizationService adminAuthorizationService,
            @Value("${app.brave-search-api-key:}") String braveSearchApiKeyEnvDefault) {
        this.repository = repository;
        this.credentialCipher = credentialCipher;
        this.adminAuthorizationService = adminAuthorizationService;
        this.braveSearchApiKeyEnvDefault = braveSearchApiKeyEnvDefault;
    }

    public enum SettingSource {
        DATABASE, ENVIRONMENT, NONE
    }

    public record BraveSearchApiKeyStatus(boolean configured, SettingSource source) {
    }

    /**
     * Brave Search APIキーの実際の値を返す(InternalPlatformSettingsController経由でlegacy-apiの
     * AiBridgeController/ai-serviceが呼び出す)。DB設定があればそれを優先し、なければ環境変数値
     * (空文字列の場合もある)を返す。
     */
    @Transactional(readOnly = true)
    public String getBraveSearchApiKey() {
        return repository.findById(BRAVE_SEARCH_API_KEY)
                .map(setting -> credentialCipher.decrypt(setting.getSettingValueEncrypted()))
                .filter(value -> !value.isBlank())
                .orElse(braveSearchApiKeyEnvDefault);
    }

    /**
     * Web管理画面向け({@code GET /api/system-settings/brave-search-api-key}経由): 実際のキー値は
     * 返さず、設定済みかどうかと設定元のみを返す(site credentialsのconfiguredSecretFieldsと同じ
     * 「値は見せない」方針)。
     *
     * <p>legacy-api版はSecurityConfigが{@code anyRequest().authenticated()}だったため、このメソッド
     * 自体にadminチェックが無くても未ログインでは到達できなかった。platform-service版は他の抽出済み
     * サービスと同じくSecurityConfigが全経路permitAllのため、ここで最低限「ログイン済みであること」を
     * 明示的に要求する(issue #693のレビュー指摘。admin限定にはしない。設定済みか否か・設定元のみを
     * 返す読み取り専用エンドポイントであり、legacy-api版も元々admin以外の認証済みユーザーからも
     * 到達可能だったため)。
     *
     * <p>ai-service向けブリッジ({@link com.letsblog.platform.controller.InternalPlatformSettingsController})
     * やConnectedServiceStatusService(legacy-api)からのPlatformServiceClient経由の呼び出しはサービス
     * 間の内部呼び出しで認証コンテキストを持たないため、このメソッドではなく
     * {@link #getBraveSearchApiKeyStatusInternal()}を使う。
     */
    @Transactional(readOnly = true)
    public BraveSearchApiKeyStatus getBraveSearchApiKeyStatus() {
        adminAuthorizationService.requireAuthenticated();
        return resolveBraveSearchApiKeyStatus();
    }

    /**
     * {@link #getBraveSearchApiKeyStatus()}と同じ値を、認可チェック無しで返す。認証コンテキストを
     * 持たない内部呼び出し専用(issue #693のレビュー指摘)。{@link ConnectedServiceStatusService}
     * (issue #695、C10-3でlegacy-apiから本サービスへ移設)の定期疎通チェックが同一プロセス内から
     * 呼ぶ。ユーザー向けControllerからは呼ばないこと。
     *
     * <p>移設前(#693〜#695の間)は、legacy-apiに残っていたConnectedServiceStatusServiceが
     * PlatformServiceClient経由のHTTPブリッジ({@code InternalPlatformSettingsController}の
     * {@code /api/internal/platform/system-settings/brave-search-api-key-status})を介して
     * このメソッドを呼んでいたが、#695でConnectedServiceStatusService自体が本サービスへ移設された
     * ことで不要になり、当該ブリッジエンドポイントは撤去した。
     */
    @Transactional(readOnly = true)
    public BraveSearchApiKeyStatus getBraveSearchApiKeyStatusInternal() {
        return resolveBraveSearchApiKeyStatus();
    }

    private BraveSearchApiKeyStatus resolveBraveSearchApiKeyStatus() {
        boolean inDatabase = repository.findById(BRAVE_SEARCH_API_KEY)
                .map(SystemSetting::getSettingValueEncrypted)
                .map(v -> !credentialCipher.decrypt(v).isBlank())
                .orElse(false);
        if (inDatabase) {
            return new BraveSearchApiKeyStatus(true, SettingSource.DATABASE);
        }
        if (braveSearchApiKeyEnvDefault != null && !braveSearchApiKeyEnvDefault.isBlank()) {
            return new BraveSearchApiKeyStatus(true, SettingSource.ENVIRONMENT);
        }
        return new BraveSearchApiKeyStatus(false, SettingSource.NONE);
    }

    @AuditLog(action = AuditLogAction.SYSTEM_SETTING_UPDATED, resourceType = "SYSTEM_SETTING")
    @Transactional
    public void setBraveSearchApiKey(String plainKey) {
        adminAuthorizationService.requireAdmin();
        if (plainKey == null || plainKey.isBlank()) {
            throw new IllegalArgumentException("APIキーを入力してください");
        }
        SystemSetting setting = repository.findById(BRAVE_SEARCH_API_KEY)
                .orElseGet(() -> new SystemSetting(BRAVE_SEARCH_API_KEY, null));
        setting.setSettingValueEncrypted(credentialCipher.encrypt(plainKey));
        repository.save(setting);
    }

    @AuditLog(action = AuditLogAction.SYSTEM_SETTING_UPDATED, resourceType = "SYSTEM_SETTING")
    @Transactional
    public void clearBraveSearchApiKey() {
        adminAuthorizationService.requireAdmin();
        repository.deleteById(BRAVE_SEARCH_API_KEY);
    }
}
