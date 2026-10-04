package com.letsblog.publishing.controller;

import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.cms.ConnectionCheckResult;
import com.letsblog.publishing.cms.WpCliInstallResult;
import com.letsblog.publishing.cms.ssh.WordPressSshOperations;
import com.letsblog.publishing.dto.CmsBridgeConnectionCheckResponse;
import com.letsblog.publishing.dto.CmsBridgeCredentialsRequest;
import com.letsblog.publishing.dto.CmsBridgeExportDatabaseResponse;
import com.letsblog.publishing.dto.CmsBridgeProvisionRequest;
import com.letsblog.publishing.dto.CmsBridgeProvisionResponse;
import com.letsblog.publishing.service.ProvisioningService;
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
 * CmsProvisioningBridgeControllerの回帰テスト(issue #577 stage2でlegacy-api向けに作成、issue #710で
 * publishing-serviceへ移管)。project-serviceへ移設したSiteService/ProvisioningService/
 * ProjectEnvironmentSyncServiceが、このブリッジ経由でpublishing-serviceの既存CmsAdapter/
 * ProvisioningService/WordPressSshOperations(いずれも移管に伴うパッケージ名の変更のみで、
 * ロジックは移管元と同一)を正しく呼び出せることを検証する。
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

    // ---- issue #1557 ----

    @Test
    void letsblogPluginStatus_導入状態を返す() {
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        com.letsblog.publishing.cms.LetsblogPluginStatus status = new com.letsblog.publishing.cms.LetsblogPluginStatus(
                com.letsblog.publishing.cms.LetsblogPluginStatus.State.INSTALLED, "1.0.0", 1);
        when(cmsAdapter.letsblogPluginStatus(any())).thenReturn(status);

        assertEquals(status, controller().letsblogPluginStatus(agentRequest()));
    }

    @Test
    void installLetsblogPlugin_導入後の状態を返す() {
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        com.letsblog.publishing.cms.LetsblogPluginStatus status = new com.letsblog.publishing.cms.LetsblogPluginStatus(
                com.letsblog.publishing.cms.LetsblogPluginStatus.State.INSTALLED, "1.0.0", 1);
        when(cmsAdapter.installLetsblogPlugin(any())).thenReturn(status);

        assertEquals(status, controller().installLetsblogPlugin(agentRequest()));
    }
}
