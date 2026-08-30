package com.letsblog.project.service;

import com.letsblog.project.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.project.dto.SiteRegisterRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.domain.Site;
import com.letsblog.project.messaging.DomainEventPublisher;
import com.letsblog.project.provisioning.WordPressProvisioningClient;
import com.letsblog.project.provisioning.WordPressSyncClient;
import com.letsblog.project.repository.SiteRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** WordPressSiteProvisioningServiceの回帰テスト(issue #577 stage2、legacy-apiから移設)。 */
@ExtendWith(MockitoExtension.class)
class WordPressSiteProvisioningServiceTest {

    @Mock
    private WordPressProvisioningClient provisioningClient;
    @Mock
    private WordPressSyncClient syncClient;
    @Mock
    private SiteService siteService;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private DomainEventPublisher domainEventPublisher;

    private WordPressSiteProvisioningService service() {
        return new WordPressSiteProvisioningService(
                provisioningClient, syncClient, siteService, siteRepository, domainEventPublisher);
    }

    @Test
    void createManagedSite_既にsiteKeyがあれば例外() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(true);

        CreateManagedWordPressSiteRequest request = new CreateManagedWordPressSiteRequest(
                "Name", "my-site", "Title", "admin", "admin@example.com", "password", "ja", null);

        assertThrows(IllegalArgumentException.class, () -> service().createManagedSite(request));
    }

    @Test
    void createManagedSite_成功時にサイトをmanagedWordpressとして更新する() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(false);
        when(provisioningClient.provision(any())).thenReturn(
                new WordPressProvisioningClient.ProvisionResult("https://localhost/sites/my-site", "admin", "app-pw"));
        when(siteService.register(any())).thenReturn(new SiteResponse(
                1L, "Name", "my-site", null, null, LocalDateTime.now(), LocalDateTime.now(), "SUCCESS", false, false));
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("my-site");
        when(siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(site));

        CreateManagedWordPressSiteRequest request = new CreateManagedWordPressSiteRequest(
                "Name", "my-site", "Title", "admin", "admin@example.com", "password", "ja", null);

        SiteResponse response = service().createManagedSite(request);

        assertEquals("my-site", response.siteKey());
        verify(siteRepository).save(site);
    }

    @Test
    void createManagedSite_サイト登録に失敗すればdeprovisionしてロールバックする() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(false);
        when(provisioningClient.provision(any())).thenReturn(
                new WordPressProvisioningClient.ProvisionResult("https://localhost/sites/my-site", "admin", "app-pw"));
        when(siteService.register(any())).thenThrow(new IllegalArgumentException("登録失敗"));

        CreateManagedWordPressSiteRequest request = new CreateManagedWordPressSiteRequest(
                "Name", "my-site", "Title", "admin", "admin@example.com", "password", "ja", null);

        assertThrows(IllegalArgumentException.class, () -> service().createManagedSite(request));
        verify(provisioningClient).deprovision("my-site", "wp_my-site");
    }

    @Test
    void deleteSite_managedサイトはdeprovisionしてから削除する() {
        Site site = new Site();
        site.setId(1L);
        site.setManagedWordpress(true);
        site.setWpSlug("my-site");
        site.setWpDbName("wp_my-site");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        service().deleteSite(1L);

        verify(provisioningClient).deprovision("my-site", "wp_my-site");
        verify(siteRepository).delete(site);
        verify(domainEventPublisher).publishSiteDeleted(1L);
    }

    @Test
    void deleteSite_存在しなければNotFound() {
        when(siteRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(SiteNotFoundException.class, () -> service().deleteSite(1L));
    }
}
