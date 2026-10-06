package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.project.client.LinkedinApiClient;
import com.letsblog.project.client.LinkedinApiClient.AccessToken;
import com.letsblog.project.client.LinkedinApiClient.Profile;
import com.letsblog.project.client.LinkedinApiException;
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
 * プロジェクトの LinkedIn アカウント接続(issue #1581。Epic #1572)。X(#1574)と同じく、本番サイトのプラグインへは
 * wp-cli(`sns config set` を標準入力の JSON で)だけで送り、アプリはトークンを保存しない・API で返さない。
 * LinkedIn のトークンは更新しないので、プラグインへ送るのはアクセストークン・メンバーID・有効期限だけ(Client Secret は送らない)。
 */
@ExtendWith(MockitoExtension.class)
class SnsLinkedinServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    @Mock
    private SnsXService snsXService;
    @Mock
    private SiteService siteService;
    @Mock
    private LinkedinApiClient linkedinApiClient;
    @Mock
    private XAuthorizationStore store;
    @Mock
    private CurrentActorService currentActorService;

    private final ObjectMapper mapper = new ObjectMapper();
    private SnsLinkedinService service;

    @BeforeEach
    void setUp() {
        service = new SnsLinkedinService(snsXService, siteService, linkedinApiClient, store, currentActorService,
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

    // ---- view / test(状態の読み取りとテスト投稿は X と共通の処理に LinkedIn を指定して任せる) ----

    @Test
    void view_LinkedInの接続状態を返す() {
        XConnectionView view = new XConnectionView(true, null, "本番サイト", null, null);
        when(snsXService.view(7L, "linkedin")).thenReturn(view);

        assertSame(view, service.view(7L));
    }

    @Test
    void test_LinkedInへのテスト投稿を依頼する() {
        XTestResult result = new XTestResult(true, null);
        when(snsXService.test(7L, "linkedin")).thenReturn(result);

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
    void startAuthorization_認可を保存してLinkedInの認可URLを返す() {
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");
        when(store.create(7L, 3L, "sub-1", "app-id", "app-secret", "https://l/cb")).thenReturn(pending());
        when(linkedinApiClient.authorizeUrl("app-id", "https://l/cb", "7.abc"))
                .thenReturn("https://threads.example/authorize?state=7.abc");

        assertEquals("https://threads.example/authorize?state=7.abc",
                service.startAuthorization(7L, "app-id", "app-secret", "https://l/cb"));
    }

    // ---- completeAuthorization ----

    private void exchanges() {
        when(linkedinApiClient.exchangeCode("app-id", "app-secret", "the-code", "https://l/cb"))
                .thenReturn(new AccessToken("ACCESS-TOKEN", 5184000L));
        when(linkedinApiClient.fetchProfile("ACCESS-TOKEN")).thenReturn(new Profile("sub-abc", "Let's Blog"));
    }

    @Test
    void completeAuthorization_アクセストークンを本番サイトへ標準入力で送りアカウント名だけを返す() throws Exception {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        exchanges();
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("linkedin"), anyString()))
                .thenReturn("{\"sns\":\"linkedin\",\"status\":\"接続済み\"}");

        XConnectResult result = service.completeAuthorization(7L, "7.abc", "the-code");

        assertEquals(7L, result.projectId());
        assertEquals("Let's Blog", result.accountName());
        ArgumentCaptor<String> stdin = ArgumentCaptor.forClass(String.class);
        verify(siteService).runLetsblogSns(eq(3L), eq("config-set"), eq("linkedin"), stdin.capture());
        JsonNode sent = mapper.readTree(stdin.getValue());
        assertEquals("linkedin", sent.path("sns").asText());
        assertEquals("ACCESS-TOKEN", sent.path("access_token").asText());
        assertEquals("sub-abc", sent.path("member_id").asText());
        assertEquals(NOW.getEpochSecond() + 5184000L, sent.path("expires_at").asLong());
        assertEquals("Let's Blog", sent.path("account_name").asText());
        // トークンを更新しないので、Client Secret はプラグインへ送らない。
        assertFalse(stdin.getValue().contains("app-secret"));
        String json = mapper.writeValueAsString(result);
        assertFalse(json.contains("ACCESS-TOKEN"));
        assertFalse(json.contains("app-secret"));
    }

    @Test
    void completeAuthorization_知らないstateや期限切れは拒否する() {
        when(store.take("7.abc")).thenReturn(Optional.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("もう一度"));
        verifyNoInteractions(linkedinApiClient);
    }

    @Test
    void completeAuthorization_別のプロジェクトのstateは拒否する() {
        pendingFor(pending());

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(8L, "7.abc", "code"));
        verifyNoInteractions(linkedinApiClient);
    }

    @Test
    void completeAuthorization_認可を始めた本人以外は拒否する() {
        when(store.take("7.abc")).thenReturn(Optional.of(pending()));
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("someone-else");

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(7L, "7.abc", "code"));
        verifyNoInteractions(linkedinApiClient);
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
        verifyNoInteractions(linkedinApiClient);
    }

    @Test
    void completeAuthorization_トークン交換が失敗したら接続失敗として返す() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        when(linkedinApiClient.exchangeCode(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new LinkedinApiException("LinkedIn のトークン交換に失敗しました(HTTP 400)"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("HTTP 400"));
        verify(siteService, never()).runLetsblogSns(any(), anyString(), any(), any());
    }

    @Test
    void completeAuthorization_メンバーの情報を取れなければ接続失敗として返し送らない() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        when(linkedinApiClient.exchangeCode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AccessToken("ACCESS-TOKEN", 5184000L));
        when(linkedinApiClient.fetchProfile("ACCESS-TOKEN"))
                .thenThrow(new LinkedinApiException("LinkedIn の自分の情報の取得に失敗しました(HTTP 401)"));

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
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("linkedin"), anyString()))
                .thenThrow(new IllegalStateException("エージェントへの接続に失敗しました"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "the-code"));

        assertTrue(e.getMessage().contains("接続失敗"));
        assertFalse(e.getMessage().contains("ACCESS-TOKEN"));
    }

    @Test
    void completeAuthorization_サイトが接続済みと答えなければ接続失敗として返す() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        exchanges();
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("linkedin"), anyString()))
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
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("linkedin"), anyString())).thenReturn("garbage");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "the-code"));

        assertTrue(e.getMessage().contains("接続失敗"));
    }

    // ---- disconnect ----

    @Test
    void disconnect_本番サイトのプラグインからLinkedInの設定だけを消す() {
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);

        service.disconnect(7L);

        verify(siteService).runLetsblogSns(3L, "config-clear", "linkedin", null);
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
        when(siteService.runLetsblogSns(3L, "config-clear", "linkedin", null))
                .thenThrow(new IllegalStateException("届かない"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.disconnect(7L));

        assertTrue(e.getMessage().contains("届かない"));
    }
}
