package com.letsblog.project.controller;

import com.letsblog.project.config.GlobalExceptionHandler;
import com.letsblog.project.dto.PvRulesView;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.service.PvRuleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** プロジェクト設定画面の PV 達成ルールの API(issue #1578)。読むのはメンバーかadmin、変更と再送はadminだけ。 */
@ExtendWith(MockitoExtension.class)
class ProjectSnsPvControllerTest {

    @Mock
    private PvRuleService pvRuleService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private MockMvc mockMvc;

    private final PvRulesView view = new PvRulesView(true, null,
            List.of(new PvRulesView.Rule("r1", "daily", 100)),
            new PvRulesView.Send("SENT", null, "2026-10-05T00:00:00Z"));

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectSnsPvController(pvRuleService, adminAuthorizationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void ルールと送信状態を返す() throws Exception {
        when(pvRuleService.view(7L)).thenReturn(view);

        mockMvc.perform(get("/api/projects/7/sns/pv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addable").value(true))
                .andExpect(jsonPath("$.rules[0].id").value("r1"))
                .andExpect(jsonPath("$.rules[0].period").value("daily"))
                .andExpect(jsonPath("$.rules[0].threshold").value(100))
                .andExpect(jsonPath("$.send.state").value("SENT"));
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
    }

    @Test
    void メンバーでもadminでもなければ読めない() throws Exception {
        doThrow(new ForbiddenException("権限がありません")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        mockMvc.perform(get("/api/projects/7/sns/pv")).andExpect(status().isForbidden());

        verifyNoInteractions(pvRuleService);
    }

    @Test
    void ルールを追加する() throws Exception {
        when(pvRuleService.addRule(7L, "total", 5000)).thenReturn(view);

        mockMvc.perform(post("/api/projects/7/sns/pv/rules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"period\":\"total\",\"threshold\":5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules[0].id").value("r1"));
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void 入力が欠けているか閾値が1未満なら400() throws Exception {
        mockMvc.perform(post("/api/projects/7/sns/pv/rules").contentType(MediaType.APPLICATION_JSON)
                .content("{\"threshold\":5}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/projects/7/sns/pv/rules").contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"daily\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/projects/7/sns/pv/rules").contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"daily\",\"threshold\":0}")).andExpect(status().isBadRequest());

        verifyNoInteractions(pvRuleService);
    }

    @Test
    void GA未連携などサービスの拒否は409で理由を返す() throws Exception {
        when(pvRuleService.addRule(7L, "daily", 100)).thenThrow(new IllegalStateException("GA が連携されていません"));

        mockMvc.perform(post("/api/projects/7/sns/pv/rules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"period\":\"daily\",\"threshold\":100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("GA が連携されていません"));
    }

    @Test
    void ルールを削除する() throws Exception {
        when(pvRuleService.deleteRule(7L, "r1")).thenReturn(view);

        mockMvc.perform(delete("/api/projects/7/sns/pv/rules/r1")).andExpect(status().isOk());

        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void 再送する() throws Exception {
        when(pvRuleService.resend(7L)).thenReturn(view);

        mockMvc.perform(post("/api/projects/7/sns/pv/resend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.send.state").value("SENT"));
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void 変更と再送は管理者だけで_メンバーは拒否し何も実行しない() throws Exception {
        doThrow(new ForbiddenException("権限がありません")).when(adminAuthorizationService).requireAdmin();

        mockMvc.perform(post("/api/projects/7/sns/pv/rules").contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"daily\",\"threshold\":100}")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/projects/7/sns/pv/rules/r1")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/pv/resend")).andExpect(status().isForbidden());

        verifyNoInteractions(pvRuleService);
    }
}
