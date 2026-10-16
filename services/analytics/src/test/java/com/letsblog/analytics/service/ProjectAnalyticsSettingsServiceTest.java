package com.letsblog.analytics.service;

import com.letsblog.analytics.adsense.AdSenseAccountSummary;
import com.letsblog.analytics.adsense.AdSenseException;
import com.letsblog.analytics.analytics.GoogleAnalyticsException;
import com.letsblog.analytics.analytics.GoogleAnalyticsPropertySummary;
import com.letsblog.analytics.domain.AnalyticsCredentials;
import com.letsblog.analytics.testsupport.AnalyticsWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProjectAnalyticsSettingsServiceのGoogle Analytics OAuth連携の回帰テスト(issue #1231)。
 * サービスアカウントJSONの検証・保存経路は廃止され、OAuthクライアントの保存 → 認可コード交換 →
 * プロパティ一覧 → プロパティ選択 → 解除、が唯一の経路になった。
 *
 * <p>issue #1827: {@code AnalyticsCredentialsService}・{@code GoogleAnalyticsClient}・{@code AdSenseClient}は実物。
 * 外部との通信である Google の各 API(サーバー側フェイクのHTTP)と DB(メモリ上のリポジトリ)だけを差し替え、
 * 保存された内容は「テーブル」({@link AnalyticsWorld#store})の中身で確かめる。
 */
class ProjectAnalyticsSettingsServiceTest {

    private static final String TOKEN_PATH = "/oauth/token";
    private static final String GA_ACCOUNT_SUMMARIES = "/ga-admin/v1beta/accountSummaries";
    private static final String ADSENSE_ACCOUNTS = "/adsense/v2/accounts";

    private AnalyticsWorld world;

    @BeforeEach
    void setUp() {
        world = new AnalyticsWorld();
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private ProjectAnalyticsSettingsService service() {
        return world.settings;
    }

    private AnalyticsCredentials row() {
        return world.store.get(1L);
    }

    private String decrypt(byte[] encrypted) {
        return world.cipher.decrypt(encrypted);
    }

    private void tokenEndpointReturns(String json) {
        world.http.json("POST", TOKEN_PATH, json);
    }

    // ---- Google Analytics ----

    @Test
    void setGoogleAnalyticsClient_シークレットは暗号化して保存する() {
        service().setGoogleAnalyticsClient(1L, "cid", "plain-secret");

        assertEquals("cid", row().getGaOauthClientId());
        assertEquals("plain-secret", decrypt(row().getGaOauthClientSecretEncrypted()));
    }

    @Test
    void setGoogleAnalyticsClient_シークレットが空なら既存を変更しない() {
        world.seedGa(1L, null, "old-cid", "keep-secret", null);

        service().setGoogleAnalyticsClient(1L, "cid", "  ");
        assertEquals("cid", row().getGaOauthClientId());
        assertEquals("keep-secret", decrypt(row().getGaOauthClientSecretEncrypted()));

        service().setGoogleAnalyticsClient(1L, "cid-2", null);
        assertEquals("cid-2", row().getGaOauthClientId());
        assertEquals("keep-secret", decrypt(row().getGaOauthClientSecretEncrypted()));
    }

    @Test
    void completeGoogleAnalyticsOAuth_認可コードを交換してリフレッシュトークンを暗号化保存する() {
        world.seedGa(1L, null, "cid", "secret", null);
        tokenEndpointReturns("{\"access_token\":\"access\",\"refresh_token\":\"refresh-xyz\"}");

        service().completeGoogleAnalyticsOAuth(1L, "code", "https://x/cb");

        assertEquals("refresh-xyz", decrypt(row().getGaRefreshTokenEncrypted()));
        var exchange = world.http.requests("POST", TOKEN_PATH);
        assertEquals(1, exchange.size());
        String form = exchange.get(0).bodyText();
        assertTrue(form.contains("grant_type=authorization_code"), form);
        assertTrue(form.contains("code=code"), form);
        assertTrue(form.contains("client_id=cid"), form);
        assertTrue(form.contains("client_secret=secret"), form);
    }

    @Test
    void completeGoogleAnalyticsOAuth_クライアントシークレット未保存ならGoogleへ問い合わせず拒否する() {
        assertThrows(GoogleAnalyticsException.class,
                () -> service().completeGoogleAnalyticsOAuth(1L, "code", "https://x/cb"));

        assertTrue(world.http.requests().isEmpty());
        assertNull(world.store.get(1L));
    }

    @Test
    void listGoogleAnalyticsProperties_保存済みのリフレッシュトークンで一覧を取得する() {
        world.seedGa(1L, null, "cid", "secret", "refresh");
        tokenEndpointReturns("{\"access_token\":\"access\"}");
        world.http.json("GET", GA_ACCOUNT_SUMMARIES, """
                {"accountSummaries":[{"account":"accounts/1","displayName":"Account One",
                  "propertySummaries":[{"property":"properties/111","displayName":"Site A"}]}]}
                """);

        List<GoogleAnalyticsPropertySummary> properties = service().listGoogleAnalyticsProperties(1L);

        assertEquals(List.of(new GoogleAnalyticsPropertySummary("111", "Site A", "Account One")), properties);
        assertTrue(world.http.requests("POST", TOKEN_PATH).get(0).bodyText().contains("refresh_token=refresh"));
        assertEquals("Bearer access", world.http.requests("GET", GA_ACCOUNT_SUMMARIES).get(0).header("Authorization"));
    }

    @Test
    void listGoogleAnalyticsProperties_未連携なら拒否してGoogleへ問い合わせない() {
        assertThrows(IllegalArgumentException.class, () -> service().listGoogleAnalyticsProperties(1L));

        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    void selectGoogleAnalyticsProperty_数字のIDを保存する() {
        world.seedGa(1L, null, "cid", "secret", "refresh");

        service().selectGoogleAnalyticsProperty(1L, "987654321");

        assertEquals("987654321", row().getGaPropertyId());
    }

    @Test
    void selectGoogleAnalyticsProperty_propertiesプレフィックスは取り除く() {
        world.seedGa(1L, null, "cid", "secret", "refresh");

        service().selectGoogleAnalyticsProperty(1L, "properties/555");

        assertEquals("555", row().getGaPropertyId());
    }

    @Test
    void selectGoogleAnalyticsProperty_未連携や数字でないIDは拒否する() {
        assertThrows(IllegalArgumentException.class, () -> service().selectGoogleAnalyticsProperty(1L, "123"));
        assertNull(world.store.get(1L));

        world.seedGa(2L, null, "cid", "secret", "refresh");
        assertThrows(IllegalArgumentException.class, () -> service().selectGoogleAnalyticsProperty(2L, "abc"));
        assertThrows(IllegalArgumentException.class, () -> service().selectGoogleAnalyticsProperty(2L, ""));
        assertThrows(IllegalArgumentException.class, () -> service().selectGoogleAnalyticsProperty(2L, null));
        assertNull(world.store.get(2L).getGaPropertyId());
    }

    @Test
    void googleAnalyticsCredentials_連携済みなら復号したOAuthの認証情報とプロパティIDを返す() {
        world.seedGa(1L, "987", "cid", "secret", "refresh");

        var credentials = service().googleAnalyticsCredentials(1L);

        assertTrue(credentials.configured());
        assertEquals("987", credentials.propertyId());
        assertEquals("cid", credentials.clientId());
        assertEquals("secret", credentials.clientSecret());
        assertEquals("refresh", credentials.refreshToken());
    }

    @Test
    void googleAnalyticsCredentials_toStringは秘密を出さない() {
        world.seedGa(1L, "987", "cid", "secret", "refresh");

        String text = service().googleAnalyticsCredentials(1L).toString();

        assertTrue(text.contains("987"));
        assertFalse(text.contains("secret"));
        assertFalse(text.contains("refresh"));
    }

    @Test
    void googleAnalyticsCredentials_未連携なら設定済みでなく秘密は返さない() {
        var credentials = service().googleAnalyticsCredentials(1L);

        assertFalse(credentials.configured());
        assertNull(credentials.propertyId());
        assertNull(credentials.clientId());
        assertNull(credentials.clientSecret());
        assertNull(credentials.refreshToken());
    }

    @Test
    void googleAnalyticsCredentials_クライアントのシークレットが無ければ設定済みとしない() {
        world.seedGa(1L, "987", "cid", null, "refresh");

        var credentials = service().googleAnalyticsCredentials(1L);

        assertFalse(credentials.configured());
        assertNull(credentials.refreshToken());
    }

    @Test
    void googleAnalyticsCredentials_クライアントIDが未保存でも設定済みとしない() {
        world.seedGa(1L, "987", null, "secret", "refresh");

        assertFalse(service().googleAnalyticsCredentials(1L).configured());
    }

    @Test
    void googleAnalyticsCredentials_クライアントIDが空白なら設定済みとしない() {
        world.seedGa(1L, "987", " ", "secret", "refresh");

        assertFalse(service().googleAnalyticsCredentials(1L).configured());
    }

    @Test
    void 状態の読み取りは保存された内容を返す() {
        world.seedGa(1L, "123", "cid", "secret", "refresh");

        assertTrue(service().hasGoogleAnalytics(1L));
        assertEquals("123", service().googleAnalyticsPropertyId(1L));
        assertEquals("cid", service().googleAnalyticsClientId(1L));
        assertTrue(service().hasGoogleAnalyticsClientSecret(1L));
        assertTrue(service().isGoogleAnalyticsConnected(1L));
    }

    @Test
    void 状態の読み取りは未保存なら未設定を返す() {
        assertFalse(service().hasGoogleAnalytics(1L));
        assertNull(service().googleAnalyticsPropertyId(1L));
        assertNull(service().googleAnalyticsClientId(1L));
        assertFalse(service().hasGoogleAnalyticsClientSecret(1L));
        assertFalse(service().isGoogleAnalyticsConnected(1L));
    }

    @Test
    void clearGoogleAnalyticsCredentials_保存された連携をすべて破棄する() {
        world.seedGa(1L, "123", "cid", "secret", "refresh");

        service().clearGoogleAnalyticsCredentials(1L);

        assertNull(service().googleAnalyticsPropertyId(1L));
        assertFalse(service().isGoogleAnalyticsConnected(1L));
        assertFalse(service().hasGoogleAnalyticsClientSecret(1L));
        assertNull(service().googleAnalyticsClientId(1L));
    }

    // ---- AdSense: パブリッシャーIDの自動発見(issue #1232) ----

    private void adSenseClientSaved() {
        world.seedAdSense(1L, null, "cid", "secret", null);
    }

    private void adSenseConnected() {
        world.seedAdSense(1L, null, "cid", "secret", "refresh");
    }

    @Test
    void completeAdSenseOAuth_アカウントが1件なら素のパブリッシャーIDを自動保存する() {
        adSenseClientSaved();
        tokenEndpointReturns("{\"access_token\":\"access\",\"refresh_token\":\"refresh-xyz\"}");
        world.http.json("GET", ADSENSE_ACCOUNTS,
                "{\"accounts\":[{\"name\":\"accounts/pub-1234567890123456\",\"displayName\":\"Site\"}]}");

        service().completeAdSenseOAuth(1L, "code", "https://x/cb");

        assertEquals("refresh-xyz", decrypt(row().getAdsenseRefreshTokenEncrypted()));
        assertEquals("pub-1234567890123456", row().getAdsenseAccountId());
        assertEquals("Bearer access", world.http.requests("GET", ADSENSE_ACCOUNTS).get(0).header("Authorization"));
    }

    @Test
    void completeAdSenseOAuth_アカウントが複数なら自動保存しない() {
        adSenseClientSaved();
        tokenEndpointReturns("{\"access_token\":\"access\",\"refresh_token\":\"refresh-xyz\"}");
        world.http.json("GET", ADSENSE_ACCOUNTS, """
                {"accounts":[{"name":"accounts/pub-1","displayName":"A"},{"name":"accounts/pub-2","displayName":"B"}]}
                """);

        service().completeAdSenseOAuth(1L, "code", "https://x/cb");

        assertEquals("refresh-xyz", decrypt(row().getAdsenseRefreshTokenEncrypted()));
        assertNull(row().getAdsenseAccountId());
    }

    @Test
    void completeAdSenseOAuth_アカウントが0件でもリフレッシュトークンは保存する() {
        adSenseClientSaved();
        tokenEndpointReturns("{\"access_token\":\"access\",\"refresh_token\":\"refresh-xyz\"}");
        world.http.json("GET", ADSENSE_ACCOUNTS, "{\"accounts\":[]}");

        service().completeAdSenseOAuth(1L, "code", "https://x/cb");

        assertEquals("refresh-xyz", decrypt(row().getAdsenseRefreshTokenEncrypted()));
        assertNull(row().getAdsenseAccountId());
    }

    @Test
    void completeAdSenseOAuth_一覧の取得に失敗してもリフレッシュトークンは保存され例外は伝えない() {
        adSenseClientSaved();
        tokenEndpointReturns("{\"access_token\":\"access\",\"refresh_token\":\"refresh-xyz\"}");
        world.http.status("GET", ADSENSE_ACCOUNTS, 403, "{}");

        service().completeAdSenseOAuth(1L, "code", "https://x/cb");

        assertEquals("refresh-xyz", decrypt(row().getAdsenseRefreshTokenEncrypted()));
        assertNull(row().getAdsenseAccountId());
    }

    @Test
    void completeAdSenseOAuth_認可コードの交換に失敗したら何も保存しない() {
        adSenseClientSaved();
        world.http.status("POST", TOKEN_PATH, 400, "{\"error\":\"invalid_grant\"}");

        assertThrows(AdSenseException.class, () -> service().completeAdSenseOAuth(1L, "bad", "https://x/cb"));

        assertNull(row().getAdsenseRefreshTokenEncrypted());
        assertTrue(world.http.requests("GET", ADSENSE_ACCOUNTS).isEmpty());
    }

    @Test
    void completeAdSenseOAuth_クライアントシークレット未保存ならGoogleへ問い合わせず拒否する() {
        assertThrows(AdSenseException.class, () -> service().completeAdSenseOAuth(1L, "code", "https://x/cb"));

        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    void listAdSenseAccounts_保存済みのリフレッシュトークンで一覧を取得する() {
        adSenseConnected();
        tokenEndpointReturns("{\"access_token\":\"access\"}");
        world.http.json("GET", ADSENSE_ACCOUNTS, "{\"accounts\":[{\"name\":\"accounts/pub-1\",\"displayName\":\"A\"}]}");

        List<AdSenseAccountSummary> accounts = service().listAdSenseAccounts(1L);

        assertEquals(List.of(new AdSenseAccountSummary("pub-1", "A")), accounts);
        assertTrue(world.http.requests("POST", TOKEN_PATH).get(0).bodyText().contains("refresh_token=refresh"));
    }

    @Test
    void listAdSenseAccounts_未連携なら拒否してGoogleへ問い合わせない() {
        assertThrows(IllegalArgumentException.class, () -> service().listAdSenseAccounts(1L));

        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    void listAdSenseAccounts_クライアントシークレット未保存なら拒否される() {
        world.seedAdSense(1L, null, "cid", null, "refresh");

        assertThrows(AdSenseException.class, () -> service().listAdSenseAccounts(1L));
        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    void selectAdSenseAccount_素のパブリッシャーIDを保存する() {
        adSenseConnected();

        service().selectAdSenseAccount(1L, " pub-2222222222222222 ");

        assertEquals("pub-2222222222222222", row().getAdsenseAccountId());
    }

    @Test
    void selectAdSenseAccount_accountsプレフィックスは取り除く() {
        adSenseConnected();

        service().selectAdSenseAccount(1L, "accounts/pub-333");

        assertEquals("pub-333", row().getAdsenseAccountId());
    }

    @Test
    void selectAdSenseAccount_未連携やpub形式でないIDは拒否する() {
        assertThrows(IllegalArgumentException.class, () -> service().selectAdSenseAccount(1L, "pub-1"));
        assertNull(world.store.get(1L));

        world.seedAdSense(2L, null, "cid", "secret", "refresh");
        assertThrows(IllegalArgumentException.class, () -> service().selectAdSenseAccount(2L, "abc"));
        assertThrows(IllegalArgumentException.class, () -> service().selectAdSenseAccount(2L, "accounts/pub-1/x"));
        assertThrows(IllegalArgumentException.class, () -> service().selectAdSenseAccount(2L, ""));
        assertThrows(IllegalArgumentException.class, () -> service().selectAdSenseAccount(2L, null));
        assertNull(world.store.get(2L).getAdsenseAccountId());
    }

    @Test
    void setAdSenseSettings_パブリッシャーIDが空ならnullとして保存する() {
        world.seedAdSense(1L, "pub-old", null, null, null);

        service().setAdSenseSettings(1L, "  ", "cid");
        assertNull(row().getAdsenseAccountId());
        assertEquals("cid", row().getAdsenseOauthClientId());

        world.seedAdSense(1L, "pub-old", null, null, null);
        service().setAdSenseSettings(1L, null, "cid");
        assertNull(row().getAdsenseAccountId());

        service().setAdSenseSettings(1L, " pub-1 ", "cid");
        assertEquals("pub-1", row().getAdsenseAccountId());
    }

    @Test
    void setAdSenseClientSecret_シークレットは暗号化して保存する() {
        service().setAdSenseClientSecret(1L, "plain-secret");

        assertEquals("plain-secret", decrypt(row().getAdsenseOauthClientSecretEncrypted()));
        assertTrue(service().hasAdSenseClientSecret(1L));
    }

    @Test
    void AdSenseの状態の読み取りは保存された内容を返す() {
        world.seedAdSense(1L, "pub-1", "cid", "secret", "refresh");

        assertTrue(service().hasAdSense(1L));
        assertEquals("pub-1", service().adSenseAccountId(1L));
        assertEquals("cid", service().adSenseClientId(1L));
        assertTrue(service().hasAdSenseClientSecret(1L));
        assertTrue(service().isAdSenseConnected(1L));
    }

    @Test
    void AdSenseの状態の読み取りは未保存なら未設定を返す() {
        assertFalse(service().hasAdSense(1L));
        assertNull(service().adSenseAccountId(1L));
        assertNull(service().adSenseClientId(1L));
        assertFalse(service().hasAdSenseClientSecret(1L));
        assertFalse(service().isAdSenseConnected(1L));
    }

    @Test
    void clearAdSenseCredentials_保存された連携をすべて破棄する() {
        world.seedAdSense(1L, "pub-1", "cid", "secret", "refresh");

        service().clearAdSenseCredentials(1L);

        assertFalse(service().hasAdSense(1L));
        assertFalse(service().isAdSenseConnected(1L));
        assertFalse(service().hasAdSenseClientSecret(1L));
        assertNull(service().adSenseClientId(1L));
    }
}
