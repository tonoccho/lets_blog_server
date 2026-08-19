package com.letsblog.api.service;

import com.letsblog.api.crypto.CredentialCipher;
import com.letsblog.api.domain.SystemSetting;
import com.letsblog.api.repository.SystemSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                "env-llm-key", "https://api.openai.com/v1", "gpt-4o-mini", "gpt-4o-mini,gpt-4o", "120",
                "smtp.example.com", "587", "env-user", "env-pass", "noreply@example.com",
                "http://localhost:3000", "10");
    }

    @Test
    void getLlmApiKey_DB設定があればそれを優先する() {
        AppSettingService service = service();
        when(repository.findById("llm_api_key"))
                .thenReturn(Optional.of(new SystemSetting("llm_api_key", credentialCipher.encrypt("db-key"))));

        assertEquals("db-key", service.getLlmApiKey());
    }

    @Test
    void getLlmApiKey_DB未設定なら環境変数値にフォールバックする() {
        AppSettingService service = service();
        when(repository.findById("llm_api_key")).thenReturn(Optional.empty());

        assertEquals("env-llm-key", service.getLlmApiKey());
    }

    @Test
    void getMailPort_数値として解決する() {
        AppSettingService service = service();
        when(repository.findById("mail_port"))
                .thenReturn(Optional.of(new SystemSetting("mail_port", credentialCipher.encrypt("2525"))));

        assertEquals(2525, service.getMailPort());
    }

    @Test
    void LlmConfigProviderとして委譲する() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        assertEquals("https://api.openai.com/v1", service.baseUrl());
        assertEquals("env-llm-key", service.apiKey());
        assertEquals("gpt-4o-mini", service.defaultModel());
        assertEquals(120L, service.requestTimeoutSeconds());
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

        AppSettingService.SettingStatus llmApiKey = statuses.stream()
                .filter(status -> status.key().equals("llm_api_key")).findFirst().orElseThrow();
        assertTrue(llmApiKey.secret());
        assertEquals(null, llmApiKey.value());
        assertEquals(AppSettingService.SettingSource.ENVIRONMENT, llmApiKey.source());

        AppSettingService.SettingStatus mailHost = statuses.stream()
                .filter(status -> status.key().equals("mail_host")).findFirst().orElseThrow();
        assertTrue(!mailHost.secret());
        assertEquals("smtp.example.com", mailHost.value());
    }

    @Test
    void updateSettings_admin権限がなければForbidden() {
        AppSettingService service = service();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> service.updateSettings(Map.of("llm_api_key", "new-key")));
    }

    @Test
    void updateSettings_不明なキーは例外で全体を保存しない() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("llm_api_key", "new-key", "unknown_key", "value")));

        verify(repository, never()).save(any());
    }

    @Test
    void updateSettings_一部の値が不正なら他の項目も含め全てロールバックされる() {
        AppSettingService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.updateSettings(
                Map.of("llm_api_key", "new-key", "app_web_base_url", "not-a-url")));

        verify(repository, never()).save(any());
    }

    @Test
    void updateSettings_正常な値は暗号化して保存する() {
        AppSettingService service = service();
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());

        service.updateSettings(Map.of("llm_api_key", "new-key", "app_web_base_url", "https://blog.example.com"));

        verify(repository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void updateSettings_空文字は既存設定を削除する() {
        AppSettingService service = service();

        service.updateSettings(Map.of("llm_api_key", ""));

        verify(repository).deleteById("llm_api_key");
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
}
