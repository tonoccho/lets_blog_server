package com.letsblog.publishing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 記事提出1件(Pull Request 1本)のレビュー進行状態(issue #1339、Epic #1333)。
 *
 * <p>{@code projectId}と{@code submittedByUserId}は他サービスが所有する行のIDで、FKは持たない(ADR-0004)。
 * {@code submittedByUserId}は提出APIを呼んだLet's Blogユーザーであり、GitHubのloginではない
 * (共通トークン設定時はPR作成者が全員同じになるため、GitHub側は担当者の根拠にならない)。
 */
@Entity
@Table(name = "article_reviews",
        uniqueConstraints = @UniqueConstraint(name = "uk_article_reviews_project_pr",
                columnNames = {"project_id", "github_pr_number"}))
@Getter
@Setter
@NoArgsConstructor
public class ArticleReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "github_pr_number", nullable = false)
    private Integer githubPrNumber;

    @Column(name = "github_issue_number", nullable = false)
    private Integer githubIssueNumber;

    @Column(name = "article_slug", nullable = false, length = 255)
    private String articleSlug;

    @Column(name = "submitted_by_user_id", nullable = false)
    private Long submittedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private ArticleReviewState state;

    /** テスト環境へ投稿した記事のURL(issue #1341)。レビュー開始までnull。 */
    @Column(name = "test_post_url", length = 2048)
    private String testPostUrl;

    /** レビュー開始APIを呼んだLet's Blogユーザー(issue #1341)。{@code submittedByUserId}と同じくFKは持たない。 */
    @Column(name = "reviewed_by_user_id")
    private Long reviewedByUserId;

    /** 差し戻しを行ったLet's Blogユーザー(issue #1344)。差し戻されるまでnull。FKは持たない。 */
    @Column(name = "rejected_by_user_id")
    private Long rejectedByUserId;

    /** 差し戻した日時(issue #1344)。差し戻されるまでnull。 */
    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    /** 指摘として投稿したPull RequestコメントのID(issue #1344)。本文はGitHub側にのみ保持する。 */
    @Column(name = "reject_comment_id")
    private Long rejectCommentId;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        submittedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /** 提出済みへ戻す(再提出)。提出日時も更新する。 */
    public void markSubmitted() {
        state = ArticleReviewState.SUBMITTED;
        submittedAt = LocalDateTime.now();
    }

    /** レビュー中へ遷移させ、テスト環境の投稿URLとレビュー実施者を記録する(issue #1341)。再レビューでも呼ぶ。 */
    public void markInReview(String testPostUrl, Long reviewerUserId) {
        state = ArticleReviewState.IN_REVIEW;
        this.testPostUrl = testPostUrl;
        this.reviewedByUserId = reviewerUserId;
    }

    /** 差し戻しへ遷移させ、実施者・時刻・投稿したコメントのIDを記録する(issue #1344)。 */
    public void markChangesRequested(Long rejectorUserId, Long commentId) {
        state = ArticleReviewState.CHANGES_REQUESTED;
        this.rejectedByUserId = rejectorUserId;
        this.rejectedAt = LocalDateTime.now();
        this.rejectCommentId = commentId;
    }
}
