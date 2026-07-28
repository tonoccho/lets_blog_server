package com.letsblog.api.service;

import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.provisioning.WordPressProvisioningClient;
import com.letsblog.api.repository.PostRepository;
import com.letsblog.api.repository.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WordPressSiteProvisioningServiceTest {

    @Mock
    private WordPressProvisioningClient provisioningClient;

    @Mock
    private SiteService siteService;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private PostRepository postRepository;

    private WordPressSiteProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new WordPressSiteProvisioningService(provisioningClient, siteService, siteRepository, postRepository);
    }

    private CreateManagedWordPressSiteRequest request() {
        return new CreateManagedWordPressSiteRequest(
                "My Blog", "main", "My Blog", "admin", "admin@example.com", "s3cret-pass");
    }

    @Test
    void createManagedSite_正常に構築しsiteServiceへ登録しフラグを保存する() {
        when(siteRepository.existsBySiteKey("main")).thenReturn(false);
        when(provisioningClient.provision(any())).thenReturn(new WordPressProvisioningClient.ProvisionResult(
                "https://localhost/sites/main", "admin", "app-pass-1234"));

        SiteResponse response = new SiteResponse(1L, "My Blog", "main", CmsType.WORDPRESS,
                "https://localhost/sites/main", LocalDateTime.now(), LocalDateTime.now(), "SUCCESS", false);
        when(siteService.register(any(), eq(9L))).thenReturn(response);

        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("main");
        when(siteRepository.findBySiteKey("main")).thenReturn(Optional.of(site));

        SiteResponse result = service.createManagedSite(request(), 9L);

        assertEquals(1L, result.id());
        assertTrue(site.isManagedWordpress());
        assertEquals("main", site.getWpSlug());
        assertEquals("wp_main", site.getWpDbName());
        verify(siteRepository).save(site);
        verify(provisioningClient, never()).deprovision(any(), any());
    }

    @Test
    void createManagedSite_siteKeyが重複していれば構築せずに例外() {
        when(siteRepository.existsBySiteKey("main")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.createManagedSite(request(), 9L));

        verify(provisioningClient, never()).provision(any());
    }

    @Test
    void createManagedSite_サイト登録失敗時は構築済みインスタンスを削除して例外を伝播する() {
        when(siteRepository.existsBySiteKey("main")).thenReturn(false);
        when(provisioningClient.provision(any())).thenReturn(new WordPressProvisioningClient.ProvisionResult(
                "https://localhost/sites/main", "admin", "app-pass-1234"));
        when(siteService.register(any(), eq(9L))).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> service.createManagedSite(request(), 9L));

        verify(provisioningClient).deprovision("main", "wp_main");
        verify(siteRepository, never()).save(any());
    }

    @Test
    void deleteSite_managedWordpressならインスタンスとDBも削除する() {
        Site site = new Site();
        site.setId(1L);
        site.setManagedWordpress(true);
        site.setWpSlug("main");
        site.setWpDbName("wp_main");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        service.deleteSite(1L);

        verify(provisioningClient).deprovision("main", "wp_main");
        verify(postRepository).deleteBySiteId(1L);
        verify(siteRepository).delete(site);
    }

    @Test
    void deleteSite_外部登録サイトはインフラ削除を行わない() {
        Site site = new Site();
        site.setId(2L);
        site.setManagedWordpress(false);
        when(siteRepository.findById(2L)).thenReturn(Optional.of(site));

        service.deleteSite(2L);

        verify(provisioningClient, never()).deprovision(any(), any());
        verify(postRepository).deleteBySiteId(2L);
        verify(siteRepository).delete(site);
    }

    @Test
    void deleteSite_存在しなければ例外() {
        when(siteRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(SiteNotFoundException.class, () -> service.deleteSite(99L));
    }
}
