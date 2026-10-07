package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.project.client.HatenaApiClient;
import com.letsblog.project.client.HatenaApiClient.AccessToken;
import com.letsblog.project.client.HatenaApiClient.Profile;
import com.letsblog.project.client.HatenaApiClient.RequestToken;
import com.letsblog.project.client.HatenaApiException;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * プロジェクトのはてなブックマーク(公式アカウント)の接続・切断(issue #1582。Epic #1572)。X(#1574)と同じく、本番サイトのプラグインへは
 * wp-cli(`sns config set` を標準入力の JSON で)だけで送り、アプリはトークンを保存しない・API で返さない。
 * OAuth 1.0a なので、リクエストトークンの秘密は認可の間だけメモリ({@link HatenaAuthorizationStore})に持つ。プラグインは
 * 署名に consumer secret が要るので、consumer key / secret・アクセストークン・その秘密を送る(プラグインが暗号化して保存する)。
 */
@ExtendWith(MockitoExtension.class)
class SnsHatenaServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    @Mock
    private SnsXService snsXService;
    @Mock
    private SiteService siteService;
    @Mock
    private HatenaApiClient hatenaApiClient;
    @Mock
    private HatenaAuthorizationStore store;
    @Mock
    private CurrentActorService currentActorService;

    private final ObjectMapper mapper = new ObjectMapper();
    private SnsHatenaService service;

    @BeforeEach
    void setUp() {
        service = new SnsHatenaService(snsXService, siteService, hatenaApiClient, store, currentActorService, mapper);
    }

    private HatenaAuthorizationStore.Pending pending() {
        return new HatenaAuthorizationStore.Pending(
                "7.abc", 7L, 3L, "sub-1", "consumer-key", "consumer-secret", "RT", "RS", NOW.plusSeconds(600));
    }

    private void pendingFor(HatenaAuthorizationStore.Pending pending) {
        when(store.take("7.abc")).thenReturn(Optional.of(pending));
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");
    }

    // ---- view / test ----

    @Test
    void view_はてなブックマークの接続状態を返す() {
        XConnectionView view = new XConnectionView(true, null, "本番サイト", null, null);
        when(snsXService.view(7L, "hatena")).thenReturn(view);

        assertSame(view, service.view(7L));
    }

    @Test
    void test_はてなブックマークへのテスト投稿を依頼する() {
        XTestResult result = new XTestResult(true, null);
        when(snsXService.test(7L, "hatena")).thenReturn(result);

        assertSame(result, service.test(7L));
    }

    // ---- startAuthorization ----

    @Test
    void startAuthorization_consumerの情報かリダイレクト先が空なら拒否する() {
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, " ", "s", "https://l/cb"));
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, "c", "", "https://l/cb"));
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, "c", "s", null));
        verifyNoInteractions(store);
        verifyNoInteractions(hatenaApiClient);
    }

    @Test
    void startAuthorization_接続できないサイトでは理由つきで拒否し認可を始めない() {
        when(snsXService.requireConnectableSiteId(7L)).thenThrow(new IllegalStateException("本番サイトが設定されていません"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.startAuthorization(7L, "consumer-key", "consumer-secret", "https://l/cb"));

        assertTrue(e.getMessage().contains("本番サイト"));
        verifyNoInteractions(store);
        verifyNoInteractions(hatenaApiClient);
    }

    @Test
    void startAuthorization_stateをcallbackに載せてリクエストトークンを取り_認可URLを返す() {
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");
        when(store.newState(7L)).thenReturn("7.abc");
        when(hatenaApiClient.fetchRequestToken("consumer-key", "consumer-secret", "https://l/cb?state=7.abc"))
                .thenReturn(new RequestToken("RT", "RS"));
        when(hatenaApiClient.authorizeUrl("RT")).thenReturn("https://hatena.example/authorize?oauth_token=RT");

        String url = service.startAuthorization(7L, "consumer-key", "consumer-secret", "https://l/cb");

        assertEquals("https://hatena.example/authorize?oauth_token=RT", url);
        verify(store).create("7.abc", 7L, 3L, "sub-1", "consumer-key", "consumer-secret", "RT", "RS");
    }

    @Test
    void startAuthorization_リダイレクト先に既にクエリがあれば連結し_stateはエンコードする() {
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");
        when(store.newState(7L)).thenReturn("7.a+b/c");
        when(hatenaApiClient.fetchRequestToken("consumer-key", "consumer-secret", "https://l/cb?x=1&state=7.a%2Bb%2Fc"))
                .thenReturn(new RequestToken("RT", "RS"));
        when(hatenaApiClient.authorizeUrl("RT")).thenReturn("https://hatena.example/authorize");

        assertEquals("https://hatena.example/authorize",
                service.startAuthorization(7L, "consumer-key", "consumer-secret", "https://l/cb?x=1"));
    }

    @Test
    void startAuthorization_リクエストトークンを取れなければ接続失敗として返し_何も保存しない() {
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        when(store.newState(7L)).thenReturn("7.abc");
        when(hatenaApiClient.fetchRequestToken(anyString(), anyString(), anyString()))
                .thenThrow(new HatenaApiException("はてなのリクエストトークンの取得に失敗しました(HTTP 401: consumer_key_unknown)"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.startAuthorization(7L, "consumer-key", "consumer-secret", "https://l/cb"));

        assertTrue(e.getMessage().contains("consumer_key_unknown"));
        assertFalse(e.getMessage().contains("consumer-secret"));
        verify(store, never()).create(anyString(), any(), any(), any(), anyString(), anyString(), anyString(), anyString());
    }

    // ---- completeAuthorization ----

    private void exchanges() {
        when(hatenaApiClient.fetchAccessToken("consumer-key", "consumer-secret", "RT", "RS", "the-verifier"))
                .thenReturn(new AccessToken("ACCESS-TOKEN", "ACCESS-SECRET"));
        when(hatenaApiClient.fetchProfile("consumer-key", "consumer-secret", "ACCESS-TOKEN", "ACCESS-SECRET"))
                .thenReturn(new Profile("Let's Blog"));
    }

    @Test
    void completeAuthorization_トークンを本番サイトへ標準入力で送りアカウント名だけを返す() throws Exception {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        exchanges();
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("hatena"), anyString()))
                .thenReturn("{\"sns\":\"hatena\",\"status\":\"接続済み\"}");

        XConnectResult result = service.completeAuthorization(7L, "7.abc", "RT", "the-verifier");

        assertEquals(7L, result.projectId());
        assertEquals("Let's Blog", result.accountName());
        ArgumentCaptor<String> stdin = ArgumentCaptor.forClass(String.class);
        verify(siteService).runLetsblogSns(eq(3L), eq("config-set"), eq("hatena"), stdin.capture());
        JsonNode sent = mapper.readTree(stdin.getValue());
        assertEquals("hatena", sent.path("sns").asText());
        assertEquals("consumer-key", sent.path("consumer_key").asText());
        assertEquals("consumer-secret", sent.path("consumer_secret").asText());
        assertEquals("ACCESS-TOKEN", sent.path("access_token").asText());
        assertEquals("ACCESS-SECRET", sent.path("access_token_secret").asText());
        assertEquals("Let's Blog", sent.path("account_name").asText());
        String json = mapper.writeValueAsString(result);
        for (String secret : new String[] {"ACCESS-TOKEN", "ACCESS-SECRET", "consumer-secret", "RS"}) {
            assertFalse(json.contains(secret), secret);
        }
    }

    @Test
    void completeAuthorization_知らないstateや期限切れは拒否する() {
        when(store.take("7.abc")).thenReturn(Optional.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.completeAuthorization(7L, "7.abc", "RT", "v"));

        assertTrue(e.getMessage().contains("もう一度"));
        verifyNoInteractions(hatenaApiClient);
    }

    @Test
    void completeAuthorization_別のプロジェクトのstateは拒否する() {
        pendingFor(pending());

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(8L, "7.abc", "RT", "v"));
        verifyNoInteractions(hatenaApiClient);
    }

    @Test
    void completeAuthorization_認可を始めた本人以外は拒否する() {
        when(store.take("7.abc")).thenReturn(Optional.of(pending()));
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("someone-else");

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(7L, "7.abc", "RT", "v"));
        verifyNoInteractions(hatenaApiClient);
    }

    @Test
    void completeAuthorization_認可を始めた本人が不明なstateは拒否する() {
        HatenaAuthorizationStore.Pending noActor = new HatenaAuthorizationStore.Pending(
                "7.abc", 7L, 3L, null, "consumer-key", "consumer-secret", "RT", "RS", NOW.plusSeconds(600));
        pendingFor(noActor);

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(7L, "7.abc", "RT", "v"));
        verifyNoInteractions(hatenaApiClient);
    }

    @Test
    void completeAuthorization_リクエストトークンがstateの認可と一致しなければ拒否する() {
        pendingFor(pending());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.completeAuthorization(7L, "7.abc", "OTHER-TOKEN", "v"));

        assertTrue(e.getMessage().contains("もう一度"));
        verifyNoInteractions(hatenaApiClient);
    }

    @Test
    void completeAuthorization_その間に接続できなくなっていたら拒否する() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenThrow(new IllegalStateException("本番サイトが設定されていません"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "RT", "v"));

        assertTrue(e.getMessage().contains("本番サイト"));
        verifyNoInteractions(hatenaApiClient);
    }

    @Test
    void completeAuthorization_トークン交換が失敗したら接続失敗として返す() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        when(hatenaApiClient.fetchAccessToken(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new HatenaApiException("はてなのアクセストークンの取得に失敗しました(HTTP 401)"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "RT", "v"));

        assertTrue(e.getMessage().contains("HTTP 401"));
        verify(siteService, never()).runLetsblogSns(any(), anyString(), any(), any());
    }

    @Test
    void completeAuthorization_アカウントの情報を取れなければ接続失敗として返し送らない() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        when(hatenaApiClient.fetchAccessToken(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AccessToken("ACCESS-TOKEN", "ACCESS-SECRET"));
        when(hatenaApiClient.fetchProfile(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new HatenaApiException("はてなの自分の情報の取得に失敗しました(HTTP 401)"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "RT", "v"));

        assertTrue(e.getMessage().contains("HTTP 401"));
        verify(siteService, never()).runLetsblogSns(any(), anyString(), any(), any());
    }

    @Test
    void completeAuthorization_サイトへ送れなければ接続失敗として返し_秘密を含めない() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        exchanges();
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("hatena"), anyString()))
                .thenThrow(new IllegalStateException("エージェントへの接続に失敗しました"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "RT", "the-verifier"));

        assertTrue(e.getMessage().contains("接続失敗"));
        assertFalse(e.getMessage().contains("ACCESS-TOKEN"));
        assertFalse(e.getMessage().contains("ACCESS-SECRET"));
    }

    @Test
    void completeAuthorization_サイトが接続済みと答えなければ接続失敗として返す() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        exchanges();
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("hatena"), anyString()))
                .thenReturn("{\"sns\":\"hatena\",\"status\":\"要再接続\"}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "RT", "the-verifier"));

        assertTrue(e.getMessage().contains("接続失敗"));
    }

    @Test
    void completeAuthorization_サイトの出力を解釈できなければ接続失敗として返す() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        exchanges();
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("hatena"), anyString())).thenReturn("garbage");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "RT", "the-verifier"));

        assertTrue(e.getMessage().contains("接続失敗"));
    }

    // ---- disconnect ----

    @Test
    void disconnect_本番サイトのプラグインからはてなブックマークの設定だけを消す() {
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);

        service.disconnect(7L);

        verify(siteService).runLetsblogSns(3L, "config-clear", "hatena", null);
    }

    @Test
    void disconnect_接続できないサイトでは理由つきで拒否する() {
        when(snsXService.requireConnectableSiteId(7L)).thenThrow(new IllegalStateException("本番サイトが設定されていません"));

        assertThrows(IllegalStateException.class, () -> service.disconnect(7L));
        verifyNoInteractions(siteService);
    }

    @Test
    void disconnect_サイトの設定を消せなければ失敗として返す() {
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        when(siteService.runLetsblogSns(3L, "config-clear", "hatena", null))
                .thenThrow(new IllegalStateException("届かない"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.disconnect(7L));

        assertTrue(e.getMessage().contains("届かない"));
    }
}
