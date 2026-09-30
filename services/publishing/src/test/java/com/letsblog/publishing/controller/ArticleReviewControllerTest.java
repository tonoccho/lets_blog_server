package com.letsblog.publishing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.github.GithubPullRequestClient;
import com.letsblog.publishing.github.GithubPullRequestSummary;
import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.CurrentActorService;
import com.letsblog.publishing.service.ForbiddenException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link ArticleReviewController}の単体テスト(issue #1337)。 */
@ExtendWith(MockitoExtension.class)
class ArticleReviewControllerTest {

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    @Mock
    private CurrentActorService currentActorService;

    @Mock
    private ProjectServiceClient projectServiceClient;

    @Mock
    private GithubPullRequestClient githubPullRequestClient;

    private ArticleReviewController controller() {
        return new ArticleReviewController(
                adminAuthorizationService, currentActorService, projectServiceClient, githubPullRequestClient);
    }

    @Test
    @DisplayName("認可後に操作者でGitHubアクセス情報を解決し、開いているPRの一覧を返す")
    void listPullRequests() {
        GithubAccess access = new GithubAccess("t", "octo", "blog");
        List<GithubPullRequestSummary> prs = List.of(
                new GithubPullRequestSummary(1, "記事", "article/a", "2026-09-01T00:00:00Z", "https://x/pull/1"));
        when(currentActorService.getCurrentActorId()).thenReturn(3L);
        when(projectServiceClient.resolveGithubAccess(7L, 3L)).thenReturn(access);
        when(githubPullRequestClient.listOpenPullRequests(access)).thenReturn(prs);

        assertThat(controller().listPullRequests(7L)).isEqualTo(prs);
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
    }

    @Test
    @DisplayName("メンバーでも管理者でもなければ拒否され、GitHubにもproject-serviceにも触れない")
    void forbidden() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        assertThatThrownBy(() -> controller().listPullRequests(7L)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(projectServiceClient, githubPullRequestClient);
    }

    @Test
    @DisplayName("操作者を解決できなければログインが必要として拒否する")
    void unresolvedActor() {
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertThatThrownBy(() -> controller().listPullRequests(7L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ログイン");
        verifyNoInteractions(projectServiceClient, githubPullRequestClient);
    }
}
