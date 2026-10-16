package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.cms.LetsblogPluginStatus.State;
import com.letsblog.project.domain.Project;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.testsupport.FakeHttpServer;
import com.letsblog.project.testsupport.ProjectWorld;
import com.letsblog.project.client.XApiClient;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * プロジェクトの X アカウント接続(issue #1574)。本番サイトのプラグインへは wp-cli(`sns config set` を標準入力の
 * JSON で)だけで送り、アプリはトークンを保存しない・API で返さない。本番サイトが無い・プラグインが導入済みでない
 * ときは接続できず理由を返す。届かないときの状態と履歴は「取得できない」として返し、例外にしない。
 *
 * <p>issue #1817: モックはDBのリポジトリだけ。SiteService・XApiClient・XAuthorizationStore・CurrentActorService
 * は実物で、X の API と publishing-service(CMSブリッジ)の代わりにフェイクHTTPサーバーが応答する。
 */
class SnsXServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final String CMS = "/api/internal/project/cms/";
    private static final String CALLBACK = "https://l/cb";

    private final ProjectWorld world = new ProjectWorld();
    private final ProjectRepository projectRepository = Mockito.mock(ProjectRepository.class);
    private final XAuthorizationStore store = new XAuthorizationStore();
    private SnsXService service;

    @BeforeEach
    void setUp() {
        XApiClient xApiClient = new XApiClient(world.http.baseUrl(), "https://x.example/authorize");
        service = new SnsXService(projectRepository, world.siteRepository, world.siteService, xApiClient, store,
                world.currentActorService, world.objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void close() {
        world.close();
    }

    private void projectWithProductionSite(Long siteId) {
        Project project = new Project();
        project.setId(7L);
        project.setProductionSiteId(siteId);
        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
    }

    private void connectableSite() {
        projectWithProductionSite(3L);
        world.addSite(3L, "本番サイト");
        world.pluginState(State.INSTALLED);
    }

    private static String stateOf(String authorizeUrl) {
        Map<String, String> query = Stream.of(URI.create(authorizeUrl).getRawQuery().split("&"))
                .map(kv -> kv.split("=", 2))
                .collect(Collectors.toMap(kv -> kv[0], kv -> URLDecoder.decode(kv[1], StandardCharsets.UTF_8)));
        return query.get("state");
    }

    // ---- view ----

    @Test
    void view_未登録のプロジェクトはNotFound() {
        when(projectRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> service.view(7L));
    }

    @Test
    void view_本番サイトが無ければ接続できず理由を返す() {
        projectWithProductionSite(null);

        XConnectionView view = service.view(7L);

        assertFalse(view.connectable());
        assertTrue(view.reason().contains("本番サイト"));
        assertNull(view.status());
        assertNull(view.log());
        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    void view_本番サイトの行が存在しなくても未設定として扱う() {
        projectWithProductionSite(3L);

        XConnectionView view = service.view(7L);

        assertFalse(view.connectable());
        assertTrue(view.reason().contains("本番サイト"));
        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    void view_プラグインが未導入なら接続できず理由を返しSNSには問い合わせない() {
        projectWithProductionSite(3L);
        world.addSite(3L, "本番サイト");
        world.pluginState(State.NOT_INSTALLED);

        XConnectionView view = service.view(7L);

        assertFalse(view.connectable());
        assertEquals("本番サイト", view.siteName());
        assertTrue(view.reason().contains("未導入"));
        assertNull(view.status());
        assertNull(view.log());
        assertTrue(world.http.requests("POST", CMS + "letsblog-sns").isEmpty());
    }

    @Test
    void view_プラグインが要更新なら接続できず理由を返す() {
        projectWithProductionSite(3L);
        world.addSite(3L, "本番サイト");
        world.pluginState(State.NEEDS_UPDATE);

        XConnectionView view = service.view(7L);

        assertFalse(view.connectable());
        assertTrue(view.reason().contains("要更新"));
    }

    @Test
    void view_プラグインの状態を取れなければ接続状態も履歴も取得できないとして返し例外にしない() {
        projectWithProductionSite(3L);
        world.addSite(3L, "本番サイト");
        world.cmsBridgeDown("接続失敗");

        XConnectionView view = service.view(7L);

        assertFalse(view.connectable());
        assertTrue(view.reason().contains("届かない"));
        assertFalse(view.status().available());
        assertNotNull(view.status().error());
        assertFalse(view.log().available());
        assertNotNull(view.log().error());
    }

    @Test
    void view_接続済みなら状態とアカウントと履歴を新しい順に返す() {
        connectableSite();
        world.letsblogSns("status", "x", "{\"x\":{\"status\":\"接続済み\",\"account_name\":\"lets_blog\"}}");
        world.letsblogSns("log", "x",
                "[{\"sns\":\"x\",\"kind\":\"test\",\"post_id\":null,\"at\":\"2026-10-04T00:00:00+00:00\",\"success\":true,\"error\":null},"
                        + "{\"sns\":\"x\",\"kind\":\"publish\",\"post_id\":5,\"at\":\"2026-10-04T01:00:00+00:00\",\"success\":false,\"error\":\"X の投稿に失敗しました\"}]");

        XConnectionView view = service.view(7L);

        assertTrue(view.connectable());
        assertNull(view.reason());
        assertEquals("本番サイト", view.siteName());
        assertTrue(view.status().available());
        assertEquals(XConnectionView.State.CONNECTED, view.status().state());
        assertEquals("lets_blog", view.status().accountName());
        assertTrue(view.log().available());
        assertEquals(2, view.log().entries().size());
        assertEquals("publish", view.log().entries().get(0).kind());
        assertFalse(view.log().entries().get(0).success());
        assertEquals("X の投稿に失敗しました", view.log().entries().get(0).error());
        assertEquals("test", view.log().entries().get(1).kind());
        assertTrue(view.log().entries().get(1).success());
        assertNull(view.log().entries().get(1).error());
        assertEquals("2026-10-04T00:00:00+00:00", view.log().entries().get(1).at());
    }

    @Test
    void view_未設定と要再接続の状態を区別する() {
        connectableSite();
        world.letsblogSns("status", "x",
                "{\"x\":{\"status\":\"未設定\",\"account_name\":null}}",
                "{\"x\":{\"status\":\"要再接続\",\"account_name\":\"a\"}}");
        world.letsblogSns("log", "x", "[]");

        XConnectionView unset = service.view(7L);
        assertEquals(XConnectionView.State.UNSET, unset.status().state());
        assertNull(unset.status().accountName());
        XConnectionView reconnect = service.view(7L);
        assertEquals(XConnectionView.State.RECONNECT, reconnect.status().state());
        assertEquals("a", reconnect.status().accountName());
    }

    @Test
    void view_履歴はXのものだけを新しい順に20件までに絞る() {
        connectableSite();
        world.letsblogSns("status", "x", "{\"x\":{\"status\":\"接続済み\",\"account_name\":null}}");
        StringBuilder log = new StringBuilder("[{\"sns\":\"bluesky\",\"kind\":\"test\",\"at\":\"a\",\"success\":true,\"error\":null}");
        for (int i = 0; i < 25; i++) {
            log.append(",{\"sns\":\"x\",\"kind\":\"k").append(i).append("\",\"at\":\"a\",\"success\":true,\"error\":null}");
        }
        log.append("]");
        world.letsblogSns("log", "x", log.toString());

        XConnectionView view = service.view(7L);

        assertEquals(20, view.log().entries().size());
        assertEquals("k24", view.log().entries().get(0).kind());
        assertEquals("k5", view.log().entries().get(19).kind());
    }

    @Test
    void view_状態の取得だけ失敗しても履歴は返す() {
        connectableSite();
        world.letsblogSnsFails("status", "x", "接続失敗");
        world.letsblogSns("log", "x", "[]");

        XConnectionView view = service.view(7L);

        assertTrue(view.connectable());
        assertFalse(view.status().available());
        assertTrue(view.status().error().contains("接続失敗"));
        assertTrue(view.log().available());
    }

    @Test
    void view_履歴の取得だけ失敗しても状態は返す() {
        connectableSite();
        world.letsblogSns("status", "x", "{\"x\":{\"status\":\"接続済み\",\"account_name\":\"a\"}}");
        world.letsblogSnsFails("log", "x", "届かない");

        XConnectionView view = service.view(7L);

        assertTrue(view.status().available());
        assertFalse(view.log().available());
        assertTrue(view.log().error().contains("届かない"));
    }

    @Test
    void view_状態の出力を解釈できなければ取得できないとして返す() {
        connectableSite();
        world.letsblogSns("status", "x", "garbage", "{\"bluesky\":{\"status\":\"接続済み\"}}", "{\"x\":{\"status\":\"謎\"}}");
        world.letsblogSns("log", "x", "{\"not\":\"an array\"}");

        XConnectionView first = service.view(7L);
        assertFalse(first.status().available());
        assertFalse(first.log().available());
        assertFalse(service.view(7L).status().available());
        assertFalse(service.view(7L).status().available());
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
        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    void startAuthorization_本番サイトが無ければ理由つきで拒否し認可を始めない() {
        projectWithProductionSite(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.startAuthorization(7L, "cid", "csecret", CALLBACK));

        assertTrue(e.getMessage().contains("本番サイト"));
        assertEquals(0, store.size());
    }

    @Test
    void startAuthorization_プラグインが導入済みでなければ理由つきで拒否する() {
        projectWithProductionSite(3L);
        world.addSite(3L, "本番サイト");
        world.pluginState(State.NOT_INSTALLED);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.startAuthorization(7L, "cid", "csecret", CALLBACK));

        assertTrue(e.getMessage().contains("未導入"));
        assertEquals(0, store.size());
    }

    @Test
    void startAuthorization_プラグインの状態を取れなければ拒否する() {
        projectWithProductionSite(3L);
        world.addSite(3L, "本番サイト");
        world.cmsBridgeDown("接続失敗");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.startAuthorization(7L, "cid", "csecret", CALLBACK));

        assertTrue(e.getMessage().contains("届かない"));
    }

    @Test
    void startAuthorization_認可を保存してXの認可URLを返す() {
        connectableSite();
        world.signedInAs("sub-1");

        String url = service.startAuthorization(7L, "cid", "csecret", CALLBACK);

        assertTrue(url.startsWith("https://x.example/authorize?"), url);
        String state = stateOf(url);
        assertTrue(state.startsWith("7."));
        assertTrue(url.contains("client_id=cid"));
        XAuthorizationStore.Pending pending = store.take(state).orElseThrow();
        assertEquals(7L, pending.projectId());
        assertEquals(3L, pending.siteId());
        assertEquals("sub-1", pending.actorSub());
        assertEquals("cid", pending.clientId());
        assertEquals("csecret", pending.clientSecret());
        assertEquals(CALLBACK, pending.redirectUri());
        assertTrue(url.contains("code_challenge=" + pending.codeChallenge()));
    }

    // ---- completeAuthorization ----

    /** 認可を始めて、X の認可画面へ渡した state を返す(actor は sub-1)。 */
    private String started() {
        connectableSite();
        world.signedInAs("sub-1");
        return stateOf(service.startAuthorization(7L, "cid", "csecret", CALLBACK));
    }

    private void xAnswers(String accessToken, String username) {
        world.http.json("POST", "/2/oauth2/token", "{\"access_token\":\"" + accessToken
                + "\",\"refresh_token\":\"REFRESH-TOKEN\",\"expires_in\":7200}");
        world.http.json("GET", "/2/users/me", "{\"data\":{\"username\":\"" + username + "\"}}");
    }

    private boolean sentToSite() {
        return !world.snsRequests("config-set").isEmpty();
    }

    @Test
    void completeAuthorization_トークンを本番サイトへ標準入力で送りアカウント名だけを返す() throws Exception {
        String state = started();
        xAnswers("ACCESS-TOKEN", "lets_blog");
        world.letsblogSns("config-set", "x", "{\"sns\":\"x\",\"status\":\"接続済み\"}");

        XConnectResult result = service.completeAuthorization(7L, state, "the-code");

        assertEquals(7L, result.projectId());
        assertEquals("lets_blog", result.accountName());
        FakeHttpServer.Request token = world.http.requests("POST", "/2/oauth2/token").get(0);
        assertTrue(token.header("Authorization").startsWith("Basic "));
        assertTrue(token.bodyText().contains("code=the-code"), token.bodyText());
        assertEquals("Bearer ACCESS-TOKEN", world.http.requests("GET", "/2/users/me").get(0).header("Authorization"));
        JsonNode command = world.snsRequests("config-set").get(0);
        assertEquals("x", command.path("sns").asText());
        JsonNode sent = world.objectMapper.readTree(command.path("stdin").asText());
        assertEquals("x", sent.path("sns").asText());
        assertEquals("cid", sent.path("client_id").asText());
        assertEquals("csecret", sent.path("client_secret").asText());
        assertEquals("ACCESS-TOKEN", sent.path("access_token").asText());
        assertEquals("REFRESH-TOKEN", sent.path("refresh_token").asText());
        assertEquals(NOW.getEpochSecond() + 7200L, sent.path("expires_at").asLong());
        assertEquals("lets_blog", sent.path("account_name").asText());
        // 応答のオブジェクトにトークンもクライアントの秘密も載らない。
        String json = world.objectMapper.writeValueAsString(result);
        assertFalse(json.contains("ACCESS-TOKEN"));
        assertFalse(json.contains("REFRESH-TOKEN"));
        assertFalse(json.contains("csecret"));
    }

    @Test
    void completeAuthorization_知らないstateや期限切れは拒否する() {
        world.signedInAs("sub-1");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("もう一度"));
        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    void completeAuthorization_別のプロジェクトのstateは拒否する() {
        String state = started();
        int before = world.http.requests().size();

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(8L, state, "code"));
        assertEquals(before, world.http.requests().size(), "X にも本番サイトにも問い合わせない");
    }

    @Test
    void completeAuthorization_認可を始めた本人以外は拒否する() {
        String state = started();
        world.signedInAs("someone-else");

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(7L, state, "code"));
        assertTrue(world.http.requests("POST", "/2/oauth2/token").isEmpty());
    }

    @Test
    void completeAuthorization_認可を始めた時点で本人が分からなければ誰にも完了させない() {
        connectableSite();
        world.signedInAs(null);
        String state = stateOf(service.startAuthorization(7L, "cid", "csecret", CALLBACK));

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(7L, state, "code"));
        assertTrue(world.http.requests("POST", "/2/oauth2/token").isEmpty());
    }

    @Test
    void completeAuthorization_その間に本番サイトが無くなっていたら拒否する() {
        String state = started();
        projectWithProductionSite(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "code"));

        assertTrue(e.getMessage().contains("本番サイト"));
        assertTrue(world.http.requests("POST", "/2/oauth2/token").isEmpty());
    }

    @Test
    void completeAuthorization_トークン交換が失敗したら接続失敗として返す() {
        String state = started();
        world.http.status("POST", "/2/oauth2/token", 400, "{\"error\":\"invalid_grant\"}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "code"));

        assertTrue(e.getMessage().contains("HTTP 400"));
        assertFalse(sentToSite());
    }

    @Test
    void completeAuthorization_アカウント名を取れなければ接続失敗として返し送らない() {
        String state = started();
        xAnswers("AT", "ignored");
        world.http.status("GET", "/2/users/me", 401, "{\"title\":\"Unauthorized\"}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "code"));

        assertTrue(e.getMessage().contains("HTTP 401"));
        assertFalse(sentToSite());
    }

    @Test
    void completeAuthorization_サイトへ送れなければ接続失敗として返し_トークンを含めない() {
        String state = started();
        xAnswers("ACCESS-TOKEN", "lets_blog");
        world.letsblogSnsFails("config-set", "x", "エージェントへの接続に失敗しました");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "code"));

        assertTrue(e.getMessage().contains("接続失敗"));
        assertTrue(e.getMessage().contains("エージェントへの接続に失敗しました"));
        assertFalse(e.getMessage().contains("ACCESS-TOKEN"));
        assertFalse(e.getMessage().contains("REFRESH-TOKEN"));
    }

    @Test
    void completeAuthorization_サイトが接続済みと答えなければ接続失敗として返す() {
        String state = started();
        xAnswers("AT", "lets_blog");
        world.letsblogSns("config-set", "x", "{\"sns\":\"x\",\"status\":\"要再接続\"}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "code"));

        assertTrue(e.getMessage().contains("接続失敗"));
    }

    @Test
    void completeAuthorization_サイトの出力を解釈できなければ接続失敗として返す() {
        String state = started();
        xAnswers("AT", "lets_blog");
        world.letsblogSns("config-set", "x", "garbage");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "code"));

        assertTrue(e.getMessage().contains("接続失敗"));
    }

    // ---- test ----

    @Test
    void test_接続できないサイトでは理由つきで拒否する() {
        projectWithProductionSite(null);

        assertThrows(IllegalStateException.class, () -> service.test(7L));
    }

    @Test
    void test_テスト投稿が成功したら成功を返す() {
        connectableSite();
        world.letsblogSns("test", "x", "{\"sns\":\"x\",\"kind\":\"test\",\"success\":true}");

        XTestResult result = service.test(7L);

        assertTrue(result.success());
        assertNull(result.error());
    }

    @Test
    void test_テスト投稿が失敗したら理由を返す() {
        connectableSite();
        world.letsblogSnsFails("test", "x", "X の投稿に失敗しました(HTTP 403)");

        XTestResult result = service.test(7L);

        assertFalse(result.success());
        assertTrue(result.error().contains("HTTP 403"));
    }

    // ---- onProductionSiteChanged ----

    @Test
    void onProductionSiteChanged_旧サイトの設定を消す() {
        world.addSite(5L, "旧本番");
        world.letsblogSns("config-clear", null, "{}");

        service.onProductionSiteChanged(5L, 10L);

        assertEquals(1, world.snsRequests("config-clear").size());
    }

    @Test
    void onProductionSiteChanged_本番を外したときも旧サイトを消す() {
        world.addSite(5L, "旧本番");
        world.letsblogSns("config-clear", null, "{}");

        service.onProductionSiteChanged(5L, null);

        assertEquals(1, world.snsRequests("config-clear").size());
    }

    @Test
    void onProductionSiteChanged_旧サイトが無い_または同じなら何もしない() {
        service.onProductionSiteChanged(null, 10L);
        service.onProductionSiteChanged(5L, 5L);

        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    void onProductionSiteChanged_消せなくても例外にしない() {
        world.addSite(5L, "旧本番");
        world.letsblogSnsFails("config-clear", null, "届かない");

        service.onProductionSiteChanged(5L, 10L);

        assertEquals(1, world.snsRequests("config-clear").size());
    }

    // ---- issue #1579: 状態・履歴・テスト投稿・接続可否の判定は SNS を指定して Threads でも使う ----

    @Test
    void view_SNSを指定するとその状態と履歴だけを返す() {
        connectableSite();
        world.letsblogSns("status", "threads", "{\"x\":{\"status\":\"未設定\",\"account_name\":null},"
                + "\"threads\":{\"status\":\"接続済み\",\"account_name\":\"th_user\"}}");
        world.letsblogSns("log", "threads",
                "[{\"sns\":\"x\",\"kind\":\"test\",\"at\":\"a\",\"success\":true,\"error\":null},"
                        + "{\"sns\":\"threads\",\"kind\":\"publish\",\"at\":\"b\",\"success\":false,\"error\":\"期限が切れています\"}]");

        XConnectionView view = service.view(7L, "threads");

        assertEquals(XConnectionView.State.CONNECTED, view.status().state());
        assertEquals("th_user", view.status().accountName());
        assertEquals(1, view.log().entries().size());
        assertEquals("publish", view.log().entries().get(0).kind());
        assertEquals("期限が切れています", view.log().entries().get(0).error());
    }

    @Test
    void test_SNSを指定してテスト投稿する() {
        connectableSite();
        world.letsblogSns("test", "threads", "{}");

        assertTrue(service.test(7L, "threads").success());
    }

    @Test
    void requireConnectableSiteId_接続できるサイトのIDを返す() {
        connectableSite();

        assertEquals(3L, service.requireConnectableSiteId(7L));
    }

    @Test
    void requireConnectableSiteId_本番サイトが無ければ理由つきで例外() {
        projectWithProductionSite(null);

        assertTrue(assertThrows(IllegalStateException.class, () -> service.requireConnectableSiteId(7L))
                .getMessage().contains("本番サイト"));
    }

    @Test
    void 公開コンストラクタはシステム時計で組み立てられる() {
        XApiClient xApiClient = new XApiClient(world.http.baseUrl(), "https://x.example/authorize");
        SnsXService real = new SnsXService(projectRepository, world.siteRepository, world.siteService, xApiClient,
                new XAuthorizationStore(), world.currentActorService, world.objectMapper);
        projectWithProductionSite(null);

        assertFalse(real.view(7L).connectable());
    }
}
