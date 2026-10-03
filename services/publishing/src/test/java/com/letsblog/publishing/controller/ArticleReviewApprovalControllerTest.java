package com.letsblog.publishing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.dto.ArticleApproveResponse;
import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.ArticleReviewApprovalService;
import com.letsblog.publishing.service.CurrentActorService;
import com.letsblog.publishing.service.ForbiddenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link ArticleReviewApprovalController}の単体テスト(issue #1343)。 */
@ExtendWith(MockitoExtension.class)
class ArticleReviewApprovalControllerTest {

    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private CurrentActorService currentActorService;
    @Mock
    private ProjectServiceClient projectServiceClient;
    @Mock
    private ArticleReviewApprovalService approvalService;

    private ArticleReviewApprovalController controller() {
        return new ArticleReviewApprovalController(
                adminAuthorizationService, currentActorService, projectServiceClient, approvalService);
    }

    @Test
    @DisplayName("レビュー完了は認可後に、操作者のGitHubアクセスを解決してサービスへ渡す")
    void approve() {
        GithubAccess access = new GithubAccess("t", "octo", "blog");
        ArticleApproveResponse expected =
                new ArticleApproveResponse(201, ArticleReviewState.PUBLISHED, "http://p/x/", "9", true, true);
        when(currentActorService.getCurrentActorId()).thenReturn(3L);
        when(projectServiceClient.resolveGithubAccess(7L, 3L)).thenReturn(access);
        when(approvalService.approve(7L, 3L, access, 201)).thenReturn(expected);

        assertThat(controller().approve(7L, 201)).isEqualTo(expected);
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
    }

    @Test
    @DisplayName("メンバーでも管理者でもなければ拒否され、サービスに触れない")
    void forbidden() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        assertThatThrownBy(() -> controller().approve(7L, 201)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(approvalService);
    }

    @Test
    @DisplayName("操作者を解決できなければログインが必要として拒否する")
    void unresolvedActor() {
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertThatThrownBy(() -> controller().approve(7L, 201)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(approvalService);
    }
}
