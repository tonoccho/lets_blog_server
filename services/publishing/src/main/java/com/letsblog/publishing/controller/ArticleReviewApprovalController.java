package com.letsblog.publishing.controller;

import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.dto.ArticleApproveResponse;
import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.ArticleReviewApprovalService;
import com.letsblog.publishing.service.CurrentActorService;
import com.letsblog.publishing.service.ForbiddenException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 記事レビューの完了(本番投稿・マージ・ブランチ削除)のAPI(issue #1343、Epic #1333)。
 * {@link ArticleReviewController}と同じ{@code /article-review}配下で、レビュー開始とは別の責務なので分けている。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/article-review")
public class ArticleReviewApprovalController {

    private final AdminAuthorizationService adminAuthorizationService;
    private final CurrentActorService currentActorService;
    private final ProjectServiceClient projectServiceClient;
    private final ArticleReviewApprovalService approvalService;

    public ArticleReviewApprovalController(
            AdminAuthorizationService adminAuthorizationService,
            CurrentActorService currentActorService,
            ProjectServiceClient projectServiceClient,
            ArticleReviewApprovalService approvalService) {
        this.adminAuthorizationService = adminAuthorizationService;
        this.currentActorService = currentActorService;
        this.projectServiceClient = projectServiceClient;
        this.approvalService = approvalService;
    }

    /**
     * レビュー中のPRの記事を本番環境のサイトへ投稿し、PRをマージし、headブランチを削除して、公開済みへ遷移させる。
     * 応答に本番の投稿URLを含める。レビュー中でない・本番環境のサイトが無い・コンフリクト等でマージできないは409、
     * 提出されていなければ404で、いずれも本番投稿もマージもしない。本番投稿が失敗したらマージもせず状態も進めない。
     */
    @PostMapping("/pull-requests/{prNumber}/approve")
    public ArticleApproveResponse approve(@PathVariable Long projectId, @PathVariable int prNumber) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null) {
            throw new ForbiddenException("この操作にはログインが必要です");
        }
        return approvalService.approve(
                projectId, actorId, projectServiceClient.resolveGithubAccess(projectId, actorId), prNumber);
    }
}
