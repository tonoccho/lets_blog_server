package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsMediaReferenceScan;
import com.letsblog.api.cms.CmsMediaSummary;
import com.letsblog.api.cms.CmsPostContentSummary;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.GenerationJobResponse;
import com.letsblog.api.dto.MediaGarbageCollectionScanResponse;
import com.letsblog.api.repository.GenerationJobRepository;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaGarbageCollectionServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private SiteService siteService;
    @Mock
    private CmsAdapterFactory cmsAdapterFactory;
    @Mock
    private GenerationJobRepository generationJobRepository;
    @Mock
    private MediaGarbageCollectionJobRunner mediaGarbageCollectionJobRunner;
    @Mock
    private CmsAdapter cmsAdapter;

    private MediaGarbageCollectionService service() {
        return new MediaGarbageCollectionService(projectRepository, siteRepository, siteService,
                cmsAdapterFactory, generationJobRepository, mediaGarbageCollectionJobRunner, new ObjectMapper());
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

    private CmsCredentials.WordPressCredentials creds() {
        return new CmsCredentials.WordPressCredentials("https://local.test", "admin", "SSH");
    }

    // --- extractContentReferences ---

    @Test
    void extractContentReferences_wpImageクラスを検出する() {
        Set<String> ids = service().extractContentReferences("<img class=\"wp-image-42\" src=\"x.jpg\">");
        assertEquals(Set.of("42"), ids);
    }

    @Test
    void extractContentReferences_Gutenbergブロックのid属性を検出する() {
        Set<String> ids = service().extractContentReferences(
                "<!-- wp:image {\"id\":77,\"sizeSlug\":\"large\"} --><figure></figure><!-- /wp:image -->");
        assertEquals(Set.of("77"), ids);
    }

    @Test
    void extractContentReferences_ギャラリーショートコードのidsを検出する() {
        Set<String> ids = service().extractContentReferences("[gallery ids=\"1,2,3\"]");
        assertEquals(Set.of("1", "2", "3"), ids);
    }

    @Test
    void extractContentReferences_null空文字は空集合を返す() {
        assertTrue(service().extractContentReferences(null).isEmpty());
        assertTrue(service().extractContentReferences("").isEmpty());
        assertTrue(service().extractContentReferences("本文中に42という数字があるだけ").isEmpty());
    }

    // --- extractReferencedIds ---

    @Test
    void extractReferencedIds_サムネイル_本文_設定の参照を合算する() {
        CmsMediaReferenceScan scan = new CmsMediaReferenceScan(
                List.of(new CmsPostContentSummary("1", "post", "publish",
                        "<img class=\"wp-image-10\">", "20")),
                Map.of("site_icon", "30", "custom_logo", "", "header_image", "0", "background_image", ""));

        Set<String> ids = service().extractReferencedIds(scan);

        assertEquals(Set.of("10", "20", "30"), ids);
    }

    // --- scan ---

    @Test
    void scan_未参照メディアのみ返す() {
        MediaGarbageCollectionService service = service();
        Project project = buildProject(10L);
        Site site = buildSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(site));
        CmsCredentials.WordPressCredentials creds = creds();
        when(siteService.getCredentials("local-site")).thenReturn(creds);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);

        CmsMediaSummary referenced = new CmsMediaSummary("10", "https://local.test/img1.jpg", "img1", "image/jpeg", "2024-01-01");
        CmsMediaSummary unreferenced = new CmsMediaSummary("99", "https://local.test/img2.jpg", "img2", "image/jpeg", "2024-01-02");
        when(cmsAdapter.listMedia(creds)).thenReturn(List.of(referenced, unreferenced));
        when(cmsAdapter.scanMediaReferences(creds)).thenReturn(new CmsMediaReferenceScan(
                List.of(new CmsPostContentSummary("1", "post", "publish", "<img class=\"wp-image-10\">", "")),
                Map.of()));

        MediaGarbageCollectionScanResponse response = service.scan(1L, "local");

        assertEquals(1, response.items().size());
        assertEquals("99", response.items().get(0).mediaId());
        assertEquals(2, response.totalMediaCount());
        assertEquals(1, response.referencedMediaCount());
        assertEquals(1, response.unreferencedMediaCount());
    }

    @Test
    void scan_URLの部分一致による二次チェックで誤検出を防ぐ() {
        MediaGarbageCollectionService service = service();
        Project project = buildProject(10L);
        Site site = buildSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(site));
        CmsCredentials.WordPressCredentials creds = creds();
        when(siteService.getCredentials("local-site")).thenReturn(creds);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);

        // wp-image-Nクラスも無くGutenbergブロックにも属さない、手動貼り付け想定の<img src>。
        CmsMediaSummary pastedRaw = new CmsMediaSummary(
                "55", "https://local.test/wp-content/uploads/raw.jpg", "raw", "image/jpeg", "2024-01-01");
        when(cmsAdapter.listMedia(creds)).thenReturn(List.of(pastedRaw));
        when(cmsAdapter.scanMediaReferences(creds)).thenReturn(new CmsMediaReferenceScan(
                List.of(new CmsPostContentSummary("1", "post", "publish",
                        "<img src=\"https://local.test/wp-content/uploads/raw.jpg\">", "")),
                Map.of()));

        MediaGarbageCollectionScanResponse response = service.scan(1L, "local");

        assertTrue(response.items().isEmpty());
        assertEquals(1, response.referencedMediaCount());
    }

    @Test
    void scan_環境にサイトが未設定なら例外() {
        MediaGarbageCollectionService service = service();
        Project project = buildProject(null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertThrows(IllegalArgumentException.class, () -> service.scan(1L, "local"));
    }

    // --- startDelete ---

    @Test
    void startDelete_ジョブを保存してランナーを起動する() {
        MediaGarbageCollectionService service = service();
        Project project = buildProject(10L);
        Site site = buildSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(site));
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(invocation -> {
            GenerationJob job = invocation.getArgument(0);
            job.setId(123L);
            return job;
        });

        GenerationJobResponse response = service.startDelete(1L, "local", List.of("10", "20"), 9L);

        assertEquals(123L, response.id());
        assertEquals("running", response.status());
        assertEquals("media_garbage_collection_delete", response.type());

        ArgumentCaptor<GenerationJob> jobCaptor = ArgumentCaptor.forClass(GenerationJob.class);
        verify(generationJobRepository).save(jobCaptor.capture());
        assertEquals("media_garbage_collection_delete", jobCaptor.getValue().getType());
        assertEquals("running", jobCaptor.getValue().getStatus());

        verify(mediaGarbageCollectionJobRunner)
                .runDelete(eq(123L), eq(10L), eq("local"), eq(1L), eq(List.of("10", "20")), eq(9L));
    }

    @Test
    void startDelete_環境にサイトが未設定なら例外() {
        MediaGarbageCollectionService service = service();
        Project project = buildProject(null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertThrows(IllegalArgumentException.class,
                () -> service.startDelete(1L, "local", List.of("10"), 9L));
    }
}
