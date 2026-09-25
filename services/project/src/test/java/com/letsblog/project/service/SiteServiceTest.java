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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
                Map.of("baseUrl", "https://example.com", "transport", "AGENT", "username", "u", "appPassword", "p"));

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
                Map.of("baseUrl", "https://example.com", "transport", "AGENT", "username", "u", "appPassword", "p"));

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
                        "sshUser", "user", "wpPath", "/var/www", "sshKeyPairId", "1"));

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
                        "sshPrivateKeyPem", "PEM", "sshKeyPairId", "1"));

        assertThrows(IllegalArgumentException.class, () -> service().register(request));
    }

    @Test
    void register_存在しないsshKeyPairIdは例外() {
        when(sshKeyPairRepository.existsById(99L)).thenReturn(false);

        SiteRegisterRequest request = new SiteRegisterRequest("Name", "site", CmsType.WORDPRESS,
                Map.of("baseUrl", "https://example.com", "transport", "SSH", "sshHost", "host",
                        "sshUser", "user", "wpPath", "/var/www", "sshKeyPairId", "99"));

        assertThrows(IllegalArgumentException.class, () -> service().register(request));
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
                () -> service().update(1L, new SiteUpdateRequest("name", null)));
    }

    @Test
    void update_managedサイトの認証情報編集は例外() {
        Site site = new Site();
        site.setId(1L);
        site.setManagedWordpress(true);
        site.setCmsType(CmsType.WORDPRESS);
        when(siteRepository.findById(1L)).thenReturn(Optional.of(site));

        SiteUpdateRequest request = new SiteUpdateRequest("name", Map.of("baseUrl", "https://x.example.com"));

        assertThrows(IllegalArgumentException.class, () -> service().update(1L, request));
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
}
