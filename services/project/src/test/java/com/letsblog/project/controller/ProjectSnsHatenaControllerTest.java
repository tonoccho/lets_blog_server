package com.letsblog.project.controller;

import com.letsblog.project.config.GlobalExceptionHandler;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.dto.XConnectionView;
import com.letsblog.project.dto.XTestResult;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.service.SnsHatenaService;
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

/** プロジェクト設定画面の はてなブックマーク 接続の API(issue #1582)。応答にトークンもクライアントの秘密も載らない。 */
@ExtendWith(MockitoExtension.class)
class ProjectSnsHatenaControllerTest {

    @Mock
    private SnsHatenaService snsHatenaService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProjectSnsHatenaController(snsHatenaService, adminAuthorizationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 接続状態を返す() throws Exception {
        XConnectionView view = new XConnectionView(true, null, "本番サイト",
                new XConnectionView.Status(true, XConnectionView.State.CONNECTED, "lets_blog", null),
                new XConnectionView.Log(true, List.of(new XConnectionView.Entry("test", true, null, "2026-10-04T00:00:00+00:00")), null));
        when(snsHatenaService.view(7L)).thenReturn(view);

        mockMvc.perform(get("/api/projects/7/sns/hatena"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectable").value(true))
                .andExpect(jsonPath("$.status.state").value("CONNECTED"))
                .andExpect(jsonPath("$.status.accountName").value("lets_blog"))
                .andExpect(jsonPath("$.log.entries[0].kind").value("test"));
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
    }

    @Test
    void メンバーでもadminでもなければ状態も読めない() throws Exception {
        doThrow(new ForbiddenException("権限がありません")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        mockMvc.perform(get("/api/projects/7/sns/hatena")).andExpect(status().isForbidden());

        verifyNoInteractions(snsHatenaService);
    }

    @Test
    void 接続と切断と投稿は管理者だけで_拒否したら何も実行しない() throws Exception {
        doThrow(new ForbiddenException("権限がありません")).when(adminAuthorizationService).requireAdmin();

        mockMvc.perform(post("/api/projects/7/sns/hatena/test")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/projects/7/sns/hatena")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/hatena/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"c\",\"clientSecret\":\"s\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/hatena/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.a\",\"oauthToken\":\"t\",\"oauthVerifier\":\"v\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(snsHatenaService);
    }

    @Test
    void 認可を始めると認可URLだけを返す() throws Exception {
        when(snsHatenaService.startAuthorization(7L, "cid", "csecret", "https://l/cb"))
                .thenReturn("https://hatena.example/authorize?state=7.abc");

        mockMvc.perform(post("/api/projects/7/sns/hatena/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"cid\",\"clientSecret\":\"csecret\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizeUrl").value("https://hatena.example/authorize?state=7.abc"))
                .andExpect(content().string(not(containsString("csecret"))));
    }

    @Test
    void 認可の入力が欠けていれば400() throws Exception {
        mockMvc.perform(post("/api/projects/7/sns/hatena/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"\",\"clientSecret\":\"s\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(snsHatenaService);
    }

    @Test
    void 接続できない理由は409で返す() throws Exception {
        when(snsHatenaService.startAuthorization(7L, "cid", "csecret", "https://l/cb"))
                .thenThrow(new IllegalStateException("本番サイトが設定されていません"));

        mockMvc.perform(post("/api/projects/7/sns/hatena/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"cid\",\"clientSecret\":\"csecret\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("本番サイト")));
    }

    @Test
    void コールバックはアカウント名だけを返す() throws Exception {
        when(snsHatenaService.completeAuthorization(7L, "7.abc", "request-token", "the-verifier")).thenReturn(new XConnectResult(7L, "lets_blog"));

        mockMvc.perform(post("/api/projects/7/sns/hatena/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.abc\",\"oauthToken\":\"request-token\",\"oauthVerifier\":\"the-verifier\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("lets_blog"))
                .andExpect(content().string(not(containsString("token"))));
    }

    @Test
    void コールバックの入力が欠けていれば400() throws Exception {
        mockMvc.perform(post("/api/projects/7/sns/hatena/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.abc\",\"oauthToken\":\"request-token\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/projects/7/sns/hatena/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.abc\",\"oauthVerifier\":\"the-verifier\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(snsHatenaService);
    }

    @Test
    void テスト投稿の結果を返す() throws Exception {
        when(snsHatenaService.test(7L)).thenReturn(new XTestResult(false, "はてなブックマークの投稿に失敗しました"));

        mockMvc.perform(post("/api/projects/7/sns/hatena/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("はてなブックマークの投稿に失敗しました"));
    }

    @Test
    void 切断すると本文なしで204を返す() throws Exception {
        mockMvc.perform(delete("/api/projects/7/sns/hatena")).andExpect(status().isNoContent());

        verify(adminAuthorizationService).requireAdmin();
        verify(snsHatenaService).disconnect(7L);
    }

    @Test
    void 切断できない理由は409で返す() throws Exception {
        doThrow(new IllegalStateException("本番サイトのプラグインが未導入です")).when(snsHatenaService).disconnect(7L);

        mockMvc.perform(delete("/api/projects/7/sns/hatena"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("未導入")));
    }
}
