package com.letsblog.analytics.service;

import com.letsblog.analytics.domain.AnalyticsCredentials;
import com.letsblog.analytics.testsupport.AnalyticsWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AnalyticsCredentialsServiceの回帰テスト(issue #571)。analytics_credentialsは初回書き込み時に
 * 遅延作成されること、GA/AdSenseそれぞれのクリア処理が全項目をnullにすることを検証する。
 */
class AnalyticsCredentialsServiceTest {

    /** issue #1827: DB(リポジトリ)だけをメモリ上の「テーブル」に差し替える。サービスは実物。 */
    private AnalyticsWorld world;

    @BeforeEach
    void setUp() {
        world = new AnalyticsWorld();
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private AnalyticsCredentialsService service() {
        return world.credentials;
    }

    @Test
    void hasGoogleAnalyticsCredentials_未設定なら行が無くてもfalse() {

        assertFalse(service().hasGoogleAnalyticsCredentials(1L));
    }

    @Test
    void setGaRefreshTokenEncrypted_新規行を作成して保存する() {

        service().setGaRefreshTokenEncrypted(1L, new byte[]{1, 2, 3});

        AnalyticsCredentials saved = world.store.get(1L);
        assertEquals(3, saved.getGaRefreshTokenEncrypted().length);
        assertTrue(saved.hasGoogleAnalyticsConnection());
        // プロパティ未選択なので、ダッシュボードの資格情報としては未設定のまま
        assertFalse(saved.hasGoogleAnalyticsCredentials());
    }

    @Test
    void hasGoogleAnalyticsCredentials_プロパティIDとリフレッシュトークンの両方が必要() {
        AnalyticsCredentials existing = new AnalyticsCredentials(1L);
        world.store.put(1L, existing);

        assertFalse(service().hasGoogleAnalyticsCredentials(1L));
        assertFalse(service().hasGaRefreshToken(1L));

        service().setGaPropertyId(1L, "123456789");
        assertFalse(existing.hasGoogleAnalyticsCredentials());
        assertEquals("123456789", service().getGaPropertyId(1L));

        service().setGaRefreshTokenEncrypted(1L, new byte[]{1});
        assertTrue(service().hasGoogleAnalyticsCredentials(1L));
        assertTrue(service().hasGaRefreshToken(1L));

        existing.setGaPropertyId(" ");
        assertFalse(existing.hasGoogleAnalyticsCredentials());
        existing.setGaPropertyId("1");
        existing.setGaRefreshTokenEncrypted(new byte[0]);
        assertFalse(existing.hasGoogleAnalyticsCredentials());
        existing.setGaRefreshTokenEncrypted(null);
        assertFalse(existing.hasGoogleAnalyticsConnection());
    }

    @Test
    void setGaOauthClient_シークレットがnullなら既存のシークレットを保つ() {
        AnalyticsCredentials existing = new AnalyticsCredentials(1L);
        existing.setGaOauthClientSecretEncrypted(new byte[]{9});
        world.store.put(1L, existing);

        service().setGaOauthClient(1L, "client-id", null);

        assertEquals("client-id", service().getGaOauthClientId(1L));
        assertTrue(service().hasGaOauthClientSecret(1L));
        assertEquals(1, service().getGaOauthClientSecretEncrypted(1L).length);

        service().setGaOauthClient(1L, "client-id-2", new byte[]{1, 2});
        assertEquals(2, existing.getGaOauthClientSecretEncrypted().length);
    }

    @Test
    void hasGaOauthClientSecret_行があってもシークレットがnullや空なら未保存() {
        AnalyticsCredentials existing = new AnalyticsCredentials(1L);
        world.store.put(1L, existing);

        assertFalse(service().hasGaOauthClientSecret(1L));
        existing.setGaOauthClientSecretEncrypted(new byte[0]);
        assertFalse(service().hasGaOauthClientSecret(1L));
        existing.setGaOauthClientSecretEncrypted(new byte[]{1});
        assertTrue(service().hasGaOauthClientSecret(1L));
    }

    @Test
    void gaの読み取りは行が無ければ未設定として返す() {

        assertNull(service().getGaOauthClientId(1L));
        assertFalse(service().hasGaOauthClientSecret(1L));
        assertNull(service().getGaOauthClientSecretEncrypted(1L));
        assertNull(service().getGaRefreshTokenEncrypted(1L));
        assertNull(service().getGaPropertyId(1L));
    }

    @Test
    void clearGoogleAnalyticsCredentials_リフレッシュトークンとプロパティとクライアントを破棄する() {
        AnalyticsCredentials existing = new AnalyticsCredentials(1L);
        existing.setGaPropertyId("123456789");
        existing.setGaRefreshTokenEncrypted(new byte[]{1, 2, 3});
        existing.setGaOauthClientId("client-id");
        existing.setGaOauthClientSecretEncrypted(new byte[]{4});
        world.store.put(1L, existing);

        service().clearGoogleAnalyticsCredentials(1L);

        assertFalse(existing.hasGoogleAnalyticsCredentials());
        assertFalse(existing.hasGoogleAnalyticsConnection());
        assertNull(existing.getGaPropertyId());
        assertNull(existing.getGaOauthClientId());
        assertNull(existing.getGaOauthClientSecretEncrypted());
    }

    @Test
    void clearAdSenseCredentials_全項目nullにする() {
        AnalyticsCredentials existing = new AnalyticsCredentials(1L);
        existing.setAdsenseAccountId("pub-1234567890123456");
        existing.setAdsenseRefreshTokenEncrypted(new byte[]{1, 2, 3});
        existing.setAdsenseOauthClientId("client-id");
        existing.setAdsenseOauthClientSecretEncrypted(new byte[]{4, 5, 6});
        world.store.put(1L, existing);

        service().clearAdSenseCredentials(1L);

        assertFalse(existing.hasAdsenseCredentials());
        assertFalse(existing.hasAdsenseOauthClient());
    }

    @Test
    void hasAdsenseCredentials_accountIdとrefreshTokenの両方が必要() {
        AnalyticsCredentials existing = new AnalyticsCredentials(1L);
        existing.setAdsenseAccountId("pub-1234567890123456");
        world.store.put(1L, existing);

        assertFalse(service().hasAdsenseCredentials(1L));

        existing.setAdsenseRefreshTokenEncrypted(new byte[]{1, 2, 3});
        assertTrue(service().hasAdsenseCredentials(1L));
    }

    @Test
    void hasAdsenseRefreshToken_パブリッシャーID未取得でもリフレッシュトークンがあればtrue() {
        AnalyticsCredentials existing = new AnalyticsCredentials(1L);
        world.store.put(1L, existing);
        assertFalse(service().hasAdsenseRefreshToken(1L));

        existing.setAdsenseRefreshTokenEncrypted(new byte[]{1, 2, 3});
        assertTrue(service().hasAdsenseRefreshToken(1L));
        // パブリッシャーID未取得なので、ダッシュボードの資格情報としては未設定のまま
        assertFalse(service().hasAdsenseCredentials(1L));
    }

    @Test
    void hasAdsenseRefreshToken_空のリフレッシュトークンは連携済みと見なさない() {
        AnalyticsCredentials existing = new AnalyticsCredentials(1L);
        existing.setAdsenseRefreshTokenEncrypted(new byte[0]);
        world.store.put(1L, existing);

        assertFalse(service().hasAdsenseRefreshToken(1L));
    }

    @Test
    void hasAdsenseRefreshToken_行が無ければfalse() {

        assertFalse(service().hasAdsenseRefreshToken(1L));
    }

    @Test
    void setAdsenseAccountId_リフレッシュトークンを変えずにIDだけを保存する() {
        AnalyticsCredentials existing = new AnalyticsCredentials(1L);
        existing.setAdsenseRefreshTokenEncrypted(new byte[]{1, 2, 3});
        existing.setAdsenseOauthClientId("client-id");
        world.store.put(1L, existing);

        service().setAdsenseAccountId(1L, "pub-1234567890123456");

        assertEquals("pub-1234567890123456", existing.getAdsenseAccountId());
        assertEquals("client-id", existing.getAdsenseOauthClientId());
        assertTrue(existing.hasAdsenseCredentials());
    }
}
