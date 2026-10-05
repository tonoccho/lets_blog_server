package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.project.client.FacebookApiClient;
import com.letsblog.project.client.FacebookApiException;
import com.letsblog.project.dto.FacebookPagesView;
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
import java.util.List;
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
 * プロジェクトの Facebook ページ接続(issue #1580。Epic #1572)。個人アカウントには投稿しないので、認可のあとに
 * 利用者が投稿先のページを選び、そのページのトークンだけを本番サイトのプラグインへ wp-cli(標準入力の JSON)で送る。
 * アプリはトークンを保存しない・API で返さない。
 */
@ExtendWith(MockitoExtension.class)
class SnsFacebookServiceTest {

    private static final Instant LATER = Instant.parse("2026-10-06T01:00:00Z");

    @Mock
    private SnsXService snsXService;
    @Mock
    private SiteService siteService;
    @Mock
    private FacebookApiClient facebookApiClient;
    @Mock
    private XAuthorizationStore store;
    @Mock
    private FacebookPageSelectionStore selectionStore;
    @Mock
    private CurrentActorService currentActorService;

    private final ObjectMapper mapper = new ObjectMapper();
    private SnsFacebookService service;

    private final List<FacebookApiClient.Page> pages = List.of(
            new FacebookApiClient.Page("100", "公式ページ", "PAGE-TOKEN-100"),
            new FacebookApiClient.Page("200", "別のページ", "PAGE-TOKEN-200"));

    @BeforeEach
    void setUp() {
        service = new SnsFacebookService(snsXService, siteService, facebookApiClient, store, selectionStore,
                currentActorService, mapper);
    }

    private XAuthorizationStore.Pending pending() {
        return new XAuthorizationStore.Pending(
                "7.abc", 7L, 3L, "sub-1", "app-id", "app-secret", "https://l/cb", "verifier", LATER);
    }

    private FacebookPageSelectionStore.Selection selection(String actor) {
        return new FacebookPageSelectionStore.Selection("7.abc", 7L, 3L, actor, pages, LATER);
    }

    // ---- view / test ----

    @Test
    void view_Facebookの接続状態を返す() {
        XConnectionView view = new XConnectionView(true, null, "本番サイト", null, null);
        when(snsXService.view(7L, "facebook")).thenReturn(view);

        assertSame(view, service.view(7L));
    }

    @Test
    void test_Facebookへのテスト投稿を依頼する() {
        XTestResult result = new XTestResult(true, null);
        when(snsXService.test(7L, "facebook")).thenReturn(result);

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
    void startAuthorization_認可を保存してFacebookの認可URLを返す() {
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");
        when(store.create(7L, 3L, "sub-1", "app-id", "app-secret", "https://l/cb")).thenReturn(pending());
        when(facebookApiClient.authorizeUrl("app-id", "https://l/cb", "7.abc"))
                .thenReturn("https://facebook.example/dialog/oauth?state=7.abc");

        assertEquals("https://facebook.example/dialog/oauth?state=7.abc",
                service.startAuthorization(7L, "app-id", "app-secret", "https://l/cb"));
    }

    // ---- completeAuthorization ----

    private void pendingFor(XAuthorizationStore.Pending pending) {
        when(store.take("7.abc")).thenReturn(Optional.of(pending));
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");
    }

    private void exchanges() {
        when(facebookApiClient.exchangeCode("app-id", "app-secret", "the-code", "https://l/cb")).thenReturn("USER-SHORT");
        when(facebookApiClient.exchangeLongLived("app-id", "app-secret", "USER-SHORT")).thenReturn("USER-LONG");
    }

    @Test
    void completeAuthorization_管理しているページを保存して選べる一覧だけを返す() throws Exception {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        exchanges();
        when(facebookApiClient.listPages("USER-LONG")).thenReturn(pages);

        FacebookPagesView result = service.completeAuthorization(7L, "7.abc", "the-code");

        assertEquals(7L, result.projectId());
        assertEquals(2, result.pages().size());
        assertEquals("100", result.pages().get(0).id());
        assertEquals("公式ページ", result.pages().get(0).name());
        verify(selectionStore).save("7.abc", 7L, 3L, "sub-1", pages);
        // ページを選ぶまでは、本番サイトへは何も送らない。
        verifyNoInteractions(siteService);
        String json = mapper.writeValueAsString(result);
        assertFalse(json.contains("PAGE-TOKEN"));
        assertFalse(json.contains("USER-LONG"));
        assertFalse(json.contains("app-secret"));
    }

    @Test
    void completeAuthorization_管理しているページが無ければ理由つきで拒否し保存しない() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        exchanges();
        when(facebookApiClient.listPages("USER-LONG")).thenReturn(List.of());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "the-code"));

        assertTrue(e.getMessage().contains("ページ"));
        verify(selectionStore, never()).save(anyString(), any(), any(), any(), any());
    }

    @Test
    void completeAuthorization_知らないstateや期限切れは拒否する() {
        when(store.take("7.abc")).thenReturn(Optional.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("もう一度"));
        verifyNoInteractions(facebookApiClient);
    }

    @Test
    void completeAuthorization_別のプロジェクトのstateは拒否する() {
        pendingFor(pending());

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(8L, "7.abc", "code"));
        verifyNoInteractions(facebookApiClient);
    }

    @Test
    void completeAuthorization_認可を始めた本人以外は拒否する() {
        when(store.take("7.abc")).thenReturn(Optional.of(pending()));
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("someone-else");

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(7L, "7.abc", "code"));
        verifyNoInteractions(facebookApiClient);
    }

    @Test
    void completeAuthorization_認可を始めた本人が不明なstateは拒否する() {
        pendingFor(new XAuthorizationStore.Pending(
                "7.abc", 7L, 3L, null, "app-id", "app-secret", "https://l/cb", "verifier", LATER));

        assertThrows(IllegalArgumentException.class, () -> service.completeAuthorization(7L, "7.abc", "code"));
    }

    @Test
    void completeAuthorization_その間に接続できなくなっていたら拒否する() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenThrow(new IllegalStateException("本番サイトが設定されていません"));

        assertThrows(IllegalStateException.class, () -> service.completeAuthorization(7L, "7.abc", "code"));
        verifyNoInteractions(facebookApiClient);
    }

    @Test
    void completeAuthorization_Facebookとの通信が失敗したら接続失敗として返し_秘密を含めない() {
        pendingFor(pending());
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);
        when(facebookApiClient.exchangeCode(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new FacebookApiException("Facebook のトークン交換に失敗しました(HTTP 400)"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.completeAuthorization(7L, "7.abc", "code"));

        assertTrue(e.getMessage().contains("HTTP 400"));
        assertFalse(e.getMessage().contains("app-secret"));
        verifyNoInteractions(siteService);
    }

    // ---- pages ----

    @Test
    void pages_本人が自分の選択肢を一覧できる() {
        when(selectionStore.find("7.abc")).thenReturn(Optional.of(selection("sub-1")));
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");

        FacebookPagesView view = service.pages(7L, "7.abc");

        assertEquals(List.of("100", "200"), view.pages().stream().map(p -> p.id()).toList());
    }

    @Test
    void pages_知らないstate_別プロジェクト_別の人は拒否する() {
        when(selectionStore.find("nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.pages(7L, "nope"));

        when(selectionStore.find("7.abc")).thenReturn(Optional.of(selection("sub-1")));
        assertThrows(IllegalArgumentException.class, () -> service.pages(8L, "7.abc"));

        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("someone-else");
        assertThrows(IllegalArgumentException.class, () -> service.pages(7L, "7.abc"));
    }

    // ---- selectPage ----

    @Test
    void selectPage_選んだページのトークンだけを本番サイトへ標準入力で送りページ名を返す() throws Exception {
        when(selectionStore.find("7.abc")).thenReturn(Optional.of(selection("sub-1")));
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("facebook"), anyString()))
                .thenReturn("{\"sns\":\"facebook\",\"status\":\"接続済み\"}");

        XConnectResult result = service.selectPage(7L, "7.abc", "100");

        assertEquals(7L, result.projectId());
        assertEquals("公式ページ", result.accountName());
        ArgumentCaptor<String> stdin = ArgumentCaptor.forClass(String.class);
        verify(siteService).runLetsblogSns(eq(3L), eq("config-set"), eq("facebook"), stdin.capture());
        JsonNode sent = mapper.readTree(stdin.getValue());
        assertEquals("facebook", sent.path("sns").asText());
        assertEquals("PAGE-TOKEN-100", sent.path("access_token").asText());
        assertEquals("100", sent.path("page_id").asText());
        assertEquals("公式ページ", sent.path("account_name").asText());
        // 別のページのトークンやアプリの秘密・ユーザーのトークンは送らない。
        assertFalse(stdin.getValue().contains("PAGE-TOKEN-200"));
        assertFalse(stdin.getValue().contains("app-secret"));
        assertFalse(mapper.writeValueAsString(result).contains("PAGE-TOKEN"));
        verify(selectionStore).remove("7.abc");
    }

    @Test
    void selectPage_選択肢に無いページは拒否し何も送らない() {
        when(selectionStore.find("7.abc")).thenReturn(Optional.of(selection("sub-1")));
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.selectPage(7L, "7.abc", "999"));

        assertTrue(e.getMessage().contains("ページ"));
        verifyNoInteractions(siteService);
        verify(selectionStore, never()).remove(anyString());
    }

    @Test
    void selectPage_知らないstate_別プロジェクト_別の人は拒否する() {
        when(selectionStore.find("nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.selectPage(7L, "nope", "100"));

        when(selectionStore.find("7.abc")).thenReturn(Optional.of(selection("sub-1")));
        assertThrows(IllegalArgumentException.class, () -> service.selectPage(8L, "7.abc", "100"));

        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("someone-else");
        assertThrows(IllegalArgumentException.class, () -> service.selectPage(7L, "7.abc", "100"));
        verifyNoInteractions(siteService);
    }

    @Test
    void selectPage_サイトへ送れなければ接続失敗として返し_選択肢は残す() {
        when(selectionStore.find("7.abc")).thenReturn(Optional.of(selection("sub-1")));
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("facebook"), anyString()))
                .thenThrow(new IllegalStateException("エージェントへの接続に失敗しました"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.selectPage(7L, "7.abc", "100"));

        assertTrue(e.getMessage().contains("接続失敗"));
        assertFalse(e.getMessage().contains("PAGE-TOKEN"));
        verify(selectionStore, never()).remove(anyString());
    }

    @Test
    void selectPage_サイトが接続済みと答えなければ接続失敗として返す() {
        when(selectionStore.find("7.abc")).thenReturn(Optional.of(selection("sub-1")));
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("facebook"), anyString()))
                .thenReturn("{\"sns\":\"facebook\",\"status\":\"要再接続\"}");

        assertTrue(assertThrows(IllegalStateException.class, () -> service.selectPage(7L, "7.abc", "100"))
                .getMessage().contains("接続失敗"));
    }

    @Test
    void selectPage_サイトの出力を解釈できなければ接続失敗として返す() {
        when(selectionStore.find("7.abc")).thenReturn(Optional.of(selection("sub-1")));
        when(currentActorService.getCurrentActorKeycloakSub()).thenReturn("sub-1");
        when(siteService.runLetsblogSns(eq(3L), eq("config-set"), eq("facebook"), anyString())).thenReturn("garbage");

        assertTrue(assertThrows(IllegalStateException.class, () -> service.selectPage(7L, "7.abc", "100"))
                .getMessage().contains("接続失敗"));
    }

    // ---- disconnect ----

    @Test
    void disconnect_本番サイトのプラグインからFacebookの設定だけを消す() {
        when(snsXService.requireConnectableSiteId(7L)).thenReturn(3L);

        service.disconnect(7L);

        verify(siteService).runLetsblogSns(3L, "config-clear", "facebook", null);
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
        when(siteService.runLetsblogSns(3L, "config-clear", "facebook", null)).thenThrow(new IllegalStateException("届かない"));

        assertTrue(assertThrows(IllegalStateException.class, () -> service.disconnect(7L)).getMessage().contains("届かない"));
    }
}
