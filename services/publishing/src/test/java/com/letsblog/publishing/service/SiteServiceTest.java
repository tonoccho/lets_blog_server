package com.letsblog.publishing.service;

import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.domain.Site;
import com.letsblog.publishing.testsupport.PublishingWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SiteServiceの単体テスト(issue #1815)。サイトの基本情報とCMS認証情報の解決を、project-serviceへのHTTP
 * ({@code FakeHttpServer})越しに、実物の{@code ProjectServiceClient}で行う。
 */
class SiteServiceTest {

    private PublishingWorld world;
    private SiteService service;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        service = world.siteService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private static Site site(boolean managed, CmsType cmsType, String key) {
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey(key);
        site.setManagedWordpress(managed);
        site.setCmsType(cmsType);
        return site;
    }

    @Test
    void getById_登録されたサイトを返し_未登録ならempty() {
        world.stubSite(1L, "main", true);

        Optional<Site> found = service.getById(1L);

        assertTrue(found.isPresent());
        assertEquals("main", found.get().getSiteKey());
        assertEquals("main-name", found.get().getName());
        assertEquals(CmsType.WORDPRESS, found.get().getCmsType());
        assertTrue(found.get().isManagedWordpress());
        assertTrue(service.getById(99L).isEmpty());
    }

    @Test
    void resolveProjectId_サイトキーからプロジェクトIDを引く() {
        world.stubSite(1L, "main", false);
        world.bindProject(1L, 7L, null, null, 1L);

        assertEquals(7L, service.resolveProjectId("main"));
    }

    @Test
    void resolveProjectId_プロジェクトに紐付かないサイトはnull() {
        world.withMainSite();

        assertNull(service.resolveProjectId("main"));
    }

    @Test
    void resolveProjectId_未登録のサイトキーはSiteNotFoundException() {
        assertThrows(SiteNotFoundException.class, () -> service.resolveProjectId("missing"));
    }

    @Test
    void getCredentials_認証情報のマップをWordPressの認証情報へ変換する() {
        world.stubCredentials("main", PublishingWorld.sshCredentialsMap());

        CmsCredentials.WordPressCredentials credentials =
                (CmsCredentials.WordPressCredentials) service.getCredentials("main");

        assertEquals("https://example.com", credentials.baseUrl());
        assertEquals("SSH", credentials.transport());
        assertEquals("ssh.example.com", credentials.sshHost());
        assertEquals(22, credentials.sshPort());
        assertEquals("deploy", credentials.sshUser());
        assertEquals("/var/www/html", credentials.wpPath());
    }

    @Test
    void getCredentials_sshPortが空白ならnull() {
        Map<String, String> map = new HashMap<>(PublishingWorld.sshCredentialsMap());
        map.put("sshPort", " ");
        world.stubCredentials("main", map);

        assertNull(((CmsCredentials.WordPressCredentials) service.getCredentials("main")).sshPort());
    }

    @Test
    void getCredentials_sshPortが数値でなければ例外() {
        Map<String, String> map = new HashMap<>(PublishingWorld.sshCredentialsMap());
        map.put("sshPort", "abc");
        world.stubCredentials("main", map);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.getCredentials("main"));
        assertTrue(e.getMessage().contains("abc"));
    }

    @Test
    void getCredentials_sshPortが無ければnull() {
        Map<String, String> map = new HashMap<>(PublishingWorld.sshCredentialsMap());
        map.remove("sshPort");
        world.stubCredentials("main", map);

        assertNull(((CmsCredentials.WordPressCredentials) service.getCredentials("main")).sshPort());
    }

    @Test
    void resolveDataSource_managedサイトは認証情報を引かずmanagedとして扱う() {
        SiteService.SiteDataSource dataSource = service.resolveDataSource(site(true, CmsType.WORDPRESS, "main"));

        assertTrue(dataSource.managed());
        assertFalse(dataSource.hasSsh());
        assertFalse(dataSource.isUnavailable());
        assertTrue(world.project.requests().isEmpty());
    }

    @Test
    void resolveDataSource_WordPress以外のCMSは対象外() {
        SiteService.SiteDataSource dataSource = service.resolveDataSource(site(false, null, "main"));

        assertTrue(dataSource.isUnavailable());
        assertTrue(world.project.requests().isEmpty());
    }

    @Test
    void resolveDataSource_SSH接続情報のある非managedサイトはSSHで扱う() {
        world.stubCredentials("main", PublishingWorld.sshCredentialsMap());

        SiteService.SiteDataSource dataSource = service.resolveDataSource(site(false, CmsType.WORDPRESS, "main"));

        assertFalse(dataSource.managed());
        assertTrue(dataSource.hasSsh());
        assertFalse(dataSource.isUnavailable());
        assertEquals("ssh.example.com", dataSource.sshCredentials().sshHost());
    }

    @Test
    void resolveDataSource_transportがSSHでない非managedサイトは対象外() {
        Map<String, String> map = new HashMap<>(PublishingWorld.sshCredentialsMap());
        map.put("transport", "AGENT");
        world.stubCredentials("main", map);

        SiteService.SiteDataSource dataSource = service.resolveDataSource(site(false, CmsType.WORDPRESS, "main"));

        assertTrue(dataSource.isUnavailable());
    }

    @Test
    void resolveDataSource_認証情報を取得できなければ例外にせず対象外にする() {
        // project-serviceに認証情報の登録が無い(404)。
        SiteService.SiteDataSource dataSource = service.resolveDataSource(site(false, CmsType.WORDPRESS, "missing"));

        assertTrue(dataSource.isUnavailable());
    }
}
