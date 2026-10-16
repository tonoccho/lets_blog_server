package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.client.ThreadsApiClient;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import com.letsblog.project.testsupport.SnsFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * プロジェクトの Threads アカウント接続(issue #1579。Epic #1572)。X(#1574)と同じく、本番サイトのプラグインへは
 * wp-cli(`sns config set` を標準入力の JSON で)だけで送り、アプリはトークンを保存しない・API で返さない。
 * Threads は更新にクライアントの秘密が要らないので、プラグインへ送るのは長期トークンとユーザーIDだけ。
 *
 * <p>issue #1817: SnsXService・SiteService・ThreadsApiClient・XAuthorizationStore・CurrentActorService は実物。
 * Threads の API と CMS ブリッジの代わりにフェイクHTTPサーバーが応答する。
 */
class SnsThreadsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final String CALLBACK = "https://l/cb";

    private final SnsFixture fx = new SnsFixture();
    private SnsThreadsService service;

    @BeforeEach
    void setUp() {
        ThreadsApiClient threadsApiClient =
                new ThreadsApiClient(fx.world.http.baseUrl(), "https://threads.example/authorize");
        service = new SnsThreadsService(fx.snsXService, fx.world.siteService, threadsApiClient, fx.store,
                fx.world.currentActorService, fx.world.objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void close() {
        fx.close();
    }

    private String started() {
        fx.connectableSite();
        fx.world.signedInAs("sub-1");
        return SnsFixture.queryParam(service.startAuthorization(7L, "app-id", "app-secret", CALLBACK), "state");
    }

    private void threadsAnswers() {
        fx.world.http.json("POST", "/oauth/access_token", "{\"access_token\":\"SHORT-TOKEN\",\"user_id\":\"17841400000000001\"}");
        fx.world.http.json("GET", "/access_token", "{\"access_token\":\"LONG-TOKEN\",\"expires_in\":5184000}");
        fx.world.http.json("GET", "/v1.0/me", "{\"id\":\"1\",\"username\":\"lets_blog\"}");
    }

    private boolean sentToSite() {
        return !fx.world.snsRequests("config-set").isEmpty();
    }

    // ---- view / test(状態の読み取りとテスト投稿は X と共通の処理に Threads を指定して任せる) ----

    @Test
    void view_Threadsの接続状態を返す() {
        fx.connectableSite();
        fx.world.letsblogSns("status", "threads", "{\"threads\":{\"status\":\"接続済み\",\"account_name\":\"th\"}}");
        fx.world.letsblogSns("log", "threads", "[]");

        XConnectionView view = service.view(7L);

        assertTrue(view.connectable());
        assertEquals(XConnectionView.State.CONNECTED, view.status().state());
        assertEquals("th", view.status().accountName());
    }

    @Test
    void test_Threadsへのテスト投稿を依頼する() {
        fx.connectableSite();
        fx.world.letsblogSns("test", "threads", "{}");

        XTestResult result = service.test(7L);

        assertTrue(result.success());
        assertEquals("threads", fx.world.snsRequests("test").get(0).path("sns").asText());
    }

    // ---- startAuthorization ----

    @Test
    void startAuthorization_クライアントの情報かリダイレクト先が空なら拒否する() {
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, " ", "s", CALLBACK));
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, "c", "", CALLBACK));
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, "c", "s", null));
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, null, "s", CALLBACK));
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, "c", null, CALLBACK));
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, "c", "s", " "));
        assertTrue(fx.world.http.requests().isEmpty());
    }

    @Test
    void startAuthorization_接続できないサイトでは理由つきで拒否し認可を始めない() {
        fx.projectWithProductionSite(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.startAuthorization(7L, "app-id", "app-secret", CALLBACK));

        assertTrue(e.getMessage().contains("本番サイト"));
        assertEquals(0, fx.store.size());
    }

    @Test
    void startAuthorization_認可を保存してThreadsの認可URLを返す() {
        fx.connectableSite();
        fx.world.signedInAs("sub-1");

        String url = service.startAuthorization(7L, "app-id", "app-secret", CALLBACK);

        assertTrue(url.startsWith("https://threads.example/authorize?"), url);
        assertTrue(url.contains("client_id=app-id"));
        XAuthorizationStore.Pending pending = fx.store.take(SnsFixture.queryParam(url, "state")).orElseThrow();
        assertEquals(7L, pending.projectId());
        assertEquals(3L, pending.siteId());
        assertEquals("sub-1", pending.actorSub());
        assertEquals("app-secret", pending.clientSecret());
    }

    // ---- completeAuthorization ----

    @Test
    void completeAuthorization_長期トークンを本番サイトへ標準入力で送りアカウント名だけを返す() throws Exception {
        String state = started();
        threadsAnswers();
        fx.world.letsblogSns("config-set", "threads", "{\"sns\":\"threads\",\"status\":\"接続済み\"}");

        XConnectResult result = service.completeAuthorization(7L, state, "the-code");

        assertEquals(7L, result.projectId());
        assertEquals("lets_blog", result.accountName());
        assertTrue(fx.world.http.requests("POST", "/oauth/access_token").get(0).bodyText().contains("code=the-code"));
        assertEquals("Bearer LONG-TOKEN", fx.world.http.requests("GET", "/v1.0/me").get(0).header("Authorization"));
        JsonNode command = fx.world.snsRequests("config-set").get(0);
        String stdin = command.path("stdin").asText();
        JsonNode sent = fx.world.objectMapper.readTree(stdin);
        assertEquals("threads", sent.path("sns").asText());
        assertEquals("LONG-TOKEN", sent.path("access_token").asText());
        assertEquals("17841400000000001", sent.path("user_id").asText());
        assertEquals(NOW.getEpochSecond(), sent.path("issued_at").asLong());
        assertEquals(NOW.getEpochSecond() + 5184000L, sent.path("expires_at").asLong());
        assertEquals("lets_blog", sent.path("account_name").asText());
        // 更新に要らないクライアントの秘密と短期トークンは、プラグインへも送らない。
        assertFalse(stdin.contains("app-secret"));
        assertFalse(stdin.contains("SHORT-TOKEN"));
        String json = fx.world.objectMapper.writeValueAsString(result);
        assertFalse(json.contains("LONG-TOKEN"));
        assertFalse(json.contains("app-secret"));
    }

    @Test
    void completeAuthorization_知らないstateや期限切れは拒否する() {
        fx.world.signedInAs("sub-1");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("もう一度"));
        assertTrue(fx.world.http.requests().isEmpty());
    }

    @Test
    void completeAuthorization_別のプロジェクトのstateは拒否する() {
        String state = started();
        int before = fx.world.http.requests().size();

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(8L, state, "code"));
        assertEquals(before, fx.world.http.requests().size());
    }

    @Test
    void completeAuthorization_認可を始めた本人以外は拒否する() {
        String state = started();
        fx.world.signedInAs("someone-else");

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(7L, state, "code"));
        assertTrue(fx.world.http.requests("POST", "/oauth/access_token").isEmpty());
    }

    @Test
    void completeAuthorization_認可を始めた本人が不明なstateは拒否する() {
        fx.connectableSite();
        fx.world.signedInAs(null);
        String state = SnsFixture.queryParam(service.startAuthorization(7L, "app-id", "app-secret", CALLBACK), "state");

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(7L, state, "code"));
    }

    @Test
    void completeAuthorization_その間に接続できなくなっていたら拒否する() {
        String state = started();
        fx.projectWithProductionSite(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "code"));

        assertTrue(e.getMessage().contains("本番サイト"));
        assertTrue(fx.world.http.requests("POST", "/oauth/access_token").isEmpty());
    }

    @Test
    void completeAuthorization_トークン交換が失敗したら接続失敗として返す() {
        String state = started();
        fx.world.http.status("POST", "/oauth/access_token", 400, "{\"error\":{\"message\":\"bad code\"}}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "code"));

        assertTrue(e.getMessage().contains("HTTP 400"));
        assertFalse(sentToSite());
    }

    @Test
    void completeAuthorization_長期トークン化に失敗したら接続失敗として返し送らない() {
        String state = started();
        threadsAnswers();
        fx.world.http.status("GET", "/access_token", 400, "{\"error\":{\"message\":\"x\"}}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "code"));

        assertTrue(e.getMessage().contains("長期トークン"));
        assertFalse(sentToSite());
    }

    @Test
    void completeAuthorization_アカウント名を取れなければ接続失敗として返し送らない() {
        String state = started();
        threadsAnswers();
        fx.world.http.status("GET", "/v1.0/me", 401, "{\"error\":{\"message\":\"expired\"}}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "code"));

        assertTrue(e.getMessage().contains("HTTP 401"));
        assertFalse(sentToSite());
    }

    @Test
    void completeAuthorization_サイトへ送れなければ接続失敗として返し_トークンを含めない() {
        String state = started();
        threadsAnswers();
        fx.world.letsblogSnsFails("config-set", "threads", "エージェントへの接続に失敗しました");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "the-code"));

        assertTrue(e.getMessage().contains("接続失敗"));
        assertFalse(e.getMessage().contains("LONG-TOKEN"));
    }

    @Test
    void completeAuthorization_サイトが接続済みと答えなければ接続失敗として返す() {
        String state = started();
        threadsAnswers();
        fx.world.letsblogSns("config-set", "threads", "{\"sns\":\"threads\",\"status\":\"要再接続\"}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "the-code"));

        assertTrue(e.getMessage().contains("接続失敗"));
    }

    @Test
    void completeAuthorization_サイトの出力を解釈できなければ接続失敗として返す() {
        String state = started();
        threadsAnswers();
        fx.world.letsblogSns("config-set", "threads", "garbage");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "the-code"));

        assertTrue(e.getMessage().contains("接続失敗"));
    }

    // ---- disconnect ----

    @Test
    void disconnect_本番サイトのプラグインからThreadsの設定だけを消す() {
        fx.connectableSite();
        fx.world.letsblogSns("config-clear", "threads", "{}");

        service.disconnect(7L);

        assertEquals(1, fx.world.snsRequests("config-clear").size());
        assertEquals("threads", fx.world.snsRequests("config-clear").get(0).path("sns").asText());
    }

    @Test
    void disconnect_接続できないサイトでは理由つきで拒否する() {
        fx.projectWithProductionSite(null);

        assertThrows(IllegalStateException.class, () -> service.disconnect(7L));
        assertTrue(fx.world.snsRequests("config-clear").isEmpty());
    }

    @Test
    void disconnect_サイトの設定を消せなければ失敗として返す() {
        fx.connectableSite();
        fx.world.letsblogSnsFails("config-clear", "threads", "届かない");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.disconnect(7L));

        assertTrue(e.getMessage().contains("届かない"));
    }

    @Test
    void 公開コンストラクタはシステム時計で組み立てられる() {
        SnsThreadsService real = new SnsThreadsService(fx.snsXService, fx.world.siteService,
                new ThreadsApiClient(fx.world.http.baseUrl(), "https://threads.example/authorize"), fx.store,
                fx.world.currentActorService, fx.world.objectMapper);
        fx.projectWithProductionSite(null);

        assertThrows(IllegalStateException.class, () -> real.disconnect(7L));
    }
}
