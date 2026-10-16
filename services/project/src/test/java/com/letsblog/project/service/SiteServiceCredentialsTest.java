package com.letsblog.project.service;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.cms.ConnectionCheckResult;
import com.letsblog.project.cms.ProvisioningResult;
import com.letsblog.project.domain.Site;
import com.letsblog.project.domain.SshKeyPair;
import com.letsblog.project.dto.SiteConnectionCheckResult;
import com.letsblog.project.dto.SiteDetailResponse;
import com.letsblog.project.dto.SiteRegisterRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.dto.SiteUpdateRequest;
import com.letsblog.project.testsupport.ProjectWorld;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SiteServiceの認証情報・疎通確認・並び替え・データソース判定の分岐(issue #1817)。
 *
 * <p>SiteService・CmsProvisioningBridgeClient・ProvisioningService・CurrentActorService は実物。DB(リポジトリ)と
 * HTTPの向こう(publishing-service)だけを差し替える。基本の登録・更新・プラグイン操作は SiteServiceTest が持つ。
 */
@DisplayName("SiteService: 認証情報・疎通確認・並び替え")
class SiteServiceCredentialsTest {

    private static final String CMS = "/api/internal/project/cms/";
    private static final String AGENT = "{\"baseUrl\":\"https://x.example.com\",\"transport\":\"AGENT\",\"username\":\"u\"}";
    private static final String SSH = "{\"baseUrl\":\"https://x.example.com\",\"transport\":\"SSH\",\"sshHost\":\"h\","
            + "\"sshUser\":\"u\",\"wpPath\":\"/w\",\"sshPrivateKeyPem\":\"PEM\"}";

    private final ProjectWorld world = new ProjectWorld();
    private final SiteService service = world.siteService;

    @BeforeEach
    void defaultBridge() {
        world.respond("POST", CMS + "test-connection", new ConnectionCheckResult(true, null, null, null));
    }

    @AfterEach
    void close() {
        world.close();
    }

    private Site site(long id, String credentialsJson) {
        Site site = world.addSite(id, "site-" + id);
        site.setSiteKey("key-" + id);
        site.setCredentialsEncrypted(credentialsJson == null ? null : world.credentialCipher.encrypt(credentialsJson));
        return site;
    }

    private SiteRegisterRequest agentRegister(Map<String, String> credentials) {
        return new SiteRegisterRequest("Name", "my-site", CmsType.WORDPRESS, credentials, null);
    }

    private static Map<String, String> agentCredentials() {
        return Map.of("baseUrl", "https://example.com", "transport", "AGENT", "username", "u");
    }

    private String storedCredentials(Site site) {
        return world.credentialCipher.decrypt(site.getCredentialsEncrypted());
    }

    // ---- register: プロビジョニングの部分失敗・ホスト鍵のピン留め ----

    @Test
    void register_プロビジョニングが部分的に失敗しても登録は続ける() {
        world.registrationSucceeds();
        world.respond("POST", CMS + "provision",
                new ProvisioningResult(null, "カテゴリ失敗", null, null, null, null));
        assertEquals("my-site", service.register(agentRegister(agentCredentials())).siteKey());
        world.respond("POST", CMS + "provision",
                new ProvisioningResult("c", null, null, "タグ失敗", null, null));
        assertEquals("my-site", service.register(agentRegister(agentCredentials())).siteKey());
        world.respond("POST", CMS + "provision",
                new ProvisioningResult("c", null, "t", null, null, "著者失敗"));
        assertEquals("my-site", service.register(agentRegister(agentCredentials())).siteKey());
    }

    @Test
    void register_疎通確認が成功しホスト鍵を観測したらピン留めして保存する() {
        world.registrationSucceeds();
        world.respond("POST", CMS + "test-connection", new ConnectionCheckResult(true, null, "SHA256:abc", null));
        Map<String, String> ssh = Map.of("baseUrl", "https://example.com", "transport", "SSH", "sshHost", "h",
                "sshUser", "u", "wpPath", "/w", "sshPrivateKeyPem", "PEM");

        service.register(agentRegister(ssh));

        org.mockito.ArgumentCaptor<Site> saved = org.mockito.ArgumentCaptor.forClass(Site.class);
        org.mockito.Mockito.verify(world.siteRepository).save(saved.capture());
        assertTrue(storedCredentials(saved.getValue()).contains("\"sshHostKeyFingerprint\":\"SHA256:abc\""));
    }

    @Test
    void register_既にホスト鍵が保存済み_疎通失敗_ホスト鍵未観測ならピン留めしない() {
        world.registrationSucceeds();
        Map<String, String> pinned = Map.of("baseUrl", "https://example.com", "transport", "SSH", "sshHost", "h",
                "sshUser", "u", "wpPath", "/w", "sshPrivateKeyPem", "PEM", "sshHostKeyFingerprint", "SHA256:old");
        Map<String, String> ssh = Map.of("baseUrl", "https://example.com", "transport", "SSH", "sshHost", "h",
                "sshUser", "u", "wpPath", "/w", "sshPrivateKeyPem", "PEM");

        world.respond("POST", CMS + "test-connection", new ConnectionCheckResult(true, null, "SHA256:new", null));
        service.register(agentRegister(pinned));
        world.respond("POST", CMS + "test-connection", ConnectionCheckResult.failure("失敗"));
        service.register(agentRegister(ssh));
        world.respond("POST", CMS + "test-connection", new ConnectionCheckResult(true, null, null, null));
        service.register(agentRegister(ssh));

        org.mockito.ArgumentCaptor<Site> saved = org.mockito.ArgumentCaptor.forClass(Site.class);
        org.mockito.Mockito.verify(world.siteRepository, org.mockito.Mockito.times(3)).save(saved.capture());
        assertTrue(storedCredentials(saved.getAllValues().get(0)).contains("SHA256:old"));
        assertFalse(storedCredentials(saved.getAllValues().get(1)).contains("sshHostKeyFingerprint"));
        assertFalse(storedCredentials(saved.getAllValues().get(2)).contains("sshHostKeyFingerprint"));
    }

    @Test
    void register_疎通確認の呼び出し自体が失敗しても登録はでき_接続失敗として返す() {
        world.registrationSucceeds();
        world.fail("POST", CMS + "test-connection", 500);

        SiteResponse response = service.register(agentRegister(agentCredentials()));

        // 疎通確認の失敗は ConnectionCheckResult.failure になり、登録は取り消さない。
        assertEquals("FAILED", response.connectionCheckStatus());
    }

    @Test
    void register_SSHは秘密鍵PEM直接指定だけでも登録できる() {
        world.registrationSucceeds();
        Map<String, String> ssh = Map.of("baseUrl", "https://example.com", "transport", "SSH", "sshHost", "h",
                "sshUser", "u", "wpPath", "/w", "sshPrivateKeyPem", "PEM");

        assertEquals("my-site", service.register(agentRegister(ssh)).siteKey());
    }

    @Test
    void register_SSHで秘密鍵の指定が無ければ例外() {
        Map<String, String> ssh = Map.of("baseUrl", "https://example.com", "transport", "SSH", "sshHost", "h",
                "sshUser", "u", "wpPath", "/w");

        assertThrows(IllegalArgumentException.class, () -> service.register(agentRegister(ssh)));
    }

    @Test
    void register_sshKeyPairIdが数値でなければ例外() {
        Map<String, String> ssh = Map.of("baseUrl", "https://example.com", "transport", "SSH", "sshHost", "h",
                "sshUser", "u", "wpPath", "/w", "sshKeyPairId", "abc");

        assertThrows(IllegalArgumentException.class, () -> service.register(agentRegister(ssh)));
    }

    // ---- list: 並び替え ----

    private List<Site> sortable() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        Site b = site(1L, AGENT);
        b.setName("bravo");
        b.setSiteKey("k-b");
        b.setCreatedAt(base.plusDays(1));
        b.setUpdatedAt(base.plusDays(3));
        Site a = site(2L, AGENT);
        a.setName("Alpha");
        a.setSiteKey("k-a");
        a.setCreatedAt(base.plusDays(2));
        a.setUpdatedAt(base.plusDays(1));
        Site c = site(3L, AGENT);
        c.setName("charlie");
        c.setSiteKey("k-c");
        c.setCreatedAt(base);
        c.setUpdatedAt(base.plusDays(2));
        return new ArrayList<>(List.of(b, a, c));
    }

    private List<Long> listIds(String sortBy, String sortOrder) {
        List<Site> sites = sortable();
        when(world.siteRepository.findAll()).thenReturn(sites);
        return service.list(sortBy, sortOrder).stream().map(SiteResponse::id).toList();
    }

    @Test
    void list_指定したキーと向きで並べる() {
        assertEquals(List.of(2L, 1L, 3L), listIds("name", "asc"));
        assertEquals(List.of(3L, 1L, 2L), listIds("name", "DESC"));
        assertEquals(List.of(2L, 1L, 3L), listIds("siteKey", null));
        assertEquals(List.of(1L, 2L, 3L), listIds("cmsType", "asc"));
        assertEquals(List.of(3L, 1L, 2L), listIds("createdAt", "asc"));
        assertEquals(List.of(1L, 3L, 2L), listIds("updatedAt", "desc"));
    }

    @Test
    void list_未知のキーや空のキーは並べ替えない() {
        assertEquals(List.of(1L, 2L, 3L), listIds("unknown", "asc"));
        assertEquals(List.of(1L, 2L, 3L), listIds(" ", "asc"));
        assertEquals(List.of(1L, 2L, 3L), listIds(null, "desc"));
    }

    // ---- resolveDataSource / isSshConfigured ----

    @Test
    void resolveDataSource_managed_SSH_AGENT_認証情報なしを判別する() {
        Site managed = world.addManagedSite(1L, "slug", "wp_slug");
        SiteService.SiteDataSource managedSource = service.resolveDataSource(managed);
        assertTrue(managedSource.managed());
        assertFalse(managedSource.hasSsh());
        assertFalse(managedSource.isUnavailable());

        SiteService.SiteDataSource ssh = service.resolveDataSource(site(2L, SSH));
        assertFalse(ssh.managed());
        assertTrue(ssh.hasSsh());
        assertEquals("PEM", ssh.sshCredentials().get("sshPrivateKeyPem"));
        assertFalse(ssh.isUnavailable());

        SiteService.SiteDataSource agent = service.resolveDataSource(site(3L, AGENT));
        assertFalse(agent.hasSsh());
        assertTrue(agent.isUnavailable());

        SiteService.SiteDataSource noCredentials = service.resolveDataSource(site(4L, null));
        assertTrue(noCredentials.isUnavailable());
    }

    @Test
    void isSshConfigured_SSHならtrue_それ以外や認証情報が読めなければfalse() {
        assertTrue(service.isSshConfigured(site(1L, SSH)));
        assertFalse(service.isSshConfigured(site(2L, AGENT)));
        assertFalse(service.isSshConfigured(site(3L, null)));
    }

    @Test
    void 認証情報のJSONが壊れていれば読めず_データソースは使えない() {
        Site broken = site(1L, "これはJSONではありません");

        assertTrue(service.resolveDataSource(broken).isUnavailable());
        assertFalse(service.isSshConfigured(broken));
    }

    @Test
    void getResolvedCredentials_秘密鍵PEMと鍵ペア参照が両方あれば保存された値をそのまま返す() {
        Site both = site(1L, SSH.replace("}", ",\"sshKeyPairId\":\"1\"}"));
        when(world.siteRepository.findBySiteKey("key-1")).thenReturn(Optional.of(both));

        assertEquals("PEM", service.getResolvedCredentials("key-1").credentials().get("sshPrivateKeyPem"));
    }

    @Test
    void getResolvedCredentials_参照先の鍵ペアが無ければSshKeyPairNotFound() {
        Site ref = site(1L, AGENT.replace("}", ",\"sshKeyPairId\":\"9\"}"));
        when(world.siteRepository.findBySiteKey("key-1")).thenReturn(Optional.of(ref));

        assertThrows(SshKeyPairNotFoundException.class, () -> service.getResolvedCredentials("key-1"));
    }

    @Test
    void getResolvedCredentials_managedサイトの認証情報にはAGENTとslugが入る() {
        Site managed = world.addManagedSite(1L, "slug", "wp_slug");
        when(world.siteRepository.findBySiteKey("site-1")).thenReturn(Optional.of(managed));
        managed.setSiteKey("site-1");

        SiteService.ResolvedSiteCredentials resolved = service.getResolvedCredentials("site-1");

        assertEquals("AGENT", resolved.credentials().get("transport"));
        assertEquals("slug", resolved.credentials().get("wpSlug"));
    }

    @Test
    void getResolvedCredentials_認証情報が無ければ例外() {
        Site none = site(1L, null);
        when(world.siteRepository.findBySiteKey("key-1")).thenReturn(Optional.of(none));

        assertThrows(IllegalStateException.class, () -> service.getResolvedCredentials("key-1"));
    }

    // ---- update: 認証情報の部分patch ----

    @Test
    void update_認証情報を部分patchで更新し疎通確認の結果をレスポンスへ返す() {
        Site site = site(1L, AGENT);
        when(world.siteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SiteResponse response = service.update(1L, new SiteUpdateRequest(null, Map.of("username", "new-user"), null));

        assertEquals("SUCCESS", response.connectionCheckStatus());
        assertTrue(storedCredentials(site).contains("\"username\":\"new-user\""));
        assertTrue(storedCredentials(site).contains("\"baseUrl\":\"https://x.example.com\""));
    }

    @Test
    void update_credentialsが空のmapなら認証情報は触らず疎通確認もしない() {
        Site site = site(1L, AGENT);
        when(world.siteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(1L, new SiteUpdateRequest("新名", Map.of(), null));

        assertEquals(AGENT, storedCredentials(site));
        assertTrue(world.http.requests("POST", CMS + "test-connection").isEmpty());
    }

    @Test
    void update_SSHでホスト鍵を観測したらピン留めして保存する() {
        Site site = site(1L, SSH);
        when(world.siteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        world.respond("POST", CMS + "test-connection", new ConnectionCheckResult(true, null, "SHA256:new", null));

        service.update(1L, new SiteUpdateRequest(null, Map.of("sshHost", "h2"), null));

        assertTrue(storedCredentials(site).contains("\"sshHostKeyFingerprint\":\"SHA256:new\""));
        assertTrue(storedCredentials(site).contains("\"sshHost\":\"h2\""));
    }

    @Test
    void update_疎通確認に失敗したらピン留めせず接続失敗を返す() {
        Site site = site(1L, SSH);
        when(world.siteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        world.respond("POST", CMS + "test-connection", ConnectionCheckResult.failure("失敗"));

        SiteResponse response = service.update(1L, new SiteUpdateRequest(null, Map.of("sshHost", "h2"), null));

        assertEquals("FAILED", response.connectionCheckStatus());
        assertFalse(storedCredentials(site).contains("sshHostKeyFingerprint"));
    }

    @Test
    void update_秘密鍵PEMを指定したら鍵ペア参照を外し_鍵ペア参照を指定したらPEMを外す() {
        when(world.sshKeyPairRepository.existsById(1L)).thenReturn(true);
        when(world.sshKeyPairRepository.findById(1L)).thenReturn(Optional.of(
                new SshKeyPair("k", "c", "ssh-ed25519 AAAA", world.credentialCipher.encrypt("PEM2"))));
        when(world.siteRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Site withKeyPair = site(1L, SSH.replace("\"sshPrivateKeyPem\":\"PEM\"", "\"sshKeyPairId\":\"1\""));

        service.update(1L, new SiteUpdateRequest(null, Map.of("sshPrivateKeyPem", "NEW"), null));
        assertTrue(storedCredentials(withKeyPair).contains("\"sshPrivateKeyPem\":\"NEW\""));
        assertFalse(storedCredentials(withKeyPair).contains("sshKeyPairId"));

        service.update(1L, new SiteUpdateRequest(null, Map.of("sshKeyPairId", "1"), null));
        assertTrue(storedCredentials(withKeyPair).contains("\"sshKeyPairId\":\"1\""));
        assertFalse(storedCredentials(withKeyPair).contains("sshPrivateKeyPem"));

        service.update(1L, new SiteUpdateRequest(null, Map.of("sshUser", "other"), null));
        assertTrue(storedCredentials(withKeyPair).contains("\"sshKeyPairId\":\"1\""));
    }

    // ---- checkConnection ----

    @Test
    void checkConnection_成功しAGENTならadmin権限ありで返す() {
        site(1L, AGENT);

        SiteConnectionCheckResult result = service.checkConnection(1L);

        assertTrue(result.connectionOk());
        assertEquals(true, result.hasAdminCapability());
    }

    @Test
    void checkConnection_SSHでホスト鍵を観測したらピン留めして保存する() {
        Site site = site(1L, SSH);
        world.respond("POST", CMS + "test-connection", new ConnectionCheckResult(true, null, "SHA256:fp", null));

        SiteConnectionCheckResult result = service.checkConnection(1L);

        assertTrue(result.connectionOk());
        assertEquals(true, result.hasAdminCapability());
        assertTrue(storedCredentials(site).contains("SHA256:fp"));
        org.mockito.Mockito.verify(world.siteRepository).save(site);
    }

    @Test
    void checkConnection_トランスポートが分からなければブリッジで投稿者の作成権限を確かめる() {
        site(1L, "{\"baseUrl\":\"https://x.example.com\",\"transport\":\"OTHER\"}");
        world.respond("POST", CMS + "has-author-capability", true);
        assertEquals(true, service.checkConnection(1L).hasAdminCapability());

        world.respond("POST", CMS + "has-author-capability", false);
        assertEquals(false, service.checkConnection(1L).hasAdminCapability());
    }

    @Test
    void checkConnection_疎通に失敗したらadmin権限は不明でnull() {
        site(1L, AGENT);
        world.respond("POST", CMS + "test-connection", ConnectionCheckResult.failure("失敗"));

        SiteConnectionCheckResult result = service.checkConnection(1L);

        assertFalse(result.connectionOk());
        assertNull(result.hasAdminCapability());
    }

    @Test
    void checkConnection_未登録のサイトはNotFound() {
        assertThrows(SiteNotFoundException.class, () -> service.checkConnection(1L));
    }

    // ---- getDetail ----

    @Test
    void getDetail_秘匿項目は名前だけ返し_空の値は含めず_SSH設定の有無を返す() {
        site(1L, "{\"baseUrl\":\"https://x.example.com\",\"transport\":\"SSH\",\"sshPrivateKeyPem\":\"PEM\","
                + "\"apiKey\":\"K\",\"sshHost\":\"\"}");

        SiteDetailResponse detail = service.getDetail(1L);

        assertTrue(detail.sshConfigured());
        assertEquals(List.of("sshPrivateKeyPem", "apiKey"), detail.configuredSecretFields().stream()
                .sorted(java.util.Comparator.comparing(f -> "sshPrivateKeyPem".equals(f) ? 0 : 1)).toList());
        assertFalse(detail.credentials().containsKey("sshPrivateKeyPem"));
        assertFalse(detail.credentials().containsKey("sshHost"));
        assertEquals("https://x.example.com", detail.credentials().get("baseUrl"));
    }

    @Test
    void getDetail_認証情報が読めなくても例外にせずSSH未設定として返す() {
        site(1L, null);

        SiteDetailResponse detail = service.getDetail(1L);

        assertFalse(detail.sshConfigured());
        assertTrue(detail.credentials().isEmpty());
    }

    @Test
    void getDetail_managedサイトはAGENTとslugを含めて返す() {
        world.addManagedSite(1L, "slug", "wp_slug");

        SiteDetailResponse detail = service.getDetail(1L);

        assertEquals("AGENT", detail.credentials().get("transport"));
        assertEquals("slug", detail.credentials().get("wpSlug"));
    }

    @Test
    void getDetail_未登録のサイトはNotFound() {
        assertThrows(SiteNotFoundException.class, () -> service.getDetail(1L));
    }

    // ---- cmsType が null の入力(Site は setter で、SiteRegisterRequest は直接生成できるため作れる) ----

    @Test
    void resolveDataSource_cmsTypeがnullの非managedサイトは使えないデータソースになる() {
        Site site = site(1L, SSH);
        site.setCmsType(null);

        assertTrue(service.resolveDataSource(site).isUnavailable());
    }

    @Test
    void register_cmsTypeがnullなら認証情報の検証の途中でNullPointerExceptionになり何も保存しない() {
        SiteRegisterRequest request = new SiteRegisterRequest("Name", "my-site", null, agentCredentials(), null);

        assertThrows(NullPointerException.class, () -> service.register(request));
        org.mockito.Mockito.verify(world.siteRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void checkConnection_cmsTypeがnullなら例外にせず接続失敗として返す() {
        Site site = site(1L, AGENT);
        site.setCmsType(null);

        SiteConnectionCheckResult result = service.checkConnection(1L);

        assertFalse(result.connectionOk());
        assertNull(result.hasAdminCapability());
    }
}
