package com.letsblog.project.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.project.cms.CmsType;
import com.letsblog.project.cms.ConnectionCheckResult;
import com.letsblog.project.cms.WpCliInstallResult;
import com.letsblog.project.client.CmsProvisioningBridgeClient;
import com.letsblog.project.domain.Site;
import com.letsblog.project.domain.SshKeyPair;
import com.letsblog.project.dto.SiteConnectionCheckResult;
import com.letsblog.project.dto.SiteRegisterRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.dto.SiteUpdateRequest;
import com.letsblog.project.repository.SiteRepository;
import com.letsblog.project.repository.SshKeyPairRepository;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SiteServiceの回帰テスト(issue #577 stage2、legacy-apiから移設)。実際のWordPress操作は
 * CmsProvisioningBridgeClient経由でlegacy-apiへ委ねるため、ブリッジ呼び出しへ渡す認証情報の組み立て
 * (sshKeyPairId解決を含む)を中心に検証する。SSH鍵ペアの参照先は本サービス自身のSshKeyPairRepository
 * (issue #577 stage1で移設済み)であることを確認する(stage1 PR説明で予告されていた「stage2で自然に
 * 解消される」に対応)。
 */
@ExtendWith(MockitoExtension.class)
class SiteServiceTest {

    @Mock
    private SiteRepository siteRepository;
    @Mock
    private CmsProvisioningBridgeClient bridgeClient;
    @Mock
    private ProvisioningService provisioningService;
    @Mock
    private SshKeyPairRepository sshKeyPairRepository;
    @Mock
    private CurrentActorService currentActorService;

    private final CredentialCipher credentialCipher = new CredentialCipher(
            Base64.getEncoder().encodeToString(new byte[32]));
    private final ObjectMapper objectMapper = new ObjectMapper();

    private SiteService service() {
        return new SiteService(siteRepository, credentialCipher, objectMapper, bridgeClient, provisioningService,
                sshKeyPairRepository, currentActorService);
    }

    @Test
    void register_既にsiteKeyがあればIllegalArgument() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(true);

        SiteRegisterRequest request = new SiteRegisterRequest("Name", "my-site", CmsType.WORDPRESS,
                Map.of("baseUrl", "https://example.com", "transport", "AGENT", "username", "u", "appPassword", "p"), null);

        assertThrows(IllegalArgumentException.class, () -> service().register(request));
    }

    @Test
    void register_成功時にプロビジョニングと疎通確認を行い暗号化して保存する() {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(false);
        when(currentActorService.getCurrentActorEmail()).thenReturn("actor@example.com");
        when(provisioningService.provisionSite(eq("WORDPRESS"), any(), eq("actor@example.com")))
                .thenReturn(new ProvisioningService.Result("cat-1", null, "tag-1", null, "author-1", null));
        when(bridgeClient.testConnection(eq("WORDPRESS"), any()))
                .thenReturn(new ConnectionCheckResult(true, null, null, null));
        when(siteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SiteRegisterRequest request = new SiteRegisterRequest("Name", "my-site", CmsType.WORDPRESS,
                Map.of("baseUrl", "https://example.com", "transport", "AGENT", "username", "u", "appPassword", "p"), null);

        SiteResponse response = service().register(request);

        assertEquals("my-site", response.siteKey());
        assertEquals("SUCCESS", response.connectionCheckStatus());
        ArgumentCaptor<Site> captor = ArgumentCaptor.forClass(Site.class);
        verify(siteRepository).save(captor.capture());
        assertTrue(captor.getValue().getCredentialsEncrypted().length > 0);
    }

    @Test
    void register_sshKeyPairId参照は解決した秘密鍵PEMをブリッジへ送る() {
        when(siteRepository.existsBySiteKey("ssh-site")).thenReturn(false);
        when(currentActorService.getCurrentActorEmail()).thenReturn(null);
        SshKeyPair keyPair = new SshKeyPair("deploy-key", "comment", "ssh-ed25519 AAAA...",
                credentialCipher.encrypt("PRIVATE-PEM"));
        when(sshKeyPairRepository.existsById(1L)).thenReturn(true);
        when(sshKeyPairRepository.findById(1L)).thenReturn(Optional.of(keyPair));
        when(provisioningService.provisionSite(anyString(), any(), any()))
                .thenReturn(new ProvisioningService.Result(null, null, null, null, null, null));
        when(bridgeClient.testConnection(anyString(), any()))
                .thenReturn(ConnectionCheckResult.failure("接続失敗"));
        when(siteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SiteRegisterRequest request = new SiteRegisterRequest("Name", "ssh-site", CmsType.WORDPRESS,
                Map.of("baseUrl", "https://example.com", "transport", "SSH", "sshHost", "host",
                        "sshUser", "user", "wpPath", "/var/www", "sshKeyPairId", "1"), null);

        service().register(request);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(bridgeClient).testConnection(eq("WORDPRESS"), captor.capture());
        assertEquals("PRIVATE-PEM", captor.getValue().get("sshPrivateKeyPem"));
    }

    @Test
    void register_SSHでsshPrivateKeyPemとsshKeyPairIdの両方指定は例外() {
        SiteRegisterRequest request = new SiteRegisterRequest("Name", "site", CmsType.WORDPRESS,
                Map.of("baseUrl", "https://example.com", "transport", "SSH", "sshHost", "host",
                        "sshUser", "user", "wpPath", "/var/www",
                        "sshPrivateKeyPem", "PEM", "sshKeyPairId", "1"), null);

        assertThrows(IllegalArgumentException.class, () -> service().register(request));
    }

    @Test
    void register_存在しないsshKeyPairIdは例外() {
        when(sshKeyPairRepository.existsById(99L)).thenReturn(false);

        SiteRegisterRequest request = new SiteRegisterRequest("Name", "site", CmsType.WORDPRESS,
                Map.of("baseUrl", "https://example.com", "transport", "SSH", "sshHost", "host",
                        "sshUser", "user", "wpPath", "/var/www", "sshKeyPairId", "99"), null);

        assertThrows(IllegalArgumentException.class, () -> service().register(request));
    }

    private SiteRegisterRequest registerRequestWithAdminPath(String adminPath) {
        return new SiteRegisterRequest("Name", "my-site", CmsType.WORDPRESS,
                Map.of("baseUrl", "https://example.com", "transport", "AGENT", "username", "u", "appPassword", "p"),
                adminPath);
    }

    private Site registerAndCaptureSite(String adminPath) {
        when(siteRepository.existsBySiteKey("my-site")).thenReturn(false);
        when(currentActorService.getCurrentActorEmail()).thenReturn("actor@example.com");
        when(provisioningService.provisionSite(eq("WORDPRESS"), any(), eq("actor@example.com")))
                .thenReturn(new ProvisioningService.Result("cat-1", null, "tag-1", null, "author-1", null));
        when(bridgeClient.testConnection(eq("WORDPRESS"), any()))
                .thenReturn(new ConnectionCheckResult(true, null, null, null));
        when(siteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().register(registerRequestWithAdminPath(adminPath));

        ArgumentCaptor<Site> captor = ArgumentCaptor.forClass(Site.class);
        verify(siteRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void register_adminPathを指定するとそのまま保存する_issue1533() {
        assertEquals("secret-login", registerAndCaptureSite("secret-login").getAdminPath());
    }

    @Test
    void register_adminPath未指定はNULLで保存する_issue1533() {
        assertNull(registerAndCaptureSite(null).getAdminPath());
    }

    @Test
    void register_adminPathが空文字ならNULLで保存する_issue1533() {
        assertNull(registerAndCaptureSite("").getAdminPath());
    }

    @Test
    void register_不正なadminPathは拒否しプロビジョニングも保存も行わない_issue1533() {
        for (String invalid : new String[] {"//evil.example.com", "https://evil.example.com", "a b", "../x",
                "a\\b", "a".repeat(201)}) {
            assertThrows(InvalidSiteAdminPathException.class,
                    () -> service().register(registerRequestWithAdminPath(invalid)), invalid);
        }

        verify(siteRepository, never()).save(any());
        verifyNoInteractions(provisioningService, bridgeClient);
    }

    @Test
    void getResolvedCredentials_sshKeyPairId参照を解決して返す() {
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("my-site");
        site.setCmsType(CmsType.WORDPRESS);
        site.setCredentialsEncrypted(credentialCipher.encrypt(
                "{\"baseUrl\":\"https://x.example.com\",\"transport\":\"SSH\",\"sshKeyPairId\":\"1\"}"));
        when(siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(site));
        SshKeyPair keyPair = new SshKeyPair("deploy-key", "comment", "ssh-ed25519 AAAA...",
                credentialCipher.encrypt("PRIVATE-PEM"));
        when(sshKeyPairRepository.findById(1L)).thenReturn(Optional.of(keyPair));

        SiteService.ResolvedSiteCredentials result = service().getResolvedCredentials("my-site");

        assertEquals(1L, result.siteId());
        assertEquals("PRIVATE-PEM", result.credentials().get("sshPrivateKeyPem"));
    }

    @Test
    void getBySiteKey_存在しなければNotFound() {
        when(siteRepository.findBySiteKey("missing")).thenReturn(Optional.empty());

        assertThrows(SiteNotFoundException.class, () -> service().getBySiteKey("missing"));
    }

    @Test
    void update_サイトが存在しなければNotFound() {
        when(siteRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(SiteNotFoundException.class,
                () -> service().update(1L, new SiteUpdateRequest("name", null, null)));
    }

    @Test
    void update_managedサイトの認証情報編集は例外() {
        Site site = new Site();
        site.setId(1L);
        site.setManagedWordpress(true);
        site.setCmsType(CmsType.WORDPRESS);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        SiteUpdateRequest request = new SiteUpdateRequest("name", Map.of("baseUrl", "https://x.example.com"), null);

        assertThrows(IllegalArgumentException.class, () -> service().update(1L, request));
    }

    @Test
    void update_空文字のnameは400相当の例外で拒否し何も変更しない() {
        Site site = new Site();
        site.setId(1L);
        site.setName("元の名前");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service().update(1L, new SiteUpdateRequest("", null, null)));

        assertEquals("サイト名は空にできません", e.getMessage());
        assertEquals("InvalidSiteNameException", e.getClass().getSimpleName());
        assertEquals("元の名前", site.getName());
        verify(siteRepository, never()).save(any());
    }

    @Test
    void update_空白のみのnameも拒否する() {
        Site site = new Site();
        site.setId(1L);
        site.setName("元の名前");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service().update(1L, new SiteUpdateRequest("   ", null, null)));

        assertEquals("InvalidSiteNameException", e.getClass().getSimpleName());
        verify(siteRepository, never()).save(any());
    }

    @Test
    void update_nameがnullなら名前は変更せずcredentialsだけの更新を許す() {
        Site site = new Site();
        site.setId(1L);
        site.setName("元の名前");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(siteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().update(1L, new SiteUpdateRequest(null, null, null));

        assertEquals("元の名前", site.getName());
    }

    @Test
    void update_有効なnameなら名前を更新する() {
        Site site = new Site();
        site.setId(1L);
        site.setName("元の名前");
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(siteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().update(1L, new SiteUpdateRequest("新しい名前", null, null));

        assertEquals("新しい名前", site.getName());
    }

    private Site siteWithAdminPath(String adminPath) {
        Site site = new Site();
        site.setId(1L);
        site.setName("元の名前");
        site.setCmsType(CmsType.WORDPRESS);
        site.setAdminPath(adminPath);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        return site;
    }

    @Test
    void list_未設定のadminPathはnullで返る() {
        Site site = new Site();
        site.setId(1L);
        when(siteRepository.findAll()).thenReturn(java.util.List.of(site));

        assertEquals(null, service().list(null, null).get(0).adminPath());
    }

    @Test
    void list_設定済みのadminPathを返す() {
        Site site = new Site();
        site.setId(1L);
        site.setAdminPath("secret-login");
        when(siteRepository.findAll()).thenReturn(java.util.List.of(site));

        assertEquals("secret-login", service().list(null, null).get(0).adminPath());
    }

    @Test
    void getDetail_adminPathを返す() {
        siteWithAdminPath("secret-login");

        assertEquals("secret-login", service().getDetail(1L).adminPath());
    }

    @Test
    void getDetail_未設定ならadminPathはnull() {
        siteWithAdminPath(null);

        assertEquals(null, service().getDetail(1L).adminPath());
    }

    @Test
    void update_adminPathを設定して保存しレスポンスに反映する() {
        Site site = siteWithAdminPath(null);
        when(siteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SiteResponse response = service().update(1L, new SiteUpdateRequest(null, null, "secret-login"));

        assertEquals("secret-login", site.getAdminPath());
        assertEquals("secret-login", response.adminPath());
    }

    @Test
    void update_先頭スラッシュ付きも許容し保存値はそのまま() {
        Site site = siteWithAdminPath(null);
        when(siteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().update(1L, new SiteUpdateRequest(null, null, "/wp/login"));

        assertEquals("/wp/login", site.getAdminPath());
    }

    @Test
    void update_adminPathが無ければ保存済みの値を変えない() {
        Site site = siteWithAdminPath("secret-login");
        when(siteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().update(1L, new SiteUpdateRequest("新しい名前", null, null));

        assertEquals("secret-login", site.getAdminPath());
        assertEquals("新しい名前", site.getName());
    }

    @Test
    void update_空文字のadminPathは上書きを解除してnullにする() {
        Site site = siteWithAdminPath("secret-login");
        when(siteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SiteResponse response = service().update(1L, new SiteUpdateRequest(null, null, ""));

        assertEquals(null, site.getAdminPath());
        assertEquals(null, response.adminPath());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "javascript:alert(1)", "//evil.example.com", "https://evil.example.com/x", "../../etc",
            "a/../b", "has space", "tab\there", "back\\slash", "a\u0000b"})
    void update_不正なadminPathは拒否し保存済みの値も名前も変えない(String invalid) {
        Site site = siteWithAdminPath("keep-me");

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service().update(1L, new SiteUpdateRequest("別の名前", null, invalid)));

        assertEquals("InvalidSiteAdminPathException", e.getClass().getSimpleName());
        assertEquals("keep-me", site.getAdminPath());
        assertEquals("元の名前", site.getName());
        verify(siteRepository, never()).save(any());
    }

    @Test
    void update_201文字のadminPathは拒否し200文字は許容する() {
        Site site = siteWithAdminPath("keep-me");
        when(siteRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service().update(1L, new SiteUpdateRequest(null, null, "a".repeat(201))));
        assertEquals("InvalidSiteAdminPathException", e.getClass().getSimpleName());
        assertEquals("keep-me", site.getAdminPath());

        service().update(1L, new SiteUpdateRequest(null, null, "a".repeat(200)));
        assertEquals("a".repeat(200), site.getAdminPath());
    }

    @Test
    void update_不正なadminPathがあればcredentialsの更新も行わない() {
        Site site = siteWithAdminPath("keep-me");
        site.setManagedWordpress(false);

        assertThrows(RuntimeException.class, () -> service().update(1L,
                new SiteUpdateRequest(null, Map.of("baseUrl", "https://x.example.com"), "//evil")));

        verify(bridgeClient, never()).testConnection(anyString(), any());
        verify(siteRepository, never()).save(any());
    }

    @Test
    void installWpCli_ブリッジ経由で実行結果を返す() {
        Site site = new Site();
        site.setId(1L);
        site.setCmsType(CmsType.WORDPRESS);
        site.setCredentialsEncrypted(credentialCipher.encrypt(
                "{\"baseUrl\":\"https://x.example.com\",\"transport\":\"SSH\"}"));
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(bridgeClient.installWpCli(eq("WORDPRESS"), any())).thenReturn(new WpCliInstallResult("インストール完了"));

        WpCliInstallResult result = service().installWpCli(1L);

        assertEquals("インストール完了", result.message());
    }

    @Test
    void checkConnection_失敗時はfalseと理由を返す() {
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("site-key");
        site.setCmsType(CmsType.WORDPRESS);
        site.setCredentialsEncrypted(credentialCipher.encrypt(
                "{\"baseUrl\":\"https://x.example.com\",\"transport\":\"AGENT\"}"));
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));
        when(bridgeClient.testConnection(anyString(), any())).thenReturn(ConnectionCheckResult.failure("接続失敗"));

        SiteConnectionCheckResult result = service().checkConnection(1L);

        assertEquals(false, result.connectionOk());
        assertEquals("接続失敗", result.failureReason());
    }

    // ---- issue #1557: letsblogプラグインの導入状態 ----

    private Site agentSite() {
        Site site = new Site();
        site.setId(1L);
        site.setSiteKey("site-key");
        site.setCmsType(CmsType.WORDPRESS);
        site.setWpSlug("my-slug");
        site.setCredentialsEncrypted(credentialCipher.encrypt(
                "{\"baseUrl\":\"https://x.example.com\",\"transport\":\"AGENT\"}"));
        return site;
    }

    @Test
    void getLetsblogPluginStatus_ブリッジ経由で導入状態を返す() {
        when(siteRepository.findById(1L)).thenReturn(Optional.of(agentSite()));
        com.letsblog.project.cms.LetsblogPluginStatus status = new com.letsblog.project.cms.LetsblogPluginStatus(
                com.letsblog.project.cms.LetsblogPluginStatus.State.INSTALLED, "1.0.0", 1);
        when(bridgeClient.letsblogPluginStatus(eq("WORDPRESS"), any())).thenReturn(status);

        assertEquals(status, service().getLetsblogPluginStatus(1L));
    }

    @Test
    void getLetsblogPluginStatus_未登録のサイトはSiteNotFound() {
        when(siteRepository.findById(9L)).thenReturn(Optional.empty());

        assertThrows(SiteNotFoundException.class, () -> service().getLetsblogPluginStatus(9L));
    }

    @Test
    void installLetsblogPlugin_ブリッジ経由で導入し導入後の状態を返す() {
        when(siteRepository.findById(1L)).thenReturn(Optional.of(agentSite()));
        com.letsblog.project.cms.LetsblogPluginStatus status = new com.letsblog.project.cms.LetsblogPluginStatus(
                com.letsblog.project.cms.LetsblogPluginStatus.State.INSTALLED, "1.0.0", 1);
        when(bridgeClient.installLetsblogPlugin(eq("WORDPRESS"), any())).thenReturn(status);

        assertEquals(status, service().installLetsblogPlugin(1L));
    }

    @Test
    void installLetsblogPlugin_未登録のサイトはSiteNotFound() {
        when(siteRepository.findById(9L)).thenReturn(Optional.empty());

        assertThrows(SiteNotFoundException.class, () -> service().installLetsblogPlugin(9L));
        verify(bridgeClient, never()).installLetsblogPlugin(anyString(), any());
    }

    // ---- issue #1558: letsblogプラグインへの同期 ----

    @Test
    void syncLetsblogPlugin_内容とハッシュをブリッジへ渡し保存されたハッシュを返す() {
        when(siteRepository.findById(1L)).thenReturn(Optional.of(agentSite()));
        when(bridgeClient.syncLetsblogPlugin(eq("WORDPRESS"), any(), eq("{\"a\":1}"), eq("h1")))
                .thenReturn(new com.letsblog.project.cms.LetsblogSyncResult("h1"));

        assertEquals("h1", service().syncLetsblogPlugin(1L, "{\"a\":1}", "h1"));
    }

    @Test
    void syncLetsblogPlugin_未登録のサイトはSiteNotFound() {
        when(siteRepository.findById(9L)).thenReturn(Optional.empty());

        assertThrows(SiteNotFoundException.class, () -> service().syncLetsblogPlugin(9L, "{}", "h"));
    }
}
