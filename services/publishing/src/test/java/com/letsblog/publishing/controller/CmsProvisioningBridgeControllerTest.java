package com.letsblog.publishing.controller;

import com.letsblog.publishing.cms.LetsblogPluginStatus;
import com.letsblog.publishing.cms.WpCliInstallResult;
import com.letsblog.publishing.dto.CmsBridgeConnectionCheckResponse;
import com.letsblog.publishing.dto.CmsBridgeCredentialsRequest;
import com.letsblog.publishing.dto.CmsBridgeExportDatabaseResponse;
import com.letsblog.publishing.dto.CmsBridgeLetsblogSnsRequest;
import com.letsblog.publishing.dto.CmsBridgeLetsblogSyncRequest;
import com.letsblog.publishing.dto.CmsBridgeProvisionRequest;
import com.letsblog.publishing.dto.CmsBridgeProvisionResponse;
import com.letsblog.publishing.service.ProvisioningService;
import com.letsblog.publishing.testsupport.PublishingWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CmsProvisioningBridgeControllerの回帰テスト(issue #577 stage2でlegacy-api向けに作成、issue #710で
 * publishing-serviceへ移管)。project-serviceへ移設したSiteService/ProvisioningService/
 * ProjectEnvironmentSyncServiceが、このブリッジ経由でpublishing-serviceの既存CmsAdapter/
 * ProvisioningService/WordPressSshOperations(いずれも移管に伴うパッケージ名の変更のみで、
 * ロジックは移管元と同一)を正しく呼び出せることを検証する。
 *
 * <p>issue #1816で書き直し: CmsAdapterFactory・CmsAdapter・ProvisioningService・WordPressSshOperationsは
 * 実物を使い、外部との通信(provision-agentへのHTTPは{@code FakeHttpServer}、WordPressへのSSHは
 * {@code FakeWordPress})だけを差し替える。
 */
class CmsProvisioningBridgeControllerTest {

    private PublishingWorld world;
    private CmsProvisioningBridgeController controller;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        controller = new CmsProvisioningBridgeController(world.cmsAdapterFactory,
                new ProvisioningService(world.cmsAdapterFactory), world.sshOperations);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private CmsBridgeCredentialsRequest agentRequest() {
        return new CmsBridgeCredentialsRequest("WORDPRESS", agentCredentials());
    }

    private static Map<String, String> agentCredentials() {
        return Map.of("baseUrl", "https://example.com", "transport", "AGENT", "wpSlug", "my-slug");
    }

    private static Map<String, String> sshCredentials() {
        Map<String, String> credentials = new LinkedHashMap<>(PublishingWorld.sshCredentialsMap());
        credentials.remove("sshPort");
        return credentials;
    }

    private CmsBridgeCredentialsRequest sshRequest() {
        return new CmsBridgeCredentialsRequest("WORDPRESS", sshCredentials());
    }

    @Test
    void testConnection_成功結果を転写する() {
        world.agent.json("POST", "/wp-cli/core-version", "{\"version\":\"6.4.2\"}");

        CmsBridgeConnectionCheckResponse response = controller.testConnection(agentRequest());

        assertEquals(true, response.ok());
        assertTrue(response.detail().contains("6.4.2"), response.detail());
        assertEquals(1, world.agent.requests("POST", "/wp-cli/core-version").size());
        assertTrue(world.agent.last().bodyText().contains("my-slug"), "wpSlugがエージェントへ渡る");
    }

    @Test
    void testConnection_失敗結果も転写する() {
        world.ssh.whenFail("echo ok", "Permission denied");

        CmsBridgeConnectionCheckResponse response = controller.testConnection(sshRequest());

        assertEquals(false, response.ok());
    }

    @Test
    void installWpCli_結果を返す() {
        world.ssh.whenFail("command -v wp", "").whenOk("curl -fsSL", "WP-CLI 2.10.0");

        WpCliInstallResult result = controller.installWpCli(sshRequest());

        assertTrue(result.message().contains("WP-CLI 2.10.0"), result.message());
    }

    @Test
    void hasAuthorProvisioningCapability_結果を返す() {
        world.agent.json("POST", "/wp-cli/core-version", "{\"version\":\"6.4.2\"}");

        assertEquals(true, controller.hasAuthorProvisioningCapability(agentRequest()));
    }

    @Test
    void provision_ProvisioningServiceの結果を転写する() {
        world.ssh.whenFail("user create", "作成に失敗しました");

        CmsBridgeProvisionRequest request = new CmsBridgeProvisionRequest(
                "WORDPRESS", sshCredentials(), "actor@example.com");

        CmsBridgeProvisionResponse response = controller.provision(request);

        assertTrue(response.defaultCategoryId() != null && !response.defaultCategoryId().isBlank(),
                "既定カテゴリがWordPressに作られる");
        assertTrue(response.authorError() != null && response.authorError().contains("作成"),
                "著者作成の失敗はauthorErrorに転写される: " + response.authorError());
        assertTrue(world.ssh.ran("term create"), world.ssh.commands.toString());
    }

    @Test
    void exportDatabase_ダンプをBase64化して返す() {
        world.ssh.whenOk("config get table_prefix", "wp_").whenOk("db export", "");
        world.ssh.downloadFallback = "DUMP".getBytes();

        CmsBridgeExportDatabaseResponse response = controller.exportDatabase(sshRequest());

        assertEquals("wp_", response.tablePrefix());
        assertEquals("DUMP", new String(Base64.getDecoder().decode(response.dumpBase64())));
    }

    @Test
    void exportDatabase_不正なcmsTypeは例外() {
        assertThrows(IllegalArgumentException.class, () -> controller.exportDatabase(
                new CmsBridgeCredentialsRequest("INVALID", Map.of())));
    }

    @Test
    void exportMedia_リモートのuploadsをtar_gzで返す() {
        world.ssh.whenOk("test -d", "").whenOk("tar -czf", "");
        world.ssh.downloadFallback = new byte[] {1, 2, 3};

        ResponseEntity<byte[]> response = controller.exportMedia(sshRequest());

        assertArrayEquals(new byte[] {1, 2, 3}, response.getBody());
        assertTrue(world.ssh.ran("wp-content/uploads"), world.ssh.commands.toString());
    }

    @Test
    void exportThemes_リモートのthemesをtar_gzで返す() {
        world.ssh.whenOk("test -d", "").whenOk("tar -czf", "");
        world.ssh.downloadFallback = new byte[] {4, 5};

        ResponseEntity<byte[]> response = controller.exportThemes(sshRequest());

        assertArrayEquals(new byte[] {4, 5}, response.getBody());
        assertTrue(world.ssh.ran("wp-content/themes"), world.ssh.commands.toString());
    }

    @Test
    void listActivePlugins_active状態のみを返す() {
        world.ssh.site().plugins.put("akismet", "active");
        world.ssh.site().plugins.put("hello-dolly", "inactive");

        List<String> result = controller.listActivePlugins(sshRequest());

        assertEquals(List.of("akismet"), result);
    }

    // ---- issue #1557 ----

    @Test
    void letsblogPluginStatus_導入状態を返す() {
        world.agent.json("POST", "/wp-cli/letsblog-status",
                "{\"exitCode\":0,\"stdout\":\"{\\\"plugin_version\\\":\\\"1.0.0\\\",\\\"protocol_version\\\":1}\"}");

        LetsblogPluginStatus status = controller.letsblogPluginStatus(agentRequest());

        assertEquals(LetsblogPluginStatus.State.INSTALLED, status.state());
        assertEquals("1.0.0", status.version());
    }

    @Test
    void installLetsblogPlugin_導入後の状態を返す() {
        world.agent.json("POST", "/wp-cli/letsblog-install", "{\"exitCode\":0}");
        world.agent.json("POST", "/wp-cli/letsblog-status",
                "{\"exitCode\":0,\"stdout\":\"{\\\"plugin_version\\\":\\\"1.0.0\\\",\\\"protocol_version\\\":1}\"}");

        LetsblogPluginStatus status = controller.installLetsblogPlugin(agentRequest());

        assertEquals(LetsblogPluginStatus.State.INSTALLED, status.state());
        assertEquals(1, world.agent.requests("POST", "/wp-cli/letsblog-install").size());
    }

    @Test
    void syncLetsblogPlugin_内容とハッシュをアダプターへ渡し保存されたハッシュを返す() {
        world.agent.json("POST", "/wp-cli/letsblog-sync", "{\"exitCode\":0,\"stdout\":\"{\\\"sync_hash\\\":\\\"h1\\\"}\"}");

        CmsBridgeLetsblogSyncRequest request =
                new CmsBridgeLetsblogSyncRequest("WORDPRESS", agentCredentials(), "{\"a\":1}", "h1");

        assertEquals("h1", controller.syncLetsblogPlugin(request).syncHash());
        String body = world.agent.requests("POST", "/wp-cli/letsblog-sync").get(0).bodyText();
        assertTrue(body.contains("\"hash\":\"h1\"") && body.contains("{\\\"a\\\":1}"), body);
    }

    @Test
    void runLetsblogSns_コマンドと標準入力をアダプターへ渡し標準出力を返す() {
        world.agent.json("POST", "/wp-cli/letsblog-sns",
                "{\"exitCode\":0,\"stdout\":\"{\\\"status\\\":\\\"接続済み\\\"}\\n\"}");

        CmsBridgeLetsblogSnsRequest request =
                new CmsBridgeLetsblogSnsRequest("WORDPRESS", agentCredentials(), "config-set", "x", "{\"a\":1}");

        assertEquals("{\"status\":\"接続済み\"}", controller.runLetsblogSns(request).stdout());
        String body = world.agent.requests("POST", "/wp-cli/letsblog-sns").get(0).bodyText();
        assertTrue(body.contains("config-set") && body.contains("\"sns\":\"x\""), body);
    }

    @Test
    void runLetsblogSns_未知のコマンドは拒否する() {
        CmsBridgeLetsblogSnsRequest request =
                new CmsBridgeLetsblogSnsRequest("WORDPRESS", agentCredentials(), "rm-rf", "x", null);

        assertThrows(IllegalArgumentException.class, () -> controller.runLetsblogSns(request));
        assertTrue(world.agent.requests().isEmpty(), "エージェントへは送らない");
    }

    // ---- sshPort の解釈 ----

    @Test
    void sshPortは空なら未指定_数値ならそのポートとして扱い_数値でなければ拒否する() {
        Map<String, String> blank = new LinkedHashMap<>(sshCredentials());
        blank.put("sshPort", " ");
        Map<String, String> numeric = new LinkedHashMap<>(sshCredentials());
        numeric.put("sshPort", "2222");
        Map<String, String> invalid = new LinkedHashMap<>(sshCredentials());
        invalid.put("sshPort", "abc");

        assertEquals(true, controller.testConnection(new CmsBridgeCredentialsRequest("WORDPRESS", blank)).ok());
        assertEquals(true, controller.testConnection(new CmsBridgeCredentialsRequest("WORDPRESS", numeric)).ok());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> controller.testConnection(new CmsBridgeCredentialsRequest("WORDPRESS", invalid)));
        assertTrue(e.getMessage().contains("abc"), e.getMessage());
        assertNull(sshCredentials().get("sshPort"), "sshPort無しは未指定として扱われる");
    }
}
