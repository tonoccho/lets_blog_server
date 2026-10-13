package com.letsblog.platform.service;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.common.net.ConnectionDestinationGuard;
import com.letsblog.platform.domain.SystemSetting;
import com.letsblog.platform.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外部Penpotの接続先(system_settingsのpenpot_base_url、issue #1774)。ComfyUI / Ollamaと同じく
 * システム全体で1つ、DBだけで決まり(環境変数既定なし)、保存時にConnectionDestinationGuardで検査する。
 */
@ExtendWith(MockitoExtension.class)
class AppSettingServicePenpotUrlTest {

    @Mock
    private SystemSettingRepository repository;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private final CredentialCipher credentialCipher = new CredentialCipher(
            java.util.Base64.getEncoder().encodeToString(new byte[32]));

    private AppSettingService service;

    /** hostの名前解決を固定したガード。コンテナ外(インタフェース無し)なのでdockerネットワーク範囲は空。 */
    private static ConnectionDestinationGuard guardResolving(String host, String address) {
        return new ConnectionDestinationGuard(
                h -> {
                    if (h.equals(host)) {
                        return new InetAddress[] {InetAddress.getByName(address)};
                    }
                    if (h.matches("[0-9.]+")) {
                        return new InetAddress[] {InetAddress.getByName(h)};
                    }
                    throw new UnknownHostException(h);
                },
                List::of, () -> false);
    }

    @BeforeEach
    void setUp() {
        service = new AppSettingService(
                repository, credentialCipher, adminAuthorizationService,
                "gpt-4o-mini", "gpt-4o-mini,gpt-4o", "120",
                "OPENAI", "claude-3-5-haiku-20241022",
                "qwen2.5:7b-instruct",
                "smtp.example.com", "587", "env-user", "env-pass", "noreply@example.com",
                "http://localhost:3000", "10", "wp-admin");
        service.useDestinationGuard(guardResolving("penpot.lan", "192.168.1.50"));
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void システム設定の一覧にpenpot_base_urlの項目があり未設定として返る() {
        AppSettingService.SettingStatus status = service.getAllSettings().stream()
                .filter(s -> s.key().equals("penpot_base_url")).findFirst().orElseThrow();

        assertEquals(AppSettingService.SettingSource.NONE, status.source());
        assertEquals("Penpot ベースURL", status.label());
    }

    @Test
    void penpot_base_urlはDBに無ければ空文字で環境変数へは落ちない() {
        assertEquals("", service.getPenpotBaseUrl());
    }

    @Test
    void penpot_base_urlにLANのホスト名のURLを保存でき解決される() {
        service.updateSettings(Map.of("penpot_base_url", "http://penpot.lan:9001"));

        ArgumentCaptor<SystemSetting> saved = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository).save(saved.capture());
        assertEquals("penpot_base_url", saved.getValue().getSettingKey());
        when(repository.findById("penpot_base_url")).thenReturn(Optional.of(saved.getValue()));
        assertEquals("http://penpot.lan:9001", service.getPenpotBaseUrl());
    }

    @Test
    void penpot_base_urlにLANのIPリテラルのURLを保存できる() {
        service.updateSettings(Map.of("penpot_base_url", "http://192.168.77.10:3000"));

        verify(repository).save(any(SystemSetting.class));
    }

    @Test
    void penpot_base_urlがURL形式でなければ例外で保存しない() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("penpot_base_url", "not-a-url")));

        assertTrue(e.getMessage().contains("penpot_base_url"), e.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    void penpot_base_urlがloopbackやメタデータを指せば拒否され保存しない() {
        for (String url : List.of("http://127.0.0.1:9001", "http://169.254.169.254/latest/meta-data/")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> service.updateSettings(Map.of("penpot_base_url", url)), url);
            assertTrue(e.getMessage().contains("Penpot"), e.getMessage());
        }
        verify(repository, never()).save(any());
    }

    @Test
    void penpot_base_urlが名前解決できなければ拒否され保存しない() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("penpot_base_url", "http://unknown.invalid:9001")));

        verify(repository, never()).save(any());
    }

    @Test
    void 禁止URLを含む一括更新では他の項目も保存されない() {
        assertThrows(IllegalArgumentException.class, () -> service.updateSettings(Map.of(
                "penpot_base_url", "http://127.0.0.1:9001", "app_web_base_url", "https://example.com")));

        verify(repository, never()).save(any());
    }

    @Test
    void penpot_base_urlを空で保存すると削除され検査は行わない() {
        service.useDestinationGuard(new ConnectionDestinationGuard(
                h -> {
                    throw new AssertionError("空のときは名前解決しない");
                }, List::of, () -> false));

        service.updateSettings(Map.of("penpot_base_url", ""));

        verify(repository).deleteById("penpot_base_url");
    }

    @Test
    void 本番の既定ガードでも禁止アドレスのIPリテラルは拒否される() {
        AppSettingService prod = new AppSettingService(
                repository, credentialCipher, adminAuthorizationService,
                "gpt-4o-mini", "gpt-4o-mini,gpt-4o", "120",
                "OPENAI", "claude-3-5-haiku-20241022",
                "qwen2.5:7b-instruct",
                "smtp.example.com", "587", "env-user", "env-pass", "noreply@example.com",
                "http://localhost:3000", "10", "wp-admin");

        assertThrows(IllegalArgumentException.class,
                () -> prod.updateSettings(Map.of("penpot_base_url", "http://169.254.169.254/")));
    }
}
