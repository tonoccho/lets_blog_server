package com.letsblog.publishing.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.dto.ReconcileStateRequest.StateChangeRequest;
import com.letsblog.publishing.dto.StatusComparisonPage;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient;
import com.letsblog.publishing.testsupport.AgentListings.Extension;
import com.letsblog.publishing.testsupport.FakeHttpServer.Response;
import com.letsblog.publishing.testsupport.FakeWordPress;
import com.letsblog.publishing.testsupport.PublishingWorld;
import com.letsblog.publishing.testsupport.PublishingWorld.SiteSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PluginThemeComparisonServiceの回帰テスト。
 *
 * <p>issue #1815で書き直し: 自前のクラス(SiteService・ProjectService・ProjectServiceClient・BulkManagementService・
 * WordPressBulkManagementClient・WordPressSshOperations等)は実物を使い、外部との通信だけを差し替える。
 * HTTP(project-service・provision-agent)は{@code FakeHttpServer}({@code AgentListings}が一覧の応答を持つ)、
 * WordPressへのSSHは{@code FakeWordPress}。環境への書き込みは、provision-agentが実際に受け取った要求と
 * WordPress(SSH)の状態で確かめる。
 */
class PluginThemeComparisonServiceTest {

    private static final String HOST = "203.0.113.5";

    private PublishingWorld world;
    private PluginThemeComparisonService service;
    private ListAppender<ILoggingEvent> bulkLogs;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        service = new PluginThemeComparisonService(world.bulkManagementClient, world.bulkManagementService,
                world.siteService, world.projectService, world.sshOperations);
        world.agent.json("POST", "/bulk-management", "{\"status\":\"success\"}");
        bulkLogs = new ListAppender<>();
        bulkLogs.start();
        ((Logger) LoggerFactory.getLogger(BulkManagementService.class)).addAppender(bulkLogs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(BulkManagementService.class)).detachAppender(bulkLogs);
        world.close();
    }

    // ---- 部品 ----

    private SiteSpec managed(long id, String slug) {
        return SiteSpec.managed(id, slug);
    }

    private SiteSpec ssh(long id, String slug, String host, String path) {
        return SiteSpec.ssh(id, slug, host, path);
    }

    private void bind(SiteSpec local, SiteSpec test, SiteSpec production) {
        world.bindEnvironments(1L, "test", local, test, production);
    }

    private void bindLocalOnly() {
        bind(managed(10L, "local-site"), null, null);
    }

    private void bindLocalAndTestManaged() {
        bind(managed(10L, "local-site"), managed(20L, "test-site"), null);
    }

    private void bindThreeManagedSites() {
        bind(managed(10L, "local-site"), managed(20L, "test-site"), managed(30L, "production-site"));
    }

    private static Extension ext(String name, String status) {
        return new Extension(name, status);
    }

    /** provision-agentが受けた書き込みの「サイトslug:action」を、受けた順に返す。 */
    private List<String> appliedActions() {
        return world.agent.requests("POST", "/bulk-management").stream().map(r -> {
            try {
                JsonNode node = world.mapper.readTree(r.body());
                return node.path("slug").asText() + ":" + node.path("action").asText();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private List<String> fetchFailureLogs() {
        return bulkLogs.list.stream()
                .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().startsWith("一覧取得に失敗しました"))
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void listPluginComparison_未紐付け環境はavailable_falseになり_未インストールはNOT_INSTALLEDになる() {
        bindLocalAndTestManaged();
        world.agentListings.plugins("test-site", ext("akismet", "active"));

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertEquals("akismet", page.items().get(0).slug());
        assertEquals("test", page.masterEnvironment());
        assertTrue(page.items().get(0).local().available());
        assertEquals("NOT_INSTALLED", page.items().get(0).local().status());
        assertEquals("ACTIVE", page.items().get(0).test().status());
        assertFalse(page.items().get(0).production().available());
    }

    @Test
    void listPluginComparison_20件単位でページングされる() {
        bindLocalOnly();
        Extension[] plugins = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> ext(String.format("plugin-%02d", i), "active")).toArray(Extension[]::new);
        world.agentListings.plugins("local-site", plugins);

        StatusComparisonPage first = service.listPluginComparison(1L, 0, 20);
        StatusComparisonPage second = service.listPluginComparison(1L, 1, 20);

        assertEquals(20, first.items().size());
        assertEquals(5, second.items().size());
        assertEquals(25, first.totalCount());
    }

    @Test
    void reconcilePlugin_未インストールから有効へはインストールと有効化を実行する() {
        bindLocalOnly();

        service.reconcilePlugin(1L, "akismet", List.of(new StateChangeRequest("local", "ACTIVE")), 9L);

        assertEquals(List.of("local-site:plugin_install", "local-site:plugin_activate"), appliedActions());
    }

    @Test
    void reconcilePlugin_現在状態と希望状態が同じなら何も実行しない() {
        bindLocalOnly();
        world.agentListings.plugins("local-site", ext("akismet", "active"));

        List<BulkOperationLog> results = service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("local", "ACTIVE")), 9L);

        assertTrue(results.isEmpty());
        assertTrue(appliedActions().isEmpty());
    }

    @Test
    void reconcileTheme_有効から無効への遷移は例外_テーマは間接的にのみ切替可能() {
        bindLocalOnly();
        world.agentListings.themes("local-site", ext("twentytwentyfour", "active"));

        assertThrows(IllegalArgumentException.class, () -> service.reconcileTheme(
                1L, "twentytwentyfour", List.of(new StateChangeRequest("local", "INACTIVE")), 9L));
        assertTrue(appliedActions().isEmpty());
    }

    @Test
    void deletePluginEverywhere_インストール済みの環境のみ削除される() {
        bindLocalAndTestManaged();
        world.agentListings.plugins("test-site", ext("akismet", "inactive"));

        List<BulkOperationLog> results = service.deletePluginEverywhere(1L, "akismet", 9L);

        assertEquals(1, results.size());
        assertEquals(List.of("test-site:plugin_delete"), appliedActions());
    }

    @Test
    void deletePluginEverywhere_どの環境にもインストールされていなければ例外() {
        bindLocalOnly();

        assertThrows(IllegalArgumentException.class, () -> service.deletePluginEverywhere(1L, "akismet", 9L));
    }

    // ---- 非managedサイト: SSHホスト単位まとめ取得・エラー ----

    @Test
    void listPluginComparison_同一ホストのSSH環境は1回のfetchPluginsOrThemesForEnvironmentsにまとめる() {
        bind(null, ssh(20L, "test-site", HOST, "/var/www/html/test"),
                ssh(30L, "production-site", HOST, "/var/www/html/production"));
        world.ssh.site(HOST, "/var/www/html/test").plugins.put("akismet", "active");
        world.ssh.site(HOST, "/var/www/html/production").plugins.put("akismet", "inactive");

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertEquals("ACTIVE", page.items().get(0).test().status());
        assertEquals("INACTIVE", page.items().get(0).production().status());
        assertEquals(1, world.ssh.batches.size());
    }

    /** issue #1137レビュー対応: toValue()のerror()分岐のカバレッジを補う。 */
    @Test
    void listPluginComparison_一部環境がエラーでも他環境のslugはerror値として表示される() {
        bind(managed(10L, "local-site"), ssh(20L, "test-site", HOST, "/var/www/html/test"), null);
        world.agentListings.plugins("local-site", ext("akismet", "active"));
        world.ssh.when("'plugin' list", FakeWordPress.fail("Connection refused"));

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertEquals("ACTIVE", page.items().get(0).local().status());
        assertFalse(page.items().get(0).test().available());
        assertTrue(page.items().get(0).test().errorMessage().contains("Connection refused"),
                page.items().get(0).test().errorMessage());
    }

    // ---- issue #1682: agent経由の一覧取得失敗はerror値になり作業ログに記録される ----

    @Test
    void listPluginComparison_agentの読み取りタイムアウトはerror値になりPLUGIN_FETCHで記録する() {
        bindLocalAndTestManaged();
        WordPressBulkManagementClient shortTimeout =
                new WordPressBulkManagementClient(world.agent.baseUrl(), PublishingWorld.PROVISION_TOKEN, 1, 1);
        PluginThemeComparisonService timeoutService = new PluginThemeComparisonService(shortTimeout,
                world.bulkManagementService, world.siteService, world.projectService, world.sshOperations);
        world.agent.on("POST", "/plugins", request -> {
            if (request.bodyText().contains("local-site")) {
                sleep(2000);
            }
            return Response.ok("{\"plugins\":[{\"name\":\"akismet\",\"status\":\"active\"}]}");
        });

        StatusComparisonPage page = timeoutService.listPluginComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertFalse(page.items().get(0).local().available());
        assertTrue(page.items().get(0).local().errorMessage().contains("I/O error"),
                page.items().get(0).local().errorMessage());
        assertEquals("ACTIVE", page.items().get(0).test().status());
        assertEquals(1, fetchFailureLogs().size());
        assertTrue(fetchFailureLogs().get(0).contains("type=PLUGIN_FETCH"), fetchFailureLogs().get(0));
        assertTrue(fetchFailureLogs().get(0).contains("environment=local"), fetchFailureLogs().get(0));
    }

    @Test
    void listThemeComparison_agentの読み取りタイムアウトはerror値になりTHEME_FETCHで記録する() {
        bindLocalAndTestManaged();
        WordPressBulkManagementClient shortTimeout =
                new WordPressBulkManagementClient(world.agent.baseUrl(), PublishingWorld.PROVISION_TOKEN, 1, 1);
        PluginThemeComparisonService timeoutService = new PluginThemeComparisonService(shortTimeout,
                world.bulkManagementService, world.siteService, world.projectService, world.sshOperations);
        world.agent.on("POST", "/themes", request -> {
            if (request.bodyText().contains("local-site")) {
                sleep(2000);
            }
            return Response.ok("{\"themes\":[{\"name\":\"twentytwenty\",\"status\":\"active\"}]}");
        });

        StatusComparisonPage page = timeoutService.listThemeComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertFalse(page.items().get(0).local().available());
        assertTrue(fetchFailureLogs().get(0).contains("type=THEME_FETCH"), fetchFailureLogs().get(0));
    }

    @Test
    void listPluginComparison_agentが5xxを返した環境もerror値になり作業ログに記録する() {
        bindLocalAndTestManaged();
        world.agentListings.plugins("local-site", ext("akismet", "active"))
                .fail("test-site", 500, "{\"error\":\"boom\"}");

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertFalse(page.items().get(0).test().available());
        assertTrue(page.items().get(0).test().errorMessage().contains("500"));
        assertEquals(1, fetchFailureLogs().size());
        assertTrue(fetchFailureLogs().get(0).contains("environment=test"), fetchFailureLogs().get(0));
        assertTrue(fetchFailureLogs().get(0).contains("type=PLUGIN_FETCH"), fetchFailureLogs().get(0));
    }

    @Test
    void listPluginComparison_agentが4xxを返した環境もerror値になる() {
        bindLocalAndTestManaged();
        world.agentListings.fail("local-site", 401, "{\"error\":\"unauthorized\"}")
                .plugins("test-site", ext("akismet", "active"));

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertFalse(page.items().get(0).local().available());
        assertTrue(fetchFailureLogs().get(0).contains("environment=local"), fetchFailureLogs().get(0));
    }

    @Test
    void listPluginComparison_agentがその他のRestClientExceptionを投げた環境もerror値になる() {
        bindLocalAndTestManaged();
        world.agentListings.plugins("test-site", ext("akismet", "active"));
        // local-siteは、本文が読めない応答(JSONではない)を返す。
        world.agent.on("POST", "/plugins", request -> request.bodyText().contains("local-site")
                ? Response.bytes(200, "application/json", "unexpected body".getBytes()) : null);

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertFalse(page.items().get(0).local().available());
        assertTrue(page.items().get(0).local().error());
        assertFalse(page.items().get(0).local().errorMessage().isBlank());
    }

    @Test
    void listPluginComparison_agentが正常に0件を返した環境はエラーにならず未インストールになる() {
        bindLocalAndTestManaged();
        world.agentListings.plugins("test-site", ext("akismet", "active"));

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertTrue(page.items().get(0).local().available());
        assertEquals("NOT_INSTALLED", page.items().get(0).local().status());
        assertTrue(fetchFailureLogs().isEmpty());
    }

    // ---- reconcile: byEnvironment側の対象外分岐 ----

    /** issue #1137レビュー対応: 対応する環境自体が存在しない(envInfos==null)分岐のカバレッジを補う。 */
    @Test
    void reconcilePlugin_未知の環境名を指定すると例外() {
        bindLocalOnly();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("staging", "ACTIVE")), 9L));
        assertTrue(thrown.getMessage().contains("staging"));
    }

    /** issue #1137レビュー対応: 環境は存在するが対象外(infos()==null)分岐のカバレッジを補う。 */
    @Test
    void reconcilePlugin_サイト未紐付けの環境を指定すると例外() {
        bindLocalOnly();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("test", "ACTIVE")), 9L));
        assertTrue(thrown.getMessage().contains("test"));
    }

    // ---- stepsFor: 状態遷移の分岐網羅 ----

    @Test
    void reconcileTheme_未インストールから有効へはテーマのインストールと有効化を実行する() {
        bindLocalOnly();

        service.reconcileTheme(1L, "twentytwentyfour", List.of(new StateChangeRequest("local", "ACTIVE")), 9L);

        assertEquals(List.of("local-site:theme_install", "local-site:theme_activate"), appliedActions());
    }

    @Test
    void reconcilePlugin_未インストールから無効へはインストールのみ実行する() {
        bindLocalOnly();

        List<BulkOperationLog> results = service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("local", "INACTIVE")), 9L);

        assertEquals(1, results.size());
        assertEquals(List.of("local-site:plugin_install"), appliedActions());
    }

    @Test
    void reconcileTheme_未インストールから無効へはテーマのインストールのみ実行する() {
        bindLocalOnly();

        List<BulkOperationLog> results = service.reconcileTheme(
                1L, "twentytwentyfour", List.of(new StateChangeRequest("local", "INACTIVE")), 9L);

        assertEquals(1, results.size());
        assertEquals(List.of("local-site:theme_install"), appliedActions());
    }

    @Test
    void reconcilePlugin_無効から有効へは有効化のみ実行する() {
        bindLocalOnly();
        world.agentListings.plugins("local-site", ext("akismet", "inactive"));

        List<BulkOperationLog> results = service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("local", "ACTIVE")), 9L);

        assertEquals(1, results.size());
        assertEquals(List.of("local-site:plugin_activate"), appliedActions());
    }

    @Test
    void reconcileTheme_無効から有効へはテーマの有効化のみ実行する() {
        bindLocalOnly();
        world.agentListings.themes("local-site", ext("twentytwentyfour", "inactive"));

        List<BulkOperationLog> results = service.reconcileTheme(
                1L, "twentytwentyfour", List.of(new StateChangeRequest("local", "ACTIVE")), 9L);

        assertEquals(1, results.size());
        assertEquals(List.of("local-site:theme_activate"), appliedActions());
    }

    /** issue #1137レビュー対応: 無効(INACTIVE)から未インストールへの遷移は未サポート。 */
    @Test
    void reconcilePlugin_無効から未インストールへの遷移は例外() {
        bindLocalOnly();
        world.agentListings.plugins("local-site", ext("akismet", "inactive"));

        assertThrows(IllegalArgumentException.class, () -> service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("local", "NOT_INSTALLED")), 9L));
    }

    /** issue #1137レビュー対応: 有効から未インストールへの遷移は未サポート。 */
    @Test
    void reconcilePlugin_有効から未インストールへの遷移は例外() {
        bindLocalOnly();
        world.agentListings.plugins("local-site", ext("akismet", "active"));

        assertThrows(IllegalArgumentException.class, () -> service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("local", "NOT_INSTALLED")), 9L));
    }

    /** issue #1137レビュー対応: 有効から無効へのプラグイン無効化(成功する唯一の経路)。 */
    @Test
    void reconcilePlugin_有効から無効へは無効化のみ実行する() {
        bindLocalOnly();
        world.agentListings.plugins("local-site", ext("akismet", "active"));

        List<BulkOperationLog> results = service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("local", "INACTIVE")), 9L);

        assertEquals(1, results.size());
        assertEquals(List.of("local-site:plugin_deactivate"), appliedActions());
    }

    /** issue #1137レビュー対応: 未インストール状態から未サポートな希望状態を指定した場合の残余分岐。 */
    @Test
    void reconcilePlugin_未インストールから未サポートな希望状態への遷移は例外() {
        bindLocalOnly();

        assertThrows(IllegalArgumentException.class, () -> service.reconcilePlugin(
                1L, "akismet", List.of(new StateChangeRequest("local", "BOGUS")), 9L));
    }

    // ---- deleteEverywhere: isTheme分岐 ----

    @Test
    void deleteThemeEverywhere_インストール済みの環境のみ削除される() {
        bindLocalOnly();
        world.agentListings.themes("local-site", ext("twentytwentyfour", "inactive"));

        List<BulkOperationLog> results = service.deleteThemeEverywhere(1L, "twentytwentyfour", 9L);

        assertEquals(1, results.size());
        assertEquals(List.of("local-site:theme_delete"), appliedActions());
    }

    // ---- resolveInfosByEnvironment: SSH非対応/テーマ側SSH ----

    @Test
    void listPluginComparison_SSHも使えない非managedサイトはunavailableになる() {
        bind(SiteSpec.noSsh(10L, "local-site"), null, null);

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        assertTrue(world.ssh.commands.isEmpty());
    }

    @Test
    void listThemeComparison_SSH取得失敗時はerror値になり作業ログに記録する() {
        bind(null, ssh(20L, "test-site", HOST, "/var/www/html/test"), null);
        world.ssh.when("'theme' list", FakeWordPress.fail("Connection refused"));

        StatusComparisonPage page = service.listThemeComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        assertTrue(world.ssh.commands.stream().anyMatch(c -> c.contains("'theme' list")));
        assertEquals(1, fetchFailureLogs().size());
        assertTrue(fetchFailureLogs().get(0).contains("type=THEME_FETCH"), fetchFailureLogs().get(0));
        assertTrue(fetchFailureLogs().get(0).contains("Connection refused"), fetchFailureLogs().get(0));
    }

    @Test
    void listPluginComparison_sshPort未設定なら既定の22番でホストをまとめる() {
        bind(null, ssh(20L, "test-site", HOST, "/var/www/html/test"),
                ssh(30L, "production-site", HOST, "/var/www/html/production"));
        Map<String, String> withoutPort = new HashMap<>(PublishingWorld.sshCredentialsMap());
        withoutPort.put("sshHost", HOST);
        withoutPort.put("wpPath", "/var/www/html/test");
        withoutPort.remove("sshPort");
        world.stubCredentials("test-site", withoutPort);

        service.listPluginComparison(1L, 0, 20);

        // 双方とも同一ホスト(203.0.113.5)かつポート未設定=既定22番なので、production側の
        // 明示的な22番指定と同一ホストキーになり1回のfetchにまとまる。
        assertEquals(1, world.ssh.batches.size());
    }

    @Test
    void listPluginComparison_SSH取得失敗時はerror値になり作業ログに記録する() {
        bind(null, ssh(20L, "test-site", HOST, "/var/www/html/test"), null);
        world.ssh.when("'plugin' list", FakeWordPress.fail("Connection refused"));

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        assertEquals(1, fetchFailureLogs().size());
        assertTrue(fetchFailureLogs().get(0).contains("type=PLUGIN_FETCH"), fetchFailureLogs().get(0));
        assertTrue(fetchFailureLogs().get(0).contains("environment=test"), fetchFailureLogs().get(0));
    }

    @Test
    void listPluginComparison_SSHに接続できなければその環境の全てがerror値になる() {
        bind(null, ssh(20L, "test-site", HOST, "/var/www/html/test"), null);
        world.ssh.rejectHost(HOST);

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        assertEquals(1, fetchFailureLogs().size());
    }

    // ---- issue #1687: 環境間の並列実行 ----

    @Test
    void 一覧取得_managed環境ごとのagent取得が重なって走る() {
        bindThreeManagedSites();
        OverlapProbe probe = new OverlapProbe(3);
        world.agent.on("POST", "/plugins", request -> {
            probe.enter();
            return Response.ok("{\"plugins\":[{\"name\":\"akismet\",\"status\":\"active\"}]}");
        });

        StatusComparisonPage page = service.listPluginComparison(1L, 0, 20);

        assertTrue(probe.allOverlapped(), "3環境の一覧取得が重なって呼ばれていない(逐次実行)");
        assertEquals(1, page.items().size());
    }

    @Test
    void 一覧取得_agent取得の実行時例外は並列でも呼び出し元へそのまま伝わる() {
        bind(managed(10L, "local-site"), managed(20L, "test-site"), managed(30L, "production-site"));
        // managedなのにwpSlugが無いサイト(project-serviceの登録不備)。リクエスト本文を作る段階でNullPointerExceptionになる。
        world.project.json("GET", "/api/internal/project/sites/10",
                "{\"id\":10,\"siteKey\":\"local-site\",\"name\":\"n\",\"baseUrl\":\"https://e.example\","
                        + "\"cmsType\":\"WORDPRESS\",\"managedWordpress\":true,\"wpSlug\":null}");

        assertThrows(NullPointerException.class, () -> service.listPluginComparison(1L, 0, 20));
    }

    /** 1回目だけ成功し、2回目以降は404になるサイト(一覧取得の時点では登録されていたが、書き込み時には消えていた)。 */
    private void siteVanishesAfterListing(SiteSpec spec) {
        world.project.sequence("GET", "/api/internal/project/sites/" + spec.id(),
                Response.ok(world.siteJson(spec)), Response.json(404, "{}"));
    }

    @Test
    void deletePluginEverywhere_環境ごとの削除が重なって走り_結果はENVIRONMENT_ORDER順() {
        bindThreeManagedSites();
        for (String slug : List.of("local-site", "test-site", "production-site")) {
            world.agentListings.plugins(slug, ext("akismet", "inactive"));
        }
        OverlapProbe probe = new OverlapProbe(3);
        world.agent.on("POST", "/bulk-management", request -> {
            if (request.bodyText().contains("local-site")) {
                sleep(150);
            }
            probe.enter();
            return Response.ok("{\"status\":\"success\"}");
        });

        List<BulkOperationLog> results = service.deletePluginEverywhere(1L, "akismet", 9L);

        assertTrue(probe.allOverlapped(), "3環境の削除が重なって呼ばれていない(逐次実行)");
        assertEquals(List.of("local", "test", "production"),
                results.stream().map(BulkOperationLog::getEnvironment).toList());
    }

    @Test
    void deleteThemeEverywhere_環境ごとの削除が重なって走り_結果はENVIRONMENT_ORDER順() {
        bindThreeManagedSites();
        for (String slug : List.of("local-site", "test-site", "production-site")) {
            world.agentListings.themes(slug, ext("twentytwentyfour", "inactive"));
        }
        OverlapProbe probe = new OverlapProbe(3);
        world.agent.on("POST", "/bulk-management", request -> {
            if (request.bodyText().contains("local-site")) {
                sleep(150);
            }
            probe.enter();
            return Response.ok("{\"status\":\"success\"}");
        });

        List<BulkOperationLog> results = service.deleteThemeEverywhere(1L, "twentytwentyfour", 9L);

        assertTrue(probe.allOverlapped(), "3環境のテーマ削除が重なって呼ばれていない(逐次実行)");
        assertEquals(List.of("local", "test", "production"),
                results.stream().map(BulkOperationLog::getEnvironment).toList());
        assertTrue(results.stream().allMatch(r -> r.getOperationType() == BulkOperationType.THEME_DELETE));
    }

    @Test
    void deletePluginEverywhere_1環境の実行時例外でも他環境の削除は最後まで実行される() {
        bindThreeManagedSites();
        for (String slug : List.of("local-site", "test-site", "production-site")) {
            world.agentListings.plugins(slug, ext("akismet", "inactive"));
        }
        siteVanishesAfterListing(managed(10L, "local-site"));
        world.agent.on("POST", "/bulk-management", request -> {
            sleep(100);
            return Response.ok("{\"status\":\"success\"}");
        });

        SiteNotFoundException thrown = assertThrows(SiteNotFoundException.class,
                () -> service.deletePluginEverywhere(1L, "akismet", 9L));

        assertTrue(thrown.getMessage().contains("10"), thrown.getMessage());
        assertTrue(appliedActions().contains("test-site:plugin_delete"));
        assertTrue(appliedActions().contains("production-site:plugin_delete"));
    }

    @Test
    void reconcilePlugin_環境間は重なって走り_結果はchangesの順_環境内の段階順は保たれる() {
        bindThreeManagedSites();
        OverlapProbe probe = new OverlapProbe(3);
        ConcurrentLinkedQueue<String> calls = new ConcurrentLinkedQueue<>();
        world.agent.on("POST", "/bulk-management", request -> {
            try {
                JsonNode node = world.mapper.readTree(request.body());
                String site = node.path("slug").asText();
                String action = node.path("action").asText();
                calls.add(site + ":" + action);
                if (action.equals("plugin_install")) {
                    probe.enter();
                } else {
                    // INSTALLより後に走ることが環境内の段階順の証拠になる
                    sleep(50);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return Response.ok("{\"status\":\"success\"}");
        });

        // changesは local/test/production の逆順
        List<BulkOperationLog> results = service.reconcilePlugin(1L, "akismet", List.of(
                new StateChangeRequest("production", "ACTIVE"),
                new StateChangeRequest("test", "ACTIVE"),
                new StateChangeRequest("local", "ACTIVE")), 9L);

        assertTrue(probe.allOverlapped(), "3環境の反映が重なって呼ばれていない(逐次実行)");
        assertEquals(List.of(
                "production:PLUGIN_INSTALL", "production:PLUGIN_ACTIVATE",
                "test:PLUGIN_INSTALL", "test:PLUGIN_ACTIVATE",
                "local:PLUGIN_INSTALL", "local:PLUGIN_ACTIVATE"),
                results.stream().map(r -> r.getEnvironment() + ":" + r.getOperationType()).toList());
        for (String site : List.of("local-site", "test-site", "production-site")) {
            List<String> perSite = calls.stream().filter(c -> c.startsWith(site + ":")).toList();
            assertEquals(List.of(site + ":plugin_install", site + ":plugin_activate"), perSite);
        }
    }

    @Test
    void reconcileTheme_環境間は重なって走る() {
        bindThreeManagedSites();
        OverlapProbe probe = new OverlapProbe(2);
        world.agent.on("POST", "/bulk-management", request -> {
            probe.enter();
            return Response.ok("{\"status\":\"success\"}");
        });

        List<BulkOperationLog> results = service.reconcileTheme(1L, "twentytwentyfour", List.of(
                new StateChangeRequest("test", "INACTIVE"),
                new StateChangeRequest("local", "INACTIVE")), 9L);

        assertTrue(probe.allOverlapped(), "2環境のテーマ反映が重なって呼ばれていない(逐次実行)");
        assertEquals(List.of("test", "local"), results.stream().map(BulkOperationLog::getEnvironment).toList());
    }

    @Test
    void reconcilePlugin_1環境の実行時例外でも他環境の反映は最後まで実行される() {
        bindThreeManagedSites();
        siteVanishesAfterListing(managed(10L, "local-site"));
        world.agent.on("POST", "/bulk-management", request -> {
            sleep(100);
            return Response.ok("{\"status\":\"success\"}");
        });

        SiteNotFoundException thrown = assertThrows(SiteNotFoundException.class, () -> service.reconcilePlugin(
                1L, "akismet", List.of(
                        new StateChangeRequest("local", "INACTIVE"),
                        new StateChangeRequest("test", "INACTIVE")), 9L));

        assertTrue(thrown.getMessage().contains("10"), thrown.getMessage());
        assertTrue(appliedActions().contains("test-site:plugin_install"));
    }

    @Test
    void reconcilePlugin_同じ環境を複数指定しても環境内は直列で実行される() {
        bindThreeManagedSites();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        world.agent.on("POST", "/bulk-management", request -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            sleep(50);
            running.decrementAndGet();
            return Response.ok("{\"status\":\"success\"}");
        });

        List<BulkOperationLog> results = service.reconcilePlugin(1L, "akismet", List.of(
                new StateChangeRequest("local", "INACTIVE"),
                new StateChangeRequest("local", "INACTIVE")), 9L);

        assertEquals(1, maxRunning.get());
        assertEquals(2, results.size());
    }

    @Test
    void reconcilePlugin_サポート外の遷移を含む場合は他の変更も実行せず例外() {
        bindThreeManagedSites();
        world.agentListings.plugins("test-site", ext("akismet", "active"));

        assertThrows(IllegalArgumentException.class, () -> service.reconcilePlugin(1L, "akismet", List.of(
                new StateChangeRequest("local", "ACTIVE"),
                new StateChangeRequest("test", "NOT_INSTALLED")), 9L));

        assertTrue(appliedActions().isEmpty(), "どの環境へも書き込まない");
    }

    @Test
    void 一覧取得_後続環境の解決が失敗しても_投入済みのagent取得を待ち終えてから例外を投げる() {
        bind(managed(10L, "local-site"), managed(20L, "test-site"), null);
        // 後続の環境(test)のサイト解決がproject-serviceの障害で失敗する。
        world.project.status("GET", "/api/internal/project/sites/20", 500, "{\"error\":\"down\"}");
        AtomicBoolean fetchFinished = new AtomicBoolean();
        world.agent.on("POST", "/plugins", request -> {
            sleep(300);
            fetchFinished.set(true);
            return Response.ok("{\"plugins\":[]}");
        });

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.listPluginComparison(1L, 0, 20));

        assertTrue(thrown.getMessage().contains("サイト照会"), thrown.getMessage());
        assertTrue(fetchFinished.get(), "投入済みのagent取得が終わる前に例外が呼び出し元へ伝わった(ワーカーが孤児になる)");
    }
}
