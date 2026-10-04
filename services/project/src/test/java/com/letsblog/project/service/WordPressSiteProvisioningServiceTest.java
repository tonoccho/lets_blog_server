package com.letsblog.project.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.letsblog.project.dto.AdoptWordPressSiteRequest;
import com.letsblog.project.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.project.dto.SiteRegisterRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.domain.Site;
import com.letsblog.project.messaging.DomainEventPublisher;
import com.letsblog.project.provisioning.WordPressProvisioningClient;
import com.letsblog.project.provisioning.WordPressSyncClient;
import com.letsblog.project.repository.SiteRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
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
                new WordPressProvisioningClient.ProvisionResult("https://localhost/sites/my-site", "admin"));
        when(siteService.register(any())).thenReturn(new SiteResponse(
                1L, "Name", "my-site", null, null, Instant.now(), Instant.now(), "SUCCESS", false, false, null));
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("my-site");
        site.setCreatedAt(LocalDateTime.of(2026, 9, 8, 20, 3, 35));
        site.setUpdatedAt(LocalDateTime.of(2026, 9, 9, 1, 2, 3));
        when(siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(site));

        CreateManagedWordPressSiteRequest request = new CreateManagedWordPressSiteRequest(
                "Name", "my-site", "Title", "admin", "admin@example.com", "password", "ja", null);

        SiteResponse response = service().createManagedSite(request);

        assertEquals("my-site", response.siteKey());
        // issue #1237: UTC壁時計のLocalDateTimeを、実時刻を変えずZ終端のInstantとして返す
        assertEquals("2026-09-08T20:03:35Z", String.valueOf(response.createdAt()));
        assertEquals("2026-09-09T01:02:03Z", String.valueOf(response.updatedAt()));
        verify(siteRepository).save(site);
    }

    @Test
    void createManagedSite_サイト登録に失敗すればdeprovisionしてロールバックする() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(false);
        when(provisioningClient.provision(any())).thenReturn(
                new WordPressProvisioningClient.ProvisionResult("https://localhost/sites/my-site", "admin"));
        when(siteService.register(any())).thenThrow(new IllegalArgumentException("登録失敗"));

        CreateManagedWordPressSiteRequest request = new CreateManagedWordPressSiteRequest(
                "Name", "my-site", "Title", "admin", "admin@example.com", "password", "ja", null);

        assertThrows(IllegalArgumentException.class, () -> service().createManagedSite(request));
        verify(provisioningClient).deprovision("my-site", "wp_my-site");
    }

    @Test
    void createManagedSite_provisionに失敗すればdeprovisionしてロールバックする() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(false);
        when(provisioningClient.provision(any())).thenThrow(new ProvisioningException("失敗", null));

        CreateManagedWordPressSiteRequest request = new CreateManagedWordPressSiteRequest(
                "Name", "my-site", "Title", "admin", "admin@example.com", "password", "ja", null);

        assertThrows(ProvisioningException.class, () -> service().createManagedSite(request));
        verify(provisioningClient).deprovision("my-site", "wp_my-site");
    }

    @Test
    void createManagedSite_localeが未指定なら既定でjaを使う() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(false);
        when(provisioningClient.provision(any())).thenReturn(
                new WordPressProvisioningClient.ProvisionResult("https://localhost/sites/my-site", "admin"));
        when(siteService.register(any())).thenReturn(new SiteResponse(
                1L, "Name", "my-site", null, null, Instant.now(), Instant.now(), "SUCCESS", false, false, null));
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("my-site");
        when(siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(site));

        CreateManagedWordPressSiteRequest request = new CreateManagedWordPressSiteRequest(
                "Name", "my-site", "Title", "admin", "admin@example.com", "password", null, null);

        service().createManagedSite(request);

        verify(provisioningClient).provision(argThat(cmd -> "ja".equals(cmd.locale())));
    }

    @Test
    void createManagedSite_templateSiteId指定時はテンプレートから複製する() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(false);
        when(provisioningClient.provision(any())).thenReturn(
                new WordPressProvisioningClient.ProvisionResult("https://localhost/sites/my-site", "admin"));
        when(siteService.register(any())).thenReturn(new SiteResponse(
                1L, "Name", "my-site", null, null, Instant.now(), Instant.now(), "SUCCESS", false, false, null));
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("my-site");
        when(siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(site));

        Site templateSite = new Site();
        templateSite.setId(9L);
        templateSite.setManagedWordpress(true);
        templateSite.setWpSlug("template-slug");
        templateSite.setWpDbName("wp_template-slug");
        when(siteRepository.findById(9L)).thenReturn(Optional.of(templateSite));

        CreateManagedWordPressSiteRequest request = new CreateManagedWordPressSiteRequest(
                "Name", "my-site", "Title", "admin", "admin@example.com", "password", "ja", 9L);

        service().createManagedSite(request);

        verify(syncClient).sync(argThat(cmd -> "template-slug".equals(cmd.fromSlug())
                && "my-site".equals(cmd.toSlug())));
    }

    @Test
    void createManagedSite_テンプレートが自動構築サイトでなければ例外にしdeprovisionする() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(false);
        when(provisioningClient.provision(any())).thenReturn(
                new WordPressProvisioningClient.ProvisionResult("https://localhost/sites/my-site", "admin"));
        when(siteService.register(any())).thenReturn(new SiteResponse(
                1L, "Name", "my-site", null, null, Instant.now(), Instant.now(), "SUCCESS", false, false, null));
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("my-site");
        when(siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(site));

        Site templateSite = new Site();
        templateSite.setId(9L);
        templateSite.setManagedWordpress(false);
        when(siteRepository.findById(9L)).thenReturn(Optional.of(templateSite));

        CreateManagedWordPressSiteRequest request = new CreateManagedWordPressSiteRequest(
                "Name", "my-site", "Title", "admin", "admin@example.com", "password", "ja", 9L);

        assertThrows(IllegalArgumentException.class, () -> service().createManagedSite(request));
        verify(provisioningClient).deprovision("my-site", "wp_my-site");
        verify(siteRepository).delete(site);
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

    @Test
    void deleteSite_managedでなければdeprovisionを呼ばずに削除する() {
        Site site = new Site();
        site.setId(1L);
        site.setManagedWordpress(false);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        service().deleteSite(1L);

        verify(provisioningClient, never()).deprovision(any(), any());
        verify(siteRepository).delete(site);
        verify(domainEventPublisher).publishSiteDeleted(1L);
    }

    @Test
    void deleteSite_他に同じwp_slugを参照するサイトが無ければ従来通りdeprovisionする() {
        Site site = new Site();
        site.setId(1L);
        site.setManagedWordpress(true);
        site.setWpSlug("my-site");
        site.setWpDbName("wp_my-site");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        Site unrelated = new Site();
        unrelated.setId(2L);
        unrelated.setManagedWordpress(true);
        unrelated.setWpSlug("other-site");
        unrelated.setWpDbName("wp_other-site");
        when(siteRepository.findAll()).thenReturn(List.of(site, unrelated));

        service().deleteSite(1L);

        verify(provisioningClient).deprovision("my-site", "wp_my-site");
        verify(siteRepository).delete(site);
        verify(domainEventPublisher).publishSiteDeleted(1L);
    }

    // issue #1198でadoptManagedSiteに分岐(wp_slug共有チェック)を追加したことでファイル単位の
    // カバレッジ計測対象になったため、この機会にadoptManagedSite自身の既存の未網羅分岐
    // (siteKey重複チェック)も補完しておく(createManagedSiteの対応するテストと同じ形)。

    @Test
    void adoptManagedSite_既にsiteKeyがあれば例外() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(true);

        AdoptWordPressSiteRequest request = new AdoptWordPressSiteRequest("Name", "my-site", "admin");

        assertThrows(IllegalArgumentException.class, () -> service().adoptManagedSite(request));
    }

    @Test
    void adoptManagedSite_通常のadoptはwp_slugの衝突が無ければ登録されWARNログも出さない() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(false);
        when(provisioningClient.adopt(any())).thenReturn(
                new WordPressProvisioningClient.ProvisionResult("https://localhost/sites/my-site", "admin"));
        when(siteService.register(any())).thenReturn(new SiteResponse(
                1L, "Name", "my-site", null, null, Instant.now(), Instant.now(), "SUCCESS", false, false, null));

        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("my-site");
        site.setCreatedAt(LocalDateTime.of(2026, 9, 8, 20, 3, 35));
        site.setUpdatedAt(LocalDateTime.of(2026, 9, 9, 1, 2, 3));
        when(siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(site));
        when(siteRepository.findAll()).thenReturn(List.of(site));

        Logger logger = (Logger) LoggerFactory.getLogger(WordPressSiteProvisioningService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        AdoptWordPressSiteRequest request = new AdoptWordPressSiteRequest("Name", "my-site", "admin");
        SiteResponse response;
        try {
            response = service().adoptManagedSite(request);
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals("my-site", response.siteKey());
        assertEquals("2026-09-08T20:03:35Z", String.valueOf(response.createdAt()));
        assertEquals("2026-09-09T01:02:03Z", String.valueOf(response.updatedAt()));
        verify(siteRepository).save(site);
        boolean warned = appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN);
        assertFalse(warned, "wp_slugの衝突が無ければWARNログを出さないこと");
    }

    /**
     * issue #1198 AC1: 同じ実体(同一wp_slug)を指す2件目のadoptは、拒否まではしない
     * (site-adoption.featureのフィクスチャ手順がこの成功を前提にしているため。Out of Scope)が、
     * 「無条件で成功しただけ」ではなく、共有先の既存サイトを名指ししたWARNログという形で
     * 明示的に許可された操作であることが分かるようにする。
     */
    @Test
    void adoptManagedSite_同じwp_slugを指す既存サイトがあれば共有をWARNログで明示する() {
        when(siteRepository.existsBySiteKey("target_key")).thenReturn(false);
        when(provisioningClient.adopt(any())).thenReturn(
                new WordPressProvisioningClient.ProvisionResult(
                        "https://localhost/sites/target-key", "admin"));
        when(siteService.register(any())).thenReturn(new SiteResponse(
                2L, "Name", "target_key", null, null, Instant.now(), Instant.now(), "SUCCESS", false, false, null));

        Site newSite = new Site();
        newSite.setId(2L);
        newSite.setSiteKey("target_key");
        when(siteRepository.findBySiteKey("target_key")).thenReturn(Optional.of(newSite));

        Site existing = new Site();
        existing.setId(1L);
        existing.setSiteKey("target-key");
        existing.setManagedWordpress(true);
        existing.setWpSlug("target-key");
        existing.setWpDbName("wp_target-key");
        when(siteRepository.findAll()).thenReturn(List.of(existing, newSite));

        Logger logger = (Logger) LoggerFactory.getLogger(WordPressSiteProvisioningService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        AdoptWordPressSiteRequest request = new AdoptWordPressSiteRequest("Name", "target_key", "admin");
        SiteResponse response;
        try {
            response = service().adoptManagedSite(request);
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals("target_key", response.siteKey());
        boolean warned = appender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("target-key")
                        && event.getFormattedMessage().contains("1"));
        assertTrue(warned,
                "同じwp_slugを共有する既存サイト(id=1, siteKey=target-key)を名指ししたWARNログが出力されること");
    }

    @Test
    void deleteSite_同じwp_slugを指す他のサイトがあればdeprovisionせずレコードだけ削除する() {
        Site site = new Site();
        site.setId(1L);
        site.setManagedWordpress(true);
        site.setWpSlug("shared-slug");
        site.setWpDbName("wp_shared-slug");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        Site sibling = new Site();
        sibling.setId(2L);
        sibling.setManagedWordpress(true);
        sibling.setWpSlug("shared-slug");
        sibling.setWpDbName("wp_shared-slug");
        when(siteRepository.findAll()).thenReturn(List.of(site, sibling));

        service().deleteSite(1L);

        verify(provisioningClient, never()).deprovision(any(), any());
        verify(siteRepository).delete(site);
        verify(domainEventPublisher).publishSiteDeleted(1L);
    }

    @Test
    void createManagedSite_登録する認証情報にappPasswordを含めない_issue1565() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(false);
        when(provisioningClient.provision(any())).thenReturn(
                new WordPressProvisioningClient.ProvisionResult("https://localhost/sites/my-site", "admin"));
        when(siteService.register(any())).thenReturn(new SiteResponse(
                1L, "Name", "my-site", null, null, Instant.now(), Instant.now(), "SUCCESS", false, false, null));
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("my-site");
        when(siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(site));

        service().createManagedSite(new CreateManagedWordPressSiteRequest(
                "Name", "my-site", "Title", "admin", "admin@example.com", "password", "ja", null));

        verify(siteService).register(argThat(req -> !req.credentials().containsKey("appPassword")
                && "AGENT".equals(req.credentials().get("transport"))
                && "admin".equals(req.credentials().get("username"))));
    }

    @Test
    void adoptManagedSite_登録する認証情報にappPasswordを含めない_issue1565() {
        when(siteRepository.existsBySiteKey("target_key")).thenReturn(false);
        when(provisioningClient.adopt(any())).thenReturn(
                new WordPressProvisioningClient.ProvisionResult(
                        "https://localhost/sites/target-key", "admin"));
        when(siteService.register(any())).thenReturn(new SiteResponse(
                2L, "Name", "target_key", null, null, Instant.now(), Instant.now(), "SUCCESS", false, false, null));
        Site site = new Site();
        site.setId(2L);
        site.setSiteKey("target_key");
        when(siteRepository.findBySiteKey("target_key")).thenReturn(Optional.of(site));

        service().adoptManagedSite(new com.letsblog.project.dto.AdoptWordPressSiteRequest(
                "Name", "target_key", "admin"));

        verify(siteService).register(argThat(req -> !req.credentials().containsKey("appPassword")
                && "AGENT".equals(req.credentials().get("transport"))));
    }
}
