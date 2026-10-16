package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.client.HatenaApiClient;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import com.letsblog.project.testsupport.FakeHttpServer;
import com.letsblog.project.testsupport.SnsFixture;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * プロジェクトのはてなブックマーク接続(issue #1580。Epic #1572)。OAuth 1.0a で、アクセストークンは失効しない。
 * 本番サイトのプラグインへは wp-cli(`sns config set` を標準入力の JSON で)だけで送り、アプリは保存しない・API で返さない。
 *
 * <p>issue #1817: SnsXService・SiteService・HatenaApiClient・HatenaAuthorizationStore・CurrentActorService は実物。
 * はてなの API と CMS ブリッジの代わりにフェイクHTTPサーバーが応答する。
 */
class SnsHatenaServiceTest {

    private static final String CALLBACK = "https://l/cb";
    private static final Pattern OAUTH_CALLBACK = Pattern.compile("oauth_callback=\"([^\"]*)\"");

    private final SnsFixture fx = new SnsFixture();
    private final HatenaAuthorizationStore store = new HatenaAuthorizationStore();
    private SnsHatenaService service;

    @BeforeEach
    void setUp() {
        String base = fx.world.http.baseUrl();
        HatenaApiClient hatenaApiClient = new HatenaApiClient(
                base + "/oauth/initiate", base + "/oauth/token", "https://hatena.example/authorize", base + "/profile");
        service = new SnsHatenaService(fx.snsXService, fx.world.siteService, hatenaApiClient, store,
                fx.world.currentActorService, fx.world.objectMapper);
    }

    @AfterEach
    void close() {
        fx.close();
    }

    private void hatenaIssuesRequestToken() {
        fx.world.http.on("POST", "/oauth/initiate", FakeHttpServer.Response.bytes(200, "application/x-www-form-urlencoded",
                "oauth_token=RT&oauth_token_secret=RS&oauth_callback_confirmed=true".getBytes(StandardCharsets.UTF_8)));
    }

    private void hatenaIssuesAccessToken() {
        fx.world.http.on("POST", "/oauth/token", FakeHttpServer.Response.bytes(200, "application/x-www-form-urlencoded",
                "oauth_token=ACCESS-TOKEN&oauth_token_secret=ACCESS-SECRET".getBytes(StandardCharsets.UTF_8)));
        fx.world.http.json("GET", "/profile", "{\"display_name\":\"Let's Blog\",\"url_name\":\"lets_blog\"}");
    }

    /** リクエストトークンの要求に載せたcallback URL(OAuthのAuthorizationヘッダーの oauth_callback を二重に戻す)。 */
    private String callbackSent() {
        String header = fx.world.http.requests("POST", "/oauth/initiate").get(0).header("Authorization");
        Matcher m = OAUTH_CALLBACK.matcher(header);
        assertTrue(m.find(), header);
        return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    }

    private String started() {
        fx.connectableSite();
        fx.world.signedInAs("sub-1");
        hatenaIssuesRequestToken();
        service.startAuthorization(7L, "consumer-key", "consumer-secret", CALLBACK);
        String callback = callbackSent();
        return URLDecoder.decode(callback.substring(callback.indexOf("state=") + "state=".length()), StandardCharsets.UTF_8);
    }

    private boolean sentToSite() {
        return !fx.world.snsRequests("config-set").isEmpty();
    }

    // ---- view / test ----

    @Test
    void view_はてなブックマークの接続状態を返す() {
        fx.connectableSite();
        fx.world.letsblogSns("status", "hatena", "{\"hatena\":{\"status\":\"接続済み\",\"account_name\":\"hb\"}}");
        fx.world.letsblogSns("log", "hatena", "[]");

        XConnectionView view = service.view(7L);

        assertTrue(view.connectable());
        assertEquals("hb", view.status().accountName());
    }

    @Test
    void test_はてなブックマークへのテスト投稿を依頼する() {
        fx.connectableSite();
        fx.world.letsblogSns("test", "hatena", "{}");

        XTestResult result = service.test(7L);

        assertTrue(result.success());
        assertEquals("hatena", fx.world.snsRequests("test").get(0).path("sns").asText());
    }

    // ---- startAuthorization ----

    @Test
    void startAuthorization_consumerの情報かリダイレクト先が空なら拒否する() {
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, " ", "s", CALLBACK));
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, "c", "", CALLBACK));
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, "c", "s", null));
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, null, "s", CALLBACK));
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, "c", null, CALLBACK));
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, "c", "s", " "));
        assertEquals(0, store.size());
        assertTrue(fx.world.http.requests().isEmpty());
    }

    @Test
    void startAuthorization_接続できないサイトでは理由つきで拒否し認可を始めない() {
        fx.projectWithProductionSite(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.startAuthorization(7L, "consumer-key", "consumer-secret", CALLBACK));

        assertTrue(e.getMessage().contains("本番サイト"));
        assertEquals(0, store.size());
        assertTrue(fx.world.http.requests("POST", "/oauth/initiate").isEmpty());
    }

    @Test
    void startAuthorization_stateをcallbackに載せてリクエストトークンを取り_認可URLを返す() {
        fx.connectableSite();
        fx.world.signedInAs("sub-1");
        hatenaIssuesRequestToken();

        String url = service.startAuthorization(7L, "consumer-key", "consumer-secret", CALLBACK);

        assertEquals("https://hatena.example/authorize?oauth_token=RT", url);
        String callback = callbackSent();
        assertTrue(callback.startsWith("https://l/cb?state=7."), callback);
        String state = callback.substring(callback.indexOf("state=") + "state=".length());
        HatenaAuthorizationStore.Pending pending = store.take(state).orElseThrow();
        assertEquals(7L, pending.projectId());
        assertEquals(3L, pending.siteId());
        assertEquals("sub-1", pending.actorSub());
        assertEquals("consumer-key", pending.consumerKey());
        assertEquals("consumer-secret", pending.consumerSecret());
        assertEquals("RT", pending.requestToken());
        assertEquals("RS", pending.requestTokenSecret());
    }

    @Test
    void startAuthorization_リダイレクト先に既にクエリがあれば連結する() {
        fx.connectableSite();
        fx.world.signedInAs("sub-1");
        hatenaIssuesRequestToken();

        service.startAuthorization(7L, "consumer-key", "consumer-secret", "https://l/cb?x=1");

        assertTrue(callbackSent().startsWith("https://l/cb?x=1&state=7."), callbackSent());
    }

    @Test
    void startAuthorization_リクエストトークンを取れなければ接続失敗として返し_何も保存しない() {
        fx.connectableSite();
        fx.world.http.on("POST", "/oauth/initiate", FakeHttpServer.Response.bytes(401, "application/x-www-form-urlencoded",
                "oauth_problem=consumer_key_unknown".getBytes(StandardCharsets.UTF_8)));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.startAuthorization(7L, "consumer-key", "consumer-secret", CALLBACK));

        assertTrue(e.getMessage().contains("consumer_key_unknown"));
        assertFalse(e.getMessage().contains("consumer-secret"));
        assertEquals(0, store.size());
    }

    // ---- completeAuthorization ----

    @Test
    void completeAuthorization_トークンを本番サイトへ標準入力で送りアカウント名だけを返す() throws Exception {
        String state = started();
        hatenaIssuesAccessToken();
        fx.world.letsblogSns("config-set", "hatena", "{\"sns\":\"hatena\",\"status\":\"接続済み\"}");

        XConnectResult result = service.completeAuthorization(7L, state, "RT", "the-verifier");

        assertEquals(7L, result.projectId());
        assertEquals("Let's Blog", result.accountName());
        String exchangeAuth = fx.world.http.requests("POST", "/oauth/token").get(0).header("Authorization");
        assertTrue(exchangeAuth.contains("oauth_verifier=\"the-verifier\""), exchangeAuth);
        assertTrue(exchangeAuth.contains("oauth_token=\"RT\""), exchangeAuth);
        JsonNode command = fx.world.snsRequests("config-set").get(0);
        JsonNode sent = fx.world.objectMapper.readTree(command.path("stdin").asText());
        assertEquals("hatena", sent.path("sns").asText());
        assertEquals("consumer-key", sent.path("consumer_key").asText());
        assertEquals("consumer-secret", sent.path("consumer_secret").asText());
        assertEquals("ACCESS-TOKEN", sent.path("access_token").asText());
        assertEquals("ACCESS-SECRET", sent.path("access_token_secret").asText());
        assertEquals("Let's Blog", sent.path("account_name").asText());
        String json = fx.world.objectMapper.writeValueAsString(result);
        for (String secret : new String[] {"ACCESS-TOKEN", "ACCESS-SECRET", "consumer-secret", "RS"}) {
            assertFalse(json.contains(secret), secret);
        }
    }

    @Test
    void completeAuthorization_知らないstateや期限切れは拒否する() {
        fx.world.signedInAs("sub-1");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.completeAuthorization(7L, "7.abc", "RT", "v"));

        assertTrue(e.getMessage().contains("もう一度"));
        assertTrue(fx.world.http.requests("POST", "/oauth/token").isEmpty());
    }

    @Test
    void completeAuthorization_別のプロジェクトのstateは拒否する() {
        String state = started();

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(8L, state, "RT", "v"));
        assertTrue(fx.world.http.requests("POST", "/oauth/token").isEmpty());
    }

    @Test
    void completeAuthorization_認可を始めた本人以外は拒否する() {
        String state = started();
        fx.world.signedInAs("someone-else");

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(7L, state, "RT", "v"));
        assertTrue(fx.world.http.requests("POST", "/oauth/token").isEmpty());
    }

    @Test
    void completeAuthorization_認可を始めた本人が不明なstateは拒否する() {
        fx.connectableSite();
        fx.world.signedInAs(null);
        hatenaIssuesRequestToken();
        service.startAuthorization(7L, "consumer-key", "consumer-secret", CALLBACK);
        String callback = callbackSent();
        String state = callback.substring(callback.indexOf("state=") + "state=".length());

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(7L, state, "RT", "v"));
        assertTrue(fx.world.http.requests("POST", "/oauth/token").isEmpty());
    }

    @Test
    void completeAuthorization_リクエストトークンがstateの認可と一致しなければ拒否する() {
        String state = started();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.completeAuthorization(7L, state, "OTHER-TOKEN", "v"));

        assertTrue(e.getMessage().contains("もう一度"));
        assertTrue(fx.world.http.requests("POST", "/oauth/token").isEmpty());
    }

    @Test
    void completeAuthorization_その間に接続できなくなっていたら拒否する() {
        String state = started();
        fx.projectWithProductionSite(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "RT", "v"));

        assertTrue(e.getMessage().contains("本番サイト"));
        assertTrue(fx.world.http.requests("POST", "/oauth/token").isEmpty());
    }

    @Test
    void completeAuthorization_トークン交換が失敗したら接続失敗として返す() {
        String state = started();
        fx.world.http.on("POST", "/oauth/token", FakeHttpServer.Response.bytes(401, "application/x-www-form-urlencoded",
                "oauth_problem=token_rejected".getBytes(StandardCharsets.UTF_8)));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "RT", "v"));

        assertTrue(e.getMessage().contains("HTTP 401"));
        assertFalse(sentToSite());
    }

    @Test
    void completeAuthorization_アカウントの情報を取れなければ接続失敗として返し送らない() {
        String state = started();
        hatenaIssuesAccessToken();
        fx.world.http.status("GET", "/profile", 401, "{}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "RT", "v"));

        assertTrue(e.getMessage().contains("HTTP 401"));
        assertFalse(sentToSite());
    }

    @Test
    void completeAuthorization_サイトへ送れなければ接続失敗として返し_秘密を含めない() {
        String state = started();
        hatenaIssuesAccessToken();
        fx.world.letsblogSnsFails("config-set", "hatena", "エージェントへの接続に失敗しました");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "RT", "the-verifier"));

        assertTrue(e.getMessage().contains("接続失敗"));
        assertFalse(e.getMessage().contains("ACCESS-TOKEN"));
        assertFalse(e.getMessage().contains("ACCESS-SECRET"));
    }

    @Test
    void completeAuthorization_サイトが接続済みと答えなければ接続失敗として返す() {
        String state = started();
        hatenaIssuesAccessToken();
        fx.world.letsblogSns("config-set", "hatena", "{\"sns\":\"hatena\",\"status\":\"要再接続\"}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "RT", "the-verifier"));

        assertTrue(e.getMessage().contains("接続失敗"));
    }

    @Test
    void completeAuthorization_サイトの出力を解釈できなければ接続失敗として返す() {
        String state = started();
        hatenaIssuesAccessToken();
        fx.world.letsblogSns("config-set", "hatena", "garbage");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, state, "RT", "the-verifier"));

        assertTrue(e.getMessage().contains("接続失敗"));
    }

    // ---- disconnect ----

    @Test
    void disconnect_本番サイトのプラグインからはてなブックマークの設定だけを消す() {
        fx.connectableSite();
        fx.world.letsblogSns("config-clear", "hatena", "{}");

        service.disconnect(7L);

        assertEquals("hatena", fx.world.snsRequests("config-clear").get(0).path("sns").asText());
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
        fx.world.letsblogSnsFails("config-clear", "hatena", "届かない");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.disconnect(7L));

        assertTrue(e.getMessage().contains("届かない"));
    }
}
