package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.project.client.ThreadsApiClient;
import com.letsblog.project.client.ThreadsApiClient.LongLivedToken;
import com.letsblog.project.client.ThreadsApiClient.ShortLivedToken;
import com.letsblog.project.client.ThreadsApiException;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * プロジェクトの Threads アカウント接続(issue #1579。Epic #1572)。X(#1574)と同じく、本番サイトのプラグインへは
 * wp-cli(`sns config set` を標準入力の JSON で)だけで送り、アプリはトークンを保存しない・API で返さない。
 * Threads は更新にクライアントの秘密が要らないので、プラグインへ送るのは長期トークンとユーザーIDだけ。
 */
@ExtendWith(MockitoExtension.class)
class SnsThreadsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    @Mock
    private SnsXService snsXService;
    @Mock
    private SiteService siteService;
    @Mock
    private ThreadsApiClient threadsApiClient;
    @Mock
    private XAuthorizationStore store;
    @Mock
    private CurrentActorService currentActorService;

    private final ObjectMapper mapper = new ObjectMapper();
    private SnsThreadsService service;

    @BeforeEach
    void setUp() {
        service = new SnsThreadsService(snsXService, siteService, threadsApiClient, store, currentActorService,
                mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private XAuthorizationStore.Pending pending() {
        return new XAuthorizationStore.Pending(
                "7.abc", 7L, 3L, "sub-1", "app-id", "app-secret", "https://l/cb", "verifier", NOW.plusSeconds(600));
    }

    private void pendingFor(XAuthorizationStore.Pending pending) {
        when(store.take("7.abc")).thenReturn(Optional.of(pending));
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");
    }

    // ---- view / test(状態の読み取りとテスト投稿は X と共通の処理に Threads を指定して任せる) ----

    @Test
    void view_Threadsの接続状態を返す() {
        XConnectionView view = new XConnectionView(true, null, "本番サイト", null, null);
        when(snsXService.view(7L, "threads")).thenReturn(view);

        assertSame(view, service.view(7L));
    }

    @Test
    void test_Threadsへのテスト投稿を依頼する() {
        XTestResult result = new XTestResult(true, null);
        when(snsXService.test(7L, "threads")).thenReturn(result);

        assertSame(result, service.test(7L));
    }

    // ---- startAuthorization ----

    @Test
    void startAuthorization_クライアントの情報かリダイレクト先が空なら拒否する() {
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, " ", "s", "https://l/cb"));
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, "c", "", "https://l/cb"));
        assertThrows(IllegalArgumentException.class, () -> service.startAuthorization(7L, "c", "s", null));
        verifyNoInteractions(store);
    }

    @Test
    void startAuthorization_接続できないサイトでは理由つきで拒否し認可を始めない() {
        when(snsXService.requireConnectableSiteId(7L)).thenThrow(new IllegalStateException("本番サイトが設定されていません"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.startAuthorization(7L, "app-id", "app-secret", "https://l/cb"));

        assertTrue(e.getMessage().contains("本番サイト"));
        verifyNoInteractions(store);
    }

    @Test
    void startAuthorization_認可を保存してThreadsの認可URLを返す() {
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");
        when(store.create(7L, 3L, "sub-1", "app-id", "app-secret", "https://l/cb")).thenReturn(pending());
        when(threadsApiClient.authorizeUrl("app-id", "https://l/cb", "7.abc"))
                .thenReturn("https://threads.example/authorize?state=7.abc");

        assertEquals("https://threads.example/authorize?state=7.abc",
                service.startAuthorization(7L, "app-id", "app-secret", "https://l/cb"));
    }

    // ---- completeAuthorization ----

    private void exchanges() {
        when(threadsApiClient.exchangeCode("app-id", "app-secret", "the-code", "https://l/cb"))
                .thenReturn(new ShortLivedToken("SHORT-TOKEN", "17841400000000001"));
        when(threadsApiClient.exchangeLongLived("app-secret", "SHORT-TOKEN"))
                .thenReturn(new LongLivedToken("LONG-TOKEN", 5184000L));
        when(threadsApiClient.fetchUsername("LONG-TOKEN")).thenReturn("lets_blog");
    }

    @Test
    void completeAuthorization_長期トークンを本番サイトへ標準入力で送りアカウント名だけを返す() throws Exception {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        exchanges();
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("threads"), anyString()))
                .thenReturn("{\"sns\":\"threads\",\"status\":\"接続済み\"}");

        XConnectResult result = service.completeAuthorization(7L, "7.abc", "the-code");

        assertEquals(7L, result.projectId());
        assertEquals("lets_blog", result.accountName());
        ArgumentCaptor<String> stdin = ArgumentCaptor.forClass(String.class);
        verify(siteService).runLetsblogSns(eq(3L), eq("config-set"), eq("threads"), stdin.capture());
        JsonNode sent = mapper.readTree(stdin.getValue());
        assertEquals("threads", sent.path("sns").asText());
        assertEquals("LONG-TOKEN", sent.path("access_token").asText());
        assertEquals("17841400000000001", sent.path("user_id").asText());
        assertEquals(NOW.getEpochSecond(), sent.path("issued_at").asLong());
        assertEquals(NOW.getEpochSecond() + 5184000L, sent.path("expires_at").asLong());
        assertEquals("lets_blog", sent.path("account_name").asText());
        // 更新に要らないクライアントの秘密と短期トークンは、プラグインへも送らない。
        assertFalse(stdin.getValue().contains("app-secret"));
        assertFalse(stdin.getValue().contains("SHORT-TOKEN"));
        String json = mapper.writeValueAsString(result);
        assertFalse(json.contains("LONG-TOKEN"));
        assertFalse(json.contains("app-secret"));
    }

    @Test
    void completeAuthorization_知らないstateや期限切れは拒否する() {
        when(store.take("7.abc")).thenReturn(Optional.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("もう一度"));
        verifyNoInteractions(threadsApiClient);
    }

    @Test
    void completeAuthorization_別のプロジェクトのstateは拒否する() {
        pendingFor(pending());

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(8L, "7.abc", "code"));
        verifyNoInteractions(threadsApiClient);
    }

    @Test
    void completeAuthorization_認可を始めた本人以外は拒否する() {
        when(store.take("7.abc")).thenReturn(Optional.of(pending()));
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("someone-else");

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(7L, "7.abc", "code"));
        verifyNoInteractions(threadsApiClient);
    }

    @Test
    void completeAuthorization_認可を始めた本人が不明なstateは拒否する() {
        XAuthorizationStore.Pending noActor = new XAuthorizationStore.Pending(
                "7.abc", 7L, 3L, null, "app-id", "app-secret", "https://l/cb", "verifier", NOW.plusSeconds(600));
        pendingFor(noActor);

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(7L, "7.abc", "code"));
    }

    @Test
    void completeAuthorization_その間に接続できなくなっていたら拒否する() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenThrow(new IllegalStateException("本番サイトが設定されていません"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("本番サイト"));
        verifyNoInteractions(threadsApiClient);
    }

    @Test
    void completeAuthorization_トークン交換が失敗したら接続失敗として返す() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        when(threadsApiClient.exchangeCode(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new ThreadsApiException("Threads のトークン交換に失敗しました(HTTP 400)"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("HTTP 400"));
        verify(siteService, never()).runLetsblogSns(any(), anyString(), any(), any());
    }

    @Test
    void completeAuthorization_長期トークン化に失敗したら接続失敗として返し送らない() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        when(threadsApiClient.exchangeCode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ShortLivedToken("SHORT-TOKEN", "1"));
        when(threadsApiClient.exchangeLongLived("app-secret", "SHORT-TOKEN"))
                .thenThrow(new ThreadsApiException("Threads の長期トークンへの交換に失敗しました(HTTP 400)"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("長期トークン"));
        verify(siteService, never()).runLetsblogSns(any(), anyString(), any(), any());
    }

    @Test
    void completeAuthorization_アカウント名を取れなければ接続失敗として返し送らない() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        when(threadsApiClient.exchangeCode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ShortLivedToken("SHORT-TOKEN", "1"));
        when(threadsApiClient.exchangeLongLived(anyString(), anyString())).thenReturn(new LongLivedToken("LONG-TOKEN", 5184000L));
        when(threadsApiClient.fetchUsername("LONG-TOKEN"))
                .thenThrow(new ThreadsApiException("Threads の自分の情報の取得に失敗しました(HTTP 401)"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("HTTP 401"));
        verify(siteService, never()).runLetsblogSns(any(), anyString(), any(), any());
    }

    @Test
    void completeAuthorization_サイトへ送れなければ接続失敗として返し_トークンを含めない() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        exchanges();
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("threads"), anyString()))
                .thenThrow(new IllegalStateException("エージェントへの接続に失敗しました"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "the-code"));

        assertTrue(e.getMessage().contains("接続失敗"));
        assertFalse(e.getMessage().contains("LONG-TOKEN"));
    }

    @Test
    void completeAuthorization_サイトが接続済みと答えなければ接続失敗として返す() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        exchanges();
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("threads"), anyString()))
                .thenReturn("{\"sns\":\"threads\",\"status\":\"要再接続\"}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "the-code"));

        assertTrue(e.getMessage().contains("接続失敗"));
    }

    @Test
    void completeAuthorization_サイトの出力を解釈できなければ接続失敗として返す() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        exchanges();
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("threads"), anyString())).thenReturn("garbage");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "the-code"));

        assertTrue(e.getMessage().contains("接続失敗"));
    }

    // ---- disconnect ----

    @Test
    void disconnect_本番サイトのプラグインからThreadsの設定だけを消す() {
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);

        service.disconnect(7L);

        verify(siteService).runLetsblogSns(3L, "config-clear", "threads", null);
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
        when(siteService.runLetsblogSns(3L, "config-clear", "threads", null))
                .thenThrow(new IllegalStateException("届かない"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.disconnect(7L));

        assertTrue(e.getMessage().contains("届かない"));
    }
}
