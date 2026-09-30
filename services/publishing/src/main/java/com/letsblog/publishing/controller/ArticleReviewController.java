package com.letsblog.publishing.controller;

import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.github.GithubPullRequestClient;
import com.letsblog.publishing.github.GithubPullRequestSummary;
import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.CurrentActorService;
import com.letsblog.publishing.service.ForbiddenException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 記事レビュー(GitHubのPull Request経由で記事を確認・投稿する流れ、Epic #1333)のAPI。
 * 本Issue(#1337)ではレビュー待ちPRの一覧だけを提供し、記事取得・投稿・マージは後続Issueが足す。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/article-review")
public class ArticleReviewController {

    private final AdminAuthorizationService adminAuthorizationService;
    private final CurrentActorService currentActorService;
    private final ProjectServiceClient projectServiceClient;
    private final GithubPullRequestClient githubPullRequestClient;

    public ArticleReviewController(
            AdminAuthorizationService adminAuthorizationService,
            CurrentActorService currentActorService,
            ProjectServiceClient projectServiceClient,
            GithubPullRequestClient githubPullRequestClient) {
        this.adminAuthorizationService = adminAuthorizationService;
        this.currentActorService = currentActorService;
        this.projectServiceClient = projectServiceClient;
        this.githubPullRequestClient = githubPullRequestClient;
    }

    /**
     * プロジェクトのGitHubリポジトリで開いているPull Requestを返す。GitHubリポジトリ・トークンの
     * 未設定は409、GitHub側の認証失敗・権限不足は502として、原因の分かるメッセージで返る。
     */
    @GetMapping("/pull-requests")
    public List<GithubPullRequestSummary> listPullRequests(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null) {
            throw new ForbiddenException("この操作にはログインが必要です");
        }
        GithubAccess access = projectServiceClient.resolveGithubAccess(projectId, actorId);
        return githubPullRequestClient.listOpenPullRequests(access);
    }
}
