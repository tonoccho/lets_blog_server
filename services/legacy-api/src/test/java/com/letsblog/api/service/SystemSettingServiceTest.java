package com.letsblog.api.service;

import com.letsblog.api.crypto.CredentialCipher;
import com.letsblog.api.domain.SystemSetting;
import com.letsblog.api.repository.SystemSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SystemSettingServiceの回帰テスト。DB設定/環境変数フォールバックの優先順位と、
 * admin権限ゲートを中心に検証する。
 */
@ExtendWith(MockitoExtension.class)
class SystemSettingServiceTest {

    @Mock
    private SystemSettingRepository repository;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private final CredentialCipher credentialCipher = new CredentialCipher(
            java.util.Base64.getEncoder().encodeToString(new byte[32]));

    private SystemSettingService buildService(String envDefault) {
        return new SystemSettingService(repository, credentialCipher, adminAuthorizationService, envDefault);
    }

    @Test
    void getBraveSearchApiKey_DB設定があればそれを優先する() {
        SystemSettingService service = buildService("env-key");
        when(repository.findById(SystemSettingService.BRAVE_SEARCH_API_KEY))
                .thenReturn(Optional.of(new SystemSetting(
                        SystemSettingService.BRAVE_SEARCH_API_KEY, credentialCipher.encrypt("db-key"))));

        assertEquals("db-key", service.getBraveSearchApiKey());
    }

    @Test
    void getBraveSearchApiKey_DB未設定なら環境変数値にフォールバックする() {
        SystemSettingService service = buildService("env-key");
        when(repository.findById(SystemSettingService.BRAVE_SEARCH_API_KEY)).thenReturn(Optional.empty());

        assertEquals("env-key", service.getBraveSearchApiKey());
    }

    @Test
    void getBraveSearchApiKeyStatus_DB設定時はDATABASEを返す() {
        SystemSettingService service = buildService("env-key");
        when(repository.findById(SystemSettingService.BRAVE_SEARCH_API_KEY))
                .thenReturn(Optional.of(new SystemSetting(
                        SystemSettingService.BRAVE_SEARCH_API_KEY, credentialCipher.encrypt("db-key"))));

        SystemSettingService.BraveSearchApiKeyStatus status = service.getBraveSearchApiKeyStatus();

        assertEquals(true, status.configured());
        assertEquals(SystemSettingService.SettingSource.DATABASE, status.source());
    }

    @Test
    void getBraveSearchApiKeyStatus_DB未設定だが環境変数ありならENVIRONMENTを返す() {
        SystemSettingService service = buildService("env-key");
        when(repository.findById(SystemSettingService.BRAVE_SEARCH_API_KEY)).thenReturn(Optional.empty());

        SystemSettingService.BraveSearchApiKeyStatus status = service.getBraveSearchApiKeyStatus();

        assertEquals(true, status.configured());
        assertEquals(SystemSettingService.SettingSource.ENVIRONMENT, status.source());
    }

    @Test
    void getBraveSearchApiKeyStatus_どちらもなければNONEを返す() {
        SystemSettingService service = buildService("");
        when(repository.findById(SystemSettingService.BRAVE_SEARCH_API_KEY)).thenReturn(Optional.empty());

        SystemSettingService.BraveSearchApiKeyStatus status = service.getBraveSearchApiKeyStatus();

        assertEquals(false, status.configured());
        assertEquals(SystemSettingService.SettingSource.NONE, status.source());
    }

    @Test
    void setBraveSearchApiKey_admin権限がなければForbidden() {
        SystemSettingService service = buildService("");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> service.setBraveSearchApiKey("new-key"));
    }

    @Test
    void setBraveSearchApiKey_空文字は例外() {
        SystemSettingService service = buildService("");

        assertThrows(IllegalArgumentException.class, () -> service.setBraveSearchApiKey(""));
        verify(repository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void setBraveSearchApiKey_暗号化して保存する() {
        SystemSettingService service = buildService("");
        lenient().when(repository.findById(SystemSettingService.BRAVE_SEARCH_API_KEY)).thenReturn(Optional.empty());

        service.setBraveSearchApiKey("new-key");

        ArgumentCaptor<SystemSetting> captor = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository).save(captor.capture());
        assertEquals("new-key", credentialCipher.decrypt(captor.getValue().getSettingValueEncrypted()));
    }

    @Test
    void clearBraveSearchApiKey_admin権限がなければForbidden() {
        SystemSettingService service = buildService("");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, service::clearBraveSearchApiKey);
    }

    @Test
    void clearBraveSearchApiKey_リポジトリから削除する() {
        SystemSettingService service = buildService("");

        service.clearBraveSearchApiKey();

        verify(repository).deleteById(SystemSettingService.BRAVE_SEARCH_API_KEY);
    }
}
