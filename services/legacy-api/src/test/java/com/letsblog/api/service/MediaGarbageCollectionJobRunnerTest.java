package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.domain.Site;
import com.letsblog.api.repository.GenerationJobRepository;
import com.letsblog.api.repository.SiteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaGarbageCollectionJobRunnerTest {

    @Mock
    private SiteRepository siteRepository;
    @Mock
    private SiteService siteService;
    @Mock
    private CmsAdapterFactory cmsAdapterFactory;
    @Mock
    private GenerationJobRepository generationJobRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private CmsAdapter cmsAdapter;

    private MediaGarbageCollectionJobRunner runner() {
        return new MediaGarbageCollectionJobRunner(
                siteRepository, siteService, cmsAdapterFactory, generationJobRepository,
                auditLogService, new ObjectMapper());
    }

    private Site buildSite() {
        Site site = new Site();
        site.setId(10L);
        site.setSiteKey("local-site");
        site.setCmsType(CmsType.WORDPRESS);
        return site;
    }

    private CmsCredentials.WordPressCredentials creds() {
        return new CmsCredentials.WordPressCredentials("https://local.test", "admin", "SSH");
    }

    private GenerationJob buildJob() {
        GenerationJob job = new GenerationJob();
        job.setId(123L);
        job.setType("media_garbage_collection_delete");
        job.setStatus("running");
        return job;
    }

    @Test
    void runDelete_全件成功で完了しAuditLogを記録する() {
        Site site = buildSite();
        when(siteRepository.findById(10L)).thenReturn(Optional.of(site));
        CmsCredentials.WordPressCredentials creds = creds();
        when(siteService.getCredentials("local-site")).thenReturn(creds);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        GenerationJob job = buildJob();
        when(generationJobRepository.findById(123L)).thenReturn(Optional.of(job));
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(inv -> inv.getArgument(0));

        runner().runDelete(123L, 10L, "local", 1L, List.of("10", "20"), 9L, "keycloak-sub-1");

        verify(cmsAdapter).deleteMedia(creds, "10");
        verify(cmsAdapter).deleteMedia(creds, "20");
        assertEquals("done", job.getStatus());
        assertTrue(job.getResultPayload().contains("\"deletedCount\":2"));
        assertTrue(job.getResultPayload().contains("\"failedCount\":0"));

        verify(auditLogService).log(eq(9L), eq("keycloak-sub-1"), eq(AuditLogAction.MEDIA_GARBAGE_COLLECTED), eq("PROJECT"), eq(1L),
                any(String.class), eq(null), eq(null));
    }

    @Test
    void runDelete_一部失敗しても完了しfailuresを記録する() {
        Site site = buildSite();
        when(siteRepository.findById(10L)).thenReturn(Optional.of(site));
        CmsCredentials.WordPressCredentials creds = creds();
        when(siteService.getCredentials("local-site")).thenReturn(creds);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        GenerationJob job = buildJob();
        when(generationJobRepository.findById(123L)).thenReturn(Optional.of(job));
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(inv -> inv.getArgument(0));

        doNothing().when(cmsAdapter).deleteMedia(creds, "10");
        doThrow(new RuntimeException("削除エラー")).when(cmsAdapter).deleteMedia(creds, "20");

        runner().runDelete(123L, 10L, "local", 1L, List.of("10", "20"), 9L, "keycloak-sub-1");

        assertEquals("done", job.getStatus());
        assertTrue(job.getResultPayload().contains("\"deletedCount\":1"));
        assertTrue(job.getResultPayload().contains("\"failedCount\":1"));
        verify(auditLogService).log(eq(9L), eq("keycloak-sub-1"), eq(AuditLogAction.MEDIA_GARBAGE_COLLECTED), eq("PROJECT"), eq(1L),
                any(String.class), eq(null), eq(null));
    }

    @Test
    void runDelete_全件失敗はジョブをfailedにする() {
        Site site = buildSite();
        when(siteRepository.findById(10L)).thenReturn(Optional.of(site));
        CmsCredentials.WordPressCredentials creds = creds();
        when(siteService.getCredentials("local-site")).thenReturn(creds);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        GenerationJob job = buildJob();
        when(generationJobRepository.findById(123L)).thenReturn(Optional.of(job));
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(inv -> inv.getArgument(0));

        doThrow(new RuntimeException("削除エラー")).when(cmsAdapter).deleteMedia(creds, "10");

        runner().runDelete(123L, 10L, "local", 1L, List.of("10"), 9L, "keycloak-sub-1");

        assertEquals("failed", job.getStatus());
        assertTrue(job.getResultPayload().contains("\"failedCount\":1"));
    }

    @Test
    void runDelete_致命的失敗はジョブをfailedにしAuditLogは記録しない() {
        when(siteRepository.findById(10L)).thenReturn(Optional.empty());
        GenerationJob job = buildJob();
        when(generationJobRepository.findById(123L)).thenReturn(Optional.of(job));
        when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(inv -> inv.getArgument(0));

        runner().runDelete(123L, 10L, "local", 1L, List.of("10"), 9L, "keycloak-sub-1");

        assertEquals("failed", job.getStatus());
        verify(auditLogService, never()).log(anyLong(), any(), any(), any(), any(), any(), any(), any());
    }
}
