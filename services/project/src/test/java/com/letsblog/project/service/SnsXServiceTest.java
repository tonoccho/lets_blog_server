package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.project.client.XApiClient;
import com.letsblog.project.client.XApiClient.XTokens;
import com.letsblog.project.client.XApiException;
import com.letsblog.project.cms.LetsblogPluginStatus;
import com.letsblog.project.cms.LetsblogPluginStatus.State;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.repository.SiteRepository;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * プロジェクトの X アカウント接続(issue #1574)。本番サイトのプラグインへは wp-cli(`sns config set` を標準入力の
 * JSON で)だけで送り、アプリはトークンを保存しない・API で返さない。本番サイトが無い・プラグインが導入済みでない
 * ときは接続できず理由を返す。届かないときの状態と履歴は「取得できない」として返し、例外にしない。
 */
@ExtendWith(MockitoExtension.class)
class SnsXServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private SiteService siteService;
    @Mock
    private XApiClient xApiClient;
    @Mock
    private XAuthorizationStore store;
    @Mock
    private CurrentActorService currentActorService;

    private final ObjectMapper mapper = new ObjectMapper();
    private SnsXService service;

    @BeforeEach
    void setUp() {
        service = new SnsXService(projectRepository, siteRepository, siteService, xApiClient, store,
                currentActorService, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void projectWithProductionSite(Long siteId) {
        Project project = new Project();
        project.setId(7L);
        project.setProductionSiteId(siteId);
        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
    }

    private void siteExists(Long siteId) {
        Site site = new Site();
        site.setId(siteId);
        site.setName("本番サイト");
        when(siteRepository.findById(siteId)).thenReturn(Optional.of(site));
    }

    private void pluginState(Long siteId, State state) {
        when(siteService.getLetsblogPluginStatus(siteId)).thenReturn(new LetsblogPluginStatus(state, "1.0.0", 1));
    }

    private void connectableSite() {
        projectWithProductionSite(3L);
        siteExists(3L);
        pluginState(3L, State.INSTALLED);
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
        verifyNoInteractions(siteService);
    }

    @Test
    void view_本番サイトの行が存在しなくても未設定として扱う() {
        projectWithProductionSite(3L);
        when(siteRepository.findById(3L)).thenReturn(Optional.empty());

        XConnectionView view = service.view(7L);

        assertFalse(view.connectable());
        assertTrue(view.reason().contains("本番サイト"));
        verifyNoInteractions(siteService);
    }

    @Test
    void view_プラグインが未導入なら接続できず理由を返しSNSには問い合わせない() {
        projectWithProductionSite(3L);
        siteExists(3L);
        pluginState(3L, State.NOT_INSTALLED);

        XConnectionView view = service.view(7L);

        assertFalse(view.connectable());
        assertEquals("本番サイト", view.siteName());
        assertTrue(view.reason().contains("未導入"));
        assertNull(view.status());
        assertNull(view.log());
        verify(siteService, never()).runLetsblogSns(any(), anyString(), any(), any());
    }

    @Test
    void view_プラグインが要更新なら接続できず理由を返す() {
        projectWithProductionSite(3L);
        siteExists(3L);
        pluginState(3L, State.NEEDS_UPDATE);

        XConnectionView view = service.view(7L);

        assertFalse(view.connectable());
        assertTrue(view.reason().contains("要更新"));
    }

    @Test
    void view_プラグインの状態を取れなければ接続状態も履歴も取得できないとして返し例外にしない() {
        projectWithProductionSite(3L);
        siteExists(3L);
        when(siteService.getLetsblogPluginStatus(3L)).thenThrow(new IllegalStateException("接続失敗"));

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
        when(siteService.runLetsblogSns(3L, "status", "x", null))
                .thenReturn("{\"x\":{\"status\":\"接続済み\",\"account_name\":\"lets_blog\"}}");
        when(siteService.runLetsblogSns(3L, "log", "x", null)).thenReturn(
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
        when(siteService.runLetsblogSns(3L, "status", "x", null))
                .thenReturn("{\"x\":{\"status\":\"未設定\",\"account_name\":null}}")
                .thenReturn("{\"x\":{\"status\":\"要再接続\",\"account_name\":\"a\"}}");
        when(siteService.runLetsblogSns(3L, "log", "x", null)).thenReturn("[]");

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
        when(siteService.runLetsblogSns(3L, "status", "x", null))
                .thenReturn("{\"x\":{\"status\":\"接続済み\",\"account_name\":null}}");
        StringBuilder log = new StringBuilder("[{\"sns\":\"bluesky\",\"kind\":\"test\",\"at\":\"a\",\"success\":true,\"error\":null}");
        for (int i = 0; i < 25; i++) {
            log.append(",{\"sns\":\"x\",\"kind\":\"k").append(i).append("\",\"at\":\"a\",\"success\":true,\"error\":null}");
        }
        log.append("]");
        when(siteService.runLetsblogSns(3L, "log", "x", null)).thenReturn(log.toString());

        XConnectionView view = service.view(7L);

        assertEquals(20, view.log().entries().size());
        assertEquals("k24", view.log().entries().get(0).kind());
        assertEquals("k5", view.log().entries().get(19).kind());
    }

    @Test
    void view_状態の取得だけ失敗しても履歴は返す() {
        connectableSite();
        when(siteService.runLetsblogSns(3L, "status", "x", null)).thenThrow(new IllegalStateException("接続失敗"));
        when(siteService.runLetsblogSns(3L, "log", "x", null)).thenReturn("[]");

        XConnectionView view = service.view(7L);

        assertTrue(view.connectable());
        assertFalse(view.status().available());
        assertTrue(view.status().error().contains("接続失敗"));
        assertTrue(view.log().available());
    }

    @Test
    void view_履歴の取得だけ失敗しても状態は返す() {
        connectableSite();
        when(siteService.runLetsblogSns(3L, "status", "x", null))
                .thenReturn("{\"x\":{\"status\":\"接続済み\",\"account_name\":\"a\"}}");
        when(siteService.runLetsblogSns(3L, "log", "x", null)).thenThrow(new IllegalStateException("届かない"));

        XConnectionView view = service.view(7L);

        assertTrue(view.status().available());
        assertFalse(view.log().available());
        assertTrue(view.log().error().contains("届かない"));
    }

    @Test
    void view_状態の出力を解釈できなければ取得できないとして返す() {
        connectableSite();
        when(siteService.runLetsblogSns(3L, "status", "x", null))
                .thenReturn("garbage")
                .thenReturn("{\"bluesky\":{\"status\":\"接続済み\"}}")
                .thenReturn("{\"x\":{\"status\":\"謎\"}}");
        when(siteService.runLetsblogSns(3L, "log", "x", null)).thenReturn("{\"not\":\"an array\"}");

        XConnectionView first = service.view(7L);
        assertFalse(first.status().available());
        assertFalse(first.log().available());
        assertFalse(service.view(7L).status().available());
        assertFalse(service.view(7L).status().available());
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
    void startAuthorization_本番サイトが無ければ理由つきで拒否し認可を始めない() {
        projectWithProductionSite(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.startAuthorization(7L, "cid", "csecret", "https://l/cb"));

        assertTrue(e.getMessage().contains("本番サイト"));
        verifyNoInteractions(store);
    }

    @Test
    void startAuthorization_プラグインが導入済みでなければ理由つきで拒否する() {
        projectWithProductionSite(3L);
        siteExists(3L);
        pluginState(3L, State.NOT_INSTALLED);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.startAuthorization(7L, "cid", "csecret", "https://l/cb"));

        assertTrue(e.getMessage().contains("未導入"));
        verifyNoInteractions(store);
    }

    @Test
    void startAuthorization_プラグインの状態を取れなければ拒否する() {
        projectWithProductionSite(3L);
        siteExists(3L);
        when(siteService.getLetsblogPluginStatus(3L)).thenThrow(new IllegalStateException("接続失敗"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.startAuthorization(7L, "cid", "csecret", "https://l/cb"));

        assertTrue(e.getMessage().contains("届かない"));
    }

    @Test
    void startAuthorization_認可を保存してXの認可URLを返す() {
        connectableSite();
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");
        XAuthorizationStore.Pending pending = new XAuthorizationStore.Pending(
                "7.abc", 7L, 3L, "sub-1", "cid", "csecret", "https://l/cb", "verifier", NOW.plusSeconds(600));
        when(store.create(7L, 3L, "sub-1", "cid", "csecret", "https://l/cb")).thenReturn(pending);
        when(xApiClient.authorizeUrl("cid", "https://l/cb", "7.abc", pending.codeChallenge()))
                .thenReturn("https://x.example/authorize?state=7.abc");

        assertEquals("https://x.example/authorize?state=7.abc",
                service.startAuthorization(7L, "cid", "csecret", "https://l/cb"));
    }

    // ---- completeAuthorization ----

    private XAuthorizationStore.Pending pending() {
        return new XAuthorizationStore.Pending(
                "7.abc", 7L, 3L, "sub-1", "cid", "csecret", "https://l/cb", "verifier", NOW.plusSeconds(600));
    }

    private void pendingFor(XAuthorizationStore.Pending pending) {
        when(store.take("7.abc")).thenReturn(Optional.of(pending));
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");
    }

    @Test
    void completeAuthorization_トークンを本番サイトへ標準入力で送りアカウント名だけを返す() throws Exception {
        pendingFor(pending());
        connectableSite();
        when(xApiClient.exchangeCode("cid", "csecret", "the-code", "https://l/cb", "verifier"))
                .thenReturn(new XTokens("ACCESS-TOKEN", "REFRESH-TOKEN", 7200L));
        when(xApiClient.fetchUsername("ACCESS-TOKEN")).thenReturn("lets_blog");
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("x"), anyString()))
                .thenReturn("{\"sns\":\"x\",\"status\":\"接続済み\"}");

        XConnectResult result = service.completeAuthorization(7L, "7.abc", "the-code");

        assertEquals(7L, result.projectId());
        assertEquals("lets_blog", result.accountName());
        ArgumentCaptor<String> stdin = ArgumentCaptor.forClass(String.class);
        verify(siteService).runLetsblogSns(eq(3L), eq("config-set"), eq("x"), stdin.capture());
        JsonNode sent = mapper.readTree(stdin.getValue());
        assertEquals("x", sent.path("sns").asText());
        assertEquals("cid", sent.path("client_id").asText());
        assertEquals("csecret", sent.path("client_secret").asText());
        assertEquals("ACCESS-TOKEN", sent.path("access_token").asText());
        assertEquals("REFRESH-TOKEN", sent.path("refresh_token").asText());
        assertEquals(NOW.getEpochSecond() + 7200L, sent.path("expires_at").asLong());
        assertEquals("lets_blog", sent.path("account_name").asText());
        // 応答のオブジェクトにトークンもクライアントの秘密も載らない。
        String json = mapper.writeValueAsString(result);
        assertFalse(json.contains("ACCESS-TOKEN"));
        assertFalse(json.contains("REFRESH-TOKEN"));
        assertFalse(json.contains("csecret"));
    }

    @Test
    void completeAuthorization_知らないstateや期限切れは拒否する() {
        when(store.take("7.abc")).thenReturn(Optional.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("もう一度"));
        verifyNoInteractions(xApiClient);
    }

    @Test
    void completeAuthorization_別のプロジェクトのstateは拒否する() {
        pendingFor(pending());

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(8L, "7.abc", "code"));
        verifyNoInteractions(xApiClient);
    }

    @Test
    void completeAuthorization_認可を始めた本人以外は拒否する() {
        when(store.take("7.abc")).thenReturn(Optional.of(pending()));
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("someone-else");

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(7L, "7.abc", "code"));
        verifyNoInteractions(xApiClient);
    }

    @Test
    void completeAuthorization_その間に本番サイトが無くなっていたら拒否する() {
        pendingFor(pending());
        projectWithProductionSite(null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("本番サイト"));
        verifyNoInteractions(xApiClient);
    }

    @Test
    void completeAuthorization_トークン交換が失敗したら接続失敗として返す() {
        pendingFor(pending());
        connectableSite();
        when(xApiClient.exchangeCode(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new XApiException("X のトークン交換に失敗しました(HTTP 400)"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("HTTP 400"));
        verify(siteService, never()).runLetsblogSns(any(), anyString(), any(), any());
    }

    @Test
    void completeAuthorization_アカウント名を取れなければ接続失敗として返し送らない() {
        pendingFor(pending());
        connectableSite();
        when(xApiClient.exchangeCode(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new XTokens("AT", "RT", 7200L));
        when(xApiClient.fetchUsername("AT")).thenThrow(new XApiException("X の自分の情報の取得に失敗しました(HTTP 401)"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("HTTP 401"));
        verify(siteService, never()).runLetsblogSns(any(), anyString(), any(), any());
    }

    @Test
    void completeAuthorization_サイトへ送れなければ接続失敗として返し_トークンを含めない() {
        pendingFor(pending());
        connectableSite();
        when(xApiClient.exchangeCode(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new XTokens("ACCESS-TOKEN", "REFRESH-TOKEN", 7200L));
        when(xApiClient.fetchUsername("ACCESS-TOKEN")).thenReturn("lets_blog");
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("x"), anyString()))
                .thenThrow(new IllegalStateException("エージェントへの接続に失敗しました"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("接続失敗"));
        assertTrue(e.getMessage().contains("エージェントへの接続に失敗しました"));
        assertFalse(e.getMessage().contains("ACCESS-TOKEN"));
        assertFalse(e.getMessage().contains("REFRESH-TOKEN"));
    }

    @Test
    void completeAuthorization_サイトが接続済みと答えなければ接続失敗として返す() {
        pendingFor(pending());
        connectableSite();
        when(xApiClient.exchangeCode(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new XTokens("AT", "RT", 7200L));
        when(xApiClient.fetchUsername("AT")).thenReturn("lets_blog");
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("x"), anyString()))
                .thenReturn("{\"sns\":\"x\",\"status\":\"要再接続\"}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("接続失敗"));
    }

    @Test
    void completeAuthorization_サイトの出力を解釈できなければ接続失敗として返す() {
        pendingFor(pending());
        connectableSite();
        when(xApiClient.exchangeCode(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new XTokens("AT", "RT", 7200L));
        when(xApiClient.fetchUsername("AT")).thenReturn("lets_blog");
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("x"), anyString())).thenReturn("garbage");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

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
        when(siteService.runLetsblogSns(3L, "test", "x", null))
                .thenReturn("{\"sns\":\"x\",\"kind\":\"test\",\"success\":true}");

        XTestResult result = service.test(7L);

        assertTrue(result.success());
        assertNull(result.error());
    }

    @Test
    void test_テスト投稿が失敗したら理由を返す() {
        connectableSite();
        when(siteService.runLetsblogSns(3L, "test", "x", null))
                .thenThrow(new IllegalStateException("X の投稿に失敗しました(HTTP 403)"));

        XTestResult result = service.test(7L);

        assertFalse(result.success());
        assertTrue(result.error().contains("HTTP 403"));
    }

    // ---- onProductionSiteChanged ----

    @Test
    void onProductionSiteChanged_旧サイトの設定を消す() {
        service.onProductionSiteChanged(5L, 10L);

        verify(siteService).runLetsblogSns(5L, "config-clear", null, null);
    }

    @Test
    void onProductionSiteChanged_本番を外したときも旧サイトを消す() {
        service.onProductionSiteChanged(5L, null);

        verify(siteService).runLetsblogSns(5L, "config-clear", null, null);
    }

    @Test
    void onProductionSiteChanged_旧サイトが無い_または同じなら何もしない() {
        service.onProductionSiteChanged(null, 10L);
        service.onProductionSiteChanged(5L, 5L);

        verifyNoInteractions(siteService);
    }

    @Test
    void onProductionSiteChanged_消せなくても例外にしない() {
        doThrow(new IllegalStateException("届かない")).when(siteService).runLetsblogSns(5L, "config-clear", null, null);

        service.onProductionSiteChanged(5L, 10L);

        verify(siteService).runLetsblogSns(5L, "config-clear", null, null);
    }

    // ---- issue #1579: 状態・履歴・テスト投稿・接続可否の判定は SNS を指定して Threads でも使う ----

    @Test
    void view_SNSを指定するとその状態と履歴だけを返す() {
        connectableSite();
        when(siteService.runLetsblogSns(3L, "status", "threads", null))
                .thenReturn("{\"x\":{\"status\":\"未設定\",\"account_name\":null},"
                        + "\"threads\":{\"status\":\"接続済み\",\"account_name\":\"th_user\"}}");
        when(siteService.runLetsblogSns(3L, "log", "threads", null)).thenReturn(
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
        when(siteService.runLetsblogSns(3L, "test", "threads", null)).thenReturn("{}");

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
}
