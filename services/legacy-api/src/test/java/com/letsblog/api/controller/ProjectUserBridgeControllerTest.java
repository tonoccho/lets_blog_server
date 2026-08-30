package com.letsblog.api.controller;

import com.letsblog.api.domain.UserSiteAuthor;
import com.letsblog.api.dto.ArticleImageLongEdgePxBridgeResponse;
import com.letsblog.api.dto.CacheUserSiteAuthorBridgeRequest;
import com.letsblog.api.dto.UserSiteAuthorBridgeResponse;
import com.letsblog.api.domain.ProjectUser;
import com.letsblog.api.repository.ProjectUserRepository;
import com.letsblog.api.repository.UserSiteAuthorRepository;
import com.letsblog.api.service.ProjectService;
import com.letsblog.api.service.ProjectUserSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ProjectUserBridgeControllerの回帰テスト(issue #577 stage2、issue #707、issue #712)。 */
@ExtendWith(MockitoExtension.class)
class ProjectUserBridgeControllerTest {

    @Mock
    private ProjectUserSyncService projectUserSyncService;

    @Mock
    private UserSiteAuthorRepository userSiteAuthorRepository;

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectUserRepository projectUserRepository;

    private ProjectUserBridgeController controller() {
        return new ProjectUserBridgeController(
                projectUserSyncService, userSiteAuthorRepository, projectService, projectUserRepository);
    }

    @Test
    void reconcileRolesForSite_サービスへ委譲する() {
        ResponseEntity<Void> response = controller().reconcileRolesForSite(1L, 2L);

        assertEquals(204, response.getStatusCode().value());
        verify(projectUserSyncService).reconcileRolesForSite(1L, 2L);
    }

    @Test
    void findUserSiteAuthor_見つかれば200() {
        when(userSiteAuthorRepository.findByUserIdAndSiteId(1L, 2L))
                .thenReturn(Optional.of(new UserSiteAuthor(1L, 2L, "9")));

        ResponseEntity<UserSiteAuthorBridgeResponse> response = controller().findUserSiteAuthor(1L, 2L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("9", response.getBody().cmsAuthorId());
    }

    @Test
    void findUserSiteAuthor_見つからなければ404() {
        when(userSiteAuthorRepository.findByUserIdAndSiteId(1L, 2L)).thenReturn(Optional.empty());

        ResponseEntity<UserSiteAuthorBridgeResponse> response = controller().findUserSiteAuthor(1L, 2L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void cacheUserSiteAuthor_新規保存() {
        when(userSiteAuthorRepository.findByUserIdAndSiteId(1L, 2L)).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller()
                .cacheUserSiteAuthor(new CacheUserSiteAuthorBridgeRequest(1L, 2L, "9"));

        assertEquals(204, response.getStatusCode().value());
        verify(userSiteAuthorRepository).save(any(UserSiteAuthor.class));
    }

    @Test
    void cacheUserSiteAuthor_既存行を上書き保存() {
        UserSiteAuthor existing = new UserSiteAuthor(1L, 2L, "old");
        when(userSiteAuthorRepository.findByUserIdAndSiteId(1L, 2L)).thenReturn(Optional.of(existing));

        controller().cacheUserSiteAuthor(new CacheUserSiteAuthorBridgeRequest(1L, 2L, "new"));

        assertEquals("new", existing.getCmsAuthorId());
        verify(userSiteAuthorRepository).save(existing);
    }

    @Test
    void isProjectMember_project_userに行があればtrue() {
        when(projectUserRepository.findByProjectIdAndUserId(1L, 10L))
                .thenReturn(Optional.of(new ProjectUser()));

        assertEquals(true, controller().isProjectMember(1L, 10L));
    }

    @Test
    void isProjectMember_project_userに行が無ければfalse() {
        when(projectUserRepository.findByProjectIdAndUserId(1L, 10L)).thenReturn(Optional.empty());

        assertEquals(false, controller().isProjectMember(1L, 10L));
    }

    @Test
    void articleImageLongEdgePx_サービスの解決値を返す() {
        when(projectService.resolveArticleImageLongEdgePx(eq(5L))).thenReturn(1500);

        ArticleImageLongEdgePxBridgeResponse response = controller().articleImageLongEdgePx(5L);

        assertEquals(1500, response.value());
    }
}
