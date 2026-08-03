package com.letsblog.api.controller;

import com.letsblog.api.dto.AcceptPlanRequest;
import com.letsblog.api.dto.AcceptPlanResponse;
import com.letsblog.api.dto.AcceptStructureRequest;
import com.letsblog.api.dto.AcceptStructureResponse;
import com.letsblog.api.dto.ArticlePlanSessionDetailResponse;
import com.letsblog.api.dto.ArticlePlanSessionSummaryResponse;
import com.letsblog.api.dto.IssueDescriptionResponse;
import com.letsblog.api.dto.PlanChatMessage;
import com.letsblog.api.dto.PlanChatRequest;
import com.letsblog.api.dto.PlanChatResponse;
import com.letsblog.api.dto.RepositoryIssueResponse;
import com.letsblog.api.dto.SuggestMetadataRequest;
import com.letsblog.api.dto.SuggestMetadataResponse;
import com.letsblog.api.dto.SuggestStructureRequest;
import com.letsblog.api.dto.SuggestStructureResponse;
import com.letsblog.api.dto.SuggestTitlesRequest;
import com.letsblog.api.dto.SuggestTitlesResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ArticlePlanService;
import com.letsblog.api.service.CurrentActorService;
import com.letsblog.api.service.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 各エンドポイントがadminAuthorizationService.requireProjectMemberOrAdmin(projectId)を
 * 呼び出したうえでサービスに委譲すること、認可拒否時にサービスを呼び出さないことを検証する。
 */
@ExtendWith(MockitoExtension.class)
class ArticlePlanControllerTest {

    @Mock
    private ArticlePlanService articlePlanService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    @Mock
    private CurrentActorService currentActorService;

    private ArticlePlanController controller() {
        lenient().when(currentActorService.getCurrentActorId()).thenReturn(10L);
        return new ArticlePlanController(articlePlanService, adminAuthorizationService, currentActorService);
    }

    @Test
    void chat_認可後にサービスへ委譲する() {
        ArticlePlanController controller = controller();
        List<PlanChatMessage> history = List.of(new PlanChatMessage("user", "テーマ"));
        when(articlePlanService.chat(1L, history, "追加発言", 5L, 42))
                .thenReturn(new PlanChatResponse("応答", 5L));

        PlanChatResponse response = controller.chat(1L, new PlanChatRequest(history, "追加発言", 5L, 42));

        assertEquals("応答", response.reply());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void chat_認可拒否ならForbidden() {
        ArticlePlanController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class,
                () -> controller.chat(1L, new PlanChatRequest(List.of(), "発言", null, null)));
    }

    @Test
    void listSessions_認可後にサービスへ委譲する() {
        ArticlePlanController controller = controller();
        when(articlePlanService.listSessions(1L)).thenReturn(List.of(
                new ArticlePlanSessionSummaryResponse(1L, "タイトル", null, null, null)));

        List<ArticlePlanSessionSummaryResponse> result = controller.listSessions(1L);

        assertEquals(1, result.size());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void listSessions_認可拒否ならForbidden() {
        ArticlePlanController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller.listSessions(1L));
    }

    @Test
    void getSession_認可後にサービスへ委譲する() {
        ArticlePlanController controller = controller();
        when(articlePlanService.getSession(1L, 5L))
                .thenReturn(new ArticlePlanSessionDetailResponse(5L, "タイトル", null, List.of(), null, null));

        ArticlePlanSessionDetailResponse response = controller.getSession(1L, 5L);

        assertEquals(5L, response.id());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void getSession_認可拒否ならForbidden() {
        ArticlePlanController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller.getSession(1L, 5L));
    }

    @Test
    void getSessionByIssue_認可後にサービスへ委譲する() {
        ArticlePlanController controller = controller();
        when(articlePlanService.getSessionByIssue(1L, 42))
                .thenReturn(new ArticlePlanSessionDetailResponse(5L, "タイトル", 42, List.of(), null, null));

        ArticlePlanSessionDetailResponse response = controller.getSessionByIssue(1L, 42);

        assertEquals(42, response.githubIssueNumber());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void getSessionByIssue_認可拒否ならForbidden() {
        ArticlePlanController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller.getSessionByIssue(1L, 42));
    }

    @Test
    void getIssueDescription_認可後にサービスへ委譲する() {
        ArticlePlanController controller = controller();
        when(articlePlanService.getIssueDescription(1L, 10L, 3))
                .thenReturn(new IssueDescriptionResponse("本文"));

        IssueDescriptionResponse response = controller.getIssueDescription(1L, 3);

        assertEquals("本文", response.body());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void getIssueDescription_認可拒否ならForbidden() {
        ArticlePlanController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller.getIssueDescription(1L, 3));
    }

    @Test
    void listIssues_認可後にサービスへ委譲する() {
        ArticlePlanController controller = controller();
        when(articlePlanService.listRepositoryIssues(1L, 10L, "open")).thenReturn(List.of(
                new RepositoryIssueResponse(1, "issue", "https://example.com/1", "open")));

        List<RepositoryIssueResponse> result = controller.listIssues(1L, "open");

        assertEquals(1, result.size());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void listIssues_認可拒否ならForbidden() {
        ArticlePlanController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller.listIssues(1L, "open"));
    }

    @Test
    void suggestTitles_認可後にサービスへ委譲する() {
        ArticlePlanController controller = controller();
        when(articlePlanService.suggestTitles(1L, List.of())).thenReturn(new SuggestTitlesResponse(List.of("タイトル")));

        SuggestTitlesResponse response = controller.suggestTitles(1L, new SuggestTitlesRequest(List.of()));

        assertEquals(List.of("タイトル"), response.titles());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void suggestTitles_認可拒否ならForbidden() {
        ArticlePlanController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class,
                () -> controller.suggestTitles(1L, new SuggestTitlesRequest(List.of())));
    }

    @Test
    void acceptPlan_認可後にサービスへ委譲する() {
        ArticlePlanController controller = controller();
        when(articlePlanService.acceptPlan(1L, 10L, List.of("タイトル"))).thenReturn(new AcceptPlanResponse(List.of()));

        AcceptPlanResponse response = controller.acceptPlan(1L, new AcceptPlanRequest(List.of("タイトル")));

        assertEquals(0, response.results().size());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void acceptPlan_認可拒否ならForbidden() {
        ArticlePlanController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class,
                () -> controller.acceptPlan(1L, new AcceptPlanRequest(List.of("タイトル"))));
    }

    @Test
    void suggestStructure_認可後にサービスへ委譲する() {
        ArticlePlanController controller = controller();
        when(articlePlanService.suggestStructure(1L, List.of()))
                .thenReturn(new SuggestStructureResponse("## 構成"));

        SuggestStructureResponse response = controller.suggestStructure(1L, new SuggestStructureRequest(List.of()));

        assertEquals("## 構成", response.structure());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void suggestStructure_認可拒否ならForbidden() {
        ArticlePlanController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class,
                () -> controller.suggestStructure(1L, new SuggestStructureRequest(List.of())));
    }

    @Test
    void acceptStructure_認可後にサービスへ委譲する() {
        ArticlePlanController controller = controller();
        when(articlePlanService.acceptStructure(1L, 10L, 42, "## 構成"))
                .thenReturn(new AcceptStructureResponse(42, "https://example.com/42"));

        AcceptStructureResponse response = controller.acceptStructure(1L, 42, new AcceptStructureRequest("## 構成"));

        assertEquals(42, response.issueNumber());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void acceptStructure_認可拒否ならForbidden() {
        ArticlePlanController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class,
                () -> controller.acceptStructure(1L, 42, new AcceptStructureRequest("## 構成")));
    }

    @Test
    void suggestMetadata_認可後にサービスへ委譲する() {
        ArticlePlanController controller = controller();
        when(articlePlanService.suggestMetadata(1L, List.of()))
                .thenReturn(new SuggestMetadataResponse("タイトル", "slug", List.of("カテゴリ"), List.of("タグ")));

        SuggestMetadataResponse response = controller.suggestMetadata(1L, new SuggestMetadataRequest(List.of()));

        assertEquals("タイトル", response.title());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void suggestMetadata_認可拒否ならForbidden() {
        ArticlePlanController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class,
                () -> controller.suggestMetadata(1L, new SuggestMetadataRequest(List.of())));
    }
}
