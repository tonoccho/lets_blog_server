package com.letsblog.api.service;

import com.letsblog.api.client.ProjectServiceClient;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.Site;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * SiteServiceの回帰テスト(issue #577 stage3)。サイトのCRUD・登録・接続確認等は
 * project-service側のSiteService(issue #577 stage2)が正となったため、legacy-api側のこのクラスは
 * project-serviceへの内部ブリッジ({@link ProjectServiceClient})経由でサイトの基本情報・CMS認証情報を
 * 取得するだけの薄い参照層になった。
 */
@ExtendWith(MockitoExtension.class)
class SiteServiceTest {

    @Mock
    private ProjectServiceClient projectServiceClient;

    private SiteService service;

    private SiteService service() {
        return new SiteService(projectServiceClient);
    }

    private ProjectServiceClient.SiteBridge bridge(Long id, String siteKey, boolean managed) {
        return new ProjectServiceClient.SiteBridge(
                id, siteKey, "My Blog", "https://example.com", CmsType.WORDPRESS, managed, "my-slug",
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void getById_project_serviceのサイトを転写する() {
        service = service();
        when(projectServiceClient.getSite(10L)).thenReturn(Optional.of(bridge(10L, "main", true)));

        Optional<Site> result = service.getById(10L);

        assertTrue(result.isPresent());
        assertEquals("main", result.get().getSiteKey());
        assertEquals(true, result.get().isManagedWordpress());
    }

    @Test
    void getById_未登録ならempty() {
        service = service();
        when(projectServiceClient.getSite(99L)).thenReturn(Optional.empty());

        assertFalse(service.getById(99L).isPresent());
    }

    @Test
    void getAllById_複数idをまとめて取得する() {
        service = service();
        when(projectServiceClient.getSite(10L)).thenReturn(Optional.of(bridge(10L, "a", false)));
        when(projectServiceClient.getSite(20L)).thenReturn(Optional.of(bridge(20L, "b", false)));

        List<Site> result = service.getAllById(List.of(10L, 20L));

        assertEquals(2, result.size());
    }

    @Test
    void getAllById_見つからないidはスキップする() {
        service = service();
        when(projectServiceClient.getSite(10L)).thenReturn(Optional.of(bridge(10L, "a", false)));
        when(projectServiceClient.getSite(99L)).thenReturn(Optional.empty());

        List<Site> result = service.getAllById(List.of(10L, 99L));

        assertEquals(1, result.size());
    }

    @Test
    void getBySiteKey_見つかれば返す() {
        service = service();
        when(projectServiceClient.getSiteByKey("main")).thenReturn(Optional.of(bridge(1L, "main", false)));

        Site result = service.getBySiteKey("main");

        assertEquals(1L, result.getId());
    }

    @Test
    void getBySiteKey_未登録ならSiteNotFoundException() {
        service = service();
        when(projectServiceClient.getSiteByKey("unknown")).thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(
                SiteNotFoundException.class, () -> service.getBySiteKey("unknown"));
    }

    @Test
    void listAll_全サイトを転写する() {
        service = service();
        when(projectServiceClient.listSites()).thenReturn(List.of(bridge(1L, "a", false), bridge(2L, "b", false)));

        List<Site> result = service.listAll();

        assertEquals(2, result.size());
    }

    @Test
    void getCredentials_project_serviceの解決済み認証情報からWordPressCredentialsを組み立てる() {
        service = service();
        Map<String, String> credentials = Map.of(
                "baseUrl", "https://example.com", "username", "admin", "transport", "SSH",
                "sshHost", "203.0.113.5", "sshPort", "2222", "sshUser", "deploy", "wpPath", "/var/www/html",
                "sshPrivateKeyPem", "RESOLVED-PEM", "sshHostKeyFingerprint", "SHA256:abc", "wpSlug", "slug");
        when(projectServiceClient.getCredentials("main"))
                .thenReturn(new ProjectServiceClient.SiteCredentialsBridge(1L, CmsType.WORDPRESS, credentials));

        CmsCredentials result = service.getCredentials("main");

        CmsCredentials.WordPressCredentials wp = (CmsCredentials.WordPressCredentials) result;
        assertEquals("https://example.com", wp.baseUrl());
        assertEquals("deploy", wp.sshUser());
        assertEquals(2222, wp.sshPort());
        assertEquals("RESOLVED-PEM", wp.sshPrivateKeyPem());
        assertTrue(wp.isSsh());
    }

    @Test
    void resolveDataSource_managedサイトはmanagedのみtrueになる() {
        service = service();
        Site site = new Site();
        site.setSiteKey("s");
        site.setManagedWordpress(true);

        SiteService.SiteDataSource dataSource = service.resolveDataSource(site);

        assertTrue(dataSource.managed());
        assertFalse(dataSource.hasSsh());
    }

    @Test
    void resolveDataSource_transportSSHならSSHのみtrueになる() {
        service = service();
        Site site = new Site();
        site.setSiteKey("s");
        site.setManagedWordpress(false);
        site.setCmsType(CmsType.WORDPRESS);
        Map<String, String> credentials = Map.of(
                "baseUrl", "https://example.com", "transport", "SSH", "sshHost", "203.0.113.5",
                "sshUser", "deploy", "wpPath", "/var/www/html", "sshPrivateKeyPem", "PEM");
        when(projectServiceClient.getCredentials("s"))
                .thenReturn(new ProjectServiceClient.SiteCredentialsBridge(1L, CmsType.WORDPRESS, credentials));

        SiteService.SiteDataSource dataSource = service.resolveDataSource(site);

        assertFalse(dataSource.managed());
        assertTrue(dataSource.hasSsh());
        assertFalse(dataSource.isUnavailable());
    }

    @Test
    void resolveDataSource_transportSSHでなければ利用不可() {
        service = service();
        Site site = new Site();
        site.setSiteKey("s");
        site.setManagedWordpress(false);
        site.setCmsType(CmsType.WORDPRESS);
        Map<String, String> credentials = Map.of("baseUrl", "https://example.com", "username", "admin");
        when(projectServiceClient.getCredentials("s"))
                .thenReturn(new ProjectServiceClient.SiteCredentialsBridge(1L, CmsType.WORDPRESS, credentials));

        SiteService.SiteDataSource dataSource = service.resolveDataSource(site);

        assertFalse(dataSource.hasSsh());
        assertTrue(dataSource.isUnavailable());
    }

    @Test
    void resolveDataSource_認証情報の取得に失敗したらhasSsh_falseで例外を投げない() {
        service = service();
        Site site = new Site();
        site.setSiteKey("broken");
        site.setManagedWordpress(false);
        site.setCmsType(CmsType.WORDPRESS);
        when(projectServiceClient.getCredentials("broken")).thenThrow(new IllegalStateException("接続失敗"));

        SiteService.SiteDataSource dataSource = service.resolveDataSource(site);

        assertFalse(dataSource.hasSsh());
    }

    @Test
    void isSshConfigured_SSHならtrue() {
        service = service();
        Site site = new Site();
        site.setSiteKey("s");
        Map<String, String> credentials = Map.of(
                "baseUrl", "https://example.com", "transport", "SSH", "sshHost", "203.0.113.5",
                "sshUser", "deploy", "wpPath", "/var/www/html", "sshPrivateKeyPem", "PEM");
        when(projectServiceClient.getCredentials("s"))
                .thenReturn(new ProjectServiceClient.SiteCredentialsBridge(1L, CmsType.WORDPRESS, credentials));

        assertTrue(service.isSshConfigured(site));
    }

    @Test
    void isSshConfigured_取得失敗ならfalse() {
        service = service();
        Site site = new Site();
        site.setSiteKey("s");
        when(projectServiceClient.getCredentials("s")).thenThrow(new IllegalStateException("失敗"));

        assertFalse(service.isSshConfigured(site));
    }
}
