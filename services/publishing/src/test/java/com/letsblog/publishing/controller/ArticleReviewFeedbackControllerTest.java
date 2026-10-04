package com.letsblog.publishing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.dto.ArticleRejectRequest;
import com.letsblog.publishing.dto.ArticleRejectResponse;
import com.letsblog.publishing.dto.MyArticleReviewResponse;
import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.ArticleReviewFeedbackService;
import com.letsblog.publishing.service.CurrentActorService;
import com.letsblog.publishing.service.ForbiddenException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link ArticleReviewFeedbackController}の単体テスト(issue #1344)。 */
@ExtendWith(MockitoExtension.class)
class ArticleReviewFeedbackControllerTest {

    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private CurrentActorService currentActorService;
    @Mock
    private ArticleReviewFeedbackService feedbackService;

    private ArticleReviewFeedbackController controller() {
        return new ArticleReviewFeedbackController(adminAuthorizationService, currentActorService, feedbackService);
    }

    @Test
    @DisplayName("差し戻しは認可後に、操作者のIDとメールを添えてサービスへ渡す")
    void reject() {
        ArticleRejectResponse expected = new ArticleRejectResponse(
                201, ArticleReviewState.CHANGES_REQUESTED, 5001L, 3L, java.time.Instant.parse("2026-10-02T09:00:00Z"));
        when(currentActorService.getCurrentActorId()).thenReturn(3L);
        when(currentActorService.getCurrentActorEmail()).thenReturn("a@example.com");
        when(feedbackService.reject(7L, 3L, "a@example.com", 201, "直して")).thenReturn(expected);

        assertThat(controller().reject(7L, 201, new ArticleRejectRequest("直して"))).isEqualTo(expected);
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
    }

    @Test
    @DisplayName("差し戻しもメンバーでも管理者でもなければ拒否され、サービスに触れない")
    void rejectForbidden() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        assertThatThrownBy(() -> controller().reject(7L, 201, new ArticleRejectRequest("x")))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(feedbackService);
    }

    @Test
    @DisplayName("差し戻しも操作者を解決できなければログインが必要として拒否する")
    void rejectUnresolvedActor() {
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertThatThrownBy(() -> controller().reject(7L, 201, new ArticleRejectRequest("x")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ログイン");
        verifyNoInteractions(feedbackService);
    }

    @Test
    @DisplayName("自分宛の一覧は認可後に、操作者のIDで絞ってサービスから返す")
    void myReviews() {
        List<MyArticleReviewResponse> expected = List.of(new MyArticleReviewResponse(
                201, "sample", ArticleReviewState.SUBMITTED, java.time.Instant.parse("2026-10-01T09:00:00Z"), null, null));
        when(currentActorService.getCurrentActorId()).thenReturn(3L);
        when(feedbackService.myReviews(7L, 3L)).thenReturn(expected);

        assertThat(controller().myReviews(7L)).isEqualTo(expected);
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
    }

    @Test
    @DisplayName("自分宛の一覧もメンバーでも管理者でもなければ拒否され、サービスに触れない")
    void myReviewsForbidden() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        assertThatThrownBy(() -> controller().myReviews(7L)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(feedbackService);
    }

    @Test
    @DisplayName("自分宛の一覧も操作者を解決できなければログインが必要として拒否する")
    void myReviewsUnresolvedActor() {
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertThatThrownBy(() -> controller().myReviews(7L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ログイン");
        verifyNoInteractions(feedbackService);
    }
}
