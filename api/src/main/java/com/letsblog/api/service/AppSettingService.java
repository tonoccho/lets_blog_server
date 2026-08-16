package com.letsblog.api.service;

import com.letsblog.api.ai.LlmConfigProvider;
import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.crypto.CredentialCipher;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.SystemSetting;
import com.letsblog.api.repository.SystemSettingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * プロジェクト/サイトに紐付かない、業務系のアプリ全体設定(外部LLMサービス連携・メール送信・
 * Google OAuthクライアント・Webフロントの公開URL)をWeb管理画面(システム設定画面、issue #403)から
 * 編集可能にする。SystemSettingService(Brave Search APIキー)と同じ仕組み(system_settingsテーブルに
 * CredentialCipherでAES-256-GCM暗号化して保存し、未設定時は環境変数にフォールバック)を再利用するが、
 * DB接続情報・暗号化キー自体・NEXTAUTH_SECRET・Docker内部サービス間通信設定等のインフラ系設定は
 * 誤設定時にアプリが起動不能になるリスクが高いため対象外とし、このサービスが扱うキーのみを編集対象とする。
 * Buffer連携・Brave Search APIキー・Google AdSense OAuthクライアント(issue #407)はプロジェクト単位の
 * 設定のため対象外。LlmClientからはインターフェース経由(LlmConfigProvider)で参照される
 * (aiパッケージがserviceパッケージへ依存しないようにするため)。
 */
@Service
public class AppSettingService implements LlmConfigProvider {

    static final String LLM_API_KEY = "llm_api_key";
    static final String LLM_BASE_URL = "llm_base_url";
    static final String LLM_MODEL = "llm_model";
    static final String LLM_AVAILABLE_MODELS = "llm_available_models";
    static final String LLM_REQUEST_TIMEOUT_SECONDS = "llm_request_timeout_seconds";
    static final String MAIL_HOST = "mail_host";
    static final String MAIL_PORT = "mail_port";
    static final String MAIL_USERNAME = "mail_username";
    static final String MAIL_PASSWORD = "mail_password";
    static final String APP_MAIL_FROM = "app_mail_from";
    static final String APP_WEB_BASE_URL = "app_web_base_url";

    public enum SettingSource {
        DATABASE, ENVIRONMENT, NONE
    }

    public record Definition(String key, String label, boolean secret) {
    }

    public record SettingStatus(
            String key, String label, boolean secret, boolean configured, SettingSource source, String value) {
    }

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition(LLM_API_KEY, "LLM APIキー", true),
            new Definition(LLM_BASE_URL, "LLM ベースURL", false),
            new Definition(LLM_MODEL, "LLM 既定モデル", false),
            new Definition(LLM_AVAILABLE_MODELS, "LLM 選択可能モデル(カンマ区切り)", false),
            new Definition(LLM_REQUEST_TIMEOUT_SECONDS, "LLM リクエストタイムアウト(秒)", false),
            new Definition(MAIL_HOST, "メール送信ホスト", false),
            new Definition(MAIL_PORT, "メール送信ポート", false),
            new Definition(MAIL_USERNAME, "メール送信ユーザー名", false),
            new Definition(MAIL_PASSWORD, "メール送信パスワード", true),
            new Definition(APP_MAIL_FROM, "メール送信元アドレス", false),
            new Definition(APP_WEB_BASE_URL, "Webフロントの公開URL", false));

    private final SystemSettingRepository repository;
    private final CredentialCipher credentialCipher;
    private final AdminAuthorizationService adminAuthorizationService;
    private final Map<String, String> envDefaults;

    public AppSettingService(
            SystemSettingRepository repository,
            CredentialCipher credentialCipher,
            AdminAuthorizationService adminAuthorizationService,
            @Value("${app.llm-api-key:}") String llmApiKeyEnvDefault,
            @Value("${app.llm-base-url}") String llmBaseUrlEnvDefault,
            @Value("${app.llm-model}") String llmModelEnvDefault,
            @Value("${app.llm-available-models}") String llmAvailableModelsEnvDefault,
            @Value("${app.llm-request-timeout-seconds}") String llmRequestTimeoutSecondsEnvDefault,
            @Value("${spring.mail.host}") String mailHostEnvDefault,
            @Value("${spring.mail.port}") String mailPortEnvDefault,
            @Value("${spring.mail.username:}") String mailUsernameEnvDefault,
            @Value("${spring.mail.password:}") String mailPasswordEnvDefault,
            @Value("${app.mail.from}") String appMailFromEnvDefault,
            @Value("${app.web.base-url}") String appWebBaseUrlEnvDefault) {
        this.repository = repository;
        this.credentialCipher = credentialCipher;
        this.adminAuthorizationService = adminAuthorizationService;
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put(LLM_API_KEY, llmApiKeyEnvDefault);
        defaults.put(LLM_BASE_URL, llmBaseUrlEnvDefault);
        defaults.put(LLM_MODEL, llmModelEnvDefault);
        defaults.put(LLM_AVAILABLE_MODELS, llmAvailableModelsEnvDefault);
        defaults.put(LLM_REQUEST_TIMEOUT_SECONDS, llmRequestTimeoutSecondsEnvDefault);
        defaults.put(MAIL_HOST, mailHostEnvDefault);
        defaults.put(MAIL_PORT, mailPortEnvDefault);
        defaults.put(MAIL_USERNAME, mailUsernameEnvDefault);
        defaults.put(MAIL_PASSWORD, mailPasswordEnvDefault);
        defaults.put(APP_MAIL_FROM, appMailFromEnvDefault);
        defaults.put(APP_WEB_BASE_URL, appWebBaseUrlEnvDefault);
        this.envDefaults = defaults;
    }

    // ---- Web管理画面向け ----

    @Transactional(readOnly = true)
    public List<SettingStatus> getAllSettings() {
        adminAuthorizationService.requireAdmin();
        List<SettingStatus> result = new ArrayList<>();
        for (Definition definition : DEFINITIONS) {
            result.add(buildStatus(definition));
        }
        return result;
    }

    private SettingStatus buildStatus(Definition definition) {
        String dbValue = repository.findById(definition.key())
                .map(setting -> credentialCipher.decrypt(setting.getSettingValueEncrypted()))
                .filter(value -> !value.isBlank())
                .orElse(null);
        if (dbValue != null) {
            return new SettingStatus(
                    definition.key(), definition.label(), definition.secret(), true, SettingSource.DATABASE,
                    definition.secret() ? null : dbValue);
        }
        String envDefault = envDefaults.get(definition.key());
        if (envDefault != null && !envDefault.isBlank()) {
            return new SettingStatus(
                    definition.key(), definition.label(), definition.secret(), true, SettingSource.ENVIRONMENT,
                    definition.secret() ? null : envDefault);
        }
        return new SettingStatus(
                definition.key(), definition.label(), definition.secret(), false, SettingSource.NONE, null);
    }

    /**
     * 複数項目をまとめて更新する。1つのトランザクションとして扱い、いずれかの値が不正な場合は
     * 例外をスローして全ての変更をロールバックする(保存の一部だけが反映された状態になることを防ぐ)。
     * 値が空文字列の場合はDB設定を削除し、環境変数の値へフォールバックする(未設定に戻す)。
     */
    @AuditLog(action = AuditLogAction.SYSTEM_SETTING_UPDATED, resourceType = "SYSTEM_SETTING")
    @Transactional
    public void updateSettings(Map<String, String> updates) {
        adminAuthorizationService.requireAdmin();
        for (String key : updates.keySet()) {
            if (DEFINITIONS.stream().noneMatch(definition -> definition.key().equals(key))) {
                throw new IllegalArgumentException("不明な設定項目です: " + key);
            }
        }
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            validate(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            applyUpdate(entry.getKey(), entry.getValue());
        }
    }

    private void applyUpdate(String key, String value) {
        if (value == null || value.isBlank()) {
            repository.deleteById(key);
            return;
        }
        SystemSetting setting = repository.findById(key).orElseGet(() -> new SystemSetting(key, null));
        setting.setSettingValueEncrypted(credentialCipher.encrypt(value));
        repository.save(setting);
    }

    private void validate(String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        switch (key) {
            case LLM_BASE_URL, APP_WEB_BASE_URL -> requireUrl(key, value);
            case LLM_REQUEST_TIMEOUT_SECONDS -> requirePositiveInt(key, value);
            case MAIL_PORT -> requirePort(key, value);
            case APP_MAIL_FROM -> requireEmailLike(key, value);
            default -> {
                // その他の項目(APIキー・ホスト名・モデル名等)は形式チェックを行わない。
            }
        }
    }

    private void requireUrl(String key, String value) {
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            throw new IllegalArgumentException(key + " はhttp://またはhttps://から始まるURLを指定してください");
        }
    }

    private void requirePositiveInt(String key, String value) {
        try {
            if (Integer.parseInt(value) <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " は正の整数を指定してください", e);
        }
    }

    private void requirePort(String key, String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1 || parsed > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " は1〜65535の整数を指定してください", e);
        }
    }

    private void requireEmailLike(String key, String value) {
        if (!value.contains("@")) {
            throw new IllegalArgumentException(key + " はメールアドレスの形式で指定してください");
        }
    }

    // ---- 実際の値の解決(各クライアント/サービスから呼ばれる) ----

    private String resolve(String key) {
        return repository.findById(key)
                .map(setting -> credentialCipher.decrypt(setting.getSettingValueEncrypted()))
                .filter(value -> !value.isBlank())
                .orElseGet(() -> envDefaults.getOrDefault(key, ""));
    }

    @Transactional(readOnly = true)
    public String getLlmApiKey() {
        return resolve(LLM_API_KEY);
    }

    @Transactional(readOnly = true)
    public String getLlmBaseUrl() {
        return resolve(LLM_BASE_URL);
    }

    @Transactional(readOnly = true)
    public String getLlmModel() {
        return resolve(LLM_MODEL);
    }

    @Transactional(readOnly = true)
    public String getLlmAvailableModels() {
        return resolve(LLM_AVAILABLE_MODELS);
    }

    @Transactional(readOnly = true)
    public long getLlmRequestTimeoutSeconds() {
        return Long.parseLong(resolve(LLM_REQUEST_TIMEOUT_SECONDS));
    }

    @Transactional(readOnly = true)
    public String getMailHost() {
        return resolve(MAIL_HOST);
    }

    @Transactional(readOnly = true)
    public int getMailPort() {
        return Integer.parseInt(resolve(MAIL_PORT));
    }

    @Transactional(readOnly = true)
    public String getMailUsername() {
        return resolve(MAIL_USERNAME);
    }

    @Transactional(readOnly = true)
    public String getMailPassword() {
        return resolve(MAIL_PASSWORD);
    }

    @Transactional(readOnly = true)
    public String getMailFrom() {
        return resolve(APP_MAIL_FROM);
    }

    @Transactional(readOnly = true)
    public String getAppWebBaseUrl() {
        return resolve(APP_WEB_BASE_URL);
    }

    @Override
    public String baseUrl() {
        return getLlmBaseUrl();
    }

    @Override
    public String apiKey() {
        return getLlmApiKey();
    }

    @Override
    public String defaultModel() {
        return getLlmModel();
    }

    @Override
    public long requestTimeoutSeconds() {
        return getLlmRequestTimeoutSeconds();
    }
}
