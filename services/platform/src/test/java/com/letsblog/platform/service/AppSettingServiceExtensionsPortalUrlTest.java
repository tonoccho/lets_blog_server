package com.letsblog.platform.service;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.platform.domain.SystemSetting;
import com.letsblog.platform.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** システム設定 extensions_portal_url(issue #1783)。保存時に ExtensionsPortalUrlPolicy で検査する。 */
@ExtendWith(MockitoExtension.class)
class AppSettingServiceExtensionsPortalUrlTest {

    @Mock
    private SystemSettingRepository repository;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private final CredentialCipher credentialCipher = new CredentialCipher(
            java.util.Base64.getEncoder().encodeToString(new byte[32]));

    private AppSettingService service;

    @BeforeEach
    void setUp() {
        service = new AppSettingService(
                repository, credentialCipher, adminAuthorizationService,
                "gpt-4o-mini", "gpt-4o-mini,gpt-4o", "120",
                "OPENAI", "claude-3-5-haiku-20241022",
                "qwen2.5:7b-instruct",
                "smtp.example.com", "587", "env-user", "env-pass", "noreply@example.com",
                "http://localhost:3000", "10", "wp-admin");
        service.usePortalUrlPolicy(new ExtensionsPortalUrlPolicy(host -> {
            if (host.equals("lbx-portal")) {
                return new InetAddress[] {InetAddress.getByName("172.20.0.9")};
            }
            if (host.matches("[0-9.]+")) {
                return new InetAddress[] {InetAddress.getByName(host)};
            }
            throw new UnknownHostException(host);
        }));
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void 一覧に項目があり未設定として返る() {
        AppSettingService.SettingStatus status = service.getAllSettings().stream()
                .filter(s -> s.key().equals("extensions_portal_url")).findFirst().orElseThrow();

        assertEquals(AppSettingService.SettingSource.NONE, status.source());
    }

    @Test
    void dockerネットワーク内のポータルのURLを保存できる() {
        service.updateSettings(Map.of("extensions_portal_url", "http://lbx-portal:3100"));

        verify(repository).save(any(SystemSetting.class));
    }

    @Test
    void ループバックのポータルのURLは拒否され保存しない() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("extensions_portal_url", "http://127.0.0.1:3100")));

        assertTrue(e.getMessage().contains("extensions_portal_url"), e.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    void 空文字なら検査せず未設定へ戻す() {
        service.updateSettings(Map.of("extensions_portal_url", ""));

        verify(repository).deleteById("extensions_portal_url");
    }
}
