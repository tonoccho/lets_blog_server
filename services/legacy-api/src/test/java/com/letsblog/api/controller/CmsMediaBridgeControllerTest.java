package com.letsblog.api.controller;

import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsMediaReferenceScan;
import com.letsblog.api.cms.CmsMediaSummary;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.MediaGcScanBridgeResponse;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
import com.letsblog.api.service.ProjectNotFoundException;
import com.letsblog.api.service.SiteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * CmsMediaBridgeControllerの回帰テスト(issue #573 stage3)。media-serviceから移設された
 * MediaController/MediaGarbageCollectionServiceのProject/Site解決ロジック(環境未設定時の
 * 例外を含む)が、このブリッジコントローラへ正しく引き継がれていることを検証する。
 */
@ExtendWith(MockitoExtension.class)
class CmsMediaBridgeControllerTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private SiteService siteService;
    @Mock
    private CmsAdapterFactory cmsAdapterFactory;
    @Mock
    private CmsAdapter cmsAdapter;

    private CmsMediaBridgeController controller;

    @BeforeEach
    void setUp() {
        controller = new CmsMediaBridgeController(projectRepository, siteRepository, siteService, cmsAdapterFactory);
    }

    private Project buildProject(Long localSiteId) {
        Project project = new Project();
        project.setId(1L);
        project.setName("テスト");
        project.setSlug("test");
        project.setLocalSiteId(localSiteId);
        return project;
    }

    private Site buildSite(Long id, String siteKey) {
        Site site = new Site();
        site.setId(id);
        site.setSiteKey(siteKey);
        site.setCmsType(CmsType.WORDPRESS);
        return site;
    }

    @Test
    void uploadMedia_CmsAdapter経由でアップロードする() throws Exception {
        CmsCredentials credentials = new CmsCredentials.WordPressCredentials("https://example.com", "user", "SSH");
        when(siteService.getCredentials("my-site")).thenReturn(credentials);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.uploadMedia(credentials, "photo.jpg", "image/jpeg", "data".getBytes()))
                .thenReturn(new MediaUploadResult("1", "https://example.com/media/1.jpg"));

        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", "data".getBytes());
        MediaUploadResult result = controller.uploadMedia("my-site", file);

        assertEquals("1", result.id());
        assertEquals("https://example.com/media/1.jpg", result.url());
    }

    @Test
    void scanMedia_プロジェクトのサイト情報からCMSデータを取得する() {
        Project project = buildProject(10L);
        Site site = buildSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(site));
        CmsCredentials credentials = new CmsCredentials.WordPressCredentials("https://local.test", "admin", "SSH");
        when(siteService.getCredentials("local-site")).thenReturn(credentials);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        List<CmsMediaSummary> media = List.of(new CmsMediaSummary("1", "url", "title", "image/jpeg", "2024-01-01"));
        CmsMediaReferenceScan refs = new CmsMediaReferenceScan(List.of(), Map.of());
        when(cmsAdapter.listMedia(credentials)).thenReturn(media);
        when(cmsAdapter.scanMediaReferences(credentials)).thenReturn(refs);

        MediaGcScanBridgeResponse response = controller.scanMedia(1L, "local");

        assertEquals(media, response.media());
        assertEquals(refs, response.refs());
    }

    @Test
    void scanMedia_環境にサイトが未設定なら例外() {
        Project project = buildProject(null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertThrows(IllegalArgumentException.class, () -> controller.scanMedia(1L, "local"));
    }

    @Test
    void scanMedia_存在しないプロジェクトはProjectNotFoundException() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> controller.scanMedia(99L, "local"));
    }

    @Test
    void deleteMedia_CmsAdapter経由で削除し204を返す() {
        Project project = buildProject(10L);
        Site site = buildSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(site));
        CmsCredentials credentials = new CmsCredentials.WordPressCredentials("https://local.test", "admin", "SSH");
        when(siteService.getCredentials("local-site")).thenReturn(credentials);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);

        ResponseEntity<Void> response = controller.deleteMedia(1L, "42", "local");

        assertEquals(204, response.getStatusCode().value());
    }
}
