package com.letsblog.platform.service;

import com.letsblog.platform.ai.AiProvider;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.platform.domain.SystemSetting;
import com.letsblog.platform.repository.SystemSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AppSettingServiceの回帰テスト(issue #403)。DB設定/環境変数フォールバックの優先順位、
 * バッチ更新時のバリデーションと全項目ロールバック、admin権限ゲートを中心に検証する。
 * legacy-api版から移設(issue #693)。
 */
@ExtendWith(MockitoExtension.class)
class AppSettingServiceTest {

    @Mock
    private SystemSettingRepository repository;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private final CredentialCipher credentialCipher = new CredentialCipher(
            java.util.Base64.getEncoder().encodeToString(new byte[32]));

    private AppSettingService service() {
        return new AppSettingService(
                repository, credentialCipher, adminAuthorizationService,
                "gpt-4o-mini", "gpt-4o-mini,gpt-4o", "120",
                "OPENAI", "claude-3-5-haiku-20241022",
                "qwen2.5:7b-instruct",
                "smtp.example.com", "587", "env-user", "env-pass", "noreply@example.com",
                "http://localhost:3000", "10", "wp-admin");
    }

    // ---- ChatGPT / ClaudeのAPIキーはプロジェクト単位だけ(issue #1568) ----

    @Test
    void getAllSettings_システム全体のChatGPTとClaudeのAPIキーの項目を持たない() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        List<String> keys = service.getAllSettings().stream().map(AppSettingService.SettingStatus::key).toList();

        assertTrue(!keys.contains("llm_api_key"), "実際のキー一覧: " + keys);
        assertTrue(!keys.contains("llm_claude_api_key"), "実際のキー一覧: " + keys);
        // 画像生成のキー(#1521)と、キー以外のLLM設定は残る。
        assertTrue(keys.contains("llm_claude_model"), "実際のキー一覧: " + keys);
    }

    @Test
    void updateSettings_llm_api_keyとllm_claude_api_keyは不明なキーとして拒否され何も保存されない() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("llm_api_key", "sk-system")));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("llm_claude_api_key", "sk-ant-system")));

        verify(repository, never()).save(any());
    }

    @Test
    void getMailPort_数値として解決する() {
        AppSettingService service = service();
        when(repository.findById("mail_port"))
                .thenReturn(Optional.of(new SystemSetting("mail_port", credentialCipher.encrypt("2525"))));

        assertEquals(2525, service.getMailPort());
    }

    @Test
    void 実効llm接続設定として委譲する() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        assertEquals("https://api.openai.com/v1", service.baseUrl());
        assertEquals("gpt-4o-mini", service.defaultModel());
        assertEquals(120L, service.requestTimeoutSeconds());
        assertEquals(AiProvider.OPENAI, service.provider());
    }

    @Test
    void getLlmProvider_DB未設定なら環境変数のデフォルトにフォールバックする() {
        AppSettingService service = service();
        when(repository.findById("llm_provider")).thenReturn(Optional.empty());

        assertEquals(AiProvider.OPENAI, service.getLlmProvider());
    }

    @Test
    void getLlmProvider_DB設定を優先する() {
        AppSettingService service = service();
        when(repository.findById("llm_provider"))
                .thenReturn(Optional.of(new SystemSetting("llm_provider", credentialCipher.encrypt("CLAUDE"))));

        assertEquals(AiProvider.CLAUDE, service.getLlmProvider());
    }

    @Test
    void getComfyUiBaseUrl_DB設定があればそれを優先する() {
        AppSettingService service = service();
        when(repository.findById("comfyui_base_url")).thenReturn(Optional.of(
                new SystemSetting("comfyui_base_url", credentialCipher.encrypt("https://comfyui.example.com"))));

        assertEquals("https://comfyui.example.com", service.getComfyUiBaseUrl());
        assertEquals("https://comfyui.example.com", service.comfyUiBaseUrl());
    }

    @Test
    void getComfyUiBaseUrl_DB未設定なら環境変数の値は使わず未設定として空を返す_issue1567() {
        AppSettingService service = service();
        when(repository.findById("comfyui_base_url")).thenReturn(Optional.empty());

        assertEquals("", service.getComfyUiBaseUrl());
        assertEquals("", service.comfyUiBaseUrl());
    }

    @Test
    void 画像生成用のAPIキーはシステム設定の項目として存在しない_issue1521() {
        AppSettingService service = service();

        assertTrue(service.getAllSettings().stream().noneMatch(s -> s.key().equals("image_llm_api_key")));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("image_llm_api_key", "sk-system")));
    }

    // ---- OpenAIのベースURLはコード内の定数で、設定項目を持たない(issue #1569) ----

    @Test
    void OpenAIのベースURLの設定項目は存在せず更新も不明なキーとして拒否される_issue1569() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        List<String> keys = service.getAllSettings().stream().map(AppSettingService.SettingStatus::key).toList();
        assertTrue(!keys.contains("llm_base_url"), "実際のキー一覧: " + keys);
        assertTrue(!keys.contains("image_llm_base_url"), "実際のキー一覧: " + keys);
        assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("llm_base_url", "https://example.test/v1")));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("image_llm_base_url", "https://example.test/v1")));
        verify(repository, never()).save(any());
    }

    @Test
    void baseUrlForのOPENAIはDBに行があっても固定のOpenAIを返す_issue1569() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());
        lenient().when(repository.findById("llm_base_url")).thenReturn(Optional.of(
                new SystemSetting("llm_base_url", credentialCipher.encrypt("https://example.test/v1"))));

        assertEquals("https://api.openai.com/v1", service.baseUrlFor(AiProvider.OPENAI));
    }

    @Test
    void updateSettings_comfyui_base_urlはURL形式でなければ例外() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("comfyui_base_url", "not-a-url")));
    }

    @Test
    void updateSettings_不正なllm_providerの値は例外で保存されない() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("llm_provider", "not-a-provider")));

        verify(repository, never()).save(any());
    }

    @Test
    void getAllSettings_admin権限がなければForbidden() {
        AppSettingService service = service();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, service::getAllSettings);
    }

    @Test
    void getAllSettings_秘匿項目は値を含めない() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        List<AppSettingService.SettingStatus> statuses = service.getAllSettings();

        AppSettingService.SettingStatus mailPassword = statuses.stream()
                .filter(status -> status.key().equals("mail_password")).findFirst().orElseThrow();
        assertTrue(mailPassword.secret());
        assertEquals(null, mailPassword.value());
        assertEquals(AppSettingService.SettingSource.ENVIRONMENT, mailPassword.source());

        AppSettingService.SettingStatus mailHost = statuses.stream()
                .filter(status -> status.key().equals("mail_host")).findFirst().orElseThrow();
        assertTrue(!mailHost.secret());
        assertEquals("smtp.example.com", mailHost.value());
    }

    @Test
    void updateSettings_admin権限がなければForbidden() {
        AppSettingService service = service();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> service.updateSettings(Map.of("mail_password", "new-key")));
    }

    @Test
    void updateSettings_不明なキーは例外で全体を保存しない() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("mail_password", "new-key", "unknown_key", "value")));

        verify(repository, never()).save(any());
    }

    @Test
    void updateSettings_一部の値が不正なら他の項目も含め全てロールバックされる() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.updateSettings(
                Map.of("mail_password", "new-key", "app_web_base_url", "not-a-url")));

        verify(repository, never()).save(any());
    }

    @Test
    void updateSettings_正常な値は暗号化して保存する() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        service.updateSettings(Map.of("mail_password", "new-key", "app_web_base_url", "https://blog.example.com"));

        verify(repository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void updateSettings_空文字は既存設定を削除する() {
        AppSettingService service = service();

        service.updateSettings(Map.of("mail_password", ""));

        verify(repository).deleteById("mail_password");
        verify(repository, never()).save(any());
    }

    @Test
    void updateSettings_ポート番号は範囲外なら例外() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.updateSettings(Map.of("mail_port", "70000")));
    }

    @Test
    void updateSettings_タイムアウト秒数は正の整数でなければ例外() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("llm_request_timeout_seconds", "-1")));
    }

    @Test
    void updateSettings_メール送信元はメールアドレス形式でなければ例外() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("app_mail_from", "not-an-email")));
    }

    @Test
    void getUploadRateLimitRequests_DB未設定なら環境変数値にフォールバックする() {
        AppSettingService service = service();
        when(repository.findById("upload_rate_limit_requests")).thenReturn(Optional.empty());

        assertEquals(10, service.getUploadRateLimitRequests());
    }

    @Test
    void getUploadRateLimitRequests_DB設定があればそれを優先する() {
        AppSettingService service = service();
        when(repository.findById("upload_rate_limit_requests"))
                .thenReturn(Optional.of(
                        new SystemSetting("upload_rate_limit_requests", credentialCipher.encrypt("50"))));

        assertEquals(50, service.getUploadRateLimitRequests());
    }

    @Test
    void getUploadRateLimitRequests_無制限を表す値をそのまま返す() {
        AppSettingService service = service();
        when(repository.findById("upload_rate_limit_requests"))
                .thenReturn(Optional.of(
                        new SystemSetting("upload_rate_limit_requests", credentialCipher.encrypt("-1"))));

        assertEquals(AppSettingService.UNLIMITED, service.getUploadRateLimitRequests());
    }

    @Test
    void updateSettings_アップロードレート制限は0以下かつ無制限指定でなければ例外() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("upload_rate_limit_requests", "0")));
    }

    @Test
    void updateSettings_アップロードレート制限は数値でなければ例外() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("upload_rate_limit_requests", "abc")));
    }

    @Test
    void updateSettings_アップロードレート制限は無制限を表す値を許可する() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        service.updateSettings(Map.of("upload_rate_limit_requests", "-1"));

        verify(repository).save(any());
    }

    // ---- OLLAMA専用の接続設定キー(issue #1086 / R4) ----

    @Test
    void baseUrlFor_OLLAMAはllm_ollama_base_urlのDB値を返す() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.findById("llm_ollama_base_url")).thenReturn(Optional.of(new SystemSetting(
                "llm_ollama_base_url", credentialCipher.encrypt("http://ollama.example:11434/v1"))));

        assertEquals("http://ollama.example:11434/v1", service.baseUrlFor(AiProvider.OLLAMA));
    }

    @Test
    void defaultModelFor_OLLAMAはllm_ollama_modelのDB値を返す() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.findById("llm_ollama_model")).thenReturn(Optional.of(
                new SystemSetting("llm_ollama_model", credentialCipher.encrypt("qwen3:8b"))));

        assertEquals("qwen3:8b", service.defaultModelFor(AiProvider.OLLAMA));
    }

    @Test
    void baseUrlFor_OLLAMAはOPENAIと共用のllm_base_urlへフォールバックしない() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        assertNotEquals("https://api.openai.com/v1", service.baseUrlFor(AiProvider.OLLAMA));
    }

    @Test
    void defaultModelFor_OLLAMAはOPENAIと共用のllm_modelへフォールバックしない() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        assertNotEquals("gpt-4o-mini", service.defaultModelFor(AiProvider.OLLAMA));
    }

    @Test
    void OPENAIとCLAUDEの解決はOLLAMA専用キーの追加後も変わらない() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        assertEquals("https://api.openai.com/v1", service.baseUrlFor(AiProvider.OPENAI));
        assertEquals("gpt-4o-mini", service.defaultModelFor(AiProvider.OPENAI));
        assertEquals("https://api.anthropic.com", service.baseUrlFor(AiProvider.CLAUDE));
        assertEquals("claude-3-5-haiku-20241022", service.defaultModelFor(AiProvider.CLAUDE));
    }

    @Test
    void getAllSettings_OLLAMA専用キーを一覧に含む() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        List<AppSettingService.SettingStatus> statuses = service.getAllSettings();
        List<String> keys = statuses.stream().map(AppSettingService.SettingStatus::key).toList();

        assertTrue(keys.contains("llm_ollama_base_url"), "実際のキー一覧: " + keys);
        assertTrue(keys.contains("llm_ollama_model"), "実際のキー一覧: " + keys);
        AppSettingService.SettingStatus baseUrl = statuses.stream()
                .filter(status -> status.key().equals("llm_ollama_base_url")).findFirst().orElseThrow();
        assertTrue(!baseUrl.secret(), "接続先URLは秘匿項目ではない(値を画面に表示する)");
    }

    @Test
    void updateSettings_llm_ollama_base_urlはURL形式でなければ例外() {
        AppSettingService service = service();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("llm_ollama_base_url", "ollama:11434")));

        // 「不明な設定項目です」ではなくURL形式の指摘であること(キーが定義済みであることも同時に検証する)
        assertTrue(e.getMessage().contains("http://またはhttps://"), "実際のメッセージ: " + e.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    void updateSettings_llm_ollama_base_urlはhttpから始まれば保存する() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        service.updateSettings(Map.of("llm_ollama_base_url", "http://ollama:11434/v1"));

        verify(repository).save(any());
    }

    @Test
    void updateSettings_llm_ollama_modelは形式チェックせず保存する() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        service.updateSettings(Map.of("llm_ollama_model", "qwen2.5:7b-instruct"));

        verify(repository).save(any());
    }

    @Test
    void baseUrlFor_OLLAMAはDB未設定なら環境変数の値は使わず未設定として空を返す_issue1567() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        assertEquals("", service.baseUrlFor(AiProvider.OLLAMA));
        assertEquals("", service.getLlmOllamaBaseUrl());
    }

    @Test
    void defaultModelFor_OLLAMAはDB未設定なら環境変数の既定値にフォールバックする() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        assertEquals("qwen2.5:7b-instruct", service.defaultModelFor(AiProvider.OLLAMA));
        assertEquals("qwen2.5:7b-instruct", service.getLlmOllamaModel());
    }

    @Test
    void getAllSettings_OLLAMAとComfyUIの接続先は環境変数があってもDB未設定なら未設定を返す_issue1567() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        for (String key : List.of("llm_ollama_base_url", "comfyui_base_url")) {
            AppSettingService.SettingStatus status = service.getAllSettings().stream()
                    .filter(s -> s.key().equals(key)).findFirst().orElseThrow();

            assertEquals(AppSettingService.SettingSource.NONE, status.source(), key);
            assertEquals(null, status.value(), key);
            assertTrue(!status.configured(), key);
        }
    }

    @Test
    void getAllSettings_DB設定済みの項目はDBを取得元として返す() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.findById("llm_ollama_model")).thenReturn(Optional.of(
                new SystemSetting("llm_ollama_model", credentialCipher.encrypt("qwen3:8b"))));

        AppSettingService.SettingStatus status = service.getAllSettings().stream()
                .filter(s -> s.key().equals("llm_ollama_model")).findFirst().orElseThrow();

        assertEquals(AppSettingService.SettingSource.DATABASE, status.source());
        assertEquals("qwen3:8b", status.value());
    }

    @Test
    void getAllSettings_環境変数もDBも無い項目は未設定として返す() {
        AppSettingService service = serviceWithoutEnvDefaults();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        AppSettingService.SettingStatus status = service.getAllSettings().stream()
                .filter(s -> s.key().equals("llm_ollama_base_url")).findFirst().orElseThrow();

        assertEquals(AppSettingService.SettingSource.NONE, status.source());
        assertEquals(null, status.value());
        assertTrue(!status.configured());
    }

    @Test
    void getAllSettings_DB値が空文字の項目は環境変数へフォールバックする() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.findById("llm_ollama_model")).thenReturn(Optional.of(
                new SystemSetting("llm_ollama_model", credentialCipher.encrypt(" "))));

        assertEquals("qwen2.5:7b-instruct", service.defaultModelFor(AiProvider.OLLAMA));
    }

    @Test
    void 接続先のDB値が空文字なら環境変数へ落ちず未設定になる_issue1567() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.findById("llm_ollama_base_url")).thenReturn(Optional.of(
                new SystemSetting("llm_ollama_base_url", credentialCipher.encrypt(" "))));
        when(repository.findById("comfyui_base_url")).thenReturn(Optional.of(
                new SystemSetting("comfyui_base_url", credentialCipher.encrypt(""))));

        assertEquals("", service.baseUrlFor(AiProvider.OLLAMA));
        assertEquals("", service.getComfyUiBaseUrl());
        assertEquals(AppSettingService.SettingSource.NONE, service.ollamaBaseUrlSource());
        assertEquals(AppSettingService.SettingSource.NONE, service.comfyUiBaseUrlSource());
    }

    @Test
    void 接続先のDB値は環境変数の値より常に優先される_issue1567() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.findById("llm_ollama_base_url")).thenReturn(Optional.of(
                new SystemSetting("llm_ollama_base_url", credentialCipher.encrypt("http://db-ollama:11434/v1"))));
        when(repository.findById("comfyui_base_url")).thenReturn(Optional.of(
                new SystemSetting("comfyui_base_url", credentialCipher.encrypt("http://db-comfy:8188"))));

        assertEquals("http://db-ollama:11434/v1", service.getLlmOllamaBaseUrl());
        assertEquals("http://db-comfy:8188", service.getComfyUiBaseUrl());
    }

    @Test
    void updateSettings_値がnullなら既存設定を削除する() {
        AppSettingService service = service();
        java.util.Map<String, String> updates = new java.util.HashMap<>();
        updates.put("llm_ollama_model", null);

        service.updateSettings(updates);

        verify(repository).deleteById("llm_ollama_model");
        verify(repository, never()).save(any());
    }

    @Test
    void updateSettings_既存の行がある項目は同じ行を更新する() {
        AppSettingService service = service();
        when(repository.findById("llm_ollama_model")).thenReturn(Optional.of(
                new SystemSetting("llm_ollama_model", credentialCipher.encrypt("qwen3:8b"))));

        service.updateSettings(Map.of("llm_ollama_model", "qwen2.5:7b-instruct"));

        verify(repository).save(any());
    }

    @Test
    void updateSettings_llm_providerは大文字小文字を問わず受け付ける() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        service.updateSettings(Map.of("llm_provider", "ollama"));

        verify(repository).save(any());
    }

    @Test
    void updateSettings_ポート番号は0以下なら例外() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.updateSettings(Map.of("mail_port", "0")));
    }

    @Test
    void updateSettings_ポート番号は数値でなければ例外() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.updateSettings(Map.of("mail_port", "abc")));
    }

    @Test
    void updateSettings_タイムアウト秒数は数値でなければ例外() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("llm_request_timeout_seconds", "abc")));
    }

    @Test
    void updateSettings_httpsから始まるURLも受け付ける() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        service.updateSettings(Map.of("app_web_base_url", "https://blog.example.com"));

        verify(repository).save(any());
    }

    @Test
    void getLlmProvider_環境変数が空ならOPENAIにフォールバックする() {
        AppSettingService service = serviceWithoutEnvDefaults();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        assertEquals(AiProvider.OPENAI, service.getLlmProvider());
    }

    /** 環境変数の既定値が空の環境(未設定の項目がどう見えるかの検証用)。 */
    private AppSettingService serviceWithoutEnvDefaults() {
        return new AppSettingService(
                repository, credentialCipher, adminAuthorizationService,
                "", "", "120",
                "", "",
                "",
                "", "587", "", "", "",
                "", "10", "");
    }

    @Test
    void getAllSettings_DB値が空文字の項目は環境変数を取得元として返す() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.findById("llm_ollama_model")).thenReturn(Optional.of(
                new SystemSetting("llm_ollama_model", credentialCipher.encrypt("  "))));

        AppSettingService.SettingStatus status = service.getAllSettings().stream()
                .filter(s -> s.key().equals("llm_ollama_model")).findFirst().orElseThrow();

        assertEquals(AppSettingService.SettingSource.ENVIRONMENT, status.source());
        assertEquals("qwen2.5:7b-instruct", status.value());
    }

    @Test
    void getAllSettings_秘匿項目はDB設定済みでも値を含めない() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.findById("mail_password")).thenReturn(Optional.of(
                new SystemSetting("mail_password", credentialCipher.encrypt("sk-secret"))));

        AppSettingService.SettingStatus status = service.getAllSettings().stream()
                .filter(s -> s.key().equals("mail_password")).findFirst().orElseThrow();

        assertEquals(AppSettingService.SettingSource.DATABASE, status.source());
        assertEquals(null, status.value());
        assertTrue(status.configured());
    }

    @Test
    void updateSettings_タイムアウト秒数は正の整数なら保存する() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        service.updateSettings(Map.of("llm_request_timeout_seconds", "300"));

        verify(repository).save(any());
    }

    @Test
    void updateSettings_ポート番号は範囲内なら保存する() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        service.updateSettings(Map.of("mail_port", "2525"));

        verify(repository).save(any());
    }

    @Test
    void updateSettings_メール送信元はアドレス形式なら保存する() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        service.updateSettings(Map.of("app_mail_from", "noreply@example.com"));

        verify(repository).save(any());
    }

    @Test
    void updateSettings_アップロードレート制限は正の整数なら保存する() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        service.updateSettings(Map.of("upload_rate_limit_requests", "50"));

        verify(repository).save(any());
    }

    // ---- site_admin_path(issue #1079) ----

    @Test
    void getSiteAdminPath_DB未設定なら環境変数既定のwp_adminを返す() {
        AppSettingService service = service();
        when(repository.findById("site_admin_path")).thenReturn(Optional.empty());

        assertEquals("wp-admin", service.getSiteAdminPath());
    }

    @Test
    void getSiteAdminPath_DB設定があればそれを優先する() {
        AppSettingService service = service();
        when(repository.findById("site_admin_path"))
                .thenReturn(Optional.of(new SystemSetting("site_admin_path", credentialCipher.encrypt("secret-admin"))));

        assertEquals("secret-admin", service.getSiteAdminPath());
    }

    @Test
    void getSiteAdminPath_認証だけ要求しadminは要求しない() {
        AppSettingService service = service();
        when(repository.findById("site_admin_path")).thenReturn(Optional.empty());

        service.getSiteAdminPath();

        verify(adminAuthorizationService).requireAuthenticated();
        verify(adminAuthorizationService, never()).requireAdmin();
    }

    @Test
    void getSiteAdminPath_未認証ならForbiddenやUnauthorizedをそのまま伝える() {
        AppSettingService service = service();
        doThrow(new ForbiddenException("認証が必要です")).when(adminAuthorizationService).requireAuthenticated();

        assertThrows(ForbiddenException.class, service::getSiteAdminPath);
    }

    @Test
    void getAllSettings_site_admin_pathは非秘匿で環境変数由来として返る() {
        AppSettingService service = service();
        when(repository.findById(any())).thenReturn(Optional.empty());

        AppSettingService.SettingStatus status = service.getAllSettings().stream()
                .filter(s -> s.key().equals("site_admin_path")).findFirst().orElseThrow();

        assertTrue(!status.secret());
        assertEquals("wp-admin", status.value());
        assertEquals(AppSettingService.SettingSource.ENVIRONMENT, status.source());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "secret-admin", "/wp-admin", "a/b/c", "wp-admin/", "a..b", "./x", "wp_admin.php"})
    void updateSettings_site_admin_pathは同一オリジンの相対パスなら保存する(String value) {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        service.updateSettings(Map.of("site_admin_path", value));

        verify(repository).save(any());
    }

    @Test
    void updateSettings_site_admin_pathは200文字ちょうどなら保存する() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        service.updateSettings(Map.of("site_admin_path", "a".repeat(200)));

        verify(repository).save(any());
    }

    @Test
    void updateSettings_site_admin_pathは空文字なら未設定に戻す() {
        AppSettingService service = service();

        service.updateSettings(Map.of("site_admin_path", ""));

        verify(repository).deleteById("site_admin_path");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)", "data:text/html,x", "http://evil.example.com", "https://evil.example.com/wp-admin",
            "//evil.example.com", "../../etc", "a/../b", "/..", "wp admin", "wp\tadmin", "wp\u0000admin",
            " wp-admin", "/\\evil.example.com", "\\\\evil.example.com", "wp\\admin"})
    void updateSettings_site_admin_pathの不正値は例外で保存されない(String value) {
        AppSettingService service = service();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("site_admin_path", value)));

        assertTrue(e.getMessage().contains("site_admin_path"));
        verify(repository, never()).save(any());
    }

    @Test
    void updateSettings_site_admin_pathが201文字なら例外で保存されない() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("site_admin_path", "a".repeat(201))));

        verify(repository, never()).save(any());
    }

    @Test
    void updateSettings_site_admin_pathが不正なら同時に送られた他の項目も保存されない() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.updateSettings(
                Map.of("mail_password", "new-key", "site_admin_path", "javascript:alert(1)")));

        verify(repository, never()).save(any());
    }

    // ---- provider別の選択可能モデル一覧(issue #1088) ----

    @Test
    void availableModelsFor_OPENAIはllm_available_modelsを返す() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        assertEquals(java.util.List.of("gpt-4o-mini", "gpt-4o"), service.availableModelsFor(AiProvider.OPENAI));
    }

    @Test
    void availableModelsFor_OLLAMAはOpenAIのモデル名を含まない() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        java.util.List<String> models = service.availableModelsFor(AiProvider.OLLAMA);

        assertEquals(java.util.List.of("qwen2.5:7b-instruct"), models);
    }

    @Test
    void availableModelsFor_OLLAMAはllm_ollama_available_modelsのDB値を優先する() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.findById("llm_ollama_available_models")).thenReturn(Optional.of(new SystemSetting(
                "llm_ollama_available_models", credentialCipher.encrypt("qwen3:8b, ,llama3.1:8b"))));

        assertEquals(java.util.List.of("qwen3:8b", "llama3.1:8b"), service.availableModelsFor(AiProvider.OLLAMA));
    }

    @Test
    void availableModelsFor_CLAUDEはClaudeのモデル名を返す() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        assertEquals(java.util.List.of("claude-3-5-haiku-20241022"), service.availableModelsFor(AiProvider.CLAUDE));
    }

    @Test
    void availableModelsFor_CLAUDEはllm_claude_available_modelsのDB値を優先する() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.findById("llm_claude_available_models")).thenReturn(Optional.of(new SystemSetting(
                "llm_claude_available_models", credentialCipher.encrypt("claude-a,claude-b"))));

        assertEquals(java.util.List.of("claude-a", "claude-b"), service.availableModelsFor(AiProvider.CLAUDE));
    }

    @Test
    void availableModelsFor_OPENAIの一覧が空なら空を返す() {
        AppSettingService service = new AppSettingService(
                repository, credentialCipher, adminAuthorizationService,
                "gpt-4o-mini", "", "120",
                "OPENAI", "claude-3-5-haiku-20241022",
                "qwen2.5:7b-instruct",
                "smtp.example.com", "587", "env-user", "env-pass", "noreply@example.com",
                "http://localhost:3000", "10", "wp-admin");
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        assertEquals(java.util.List.of(), service.availableModelsFor(AiProvider.OPENAI));
    }

    @Test
    void availableModelsFor_OLLAMAの既定モデルも空なら空を返す() {
        AppSettingService service = new AppSettingService(
                repository, credentialCipher, adminAuthorizationService,
                "gpt-4o-mini", "gpt-4o", "120",
                "OPENAI", "claude-3-5-haiku-20241022",
                "",
                "smtp.example.com", "587", "env-user", "env-pass", "noreply@example.com",
                "http://localhost:3000", "10", "wp-admin");
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        assertEquals(java.util.List.of(), service.availableModelsFor(AiProvider.OLLAMA));
    }

    @Test
    void getAllSettings_provider別の候補モデルキーを一覧に含む() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        java.util.List<String> keys = service.getAllSettings().stream()
                .map(AppSettingService.SettingStatus::key).toList();

        assertTrue(keys.contains("llm_ollama_available_models"), "実際のキー一覧: " + keys);
        assertTrue(keys.contains("llm_claude_available_models"), "実際のキー一覧: " + keys);
    }

    // ---- ai-connections向けの取得元(issue #1499) ----

    @Test
    void 取得元_環境変数に値があってもDBに無ければ2項目ともENVIRONMENTではなくNONEを返す_issue1567() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        assertEquals(AppSettingService.SettingSource.NONE, service.ollamaBaseUrlSource());
        assertEquals(AppSettingService.SettingSource.NONE, service.comfyUiBaseUrlSource());
    }

    @Test
    void 取得元_DBに設定があればDATABASEを返す() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.findById("llm_ollama_base_url")).thenReturn(Optional.of(
                new SystemSetting("llm_ollama_base_url", credentialCipher.encrypt("http://db-ollama/v1"))));

        assertEquals(AppSettingService.SettingSource.DATABASE, service.ollamaBaseUrlSource());
    }

    @Test
    void 取得元_どちらにも無ければNONEを返す() {
        AppSettingService service = serviceWithoutEnvDefaults();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        assertEquals(AppSettingService.SettingSource.NONE, service.ollamaBaseUrlSource());
        assertEquals(AppSettingService.SettingSource.NONE, service.comfyUiBaseUrlSource());
    }

    @Test
    void 取得元_admin権限を要求しない() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        service.comfyUiBaseUrlSource();

        verify(adminAuthorizationService, never()).requireAdmin();
    }
}
