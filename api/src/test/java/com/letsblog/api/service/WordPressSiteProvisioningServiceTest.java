package com.letsblog.api.service;

import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.AdoptWordPressSiteRequest;
import com.letsblog.api.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.provisioning.WordPressProvisioningClient;
import com.letsblog.api.provisioning.WordPressSyncClient;
import com.letsblog.api.repository.PostRepository;
import com.letsblog.api.repository.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private WordPressSyncClient syncClient;

    @Mock
    private SiteService siteService;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private PostRepository postRepository;

    private WordPressSiteProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new WordPressSiteProvisioningService(
                provisioningClient, syncClient, siteService, siteRepository, postRepository);
    }

    private CreateManagedWordPressSiteRequest request() {
        return new CreateManagedWordPressSiteRequest(
                "My Blog", "main", "My Blog", "admin", "admin@example.com", "s3cret-pass", null, null);
    }

    private AdoptWordPressSiteRequest adoptRequest() {
        return new AdoptWordPressSiteRequest("My Blog", "main", "admin");
    }

    private CreateManagedWordPressSiteRequest requestWithTemplate(Long templateSiteId) {
        return new CreateManagedWordPressSiteRequest(
                "My Blog", "main", "My Blog", "admin", "admin@example.com", "s3cret-pass", null, templateSiteId);
    }

    private void stubSuccessfulProvisionAndRegister() {
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
    void createManagedSite_credentialsにtransportAGENTとwpSlugを設定する() {
        when(siteRepository.existsBySiteKey("main")).thenReturn(false);
        when(provisioningClient.provision(any())).thenReturn(new WordPressProvisioningClient.ProvisionResult(
                "https://localhost/sites/main", "admin", "app-pass-1234"));
        SiteResponse response = new SiteResponse(1L, "My Blog", "main", CmsType.WORDPRESS,
                "https://localhost/sites/main", LocalDateTime.now(), LocalDateTime.now(), "SUCCESS", false);
        ArgumentCaptor<com.letsblog.api.dto.SiteRegisterRequest> registerCaptor =
                ArgumentCaptor.forClass(com.letsblog.api.dto.SiteRegisterRequest.class);
        when(siteService.register(registerCaptor.capture(), eq(9L))).thenReturn(response);
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("main");
        when(siteRepository.findBySiteKey("main")).thenReturn(Optional.of(site));

        service.createManagedSite(request(), 9L);

        assertEquals("AGENT", registerCaptor.getValue().credentials().get("transport"));
        assertEquals("main", registerCaptor.getValue().credentials().get("wpSlug"));
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
    void createManagedSite_locale未指定の場合はjaがデフォルトで渡される() {
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

        service.createManagedSite(request(), 9L);

        ArgumentCaptor<WordPressProvisioningClient.ProvisionCommand> captor =
                ArgumentCaptor.forClass(WordPressProvisioningClient.ProvisionCommand.class);
        verify(provisioningClient).provision(captor.capture());
        assertEquals("ja", captor.getValue().locale());
    }

    @Test
    void createManagedSite_locale指定時はそのままProvisionCommandへ伝搬する() {
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

        CreateManagedWordPressSiteRequest requestWithLocale = new CreateManagedWordPressSiteRequest(
                "My Blog", "main", "My Blog", "admin", "admin@example.com", "s3cret-pass", "en_US", null);

        service.createManagedSite(requestWithLocale, 9L);

        ArgumentCaptor<WordPressProvisioningClient.ProvisionCommand> captor =
                ArgumentCaptor.forClass(WordPressProvisioningClient.ProvisionCommand.class);
        verify(provisioningClient).provision(captor.capture());
        assertEquals("en_US", captor.getValue().locale());
    }

    @Test
    void createManagedSite_provision呼び出し自体が失敗した場合はdeprovisionして例外を伝播する() {
        when(siteRepository.existsBySiteKey("main")).thenReturn(false);
        when(provisioningClient.provision(any())).thenThrow(new ProvisioningException("接続に失敗しました", null));

        assertThrows(ProvisioningException.class, () -> service.createManagedSite(request(), 9L));

        verify(provisioningClient).deprovision("main", "wp_main");
        verify(siteService, never()).register(any(), any());
    }

    @Test
    void createManagedSite_サイトが既に存在する場合はdeprovisionせずに例外を伝播する() {
        when(siteRepository.existsBySiteKey("main")).thenReturn(false);
        when(provisioningClient.provision(any()))
                .thenThrow(new SiteAlreadyProvisionedException("サイト 'main' は既に存在します", null));

        assertThrows(SiteAlreadyProvisionedException.class, () -> service.createManagedSite(request(), 9L));

        verify(provisioningClient, never()).deprovision(any(), any());
        verify(siteService, never()).register(any(), any());
    }

    @Test
    void adoptManagedSite_取り込みに成功しsiteServiceへ登録しフラグを保存する() {
        when(siteRepository.existsBySiteKey("main")).thenReturn(false);
        when(provisioningClient.adopt(any())).thenReturn(new WordPressProvisioningClient.ProvisionResult(
                "https://localhost/sites/main", "admin", "app-pass-1234"));
        SiteResponse response = new SiteResponse(1L, "My Blog", "main", CmsType.WORDPRESS,
                "https://localhost/sites/main", LocalDateTime.now(), LocalDateTime.now(), "SUCCESS", false);
        when(siteService.register(any(), eq(9L))).thenReturn(response);
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("main");
        when(siteRepository.findBySiteKey("main")).thenReturn(Optional.of(site));

        SiteResponse result = service.adoptManagedSite(adoptRequest(), 9L);

        assertEquals(1L, result.id());
        assertTrue(site.isManagedWordpress());
        assertEquals("main", site.getWpSlug());
        assertEquals("wp_main", site.getWpDbName());
        verify(siteRepository).save(site);
        verify(provisioningClient, never()).deprovision(any(), any());
    }

    @Test
    void adoptManagedSite_siteKeyが重複していれば取り込まずに例外() {
        when(siteRepository.existsBySiteKey("main")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.adoptManagedSite(adoptRequest(), 9L));

        verify(provisioningClient, never()).adopt(any());
    }

    @Test
    void adoptManagedSite_取り込み先が見つからない場合は例外を伝播しdeprovisionしない() {
        when(siteRepository.existsBySiteKey("main")).thenReturn(false);
        when(provisioningClient.adopt(any()))
                .thenThrow(new SiteNotFoundException("サイト 'main' が見つかりません"));

        assertThrows(SiteNotFoundException.class, () -> service.adoptManagedSite(adoptRequest(), 9L));

        verify(provisioningClient, never()).deprovision(any(), any());
        verify(siteService, never()).register(any(), any());
    }

    @Test
    void adoptManagedSite_サイト登録失敗時もdeprovisionしない() {
        when(siteRepository.existsBySiteKey("main")).thenReturn(false);
        when(provisioningClient.adopt(any())).thenReturn(new WordPressProvisioningClient.ProvisionResult(
                "https://localhost/sites/main", "admin", "app-pass-1234"));
        when(siteService.register(any(), eq(9L))).thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class, () -> service.adoptManagedSite(adoptRequest(), 9L));

        verify(provisioningClient, never()).deprovision(any(), any());
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

    @Test
    void createManagedSite_templateSiteId未指定ならsyncClientは呼ばれない() {
        stubSuccessfulProvisionAndRegister();

        service.createManagedSite(request(), 9L);

        verify(syncClient, never()).sync(any());
    }

    @Test
    void createManagedSite_templateSiteId指定時はWordPressSyncClientでクローンする() {
        stubSuccessfulProvisionAndRegister();
        Site templateSite = new Site();
        templateSite.setId(5L);
        templateSite.setManagedWordpress(true);
        templateSite.setWpSlug("template-site");
        templateSite.setWpDbName("wp_template-site");
        when(siteRepository.findById(5L)).thenReturn(Optional.of(templateSite));

        service.createManagedSite(requestWithTemplate(5L), 9L);

        ArgumentCaptor<WordPressSyncClient.SyncCommand> captor =
                ArgumentCaptor.forClass(WordPressSyncClient.SyncCommand.class);
        verify(syncClient).sync(captor.capture());
        assertEquals("template-site", captor.getValue().fromSlug());
        assertEquals("wp_template-site", captor.getValue().fromDbName());
        assertEquals("main", captor.getValue().toSlug());
        assertEquals("wp_main", captor.getValue().toDbName());
        assertEquals(java.util.List.of("themes", "plugins", "media", "db"), captor.getValue().targets());
    }

    @Test
    void createManagedSite_テンプレートが非managedなら構築済みリソースを削除して例外() {
        stubSuccessfulProvisionAndRegister();
        Site templateSite = new Site();
        templateSite.setId(5L);
        templateSite.setManagedWordpress(false);
        when(siteRepository.findById(5L)).thenReturn(Optional.of(templateSite));

        assertThrows(IllegalArgumentException.class, () -> service.createManagedSite(requestWithTemplate(5L), 9L));

        verify(provisioningClient).deprovision("main", "wp_main");
        ArgumentCaptor<Site> deletedSiteCaptor = ArgumentCaptor.forClass(Site.class);
        verify(siteRepository).delete(deletedSiteCaptor.capture());
        assertEquals("main", deletedSiteCaptor.getValue().getSiteKey());
        verify(syncClient, never()).sync(any());
    }

    @Test
    void createManagedSite_クローン失敗時は構築済みリソースを削除して例外を伝播する() {
        stubSuccessfulProvisionAndRegister();
        Site templateSite = new Site();
        templateSite.setId(5L);
        templateSite.setManagedWordpress(true);
        templateSite.setWpSlug("template-site");
        templateSite.setWpDbName("wp_template-site");
        when(siteRepository.findById(5L)).thenReturn(Optional.of(templateSite));
        doThrow(new ProvisioningException("環境同期に失敗しました", null)).when(syncClient).sync(any());

        assertThrows(ProvisioningException.class, () -> service.createManagedSite(requestWithTemplate(5L), 9L));

        verify(provisioningClient).deprovision("main", "wp_main");
        ArgumentCaptor<Site> deletedSiteCaptor = ArgumentCaptor.forClass(Site.class);
        verify(siteRepository).delete(deletedSiteCaptor.capture());
        assertEquals("main", deletedSiteCaptor.getValue().getSiteKey());
    }

    @Test
    void createManagedSite_存在しないtemplateSiteIdは例外() {
        stubSuccessfulProvisionAndRegister();
        when(siteRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(SiteNotFoundException.class, () -> service.createManagedSite(requestWithTemplate(99L), 9L));
    }
}
