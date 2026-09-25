package com.letsblog.project.controller;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.project.client.IdentityBridgeClient;
import com.letsblog.project.cms.CmsType;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.ProjectBridgeResponse;
import com.letsblog.project.dto.SiteBridgeResponse;
import com.letsblog.project.repository.SiteRepository;
import com.letsblog.project.service.CurrentActorService;
import com.letsblog.project.service.ProjectService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * ProjectInternalControllerの回帰テスト(issue #577 stage3)。他サービスがこのエンドポイント経由で
 * プロジェクト/サイトの基本情報を取得できることを検証する。
 */
@ExtendWith(MockitoExtension.class)
class ProjectInternalControllerTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private IdentityBridgeClient identityBridgeClient;

    @Mock
    private CurrentActorService currentActorService;

    @Mock
    private CredentialCipher credentialCipher;

    private ProjectInternalController controller() {
        return new ProjectInternalController(
                projectService, siteRepository, identityBridgeClient, currentActorService, credentialCipher);
    }

    private Project buildProject(Long id) {
        Project project = new Project();
        project.setId(id);
        project.setName("テストプロジェクト");
        project.setSlug("test-project");
        project.setMasterEnvironment("test");
        project.setLocalSiteId(10L);
        project.setTestSiteId(20L);
        project.setProductionSiteId(null);
        project.setGithubRepository("owner/repo");
        return project;
    }

    private Site buildSite(Long id, String siteKey) {
        Site site = new Site();
        site.setId(id);
        site.setSiteKey(siteKey);
        site.setName("My Blog");
        site.setBaseUrl("https://example.com");
        site.setCmsType(CmsType.WORDPRESS);
        site.setManagedWordpress(true);
        site.setWpSlug("my-blog");
        return site;
    }

    @Test
    void project_基本情報を返す() {
        when(projectService.getProjectEntity(1L)).thenReturn(buildProject(1L));

        ProjectBridgeResponse response = controller().project(1L);

        assertEquals(1L, response.id());
        assertEquals("test-project", response.slug());
        assertEquals("test", response.masterEnvironment());
        assertEquals(10L, response.localSiteId());
        assertEquals(20L, response.testSiteId());
        assertNull(response.productionSiteId());
        assertEquals("owner/repo", response.githubRepository());
    }

    @Test
    void projectIdForSite_所属プロジェクトIDを返す() {
        when(projectService.findProjectIdBySiteId(10L)).thenReturn(1L);

        assertEquals(1L, controller().projectIdForSite(10L).projectId());
    }

    @Test
    void projectIdForSite_未紐付けならnull() {
        when(projectService.findProjectIdBySiteId(99L)).thenReturn(null);

        assertNull(controller().projectIdForSite(99L).projectId());
    }

    @Test
    void site_存在すれば200で返す() {
        when(siteRepository.findById(10L)).thenReturn(Optional.of(buildSite(10L, "my-blog")));

        ResponseEntity<SiteBridgeResponse> response = controller().site(10L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("my-blog", response.getBody().siteKey());
        assertEquals(true, response.getBody().managedWordpress());
    }

    @Test
    void site_存在しなければ404() {
        when(siteRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<SiteBridgeResponse> response = controller().site(99L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void siteByKey_存在すれば200で返す() {
        when(siteRepository.findBySiteKey("my-blog")).thenReturn(Optional.of(buildSite(10L, "my-blog")));

        ResponseEntity<SiteBridgeResponse> response = controller().siteByKey("my-blog");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(10L, response.getBody().id());
    }

    @Test
    void siteByKey_存在しなければ404() {
        when(siteRepository.findBySiteKey("unknown")).thenReturn(Optional.empty());

        ResponseEntity<SiteBridgeResponse> response = controller().siteByKey("unknown");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void sites_全件を返す() {
        when(siteRepository.findAll()).thenReturn(List.of(buildSite(10L, "a"), buildSite(20L, "b")));

        List<SiteBridgeResponse> response = controller().sites();

        assertEquals(2, response.size());
    }
}
