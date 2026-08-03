package com.letsblog.api.controller;

import com.letsblog.api.dto.AcceptPlanRequest;
import com.letsblog.api.dto.AcceptPlanResponse;
import com.letsblog.api.dto.PlanChatRequest;
import com.letsblog.api.dto.PlanChatResponse;
import com.letsblog.api.dto.SuggestTitlesRequest;
import com.letsblog.api.dto.SuggestTitlesResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ArticlePlanService;
import com.letsblog.api.service.CurrentActorService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        return articlePlanService.chat(projectId, request.history(), request.message());
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
