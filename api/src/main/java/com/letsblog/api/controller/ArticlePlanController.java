package com.letsblog.api.controller;

import com.letsblog.api.dto.AcceptPlanRequest;
import com.letsblog.api.dto.AcceptPlanResponse;
import com.letsblog.api.dto.AcceptStructureRequest;
import com.letsblog.api.dto.AcceptStructureResponse;
import com.letsblog.api.dto.ArticlePlanSessionDetailResponse;
import com.letsblog.api.dto.ArticlePlanSessionSummaryResponse;
import com.letsblog.api.dto.IssueDescriptionResponse;
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
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}/article-plan")
public class ArticlePlanController {

    private final ArticlePlanService articlePlanService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final CurrentActorService currentActorService;

    public ArticlePlanController(
            ArticlePlanService articlePlanService,
            AdminAuthorizationService adminAuthorizationService,
            CurrentActorService currentActorService) {
        this.articlePlanService = articlePlanService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.currentActorService = currentActorService;
    }

    @PostMapping("/chat")
    public PlanChatResponse chat(@PathVariable Long projectId, @Valid @RequestBody PlanChatRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return articlePlanService.chat(
                projectId, request.history(), request.message(), request.sessionId(), request.githubIssueNumber());
    }

    @GetMapping("/sessions")
    public List<ArticlePlanSessionSummaryResponse> listSessions(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return articlePlanService.listSessions(projectId);
    }

    @GetMapping("/sessions/{sessionId}")
    public ArticlePlanSessionDetailResponse getSession(
            @PathVariable Long projectId, @PathVariable Long sessionId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return articlePlanService.getSession(projectId, sessionId);
    }

    @GetMapping("/sessions/by-issue/{issueNumber}")
    public ArticlePlanSessionDetailResponse getSessionByIssue(
            @PathVariable Long projectId, @PathVariable Integer issueNumber) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return articlePlanService.getSessionByIssue(projectId, issueNumber);
    }

    @GetMapping("/issues/{issueNumber}/description")
    public IssueDescriptionResponse getIssueDescription(
            @PathVariable Long projectId, @PathVariable Integer issueNumber) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        Long userId = currentActorService.getCurrentActorId();
        return articlePlanService.getIssueDescription(projectId, userId, issueNumber);
    }

    @GetMapping("/issues")
    public List<RepositoryIssueResponse> listIssues(
            @PathVariable Long projectId, @RequestParam(defaultValue = "open") String state) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        Long userId = currentActorService.getCurrentActorId();
        return articlePlanService.listRepositoryIssues(projectId, userId, state);
    }

    @PostMapping("/suggest-titles")
    public SuggestTitlesResponse suggestTitles(
            @PathVariable Long projectId, @Valid @RequestBody SuggestTitlesRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return articlePlanService.suggestTitles(projectId, request.history());
    }

    @PostMapping("/accept")
    public AcceptPlanResponse acceptPlan(
            @PathVariable Long projectId, @Valid @RequestBody AcceptPlanRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        Long userId = currentActorService.getCurrentActorId();
        return articlePlanService.acceptPlan(projectId, userId, request.titles());
    }

    @PostMapping("/suggest-structure")
    public SuggestStructureResponse suggestStructure(
            @PathVariable Long projectId, @Valid @RequestBody SuggestStructureRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return articlePlanService.suggestStructure(projectId, request.history());
    }

    @PostMapping("/issues/{issueNumber}/accept-structure")
    public AcceptStructureResponse acceptStructure(
            @PathVariable Long projectId,
            @PathVariable Integer issueNumber,
            @Valid @RequestBody AcceptStructureRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        Long userId = currentActorService.getCurrentActorId();
        return articlePlanService.acceptStructure(projectId, userId, issueNumber, request.structure());
    }

    @PostMapping("/suggest-metadata")
    public SuggestMetadataResponse suggestMetadata(
            @PathVariable Long projectId, @Valid @RequestBody SuggestMetadataRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return articlePlanService.suggestMetadata(projectId, request.history());
    }
}
