package com.letsblog.project.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.common.messaging.EventExchanges;
import com.letsblog.common.messaging.SiteDeletedEvent;
import com.letsblog.project.dto.AdoptWordPressSiteRequest;
import com.letsblog.project.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.domain.Site;
import com.letsblog.project.testsupport.FakeHttpServer;
import com.letsblog.project.testsupport.ProjectWorld;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WordPressSiteProvisioningServiceの回帰テスト(issue #577 stage2、legacy-apiから移設)。
 *
 * <p>issue #1817: SiteService・各クライアント・DomainEventPublisher は実物。差し替えるのはDB(SiteRepository)・
 * MQ(RabbitTemplate)と、HTTPの向こうのprovision-agent・publishing-service(フェイクHTTPサーバー)だけ。
 */
class WordPressSiteProvisioningServiceTest {

    private static final String PROVISION_URL = "https://localhost/sites/my-site";

    private final ProjectWorld world = new ProjectWorld();
    private WordPressSiteProvisioningService service;

    @BeforeEach
    void setUp() {
        service = new WordPressSiteProvisioningService(world.wordPressProvisioningClient, world.wordPressSyncClient,
                world.siteService, world.siteRepository, world.domainEventPublisher);
        world.registrationSucceeds();
        world.respond("POST", "/provision", java.util.Map.of("url", PROVISION_URL, "adminUser", "admin"));
        world.http.on("POST", "/deprovision", FakeHttpServer.Response.empty(200));
        world.http.on("POST", "/sync", FakeHttpServer.Response.empty(200));
    }

    @AfterEach
    void close() {
        world.close();
    }

    private static CreateManagedWordPressSiteRequest request(String locale, Long templateSiteId) {
        return new CreateManagedWordPressSiteRequest(
                "Name", "my-site", "Title", "admin", "admin@example.com", "password", locale, templateSiteId);
    }

    private Site registeredSite() {
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("my-site");
        site.setCreatedAt(LocalDateTime.of(2026, 9, 8, 20, 3, 35));
        site.setUpdatedAt(LocalDateTime.of(2026, 9, 9, 1, 2, 3));
        when(world.siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(site));
        return site;
    }

    private void assertDeprovisioned(String slug, String dbName) {
        List<FakeHttpServer.Request> sent = world.http.requests("POST", "/deprovision");
        assertEquals(1, sent.size());
        assertEquals(slug, world.bodyOf(sent.get(0)).get("slug").asText());
        assertEquals(dbName, world.bodyOf(sent.get(0)).get("dbName").asText());
    }

    @Test
    void createManagedSite_既にsiteKeyがあれば例外() {
        when(world.siteRepository.existsBySiteKey("my-site")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.createManagedSite(request("ja", null)));
        assertTrue(world.http.requests("POST", "/provision").isEmpty());
    }

    @Test
    void createManagedSite_成功時にサイトをmanagedWordpressとして更新する() {
        Site site = registeredSite();

        SiteResponse response = service.createManagedSite(request("ja", null));

        assertEquals("my-site", response.siteKey());
        // issue #1237: UTC壁時計のLocalDateTimeを、実時刻を変えずZ終端のInstantとして返す
        assertEquals("2026-09-08T20:03:35Z", String.valueOf(response.createdAt()));
        assertEquals("2026-09-09T01:02:03Z", String.valueOf(response.updatedAt()));
        assertTrue(site.isManagedWordpress());
        assertEquals("my-site", site.getWpSlug());
        assertEquals("wp_my-site", site.getWpDbName());
        assertEquals(PROVISION_URL, site.getBaseUrl());
        verify(world.siteRepository).save(site);
    }

    @Test
    void createManagedSite_サイト登録に失敗すればdeprovisionしてロールバックする() {
        world.fail("POST", "/api/internal/project/cms/provision", 500);

        assertThrows(RuntimeException.class, () -> service.createManagedSite(request("ja", null)));
        assertDeprovisioned("my-site", "wp_my-site");
    }

    @Test
    void createManagedSite_provisionに失敗すればdeprovisionしてロールバックする() {
        world.fail("POST", "/provision", 500);

        assertThrows(ProvisioningException.class, () -> service.createManagedSite(request("ja", null)));
        assertDeprovisioned("my-site", "wp_my-site");
    }

    @Test
    void createManagedSite_localeが未指定なら既定でjaを使う() {
        registeredSite();

        service.createManagedSite(request(null, null));
        service.createManagedSite(request(" ", null));
        service.createManagedSite(request("en", null));

        List<JsonNode> sent = world.bodiesOf("POST", "/provision");
        assertEquals("ja", sent.get(0).get("locale").asText());
        assertEquals("ja", sent.get(1).get("locale").asText());
        assertEquals("en", sent.get(2).get("locale").asText());
    }

    @Test
    void createManagedSite_templateSiteId指定時はテンプレートから複製する() {
        registeredSite();
        world.addManagedSite(9L, "template-slug", "wp_template-slug");

        service.createManagedSite(request("ja", 9L));

        JsonNode sync = world.bodiesOf("POST", "/sync").get(0);
        assertEquals("template-slug", sync.get("fromSlug").asText());
        assertEquals("my-site", sync.get("toSlug").asText());
        assertEquals("wp_my-site", sync.get("toDbName").asText());
    }

    @Test
    void createManagedSite_テンプレートが自動構築サイトでなければ例外にしdeprovisionする() {
        Site site = registeredSite();
        world.addSite(9L, "テンプレート");

        assertThrows(IllegalArgumentException.class, () -> service.createManagedSite(request("ja", 9L)));
        assertDeprovisioned("my-site", "wp_my-site");
        verify(world.siteRepository).delete(site);
    }

    @Test
    void createManagedSite_テンプレートが登録に無ければSiteNotFoundで例外にする() {
        registeredSite();

        assertThrows(SiteNotFoundException.class, () -> service.createManagedSite(request("ja", 9L)));
    }

    @Test
    void createManagedSite_テンプレートの複製に失敗すればdeprovisionしてサイトを削除する() {
        Site site = registeredSite();
        world.addManagedSite(9L, "template-slug", "wp_template-slug");
        world.fail("POST", "/sync", 500);

        assertThrows(ProvisioningException.class, () -> service.createManagedSite(request("ja", 9L)));
        assertDeprovisioned("my-site", "wp_my-site");
        verify(world.siteRepository).delete(site);
    }

    @Test
    void createManagedSite_登録したsiteKeyのサイトが見つからなければSiteNotFound() {
        assertThrows(SiteNotFoundException.class, () -> service.createManagedSite(request("ja", null)));
    }

    @Test
    void deleteSite_managedサイトはdeprovisionしてから削除する() {
        Site site = world.addManagedSite(1L, "my-site", "wp_my-site");

        service.deleteSite(1L);

        assertDeprovisioned("my-site", "wp_my-site");
        verify(world.siteRepository).delete(site);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(world.rabbitTemplate).convertAndSend(eq(EventExchanges.EVENTS_EXCHANGE),
                eq(EventExchanges.SITE_DELETED_ROUTING_KEY), event.capture());
        assertEquals(1L, ((SiteDeletedEvent) event.getValue()).siteId());
    }

    @Test
    void deleteSite_存在しなければNotFound() {
        assertThrows(SiteNotFoundException.class, () -> service.deleteSite(1L));
    }

    @Test
    void deleteSite_managedでなければdeprovisionを呼ばずに削除する() {
        Site site = world.addSite(1L, "管理対象外");

        service.deleteSite(1L);

        assertTrue(world.http.requests("POST", "/deprovision").isEmpty());
        verify(world.siteRepository).delete(site);
        verify(world.rabbitTemplate).convertAndSend(eq(EventExchanges.EVENTS_EXCHANGE),
                eq(EventExchanges.SITE_DELETED_ROUTING_KEY), any(Object.class));
    }

    @Test
    void deleteSite_他に同じwp_slugを参照するサイトが無ければ従来通りdeprovisionする() {
        Site site = world.addManagedSite(1L, "my-site", "wp_my-site");
        Site unrelated = world.addManagedSite(2L, "other-site", "wp_other-site");
        when(world.siteRepository.findAll()).thenReturn(List.of(site, unrelated));

        service.deleteSite(1L);

        assertDeprovisioned("my-site", "wp_my-site");
        verify(world.siteRepository).delete(site);
    }

    @Test
    void deleteSite_同じwp_slugを指す他のサイトがあればdeprovisionせずレコードだけ削除する() {
        Site site = world.addManagedSite(1L, "shared-slug", "wp_shared-slug");
        Site sibling = world.addManagedSite(2L, "shared-slug", "wp_shared-slug");
        when(world.siteRepository.findAll()).thenReturn(List.of(site, sibling));

        service.deleteSite(1L);

        assertTrue(world.http.requests("POST", "/deprovision").isEmpty());
        verify(world.siteRepository).delete(site);
        verify(world.rabbitTemplate).convertAndSend(eq(EventExchanges.EVENTS_EXCHANGE),
                eq(EventExchanges.SITE_DELETED_ROUTING_KEY), any(Object.class));
    }

    // issue #1198でadoptManagedSiteに分岐(wp_slug共有チェック)を追加したことでファイル単位の
    // カバレッジ計測対象になったため、adoptManagedSite自身の既存の未網羅分岐も補完している。

    @Test
    void adoptManagedSite_既にsiteKeyがあれば例外() {
        when(world.siteRepository.existsBySiteKey("my-site")).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.adoptManagedSite(new AdoptWordPressSiteRequest("Name", "my-site", "admin")));
    }

    @Test
    void adoptManagedSite_通常のadoptはwp_slugの衝突が無ければ登録されWARNログも出さない() {
        world.respond("POST", "/adopt", java.util.Map.of("url", PROVISION_URL, "adminUser", "admin"));
        Site site = registeredSite();
        when(world.siteRepository.findAll()).thenReturn(List.of(site));

        Logger logger = (Logger) LoggerFactory.getLogger(WordPressSiteProvisioningService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        SiteResponse response;
        try {
            response = service.adoptManagedSite(new AdoptWordPressSiteRequest("Name", "my-site", "admin"));
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals("my-site", response.siteKey());
        assertEquals("2026-09-08T20:03:35Z", String.valueOf(response.createdAt()));
        assertEquals("2026-09-09T01:02:03Z", String.valueOf(response.updatedAt()));
        verify(world.siteRepository).save(site);
        assertFalse(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN),
                "wp_slugの衝突が無ければWARNログを出さないこと");
    }

    /**
     * issue #1198 AC1: 同じ実体(同一wp_slug)を指す2件目のadoptは、拒否まではしない
     * (site-adoption.featureのフィクスチャ手順がこの成功を前提にしているため。Out of Scope)が、
     * 共有先の既存サイトを名指ししたWARNログという形で明示的に許可された操作であることが分かるようにする。
     */
    @Test
    void adoptManagedSite_同じwp_slugを指す既存サイトがあれば共有をWARNログで明示する() {
        world.respond("POST", "/adopt", java.util.Map.of("url", "https://localhost/sites/target-key", "adminUser", "admin"));
        Site newSite = new Site();
        newSite.setId(2L);
        newSite.setSiteKey("target_key");
        when(world.siteRepository.findBySiteKey("target_key")).thenReturn(Optional.of(newSite));
        Site existing = world.addManagedSite(1L, "target-key", "wp_target-key");
        existing.setSiteKey("target-key");
        when(world.siteRepository.findAll()).thenReturn(List.of(existing, newSite));

        Logger logger = (Logger) LoggerFactory.getLogger(WordPressSiteProvisioningService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        SiteResponse response;
        try {
            response = service.adoptManagedSite(new AdoptWordPressSiteRequest("Name", "target_key", "admin"));
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals("target_key", response.siteKey());
        assertTrue(appender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("target-key")
                        && event.getFormattedMessage().contains("1")),
                "同じwp_slugを共有する既存サイト(id=1, siteKey=target-key)を名指ししたWARNログが出力されること");
    }

    @Test
    void adoptManagedSite_実体がagentに無ければSiteNotFound() {
        world.fail("POST", "/adopt", 404);

        assertThrows(SiteNotFoundException.class,
                () -> service.adoptManagedSite(new AdoptWordPressSiteRequest("Name", "my-site", "admin")));
    }

    @Test
    void adoptManagedSite_agentの取り込みが失敗すればProvisioningException() {
        world.fail("POST", "/adopt", 500);

        assertThrows(ProvisioningException.class,
                () -> service.adoptManagedSite(new AdoptWordPressSiteRequest("Name", "my-site", "admin")));
    }

    @Test
    void adoptManagedSite_登録したsiteKeyのサイトが見つからなければSiteNotFound() {
        world.respond("POST", "/adopt", java.util.Map.of("url", PROVISION_URL, "adminUser", "admin"));

        assertThrows(SiteNotFoundException.class,
                () -> service.adoptManagedSite(new AdoptWordPressSiteRequest("Name", "my-site", "admin")));
    }

    @Test
    void createManagedSite_登録する認証情報にappPasswordを含めない_issue1565() {
        registeredSite();
        ArgumentCaptor<Site> saved = ArgumentCaptor.forClass(Site.class);

        service.createManagedSite(request("ja", null));

        // 登録(SiteService#register)が保存したサイトの認証情報(暗号化済み)を復号して確かめる。
        verify(world.siteRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        Site registered = saved.getAllValues().get(0);
        JsonNode credentials = world.bodyOf(world.http.requests("POST", "/api/internal/project/cms/provision").get(0))
                .get("credentials");
        assertFalse(credentials.has("appPassword"));
        assertEquals("AGENT", credentials.get("transport").asText());
        assertEquals("admin", credentials.get("username").asText());
        assertFalse(world.credentialCipher.decrypt(registered.getCredentialsEncrypted()).contains("appPassword"));
    }

    @Test
    void adoptManagedSite_登録する認証情報にappPasswordを含めない_issue1565() {
        world.respond("POST", "/adopt", java.util.Map.of("url", "https://localhost/sites/target-key", "adminUser", "admin"));
        Site site = new Site();
        site.setId(2L);
        site.setSiteKey("target_key");
        when(world.siteRepository.findBySiteKey("target_key")).thenReturn(Optional.of(site));

        service.adoptManagedSite(new AdoptWordPressSiteRequest("Name", "target_key", "admin"));

        JsonNode credentials = world.bodyOf(world.http.requests("POST", "/api/internal/project/cms/provision").get(0))
                .get("credentials");
        assertFalse(credentials.has("appPassword"));
        assertEquals("AGENT", credentials.get("transport").asText());
    }
}
