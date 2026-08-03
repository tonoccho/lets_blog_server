package com.letsblog.api.controller;

import com.letsblog.api.dto.AcceptPlanRequest;
import com.letsblog.api.dto.AcceptPlanResponse;
import com.letsblog.api.dto.ArticlePlanSessionDetailResponse;
import com.letsblog.api.dto.ArticlePlanSessionSummaryResponse;
import com.letsblog.api.dto.PlanChatRequest;
import com.letsblog.api.dto.PlanChatResponse;
import com.letsblog.api.dto.RepositoryIssueResponse;
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
        adminAuthorizationService.requireAdmin();
        return articlePlanService.chat(projectId, request.history(), request.message(), request.sessionId());
    }

    @GetMapping("/sessions")
    public List<ArticlePlanSessionSummaryResponse> listSessions(@PathVariable Long projectId) {
        adminAuthorizationService.requireAdmin();
        return articlePlanService.listSessions(projectId);
    }

    @GetMapping("/sessions/{sessionId}")
    public ArticlePlanSessionDetailResponse getSession(
            @PathVariable Long projectId, @PathVariable Long sessionId) {
        adminAuthorizationService.requireAdmin();
        return articlePlanService.getSession(projectId, sessionId);
    }

    @GetMapping("/issues")
    public List<RepositoryIssueResponse> listIssues(
            @PathVariable Long projectId, @RequestParam(defaultValue = "open") String state) {
        adminAuthorizationService.requireAdmin();
        Long userId = currentActorService.getCurrentActorId();
        return articlePlanService.listRepositoryIssues(projectId, userId, state);
    }

    @PostMapping("/suggest-titles")
    public SuggestTitlesResponse suggestTitles(
            @PathVariable Long projectId, @Valid @RequestBody SuggestTitlesRequest request) {
        adminAuthorizationService.requireAdmin();
        return articlePlanService.suggestTitles(projectId, request.history());
    }

    @PostMapping("/accept")
    public AcceptPlanResponse acceptPlan(
            @PathVariable Long projectId, @Valid @RequestBody AcceptPlanRequest request) {
        adminAuthorizationService.requireAdmin();
        Long userId = currentActorService.getCurrentActorId();
        return articlePlanService.acceptPlan(projectId, userId, request.titles());
    }
}
