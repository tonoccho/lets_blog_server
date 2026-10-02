package com.letsblog.publishing.controller;

import com.letsblog.publishing.dto.ArticleRejectRequest;
import com.letsblog.publishing.dto.ArticleRejectResponse;
import com.letsblog.publishing.dto.MyArticleReviewResponse;
import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.ArticleReviewFeedbackService;
import com.letsblog.publishing.service.CurrentActorService;
import com.letsblog.publishing.service.ForbiddenException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 記事レビュー(Epic #1333)の差し戻しと、自分宛のレビュー一覧のAPI(issue #1344)。
 * {@link ArticleReviewController}と同じ{@code /article-review}配下で、提出・レビュー開始とは別の責務なので分けている。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/article-review")
public class ArticleReviewFeedbackController {

    private final AdminAuthorizationService adminAuthorizationService;
    private final CurrentActorService currentActorService;
    private final ArticleReviewFeedbackService feedbackService;

    public ArticleReviewFeedbackController(
            AdminAuthorizationService adminAuthorizationService,
            CurrentActorService currentActorService,
            ArticleReviewFeedbackService feedbackService) {
        this.adminAuthorizationService = adminAuthorizationService;
        this.currentActorService = currentActorService;
        this.feedbackService = feedbackService;
    }

    /**
     * レビュー中のPRを、指摘事項をPRのコメントとして投稿して差し戻す。指摘事項が空・空白だけなら400で、
     * コメントも状態変更も行わない。レビュー中でなければ409、提出されていなければ404。
     * コメント本文に、差し戻した Let's Blog 上の利用者が書かれる。
     */
    @PostMapping("/pull-requests/{prNumber}/reject")
    public ArticleRejectResponse reject(
            @PathVariable Long projectId, @PathVariable int prNumber,
            @Valid @RequestBody ArticleRejectRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        Long actorId = requireActorId();
        return feedbackService.reject(
                projectId, actorId, currentActorService.getCurrentActorEmail(), prNumber, request.comment());
    }

    /** 呼び出し元が提出した記事の状態一覧。差し戻しには指摘コメントの本文と差し戻し時刻が付く。他人の提出分は含まれない。 */
    @GetMapping("/my-reviews")
    public List<MyArticleReviewResponse> myReviews(@PathVariable Long projectId) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return feedbackService.myReviews(projectId, requireActorId());
    }

    private Long requireActorId() {
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null) {
            throw new ForbiddenException("この操作にはログインが必要です");
        }
        return actorId;
    }
}
