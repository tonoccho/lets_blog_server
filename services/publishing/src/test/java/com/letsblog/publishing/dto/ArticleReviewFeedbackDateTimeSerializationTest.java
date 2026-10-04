package com.letsblog.publishing.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.github.GithubIssueComment;
import com.letsblog.publishing.github.GithubPullRequestClient;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import com.letsblog.publishing.service.ArticleReviewFeedbackService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * issue #1611: 差し戻し・自分宛レビュー一覧の応答日時(submittedAt / rejectedAt)が、
 * UTC の Z 終端 RFC 3339 で出力されること。#1540 と同じく DB / エンティティは UTC 壁時計の
 * LocalDateTime のまま。文字列形式は画面から観測できないためサービスレベルのシリアライズテストで表す。
 */
class ArticleReviewFeedbackDateTimeSerializationTest {

    private static final GithubAccess ACCESS = new GithubAccess("t", "octo", "blog");
    private static final long PROJECT_ID = 7L;
    private static final long ACTOR_ID = 3L;
    private static final int PR = 201;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ArticleReviewRepository repository = mock(ArticleReviewRepository.class);
    private final GithubPullRequestClient githubClient = mock(GithubPullRequestClient.class);
    private final ProjectServiceClient projectServiceClient = mock(ProjectServiceClient.class);
    private final ArticleReviewFeedbackService service =
            new ArticleReviewFeedbackService(repository, githubClient, projectServiceClient);

    private static ArticleReview review(ArticleReviewState state, LocalDateTime submittedAt, LocalDateTime rejectedAt) {
        ArticleReview review = new ArticleReview();
        review.setProjectId(PROJECT_ID);
        review.setGithubPrNumber(PR);
        review.setArticleSlug("sample");
        review.setSubmittedByUserId(ACTOR_ID);
        review.setState(state);
        review.setSubmittedAt(submittedAt);
        review.setRejectedAt(rejectedAt);
        return review;
    }

    private JsonNode myReviewsRow(ArticleReview review) {
        when(repository.findByProjectIdAndSubmittedByUserIdOrderBySubmittedAtDesc(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(review));
        when(projectServiceClient.resolveGithubAccess(PROJECT_ID, ACTOR_ID)).thenReturn(ACCESS);
        when(githubClient.findIssueComment(any(), anyLong())).thenReturn(Optional.empty());
        return mapper.valueToTree(service.myReviews(PROJECT_ID, ACTOR_ID)).get(0);
    }

    @Test
    void myReviewsSubmittedAtAndRejectedAtAreZTerminatedRfc3339() {
        ArticleReview rejected = review(ArticleReviewState.CHANGES_REQUESTED,
                LocalDateTime.of(2026, 9, 8, 20, 3, 35), LocalDateTime.of(2026, 9, 9, 1, 2, 3));
        rejected.setRejectCommentId(5001L);

        JsonNode row = myReviewsRow(rejected);

        assertThat(row.get("submittedAt").asString()).isEqualTo("2026-09-08T20:03:35Z");
        assertThat(row.get("rejectedAt").asString()).isEqualTo("2026-09-09T01:02:03Z");
    }

    @Test
    void myReviewsNullSubmittedAtAndNonRejectedRejectedAtStayNull() {
        JsonNode row = myReviewsRow(review(ArticleReviewState.IN_REVIEW, null, LocalDateTime.of(2026, 9, 9, 1, 2, 3)));

        assertThat(row.get("submittedAt").isNull()).isTrue();
        assertThat(row.get("rejectedAt").isNull()).isTrue();
    }

    @Test
    void myReviewsRejectedWithNullRejectedAtStaysNull() {
        JsonNode row = myReviewsRow(review(ArticleReviewState.CHANGES_REQUESTED,
                LocalDateTime.of(2026, 9, 8, 20, 3, 35), null));

        assertThat(row.get("rejectedAt").isNull()).isTrue();
    }

    @Test
    void rejectRejectedAtIsZTerminatedRfc3339() {
        ArticleReview inReview = new ArticleReview();
        inReview.setProjectId(PROJECT_ID);
        inReview.setGithubPrNumber(PR);
        inReview.setState(ArticleReviewState.IN_REVIEW);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(inReview));
        when(projectServiceClient.resolveGithubAccess(PROJECT_ID, 9L)).thenReturn(ACCESS);
        when(githubClient.createIssueComment(any(), anyInt(), anyString()))
                .thenReturn(new GithubIssueComment(5001L, "x"));

        JsonNode json = mapper.valueToTree(service.reject(PROJECT_ID, 9L, "a@b", PR, "直して"));

        // rejectedAt は markChangesRequested が現在の UTC 壁時計で採る。Z で終わり、保存値と同じ実時刻であること。
        String text = json.get("rejectedAt").asString();
        assertThat(text).endsWith("Z");
        assertThat(java.time.Instant.parse(text))
                .isEqualTo(inReview.getRejectedAt().toInstant(java.time.ZoneOffset.UTC));
    }

    @Test
    void rejectResponseOfNullRejectedAtStaysNull() {
        ArticleRejectResponse response =
                ArticleRejectResponse.of(PR, ArticleReviewState.CHANGES_REQUESTED, 1L, 2L, null);

        assertThat(response.rejectedAt()).isNull();
    }

    @Test
    void rejectResponseOfKeepsSameInstantAsUtcWallClock() {
        ArticleRejectResponse response = ArticleRejectResponse.of(
                PR, ArticleReviewState.CHANGES_REQUESTED, 1L, 2L, LocalDateTime.of(2026, 9, 9, 1, 2, 3));

        assertThat(mapper.valueToTree(response).get("rejectedAt").asString()).isEqualTo("2026-09-09T01:02:03Z");
    }
}
