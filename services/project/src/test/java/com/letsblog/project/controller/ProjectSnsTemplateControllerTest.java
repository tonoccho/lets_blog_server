package com.letsblog.project.controller;

import com.letsblog.project.config.GlobalExceptionHandler;
import com.letsblog.project.dto.SnsTemplatesView;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.service.SnsTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** プロジェクト設定画面の告知文テンプレートの API(issue #1583)。読むのはメンバーかadmin、保存と再送はadminだけ。 */
@ExtendWith(MockitoExtension.class)
class ProjectSnsTemplateControllerTest {

    @Mock
    private SnsTemplateService snsTemplateService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private MockMvc mockMvc;

    private final SnsTemplatesView view = new SnsTemplatesView("【新着】{title} {url}", "{threshold}PV",
            new SnsTemplatesView.Send("SENT", null, "2026-10-06T00:00:00Z"));

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProjectSnsTemplateController(snsTemplateService, adminAuthorizationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void テンプレートと送信状態を返す() throws Exception {
        when(snsTemplateService.view(7L)).thenReturn(view);

        mockMvc.perform(get("/api/projects/7/sns/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishTemplate").value("【新着】{title} {url}"))
                .andExpect(jsonPath("$.pvTemplate").value("{threshold}PV"))
                .andExpect(jsonPath("$.send.state").value("SENT"));
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
    }

    @Test
    void メンバーでもadminでもなければ読めない() throws Exception {
        doThrow(new ForbiddenException("権限がありません")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        mockMvc.perform(get("/api/projects/7/sns/templates")).andExpect(status().isForbidden());

        verifyNoInteractions(snsTemplateService);
    }

    @Test
    void 公開時とPV達成時のテンプレートを保存する() throws Exception {
        when(snsTemplateService.save(7L, "【新着】{title} {url}", "{threshold}PV")).thenReturn(view);

        mockMvc.perform(put("/api/projects/7/sns/templates").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publishTemplate\":\"【新着】{title} {url}\",\"pvTemplate\":\"{threshold}PV\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.send.state").value("SENT"));
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void 欄が欠けていても空として保存へ渡す() throws Exception {
        when(snsTemplateService.save(7L, null, null)).thenReturn(view);

        mockMvc.perform(put("/api/projects/7/sns/templates").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void サービスの拒否は409で理由を返す() throws Exception {
        when(snsTemplateService.save(7L, "{period}", ""))
                .thenThrow(new IllegalArgumentException("公開時のテンプレートに {period} は使えません"));

        mockMvc.perform(put("/api/projects/7/sns/templates").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publishTemplate\":\"{period}\",\"pvTemplate\":\"\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("公開時のテンプレートに {period} は使えません"));
    }

    @Test
    void 再送する() throws Exception {
        when(snsTemplateService.resend(7L)).thenReturn(view);

        mockMvc.perform(post("/api/projects/7/sns/templates/resend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.send.state").value("SENT"));
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void 保存と再送は管理者だけで_メンバーは拒否し何も実行しない() throws Exception {
        doThrow(new ForbiddenException("権限がありません")).when(adminAuthorizationService).requireAdmin();

        mockMvc.perform(put("/api/projects/7/sns/templates").contentType(MediaType.APPLICATION_JSON)
                .content("{\"publishTemplate\":\"a\",\"pvTemplate\":\"b\"}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/templates/resend")).andExpect(status().isForbidden());

        verifyNoInteractions(snsTemplateService);
    }
}
