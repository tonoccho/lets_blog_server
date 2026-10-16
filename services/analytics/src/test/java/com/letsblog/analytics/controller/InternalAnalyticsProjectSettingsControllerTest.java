package com.letsblog.analytics.controller;

import com.letsblog.analytics.domain.AnalyticsCredentials;
import com.letsblog.analytics.testsupport.AnalyticsWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 内部ブリッジの GA 認証情報(issue #1578)。project-service が本番サイトのプラグインへ送るために、復号済みの
 * 認証情報を読む。公開 API には載せず、呼び出し元ユーザーがプロジェクトのメンバーかadminであることを検査する。
 *
 * <p>issue #1827: サービスのモックをやめ、実物のコントローラ・サービスをMockMvcに載せる。外部との通信
 * (identity-service・Google)はサーバー側フェイクのHTTP、DBはメモリ上のリポジトリ。「権限を確かめてから復号済みの
 * 認証情報を返す」順序は、権限が無いときに何も返らないこと(403の本文に秘密が無いこと)で確かめる。
 */
class InternalAnalyticsProjectSettingsControllerTest {

    private static final String BASE = "/api/internal/analytics/projects/1";

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

    @Test
    void 権限を確かめてから復号済みの認証情報を返す() throws Exception {
        world.seedGa(1L, "987", "cid", "cs", "rt");

        world.mvc.perform(get(BASE + "/google-analytics/credentials").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.propertyId").value("987"))
                .andExpect(jsonPath("$.clientId").value("cid"))
                .andExpect(jsonPath("$.clientSecret").value("cs"))
                .andExpect(jsonPath("$.refreshToken").value("rt"));
    }

    @Test
    void 未連携なら設定済みでなく秘密は返さない() throws Exception {
        world.mvc.perform(get(BASE + "/google-analytics/credentials").header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void メンバーでもadminでもなければ秘密を読ませない() throws Exception {
        world.seedGa(1L, "987", "cid", "cs", "rt");
        world.actor("user-token", 10L, "user");

        String body = world.mvc.perform(
                        get(BASE + "/google-analytics/credentials").header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("cs"), body);
        assertFalse(body.contains("rt"), body);
    }

    @Test
    void プロジェクトメンバーなら秘密を読める() throws Exception {
        world.seedGa(1L, "987", "cid", "cs", "rt");
        world.actor("member-token", 10L, "user").member(1L, 10L);

        world.mvc.perform(get(BASE + "/google-analytics/credentials").header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").value("cs"));
    }

    @Test
    void GAの状態を返し解除できる() throws Exception {
        world.seedGa(1L, "987", "cid", "cs", "rt");

        world.mvc.perform(get(BASE + "/google-analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.propertyId").value("987"));

        world.mvc.perform(delete(BASE + "/google-analytics")).andExpect(status().isOk());

        assertNull(row().getGaPropertyId());
        assertNull(row().getGaRefreshTokenEncrypted());
        world.mvc.perform(get(BASE + "/google-analytics"))
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void AdSenseの状態を返す() throws Exception {
        world.seedAdSense(1L, "pub-1", "cid", "secret", "refresh");

        world.mvc.perform(get(BASE + "/adsense"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.accountId").value("pub-1"))
                .andExpect(jsonPath("$.clientId").value("cid"))
                .andExpect(jsonPath("$.hasClientSecret").value(true));
    }

    @Test
    void AdSenseの設定を保存しシークレットは暗号化して保存し解除できる() throws Exception {
        world.mvc.perform(put(BASE + "/adsense").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\" pub-9 \",\"clientId\":\"cid\"}"))
                .andExpect(status().isOk());
        assertEquals("pub-9", row().getAdsenseAccountId());
        assertEquals("cid", row().getAdsenseOauthClientId());

        world.mvc.perform(put(BASE + "/adsense/client-secret").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientSecret\":\"plain\"}"))
                .andExpect(status().isOk());
        assertEquals("plain", world.cipher.decrypt(row().getAdsenseOauthClientSecretEncrypted()));

        world.mvc.perform(delete(BASE + "/adsense")).andExpect(status().isOk());
        assertFalse(row().hasAdsenseOauthClient());
        assertNull(row().getAdsenseAccountId());
    }

    @Test
    void AdSenseの認可コードを交換してリフレッシュトークンを保存する() throws Exception {
        world.seedAdSense(1L, null, "cid", "secret", null);
        world.http.json("POST", "/oauth/token", "{\"access_token\":\"access\",\"refresh_token\":\"refresh-xyz\"}");
        world.http.json("GET", "/adsense/v2/accounts", "{\"accounts\":[{\"name\":\"accounts/pub-7\"}]}");

        world.mvc.perform(post(BASE + "/adsense/oauth-callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"redirectUri\":\"https://x/cb\"}"))
                .andExpect(status().isOk());

        assertEquals("refresh-xyz", world.cipher.decrypt(row().getAdsenseRefreshTokenEncrypted()));
        assertEquals("pub-7", row().getAdsenseAccountId());
        assertTrue(row().hasAdsenseCredentials());
    }

    @Test
    void AdSenseの認可コード交換がGoogleに拒否されたら502() throws Exception {
        world.seedAdSense(1L, null, "cid", "secret", null);
        world.http.status("POST", "/oauth/token", 400, "{}");

        world.mvc.perform(post(BASE + "/adsense/oauth-callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"c\",\"redirectUri\":\"https://x/cb\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
