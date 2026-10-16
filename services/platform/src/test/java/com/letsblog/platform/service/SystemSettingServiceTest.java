package com.letsblog.platform.service;

import com.letsblog.platform.domain.SystemSetting;
import com.letsblog.platform.repository.SystemSettingRepository;
import com.letsblog.platform.testsupport.InMemoryRepositories;
import com.letsblog.platform.testsupport.PlatformWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SystemSettingServiceの回帰テスト。DB設定/環境変数フォールバックの優先順位と、
 * admin権限ゲートを中心に検証する。legacy-api版から移設(issue #693)。
 *
 * <p>issue #1825: AdminAuthorizationService・CurrentActorServiceは実物にし、identity-serviceはHTTPのフェイクで模す。
 * DB(Spring Dataのリポジトリ)だけがMockitoの差し替え。
 */
class SystemSettingServiceTest {

    private PlatformWorld world;
    private Map<String, SystemSetting> store;
    private SystemSettingRepository repository;

    @BeforeEach
    void setUp() {
        world = new PlatformWorld();
        store = new HashMap<>();
        repository = InMemoryRepositories.systemSettings(store);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private SystemSettingService buildService(String envDefault) {
        return new SystemSettingService(repository, world.credentialCipher, world.adminAuthorizationService, envDefault);
    }

    private void putDbKey(String plain) {
        store.put(SystemSettingService.BRAVE_SEARCH_API_KEY, new SystemSetting(
                SystemSettingService.BRAVE_SEARCH_API_KEY, world.credentialCipher.encrypt(plain)));
    }

    @Test
    void getBraveSearchApiKey_DB設定があればそれを優先する() {
        putDbKey("db-key");

        assertEquals("db-key", buildService("env-key").getBraveSearchApiKey());
    }

    @Test
    void getBraveSearchApiKey_DB未設定なら環境変数値にフォールバックする() {
        assertEquals("env-key", buildService("env-key").getBraveSearchApiKey());
    }

    @Test
    void getBraveSearchApiKey_DBの値が空白なら環境変数値にフォールバックする() {
        putDbKey("  ");

        assertEquals("env-key", buildService("env-key").getBraveSearchApiKey());
    }

    @Test
    void getBraveSearchApiKeyStatus_DB設定時はDATABASEを返す() {
        putDbKey("db-key");
        world.actingAsUser(1L);

        SystemSettingService.BraveSearchApiKeyStatus status = buildService("env-key").getBraveSearchApiKeyStatus();

        assertTrue(status.configured());
        assertEquals(SystemSettingService.SettingSource.DATABASE, status.source());
    }

    @Test
    void getBraveSearchApiKeyStatus_DB未設定だが環境変数ありならENVIRONMENTを返す() {
        world.actingAsUser(1L);

        SystemSettingService.BraveSearchApiKeyStatus status = buildService("env-key").getBraveSearchApiKeyStatus();

        assertTrue(status.configured());
        assertEquals(SystemSettingService.SettingSource.ENVIRONMENT, status.source());
    }

    @Test
    void getBraveSearchApiKeyStatus_どちらもなければNONEを返す() {
        world.actingAsUser(1L);

        SystemSettingService.BraveSearchApiKeyStatus status = buildService("").getBraveSearchApiKeyStatus();

        assertFalse(status.configured());
        assertEquals(SystemSettingService.SettingSource.NONE, status.source());
    }

    @Test
    void getBraveSearchApiKeyStatus_DBの値が空白で環境変数が空白ならNONEを返す() {
        putDbKey("  ");
        world.actingAsUser(1L);

        assertEquals(SystemSettingService.SettingSource.NONE, buildService("  ").getBraveSearchApiKeyStatus().source());
    }

    @Test
    void getBraveSearchApiKeyStatus_環境変数がnullならNONEを返す() {
        world.actingAsUser(1L);

        assertEquals(SystemSettingService.SettingSource.NONE, buildService(null).getBraveSearchApiKeyStatus().source());
    }

    @Test
    void getBraveSearchApiKeyStatus_未認証ならForbidden() {
        world.anonymous();

        assertThrows(ForbiddenException.class, () -> buildService("env-key").getBraveSearchApiKeyStatus());
    }

    @Test
    void getBraveSearchApiKeyStatusInternal_認可チェックなしで取得できる() {
        world.anonymous();

        SystemSettingService.BraveSearchApiKeyStatus status = buildService("env-key").getBraveSearchApiKeyStatusInternal();

        assertTrue(status.configured());
        assertEquals(SystemSettingService.SettingSource.ENVIRONMENT, status.source());
        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    void setBraveSearchApiKey_admin権限がなければForbidden() {
        world.actingAsUser(2L);

        assertThrows(ForbiddenException.class, () -> buildService("").setBraveSearchApiKey("new-key"));
        assertTrue(store.isEmpty());
    }

    @Test
    void setBraveSearchApiKey_空文字とnullは例外() {
        world.actingAsAdmin(1L);
        SystemSettingService service = buildService("");

        assertThrows(IllegalArgumentException.class, () -> service.setBraveSearchApiKey(""));
        assertThrows(IllegalArgumentException.class, () -> service.setBraveSearchApiKey("  "));
        assertThrows(IllegalArgumentException.class, () -> service.setBraveSearchApiKey(null));
        assertTrue(store.isEmpty());
    }

    @Test
    void setBraveSearchApiKey_暗号化して保存する() {
        world.actingAsAdmin(1L);

        buildService("").setBraveSearchApiKey("new-key");

        assertEquals("new-key", world.credentialCipher.decrypt(
                store.get(SystemSettingService.BRAVE_SEARCH_API_KEY).getSettingValueEncrypted()));
    }

    @Test
    void setBraveSearchApiKey_既存の行は上書きする() {
        putDbKey("old-key");
        SystemSetting existing = store.get(SystemSettingService.BRAVE_SEARCH_API_KEY);
        world.actingAsAdmin(1L);

        buildService("").setBraveSearchApiKey("new-key");

        assertEquals(1, store.size());
        assertEquals("new-key", world.credentialCipher.decrypt(existing.getSettingValueEncrypted()));
    }

    @Test
    void clearBraveSearchApiKey_admin権限がなければForbidden() {
        putDbKey("db-key");
        world.actingAsUser(2L);

        assertThrows(ForbiddenException.class, () -> buildService("").clearBraveSearchApiKey());
        assertEquals(1, store.size());
    }

    @Test
    void clearBraveSearchApiKey_リポジトリから削除する() {
        putDbKey("db-key");
        world.actingAsAdmin(1L);

        buildService("").clearBraveSearchApiKey();

        assertTrue(store.isEmpty());
    }
}
