package com.letsblog.publishing.controller;

import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.dto.PullRequestArticleResponse;
import com.letsblog.publishing.github.GithubPullRequestClient;
import com.letsblog.publishing.github.GithubPullRequestSummary;
import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.CurrentActorService;
import com.letsblog.publishing.service.ForbiddenException;
import com.letsblog.publishing.service.PullRequestArticleService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 記事レビュー(GitHubのPull Request経由で記事を確認・投稿する流れ、Epic #1333)のAPI。
 * レビュー待ちPRの一覧(#1337)とPRのheadからの記事取得(#1338)を提供し、投稿・マージは後続Issueが足す。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/article-review")
public class ArticleReviewController {

    private final AdminAuthorizationService adminAuthorizationService;
    private final CurrentActorService currentActorService;
    private final ProjectServiceClient projectServiceClient;
    private final GithubPullRequestClient githubPullRequestClient;
    private final PullRequestArticleService pullRequestArticleService;

    public ArticleReviewController(
            AdminAuthorizationService adminAuthorizationService,
            CurrentActorService currentActorService,
            ProjectServiceClient projectServiceClient,
            GithubPullRequestClient githubPullRequestClient,
            PullRequestArticleService pullRequestArticleService) {
        this.adminAuthorizationService = adminAuthorizationService;
        this.currentActorService = currentActorService;
        this.projectServiceClient = projectServiceClient;
        this.githubPullRequestClient = githubPullRequestClient;
        this.pullRequestArticleService = pullRequestArticleService;
    }

    /**
     * プロジェクトのGitHubリポジトリで開いているPull Requestを返す。GitHubリポジトリ・トークンの
     * 未設定は409、GitHub側の認証失敗・権限不足は502として、原因の分かるメッセージで返る。
     */
    @GetMapping("/pull-requests")
    public List<GithubPullRequestSummary> listPullRequests(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return githubPullRequestClient.listOpenPullRequests(resolveAccess(projectId));
    }

    /**
     * PRのheadにある記事1本分(front matter・本文Markdown・assetsの名前とサイズ)を返す(issue #1338)。
     * 記事が無い(404)・2本以上ある(409)・スラッグ/front matterが不正(422)は原因の分かる文面で返る。
     */
    @GetMapping("/pull-requests/{prNumber}/article")
    public PullRequestArticleResponse getArticle(@PathVariable Long projectId, @PathVariable int prNumber) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return pullRequestArticleService.fetch(resolveAccess(projectId), prNumber);
    }

    private GithubAccess resolveAccess(Long projectId) {
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null) {
            throw new ForbiddenException("この操作にはログインが必要です");
        }
        return projectServiceClient.resolveGithubAccess(projectId, actorId);
    }
}
