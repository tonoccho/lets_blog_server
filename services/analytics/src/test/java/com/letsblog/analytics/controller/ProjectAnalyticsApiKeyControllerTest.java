package com.letsblog.analytics.controller;

import com.letsblog.analytics.domain.AnalyticsCredentials;
import com.letsblog.analytics.testsupport.AnalyticsWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ProjectAnalyticsApiKeyControllerのGoogle Analytics OAuth連携エンドポイントの回帰テスト(issue #1231)。
 * 各エンドポイントが認可を先に行うこと、状態応答が秘密情報(クライアントシークレット/リフレッシュトークン)を
 * 含まず「あるか否か」だけを返すことを確かめる。
 *
 * <p>issue #1827: サービス・認可・{@code ProjectBridgeClient}のモックをやめ、実物のコントローラ・サービスを
 * MockMvcに載せる。外部との通信(identity-service・project-service・Google)はサーバー側フェイクのHTTP、DBは
 * メモリ上のリポジトリ。「認可を先に行う」は、認可に失敗したとき保存も下流への通信も起きないことで確かめる。
 */
class ProjectAnalyticsApiKeyControllerTest {

    private static final String BASE = "/api/projects/1/api-keys";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String ADMIN = "Bearer admin-token";

    private AnalyticsWorld world;

    @BeforeEach
    void setUp() {
        world = new AnalyticsWorld();
        world.actor("admin-token", 1L, "admin");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private AnalyticsCredentials row() {
        return world.store.get(1L);
    }

    // ---- Google Analytics ----

    @Test
    void getGoogleAnalyticsStatus_連携状態を返しシークレットは含まない() throws Exception {
        world.seedGa(1L, "123", "cid", "super-secret", "super-refresh");

        String body = world.mvc.perform(get(BASE + "/google-analytics").header("Authorization", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.propertyId").value("123"))
                .andExpect(jsonPath("$.clientId").value("cid"))
                .andExpect(jsonPath("$.hasClientSecret").value(true))
                .andExpect(jsonPath("$.connected").value(true))
                .andReturn().getResponse().getContentAsString();

        assertTrue(!body.contains("super-secret") && !body.contains("super-refresh"), body);
    }

    @Test
    void getGoogleAnalyticsStatus_未設定なら全てfalse() throws Exception {
        world.mvc.perform(get(BASE + "/google-analytics").header("Authorization", ADMIN))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.hasClientSecret").value(false))
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    void setGoogleAnalyticsClient_認可後にクライアントを保存する() throws Exception {
        world.mvc.perform(put(BASE + "/google-analytics/client").header("Authorization", ADMIN)
                        .contentType(JSON).content("{\"clientId\":\"cid\",\"clientSecret\":\"secret\"}"))
                .andExpect(status().isNoContent());

        assertEquals("cid", row().getGaOauthClientId());
        assertEquals("secret", world.cipher.decrypt(row().getGaOauthClientSecretEncrypted()));
    }

    @Test
    void setGoogleAnalyticsClient_clientIdが空なら400で保存しない() throws Exception {
        world.mvc.perform(put(BASE + "/google-analytics/client").header("Authorization", ADMIN)
                        .contentType(JSON).content("{\"clientId\":\" \",\"clientSecret\":\"secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("clientId"));

        assertNull(world.store.get(1L));
    }

    @Test
    void completeGoogleAnalyticsOAuth_認可コードを渡す() throws Exception {
        world.seedGa(1L, null, "cid", "secret", null);
        world.http.json("POST", "/oauth/token", "{\"access_token\":\"a\",\"refresh_token\":\"refresh-xyz\"}");

        world.mvc.perform(post(BASE + "/google-analytics/oauth-callback").header("Authorization", ADMIN)
                        .contentType(JSON).content("{\"code\":\"code\",\"redirectUri\":\"https://x/cb\"}"))
                .andExpect(status().isNoContent());

        assertEquals("refresh-xyz", world.cipher.decrypt(row().getGaRefreshTokenEncrypted()));
        assertTrue(world.http.requests("POST", "/oauth/token").get(0).bodyText().contains("code=code"));
    }

    @Test
    void listGoogleAnalyticsProperties_プロパティIDと表示名の一覧を返す() throws Exception {
        world.seedGa(1L, null, "cid", "secret", "refresh");
        world.http.json("POST", "/oauth/token", "{\"access_token\":\"access\"}");
        world.http.json("GET", "/ga-admin/v1beta/accountSummaries", """
                {"accountSummaries":[{"account":"accounts/1","displayName":"Account One",
                  "propertySummaries":[{"property":"properties/111","displayName":"Site A"}]}]}
                """);

        world.mvc.perform(get(BASE + "/google-analytics/properties").header("Authorization", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].propertyId").value("111"))
                .andExpect(jsonPath("$[0].displayName").value("Site A"))
                .andExpect(jsonPath("$[0].accountDisplayName").value("Account One"));
    }

    @Test
    void listGoogleAnalyticsProperties_未連携なら409でGoogleへ問い合わせない() throws Exception {
        world.mvc.perform(get(BASE + "/google-analytics/properties").header("Authorization", ADMIN))
                .andExpect(status().isConflict());

        assertTrue(world.http.requests("*", "/(oauth|ga-admin)/.*").isEmpty());
    }

    @Test
    void selectGoogleAnalyticsProperty_選択を保存する() throws Exception {
        world.seedGa(1L, null, "cid", "secret", "refresh");

        world.mvc.perform(put(BASE + "/google-analytics/property").header("Authorization", ADMIN)
                        .contentType(JSON).content("{\"propertyId\":\"111\"}"))
                .andExpect(status().isNoContent());

        assertEquals("111", row().getGaPropertyId());
    }

    @Test
    void selectGoogleAnalyticsProperty_保存したあと_本番サイトへ送るようproject_serviceへ知らせる() throws Exception {
        world.seedGa(1L, null, "cid", "secret", "refresh");

        world.mvc.perform(put(BASE + "/google-analytics/property").header("Authorization", ADMIN)
                        .contentType(JSON).content("{\"propertyId\":\"111\"}"))
                .andExpect(status().isNoContent());

        var notify = world.http.requests("POST", "/api/internal/project/projects/1/sns/pv/sync");
        assertEquals(1, notify.size());
        assertEquals("Bearer admin-token", notify.get(0).header("Authorization"));
        assertEquals("111", row().getGaPropertyId());
    }

    @Test
    void selectGoogleAnalyticsProperty_保存が拒否されたらproject_serviceへは知らせない() throws Exception {
        world.mvc.perform(put(BASE + "/google-analytics/property").header("Authorization", ADMIN)
                        .contentType(JSON).content("{\"propertyId\":\"111\"}"))
                .andExpect(status().isConflict());

        assertTrue(world.http.requests("POST", "/api/internal/project/.*").isEmpty());
    }

    @Test
    void clearGoogleAnalyticsCredentials_連携を解除する() throws Exception {
        world.seedGa(1L, "123", "cid", "secret", "refresh");

        world.mvc.perform(delete(BASE + "/google-analytics").header("Authorization", ADMIN))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertNull(row().getGaPropertyId());
        assertNull(row().getGaRefreshTokenEncrypted());
    }

    // ---- AdSense ----

    @Test
    void getAdSenseStatus_連携状態を返しシークレットは含まない() throws Exception {
        world.seedAdSense(1L, null, "cid", "super-secret", "super-refresh");

        String body = world.mvc.perform(get(BASE + "/adsense").header("Authorization", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.accountId").doesNotExist())
                .andExpect(jsonPath("$.clientId").value("cid"))
                .andExpect(jsonPath("$.hasClientSecret").value(true))
                .andExpect(jsonPath("$.connected").value(true))
                .andReturn().getResponse().getContentAsString();

        assertTrue(!body.contains("super-secret") && !body.contains("super-refresh"), body);
    }

    @Test
    void setAdSenseSettings_パブリッシャーIDは省略できる() throws Exception {
        world.mvc.perform(put(BASE + "/adsense").header("Authorization", ADMIN)
                        .contentType(JSON).content("{\"clientId\":\"cid\"}"))
                .andExpect(status().isNoContent());

        assertNull(row().getAdsenseAccountId());
        assertEquals("cid", row().getAdsenseOauthClientId());
    }

    @Test
    void listAdSenseAccounts_パブリッシャーIDと表示名の一覧を返す() throws Exception {
        world.seedAdSense(1L, null, "cid", "secret", "refresh");
        world.http.json("POST", "/oauth/token", "{\"access_token\":\"access\"}");
        world.http.json("GET", "/adsense/v2/accounts",
                "{\"accounts\":[{\"name\":\"accounts/pub-1\",\"displayName\":\"Site A\"}]}");

        world.mvc.perform(get(BASE + "/adsense/accounts").header("Authorization", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].accountId").value("pub-1"))
                .andExpect(jsonPath("$[0].displayName").value("Site A"));
    }

    @Test
    void selectAdSenseAccount_認可後に選択したIDを保存する() throws Exception {
        world.seedAdSense(1L, null, "cid", "secret", "refresh");

        world.mvc.perform(put(BASE + "/adsense/account").header("Authorization", ADMIN)
                        .contentType(JSON).content("{\"accountId\":\"pub-2\"}"))
                .andExpect(status().isNoContent());

        assertEquals("pub-2", row().getAdsenseAccountId());
    }

    @Test
    void setAdSenseClientSecret_暗号化して保存する() throws Exception {
        world.mvc.perform(put(BASE + "/adsense/client-secret").header("Authorization", ADMIN)
                        .contentType(JSON).content("{\"clientSecret\":\"plain\"}"))
                .andExpect(status().isNoContent());

        assertEquals("plain", world.cipher.decrypt(row().getAdsenseOauthClientSecretEncrypted()));
    }

    @Test
    void clearAdSenseCredentials_連携を解除する() throws Exception {
        world.seedAdSense(1L, "pub-1", "cid", "secret", "refresh");

        world.mvc.perform(delete(BASE + "/adsense").header("Authorization", ADMIN))
                .andExpect(status().isNoContent());

        assertNull(row().getAdsenseAccountId());
        assertNull(row().getAdsenseRefreshTokenEncrypted());
    }

    @Test
    void completeAdSenseOAuth_認可コードを渡す() throws Exception {
        world.seedAdSense(1L, null, "cid", "secret", null);
        world.http.json("POST", "/oauth/token", "{\"access_token\":\"a\",\"refresh_token\":\"refresh-xyz\"}");
        world.http.json("GET", "/adsense/v2/accounts", "{\"accounts\":[]}");

        world.mvc.perform(post(BASE + "/adsense/oauth-callback").header("Authorization", ADMIN)
                        .contentType(JSON).content("{\"code\":\"code\",\"redirectUri\":\"https://x/cb\"}"))
                .andExpect(status().isNoContent());

        assertEquals("refresh-xyz", world.cipher.decrypt(row().getAdsenseRefreshTokenEncrypted()));
    }

    // ---- 認可 ----

    @Test
    void 認可に失敗したら全てのエンドポイントで保存も下流への通信も起きない() throws Exception {
        world.seedGa(1L, "123", "cid", "secret", "refresh");
        world.seedAdSense(1L, "pub-1", "cid", "secret", "refresh");
        world.actor("user-token", 10L, "user");
        String user = "Bearer user-token";

        world.mvc.perform(get(BASE + "/google-analytics").header("Authorization", user))
                .andExpect(status().isForbidden());
        world.mvc.perform(put(BASE + "/google-analytics/client").header("Authorization", user)
                        .contentType(JSON).content("{\"clientId\":\"x\"}"))
                .andExpect(status().isForbidden());
        world.mvc.perform(post(BASE + "/google-analytics/oauth-callback").header("Authorization", user)
                        .contentType(JSON).content("{\"code\":\"c\",\"redirectUri\":\"r\"}"))
                .andExpect(status().isForbidden());
        world.mvc.perform(get(BASE + "/google-analytics/properties").header("Authorization", user))
                .andExpect(status().isForbidden());
        world.mvc.perform(put(BASE + "/google-analytics/property").header("Authorization", user)
                        .contentType(JSON).content("{\"propertyId\":\"1\"}"))
                .andExpect(status().isForbidden());
        world.mvc.perform(delete(BASE + "/google-analytics").header("Authorization", user))
                .andExpect(status().isForbidden());
        world.mvc.perform(get(BASE + "/adsense").header("Authorization", user))
                .andExpect(status().isForbidden());
        world.mvc.perform(get(BASE + "/adsense/accounts").header("Authorization", user))
                .andExpect(status().isForbidden());
        world.mvc.perform(put(BASE + "/adsense/account").header("Authorization", user)
                        .contentType(JSON).content("{\"accountId\":\"pub-1\"}"))
                .andExpect(status().isForbidden());
        world.mvc.perform(put(BASE + "/adsense").header("Authorization", user)
                        .contentType(JSON).content("{\"clientId\":\"x\"}"))
                .andExpect(status().isForbidden());
        world.mvc.perform(put(BASE + "/adsense/client-secret").header("Authorization", user)
                        .contentType(JSON).content("{\"clientSecret\":\"x\"}"))
                .andExpect(status().isForbidden());
        world.mvc.perform(delete(BASE + "/adsense").header("Authorization", user))
                .andExpect(status().isForbidden());
        world.mvc.perform(post(BASE + "/adsense/oauth-callback").header("Authorization", user)
                        .contentType(JSON).content("{\"code\":\"c\",\"redirectUri\":\"r\"}"))
                .andExpect(status().isForbidden());

        assertEquals("123", row().getGaPropertyId());
        assertEquals("cid", row().getGaOauthClientId());
        assertEquals("pub-1", row().getAdsenseAccountId());
        assertTrue(world.http.requests("*", "/(oauth|ga-admin|adsense|ga-data|api/internal/project)/.*").isEmpty());
    }

    @Test
    void プロジェクトメンバーなら操作できる() throws Exception {
        world.actor("member-token", 10L, "user").member(1L, 10L);

        world.mvc.perform(get(BASE + "/adsense").header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk());
    }
}
