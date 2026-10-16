package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.client.FacebookApiClient;
import com.letsblog.project.dto.FacebookPagesView;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import com.letsblog.project.testsupport.FakeHttpServer;
import com.letsblog.project.testsupport.SnsFixture;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * プロジェクトの Facebook ページ接続(issue #1581。Epic #1572)。認可のあと管理しているページを選び、選んだページの
 * トークンだけを本番サイトのプラグインへ送る。アプリは保存しない・API で返さない。
 *
 * <p>issue #1817: SnsXService・SiteService・FacebookApiClient・XAuthorizationStore・FacebookPageSelectionStore・
 * CurrentActorService は実物。Facebook の API と CMS ブリッジの代わりにフェイクHTTPサーバーが応答する。
 */
class SnsFacebookServiceTest {

    private static final String CALLBACK = "https://l/cb";
    private static final String PAGES_JSON = "{\"data\":[{\"id\":\"100\",\"name\":\"公式ページ\",\"access_token\":\"PAGE-TOKEN-100\"},"
            + "{\"id\":\"200\",\"name\":\"別のページ\",\"access_token\":\"PAGE-TOKEN-200\"}]}";

    private final SnsFixture fx = new SnsFixture();
    private final FacebookPageSelectionStore selectionStore = new FacebookPageSelectionStore();
    private SnsFacebookService service;

    @BeforeEach
    void setUp() {
        FacebookApiClient facebookApiClient =
                new FacebookApiClient(fx.world.http.baseUrl(), "https://facebook.example/dialog/oauth");
        service = new SnsFacebookService(fx.snsXService, fx.world.siteService, facebookApiClient, fx.store,
                selectionStore, fx.world.currentActorService, fx.world.objectMapper);
    }

    @AfterEach
    void close() {
        fx.close();
    }

    private void facebookAnswers(String pagesJson) {
        fx.world.http.on("GET", "/oauth/access_token", req -> req.query().contains("fb_exchange_token")
                ? FakeHttpServer.Response.ok("{\"access_token\":\"USER-LONG\"}")
                : FakeHttpServer.Response.ok("{\"access_token\":\"USER-SHORT\"}"));
        fx.world.http.json("GET", "/me/accounts", pagesJson);
    }

    private String started() {
        fx.connectableSite();
        fx.world.signedInAs("sub-1");
        return SnsFixture.queryParam(service.startAuthorization(7L, "app-id", "app-secret", CALLBACK), "state");
    }

    /** 認可を終えてページを選べる状態にし、そのstateを返す。 */
    private String authorizedWithPages() {
        String state = started();
        facebookAnswers(PAGES_JSON);
        service.completeAuthorization(7L, state, "the-code");
        return state;
    }

    // ---- view / test ----

    @Test
    void view_Facebookの接続状態を返す() {
        fx.connectableSite();
        fx.world.letsblogSns("status", "facebook", "{\"facebook\":{\"status\":\"接続済み\",\"account_name\":\"fb\"}}");
        fx.world.letsblogSns("log", "facebook", "[]");

        XConnectionView view = service.view(7L);

        assertEquals("fb", view.status().accountName());
    }

    @Test
    void test_Facebookへのテスト投稿を依頼する() {
        fx.connectableSite();
        fx.world.letsblogSns("test", "facebook", "{}");

        XTestResult result = service.test(7L);

        assertTrue(result.success());
        assertEquals("facebook", fx.world.snsRequests("test").get(0).path("sns").asText());
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
        assertEquals(0, fx.store.size());
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
    void startAuthorization_認可を保存してFacebookの認可URLを返す() {
        fx.connectableSite();
        fx.world.signedInAs("sub-1");

        String url = service.startAuthorization(7L, "app-id", "app-secret", CALLBACK);

        assertTrue(url.startsWith("https://facebook.example/dialog/oauth?"), url);
        assertTrue(url.contains("client_id=app-id"));
        XAuthorizationStore.Pending pending = fx.store.take(SnsFixture.queryParam(url, "state")).orElseThrow();
        assertEquals(7L, pending.projectId());
        assertEquals(3L, pending.siteId());
        assertEquals("sub-1", pending.actorSub());
    }

    // ---- completeAuthorization ----

    @Test
    void completeAuthorization_管理しているページを保存して選べる一覧だけを返す() throws Exception {
        String state = started();
        facebookAnswers(PAGES_JSON);

        FacebookPagesView result = service.completeAuthorization(7L, state, "the-code");

        assertEquals(7L, result.projectId());
        assertEquals(2, result.pages().size());
        assertEquals("100", result.pages().get(0).id());
        assertEquals("公式ページ", result.pages().get(0).name());
        assertTrue(selectionStore.find(state).isPresent());
        assertEquals(3L, selectionStore.find(state).get().siteId());
        // ページを選ぶまでは、本番サイトへは何も送らない。
        assertTrue(fx.world.snsRequests("config-set").isEmpty());
        assertEquals("Bearer USER-LONG", fx.world.http.requests("GET", "/me/accounts").get(0).header("Authorization"));
        String json = fx.world.objectMapper.writeValueAsString(result);
        assertFalse(json.contains("PAGE-TOKEN"));
        assertFalse(json.contains("USER-LONG"));
        assertFalse(json.contains("app-secret"));
    }

    @Test
    void completeAuthorization_管理しているページが無ければ理由つきで拒否し保存しない() {
        String state = started();
        facebookAnswers("{\"data\":[]}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "the-code"));

        assertTrue(e.getMessage().contains("ページ"));
        assertEquals(0, selectionStore.size());
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

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(8L, state, "code"));
        assertTrue(fx.world.http.requests("GET", "/oauth/access_token").isEmpty());
    }

    @Test
    void completeAuthorization_認可を始めた本人以外は拒否する() {
        String state = started();
        fx.world.signedInAs("someone-else");

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(7L, state, "code"));
        assertTrue(fx.world.http.requests("GET", "/oauth/access_token").isEmpty());
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

        assertThrows(IllegalStateException.class, () -> service.completeAuthorization(7L, state, "code"));
        assertTrue(fx.world.http.requests("GET", "/oauth/access_token").isEmpty());
    }

    @Test
    void completeAuthorization_Facebookとの通信が失敗したら接続失敗として返し_秘密を含めない() {
        String state = started();
        fx.world.http.status("GET", "/oauth/access_token", 400, "{\"error\":{\"message\":\"bad code\"}}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "code"));

        assertTrue(e.getMessage().contains("HTTP 400"));
        assertFalse(e.getMessage().contains("app-secret"));
        assertTrue(fx.world.snsRequests("config-set").isEmpty());
    }

    // ---- pages ----

    @Test
    void pages_本人が自分の選択肢を一覧できる() {
        String state = authorizedWithPages();

        FacebookPagesView view = service.pages(7L, state);

        assertEquals(List.of("100", "200"), view.pages().stream().map(p -> p.id()).toList());
    }

    @Test
    void pages_知らないstate_別プロジェクト_別の人は拒否する() {
        String state = authorizedWithPages();
        assertThrows(IllegalArgumentException.class, () -> service.pages(7L, "nope"));
        assertThrows(IllegalArgumentException.class, () -> service.pages(8L, state));

        fx.world.signedInAs("someone-else");
        assertThrows(IllegalArgumentException.class, () -> service.pages(7L, state));
    }

    @Test
    void pages_認可を始めた本人が不明な選択は誰にも見せない() {
        selectionStore.save("7.abc", 7L, 3L, null, List.of(new FacebookApiClient.Page("100", "公式ページ", "T")));
        fx.world.signedInAs(null);

        assertThrows(IllegalArgumentException.class, () -> service.pages(7L, "7.abc"));
    }

    // ---- selectPage ----

    @Test
    void selectPage_選んだページのトークンだけを本番サイトへ標準入力で送りページ名を返す() throws Exception {
        String state = authorizedWithPages();
        fx.world.letsblogSns("config-set", "facebook", "{\"sns\":\"facebook\",\"status\":\"接続済み\"}");

        XConnectResult result = service.selectPage(7L, state, "100");

        assertEquals(7L, result.projectId());
        assertEquals("公式ページ", result.accountName());
        String stdin = fx.world.snsRequests("config-set").get(0).path("stdin").asText();
        JsonNode sent = fx.world.objectMapper.readTree(stdin);
        assertEquals("facebook", sent.path("sns").asText());
        assertEquals("PAGE-TOKEN-100", sent.path("access_token").asText());
        assertEquals("100", sent.path("page_id").asText());
        assertEquals("公式ページ", sent.path("account_name").asText());
        // 別のページのトークンやアプリの秘密・ユーザーのトークンは送らない。
        assertFalse(stdin.contains("PAGE-TOKEN-200"));
        assertFalse(stdin.contains("app-secret"));
        assertFalse(fx.world.objectMapper.writeValueAsString(result).contains("PAGE-TOKEN"));
        assertTrue(selectionStore.find(state).isEmpty(), "送れたら選択肢は消える");
    }

    @Test
    void selectPage_選択肢に無いページは拒否し何も送らない() {
        String state = authorizedWithPages();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.selectPage(7L, state, "999"));

        assertTrue(e.getMessage().contains("ページ"));
        assertTrue(fx.world.snsRequests("config-set").isEmpty());
        assertTrue(selectionStore.find(state).isPresent());
    }

    @Test
    void selectPage_知らないstate_別プロジェクト_別の人は拒否する() {
        String state = authorizedWithPages();
        assertThrows(IllegalArgumentException.class, () -> service.selectPage(7L, "nope", "100"));
        assertThrows(IllegalArgumentException.class, () -> service.selectPage(8L, state, "100"));

        fx.world.signedInAs("someone-else");
        assertThrows(IllegalArgumentException.class, () -> service.selectPage(7L, state, "100"));
        assertTrue(fx.world.snsRequests("config-set").isEmpty());
    }

    @Test
    void selectPage_サイトへ送れなければ接続失敗として返し_選択肢は残す() {
        String state = authorizedWithPages();
        fx.world.letsblogSnsFails("config-set", "facebook", "エージェントへの接続に失敗しました");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.selectPage(7L, state, "100"));

        assertTrue(e.getMessage().contains("接続失敗"));
        assertFalse(e.getMessage().contains("PAGE-TOKEN"));
        assertTrue(selectionStore.find(state).isPresent());
    }

    @Test
    void selectPage_サイトが接続済みと答えなければ接続失敗として返す() {
        String state = authorizedWithPages();
        fx.world.letsblogSns("config-set", "facebook", "{\"sns\":\"facebook\",\"status\":\"要再接続\"}");

        assertTrue(assertThrows(IllegalStateException.class, () -> service.selectPage(7L, state, "100"))
                .getMessage().contains("接続失敗"));
    }

    @Test
    void selectPage_サイトの出力を解釈できなければ接続失敗として返す() {
        String state = authorizedWithPages();
        fx.world.letsblogSns("config-set", "facebook", "garbage");

        assertTrue(assertThrows(IllegalStateException.class, () -> service.selectPage(7L, state, "100"))
                .getMessage().contains("接続失敗"));
    }

    // ---- disconnect ----

    @Test
    void disconnect_本番サイトのプラグインからFacebookの設定だけを消す() {
        fx.connectableSite();
        fx.world.letsblogSns("config-clear", "facebook", "{}");

        service.disconnect(7L);

        assertEquals("facebook", fx.world.snsRequests("config-clear").get(0).path("sns").asText());
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
        fx.world.letsblogSnsFails("config-clear", "facebook", "届かない");

        assertTrue(assertThrows(IllegalStateException.class, () -> service.disconnect(7L)).getMessage().contains("届かない"));
    }
}
