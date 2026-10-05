package com.letsblog.project.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.project.config.GlobalExceptionHandler;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.service.SnsXService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** プロジェクト設定画面の X 接続の API(issue #1574)。応答にトークンもクライアントの秘密も載らない。 */
@ExtendWith(MockitoExtension.class)
class ProjectSnsControllerTest {

    @Mock
    private SnsXService snsXService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectSnsController(snsXService, adminAuthorizationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 接続状態を返す() throws Exception {
        XConnectionView view = new XConnectionView(true, null, "本番サイト",
                new XConnectionView.Status(true, XConnectionView.State.CONNECTED, "lets_blog", null),
                new XConnectionView.Log(true, List.of(new XConnectionView.Entry("test", true, null, "2026-10-04T00:00:00+00:00")), null));
        when(snsXService.view(7L)).thenReturn(view);

        mockMvc.perform(get("/api/projects/7/sns/x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectable").value(true))
                .andExpect(jsonPath("$.siteName").value("本番サイト"))
                .andExpect(jsonPath("$.status.state").value("CONNECTED"))
                .andExpect(jsonPath("$.status.accountName").value("lets_blog"))
                .andExpect(jsonPath("$.log.entries[0].kind").value("test"));
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
    }

    @Test
    void メンバーでもadminでもなければ状態も読めない() throws Exception {
        doThrow(new ForbiddenException("権限がありません")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        mockMvc.perform(get("/api/projects/7/sns/x")).andExpect(status().isForbidden());

        verifyNoInteractions(snsXService);
    }

    @Test
    void 接続と投稿は管理者だけで_メンバーは拒否し何も実行しない() throws Exception {
        doThrow(new ForbiddenException("権限がありません")).when(adminAuthorizationService).requireAdmin();

        mockMvc.perform(post("/api/projects/7/sns/x/test")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/x/authorize").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"c\",\"clientSecret\":\"s\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/x/callback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"7.a\",\"code\":\"c\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(snsXService);
    }

    @Test
    void 認可を始めるとXの認可URLだけを返す() throws Exception {
        when(snsXService.startAuthorization(7L, "cid", "csecret", "https://l/cb"))
                .thenReturn("https://x.example/authorize?state=7.abc");

        mockMvc.perform(post("/api/projects/7/sns/x/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"cid\",\"clientSecret\":\"csecret\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizeUrl").value("https://x.example/authorize?state=7.abc"))
                .andExpect(content().string(not(containsString("csecret"))));
    }

    @Test
    void 認可の入力が欠けていれば400() throws Exception {
        mockMvc.perform(post("/api/projects/7/sns/x/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"\",\"clientSecret\":\"s\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(snsXService);
    }

    @Test
    void 接続できない理由は409で返す() throws Exception {
        when(snsXService.startAuthorization(7L, "cid", "csecret", "https://l/cb"))
                .thenThrow(new IllegalStateException("本番サイトが設定されていません"));

        mockMvc.perform(post("/api/projects/7/sns/x/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"cid\",\"clientSecret\":\"csecret\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("本番サイト")));
    }

    @Test
    void コールバックはアカウント名だけを返す() throws Exception {
        when(snsXService.completeAuthorization(7L, "7.abc", "the-code")).thenReturn(new XConnectResult(7L, "lets_blog"));

        mockMvc.perform(post("/api/projects/7/sns/x/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.abc\",\"code\":\"the-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("lets_blog"))
                .andExpect(content().string(not(containsString("token"))));
    }

    @Test
    void コールバックの入力が欠けていれば400() throws Exception {
        mockMvc.perform(post("/api/projects/7/sns/x/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.abc\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(snsXService);
    }

    @Test
    void テスト投稿の結果を返す() throws Exception {
        when(snsXService.test(7L)).thenReturn(new XTestResult(false, "X の投稿に失敗しました"));

        mockMvc.perform(post("/api/projects/7/sns/x/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("X の投稿に失敗しました"));
    }

    @Test
    void 応答を直列化してもトークンのフィールドは存在しない() throws Exception {
        String json = new ObjectMapper().writeValueAsString(new XConnectResult(7L, "lets_blog"));

        org.junit.jupiter.api.Assertions.assertEquals("{\"projectId\":7,\"accountName\":\"lets_blog\"}", json);
    }
}
