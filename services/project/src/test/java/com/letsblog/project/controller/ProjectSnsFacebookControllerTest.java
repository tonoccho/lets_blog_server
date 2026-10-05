package com.letsblog.project.controller;

import com.letsblog.project.config.GlobalExceptionHandler;
import com.letsblog.project.dto.FacebookPageView;
import com.letsblog.project.dto.FacebookPagesView;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.service.SnsFacebookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** プロジェクト設定画面の Facebook ページ接続の API(issue #1580)。応答にトークンもクライアントの秘密も載らない。 */
@ExtendWith(MockitoExtension.class)
class ProjectSnsFacebookControllerTest {

    @Mock
    private SnsFacebookService snsFacebookService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProjectSnsFacebookController(snsFacebookService, adminAuthorizationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 接続状態を返す() throws Exception {
        XConnectionView view = new XConnectionView(true, null, "本番サイト",
                new XConnectionView.Status(true, XConnectionView.State.CONNECTED, "公式ページ", null),
                new XConnectionView.Log(true, List.of(new XConnectionView.Entry("test", true, null, "2026-10-06T00:00:00+00:00")), null));
        when(snsFacebookService.view(7L)).thenReturn(view);

        mockMvc.perform(get("/api/projects/7/sns/facebook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.state").value("CONNECTED"))
                .andExpect(jsonPath("$.status.accountName").value("公式ページ"))
                .andExpect(jsonPath("$.log.entries[0].kind").value("test"));
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
    }

    @Test
    void メンバーでもadminでもなければ状態も読めない() throws Exception {
        doThrow(new ForbiddenException("権限がありません")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        mockMvc.perform(get("/api/projects/7/sns/facebook")).andExpect(status().isForbidden());

        verifyNoInteractions(snsFacebookService);
    }

    @Test
    void 接続と選択と切断と投稿は管理者だけで_拒否したら何も実行しない() throws Exception {
        doThrow(new ForbiddenException("権限がありません")).when(adminAuthorizationService).requireAdmin();

        mockMvc.perform(post("/api/projects/7/sns/facebook/test")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/projects/7/sns/facebook")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/projects/7/sns/facebook/pages").param("state", "7.a")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/facebook/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"c\",\"clientSecret\":\"s\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/facebook/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.a\",\"code\":\"c\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/facebook/page").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.a\",\"pageId\":\"100\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(snsFacebookService);
    }

    @Test
    void 認可を始めると認可URLだけを返す() throws Exception {
        when(snsFacebookService.startAuthorization(7L, "cid", "csecret", "https://l/cb"))
                .thenReturn("https://facebook.example/dialog/oauth?state=7.abc");

        mockMvc.perform(post("/api/projects/7/sns/facebook/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"cid\",\"clientSecret\":\"csecret\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizeUrl").value("https://facebook.example/dialog/oauth?state=7.abc"))
                .andExpect(content().string(not(containsString("csecret"))));
    }

    @Test
    void 認可の入力が欠けていれば400() throws Exception {
        mockMvc.perform(post("/api/projects/7/sns/facebook/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"\",\"clientSecret\":\"s\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(snsFacebookService);
    }

    @Test
    void 接続できない理由は409で返す() throws Exception {
        when(snsFacebookService.startAuthorization(7L, "cid", "csecret", "https://l/cb"))
                .thenThrow(new IllegalStateException("本番サイトが設定されていません"));

        mockMvc.perform(post("/api/projects/7/sns/facebook/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"cid\",\"clientSecret\":\"csecret\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("本番サイト")));
    }

    @Test
    void コールバックは選べるページの一覧だけを返す() throws Exception {
        when(snsFacebookService.completeAuthorization(7L, "7.abc", "the-code"))
                .thenReturn(new FacebookPagesView(7L, List.of(new FacebookPageView("100", "ページA"))));

        mockMvc.perform(post("/api/projects/7/sns/facebook/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.abc\",\"code\":\"the-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages[0].id").value("100"))
                .andExpect(jsonPath("$.pages[0].name").value("ページA"))
                .andExpect(content().string(not(containsString("token"))));
    }

    @Test
    void コールバックの入力が欠けていれば400() throws Exception {
        mockMvc.perform(post("/api/projects/7/sns/facebook/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.abc\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(snsFacebookService);
    }

    @Test
    void 選べるページの一覧を返す() throws Exception {
        when(snsFacebookService.pages(7L, "7.abc"))
                .thenReturn(new FacebookPagesView(7L, List.of(new FacebookPageView("100", "ページA"))));

        mockMvc.perform(get("/api/projects/7/sns/facebook/pages").param("state", "7.abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages[0].name").value("ページA"))
                .andExpect(content().string(not(containsString("token"))));
    }

    @Test
    void ページを選ぶとページ名だけを返す() throws Exception {
        when(snsFacebookService.selectPage(7L, "7.abc", "100")).thenReturn(new XConnectResult(7L, "ページA"));

        mockMvc.perform(post("/api/projects/7/sns/facebook/page").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.abc\",\"pageId\":\"100\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("ページA"))
                .andExpect(content().string(not(containsString("token"))));
    }

    @Test
    void ページの選択の入力が欠けていれば400() throws Exception {
        mockMvc.perform(post("/api/projects/7/sns/facebook/page").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.abc\",\"pageId\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(snsFacebookService);
    }

    @Test
    void テスト投稿の結果を返す() throws Exception {
        when(snsFacebookService.test(7L)).thenReturn(new XTestResult(false, "Facebook の投稿に失敗しました"));

        mockMvc.perform(post("/api/projects/7/sns/facebook/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Facebook の投稿に失敗しました"));
    }

    @Test
    void 切断すると本文なしで204を返す() throws Exception {
        mockMvc.perform(delete("/api/projects/7/sns/facebook")).andExpect(status().isNoContent());

        verify(adminAuthorizationService).requireAdmin();
        verify(snsFacebookService).disconnect(7L);
    }

    @Test
    void 切断できない理由は409で返す() throws Exception {
        doThrow(new IllegalStateException("本番サイトのプラグインが未導入です")).when(snsFacebookService).disconnect(7L);

        mockMvc.perform(delete("/api/projects/7/sns/facebook"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("未導入")));
    }
}
