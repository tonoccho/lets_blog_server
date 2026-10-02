package com.letsblog.publishing.controller;

import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.dto.ArticleReviewResponse;
import com.letsblog.publishing.dto.ArticleSubmissionRequest;
import com.letsblog.publishing.dto.ArticleSubmissionResponse;
import com.letsblog.publishing.dto.PullRequestArticleResponse;
import com.letsblog.publishing.github.GithubPullRequestClient;
import com.letsblog.publishing.github.GithubPullRequestSummary;
import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.ArticleReviewPublishService;
import com.letsblog.publishing.service.ArticleSubmissionService;
import com.letsblog.publishing.service.CurrentActorService;
import com.letsblog.publishing.service.ForbiddenException;
import com.letsblog.publishing.service.PullRequestArticleService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 記事レビュー(GitHubのPull Request経由で記事を確認・投稿する流れ、Epic #1333)のAPI。
 * レビュー待ちPRの一覧(#1337)・PRのheadからの記事取得(#1338)・提出(#1339)・テスト環境への投稿(#1341)を提供し、
 * マージ・差し戻しは後続Issueが足す。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/article-review")
public class ArticleReviewController {

    private final AdminAuthorizationService adminAuthorizationService;
    private final CurrentActorService currentActorService;
    private final ProjectServiceClient projectServiceClient;
    private final GithubPullRequestClient githubPullRequestClient;
    private final PullRequestArticleService pullRequestArticleService;
    private final ArticleSubmissionService articleSubmissionService;
    private final ArticleReviewPublishService articleReviewPublishService;

    public ArticleReviewController(
            AdminAuthorizationService adminAuthorizationService,
            CurrentActorService currentActorService,
            ProjectServiceClient projectServiceClient,
            GithubPullRequestClient githubPullRequestClient,
            PullRequestArticleService pullRequestArticleService,
            ArticleSubmissionService articleSubmissionService,
            ArticleReviewPublishService articleReviewPublishService) {
        this.adminAuthorizationService = adminAuthorizationService;
        this.currentActorService = currentActorService;
        this.projectServiceClient = projectServiceClient;
        this.githubPullRequestClient = githubPullRequestClient;
        this.pullRequestArticleService = pullRequestArticleService;
        this.articleSubmissionService = articleSubmissionService;
        this.articleReviewPublishService = articleReviewPublishService;
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

    /**
     * プッシュ済みのheadブランチを提出する(issue #1339)。サーバがPull Requestを作り(拡張はGitHubへ直接
     * アクセスしない)、API を呼んだLet's Blogユーザーを提出者として記録する。同じheadに開いているPRが
     * あれば新規作成せずそのPRを返し、状態を提出済みへ戻す。ブランチが無ければ404。
     */
    @PostMapping("/submissions")
    public ArticleSubmissionResponse submit(
            @PathVariable Long projectId, @Valid @RequestBody ArticleSubmissionRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        Long actorId = requireActorId();
        return articleSubmissionService.submit(
                projectId, actorId, projectServiceClient.resolveGithubAccess(projectId, actorId), request);
    }

    /**
     * PRの記事をプロジェクトのテスト環境のサイトへ即公開で投稿し、レビュー中へ遷移させる(issue #1341)。
     * 同じスラッグの投稿が既にあれば更新するので、再レビューで記事は重複しない。応答にテスト環境の投稿URLを
     * 含める。テスト環境のサイトが紐づいていなければ409、PRが提出されていなければ404で、どちらも状態は進まない。
     * 投稿が失敗しても状態は進まない(アップロード済みの画像は巻き戻さず残す)。
     */
    @PostMapping("/pull-requests/{prNumber}/review")
    public ArticleReviewResponse review(@PathVariable Long projectId, @PathVariable int prNumber) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        Long actorId = requireActorId();
        return articleReviewPublishService.review(
                projectId, actorId, projectServiceClient.resolveGithubAccess(projectId, actorId), prNumber);
    }

    private GithubAccess resolveAccess(Long projectId) {
        return projectServiceClient.resolveGithubAccess(projectId, requireActorId());
    }

    private Long requireActorId() {
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null) {
            throw new ForbiddenException("この操作にはログインが必要です");
        }
        return actorId;
    }
}
