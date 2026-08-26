package com.letsblog.api.controller;

import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.cms.ConnectionCheckResult;
import com.letsblog.api.cms.WpCliInstallResult;
import com.letsblog.api.cms.ssh.WordPressSshOperations;
import com.letsblog.api.dto.CmsBridgeConnectionCheckResponse;
import com.letsblog.api.dto.CmsBridgeCredentialsRequest;
import com.letsblog.api.dto.CmsBridgeExportDatabaseResponse;
import com.letsblog.api.dto.CmsBridgeProvisionRequest;
import com.letsblog.api.dto.CmsBridgeProvisionResponse;
import com.letsblog.api.service.ProvisioningService;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * CmsProvisioningBridgeControllerの回帰テスト(issue #577 stage2)。project-serviceへ移設した
 * SiteService/ProvisioningService/ProjectEnvironmentSyncServiceが、このブリッジ経由で
 * legacy-apiの既存CmsAdapter/ProvisioningService/WordPressSshOperations(いずれも本stageでは
 * 未変更)を正しく呼び出せることを検証する。
 */
@ExtendWith(MockitoExtension.class)
class CmsProvisioningBridgeControllerTest {

    @Mock
    private CmsAdapterFactory cmsAdapterFactory;
    @Mock
    private ProvisioningService provisioningService;
    @Mock
    private WordPressSshOperations sshOperations;
    @Mock
    private CmsAdapter cmsAdapter;

    private CmsProvisioningBridgeController controller() {
        return new CmsProvisioningBridgeController(cmsAdapterFactory, provisioningService, sshOperations);
    }

    private CmsBridgeCredentialsRequest agentRequest() {
        return new CmsBridgeCredentialsRequest("WORDPRESS", Map.of(
                "baseUrl", "https://example.com", "transport", "AGENT", "wpSlug", "my-slug"));
    }

    @Test
    void testConnection_成功結果を転写する() {
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.testConnection(any())).thenReturn(ConnectionCheckResult.success("fingerprint", "detail"));

        CmsBridgeConnectionCheckResponse response = controller().testConnection(agentRequest());

        assertEquals(true, response.ok());
        assertEquals("fingerprint", response.observedHostKeyFingerprint());
    }

    @Test
    void installWpCli_結果を返す() {
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.installWpCli(any())).thenReturn(new WpCliInstallResult("インストール完了"));

        WpCliInstallResult result = controller().installWpCli(agentRequest());

        assertEquals("インストール完了", result.message());
    }

    @Test
    void hasAuthorProvisioningCapability_結果を返す() {
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.hasAuthorProvisioningCapability(any())).thenReturn(true);

        assertEquals(true, controller().hasAuthorProvisioningCapability(agentRequest()));
    }

    @Test
    void provision_ProvisioningServiceの結果を転写する() {
        ProvisioningService.ProvisioningResult result = new ProvisioningService.ProvisioningResult();
        result.defaultCategoryId = "cat-1";
        result.authorError = "著者作成失敗";
        when(provisioningService.provisionSite(eq(CmsType.WORDPRESS), any(), eq("actor@example.com")))
                .thenReturn(result);

        CmsBridgeProvisionRequest request = new CmsBridgeProvisionRequest(
                "WORDPRESS", Map.of("baseUrl", "https://example.com", "transport", "AGENT", "wpSlug", "my-slug"),
                "actor@example.com");

        CmsBridgeProvisionResponse response = controller().provision(request);

        assertEquals("cat-1", response.defaultCategoryId());
        assertEquals("著者作成失敗", response.authorError());
    }

    @Test
    void exportDatabase_ダンプをBase64化して返す() {
        CmsBridgeCredentialsRequest request = new CmsBridgeCredentialsRequest("WORDPRESS", Map.of(
                "baseUrl", "https://example.com", "transport", "SSH", "sshHost", "host",
                "sshUser", "user", "wpPath", "/var/www", "sshPrivateKeyPem", "PEM"));
        when(sshOperations.exportDatabase(any())).thenReturn(
                new WordPressSshOperations.DatabaseExport("wp_", "DUMP".getBytes()));

        CmsBridgeExportDatabaseResponse response = controller().exportDatabase(request);

        assertEquals("wp_", response.tablePrefix());
        assertEquals("DUMP", new String(Base64.getDecoder().decode(response.dumpBase64())));
    }

    @Test
    void exportDatabase_不正なcmsTypeは例外() {
        assertThrows(IllegalArgumentException.class, () -> controller().exportDatabase(
                new CmsBridgeCredentialsRequest("INVALID", Map.of())));
    }

    @Test
    void listActivePlugins_active状態のみを返す() {
        when(sshOperations.listPlugins(any())).thenReturn(List.of(
                new WordPressSshOperations.PluginThemeInfo("Akismet", "active"),
                new WordPressSshOperations.PluginThemeInfo("Hello Dolly", "inactive")));

        CmsBridgeCredentialsRequest request = new CmsBridgeCredentialsRequest("WORDPRESS", Map.of(
                "baseUrl", "https://example.com", "transport", "SSH", "sshHost", "host",
                "sshUser", "user", "wpPath", "/var/www", "sshPrivateKeyPem", "PEM"));

        List<String> result = controller().listActivePlugins(request);

        assertEquals(List.of("Akismet"), result);
    }
}
