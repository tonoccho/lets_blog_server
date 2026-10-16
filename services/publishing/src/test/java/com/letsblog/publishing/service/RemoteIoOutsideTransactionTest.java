package com.letsblog.publishing.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationStatus;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.dto.TermComparisonPage;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient;
import com.letsblog.publishing.testsupport.AgentListings.Extension;
import com.letsblog.publishing.testsupport.AgentListings.Term;
import com.letsblog.publishing.testsupport.FakeHttpServer.Response;
import com.letsblog.publishing.testsupport.PublishingWorld;
import com.letsblog.publishing.testsupport.PublishingWorld.SiteSpec;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1124: 比較/一括管理サービスのリモートI/O(HTTP/SSH)が、トランザクションの外で、
 * つまりHikariCPのコネクションを掴まずに行われることを、実際のSpringトランザクションプロキシ
 * (DataSourceTransactionManager + HikariCP/H2)越しに検証する。
 *
 * <p>受け入れ基準はWeb UIからは観測できない内部のトランザクション境界(DBコネクション占有)であり、
 * Gherkinシナリオでは表現できないため、CLAUDE.mdの「Web UIから到達できない基準」の例外として
 * サービスレベルのテストで表す。リモート呼び出しの瞬間(呼び出しスレッド上)に、
 * {@code TransactionSynchronizationManager.isActualTransactionActive()}とHikariCPの
 * {@code active}を記録する。
 *
 * <p>issue #1815で書き直し: 自前のクラス(各*Service・WordPressBulkManagementClient・WordPressSshOperations・
 * CmsAdapterFactory・WordPressAdapter等)は実物を使い、外部との通信だけを差し替える。provision-agentへのHTTPは
 * {@code FakeHttpServer}(呼び出しスレッドで外部呼び出しの記録行を出す{@code ExternalCallLoggingInterceptor}の
 * ログ行を契機に状態を記録する)、WordPressへのSSHは{@code FakeWordPress}(コマンド実行の直前のフック)。
 * DBはH2のHikariCP(本テストが調べる対象のDB側)。
 */
class RemoteIoOutsideTransactionTest {

    private static final String MASTER = "test";
    private static final long PROJECT = 1L;
    /** 投稿の比較・削除・ステータス変更はSSHのサイトで行う(managedサイトの投稿操作もエージェントHTTPだが、SSHの方が呼び出し元スレッドで観測しやすい)。 */
    private static final long SSH_PROJECT = 2L;

    private final List<String> violations = new CopyOnWriteArrayList<>();
    private final AtomicInteger remoteCalls = new AtomicInteger();

    private PublishingWorld world;
    private HikariDataSource dataSource;
    private AnnotationConfigApplicationContext context;
    private Logger externalCallLogger;
    private AppenderBase<ILoggingEvent> probeAppender;

    @BeforeEach
    void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:txboundary" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(5_000);
        dataSource = new HikariDataSource(config);

        world = new PublishingWorld();
        world.bindEnvironments(PROJECT, MASTER, SiteSpec.managed(10L, "wp-10"), SiteSpec.managed(20L, "wp-20"),
                SiteSpec.managed(30L, "wp-30"));
        world.bindEnvironments(SSH_PROJECT, MASTER, SiteSpec.ssh(40L, "ssh-40", "host-40", "/var/www/40"),
                SiteSpec.ssh(50L, "ssh-50", "host-50", "/var/www/50"),
                SiteSpec.ssh(60L, "ssh-60", "host-60", "/var/www/60"));
        world.agent.json("POST", "/bulk-management", "{\"status\":\"success\"}");
        for (String slug : List.of("wp-10", "wp-20", "wp-30")) {
            world.agentListings
                    .categories(slug, new Term("Cat", "cat", null, ""))
                    .tags(slug, new Term("Tag", "tag", null, ""))
                    .plugins(slug, new Extension("akismet", "active"))
                    .themes(slug, new Extension("twenty", "active"));
        }
        for (String host : List.of("40", "50", "60")) {
            world.ssh.site("host-" + host, "/var/www/" + host).addPost("1", "post", "T", "slug", "publish");
        }

        // 「リモート呼び出し」の瞬間(呼び出しスレッド)に、トランザクション/コネクション状態を記録する。
        probeAppender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getFormattedMessage().startsWith("external call:")) {
                    probe(event.getFormattedMessage());
                }
            }
        };
        probeAppender.start();
        externalCallLogger = (Logger) LoggerFactory.getLogger(
                com.letsblog.common.client.ExternalCallLoggingInterceptor.class);
        externalCallLogger.addAppender(probeAppender);
        world.ssh.onCommand("", params -> probe("ssh"));

        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.register(TxConfig.class);
        context.registerBean(WordPressBulkManagementClient.class, () -> world.bulkManagementClient);
        context.registerBean(com.letsblog.publishing.cms.ssh.WordPressSshOperations.class, () -> world.sshOperations);
        context.registerBean(ProjectService.class, () -> world.projectService);
        context.registerBean(SiteService.class, () -> world.siteService);
        context.registerBean(com.letsblog.publishing.cms.CmsAdapterFactory.class, () -> world.cmsAdapterFactory);
        // 本番の共有Executorは並列度に上限がある(issue #1474)。このテストの関心は「リモート待ちの間にDBを
        // 保持しないこと」なので、20並行の呼び出しが全員リモートへ届けるよう十分大きいプールを使う。
        context.registerBean("environmentFetchExecutor", ExecutorService.class,
                () -> Executors.newFixedThreadPool(60));
        context.registerBean(BulkUploadStorageService.class, () -> world.bulkUploadStorageService);
        context.registerBean(ImageResizeService.class, () -> world.imageResizeService);
        context.registerBean(com.letsblog.publishing.client.MediaSettingsBridgeClient.class,
                () -> world.mediaSettingsBridgeClient);
        context.register(BulkManagementService.class, TermComparisonService.class,
                PluginThemeComparisonService.class, PostComparisonService.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        externalCallLogger.detachAppender(probeAppender);
        context.close();
        dataSource.close();
        world.close();
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TxConfig {
        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    private void probe(String what) {
        remoteCalls.incrementAndGet();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            violations.add(what + ": トランザクションが有効なままリモートI/Oを行った");
        }
        int active = dataSource.getHikariPoolMXBean().getActiveConnections();
        if (active > 0) {
            violations.add(what + ": リモートI/O中にDBコネクションを" + active + "本保持している");
        }
    }

    private void assertNoViolations(int minimumRemoteCalls) {
        assertTrue(remoteCalls.get() >= minimumRemoteCalls,
                "リモート呼び出しが行われていない(テストが対象を通っていない): " + remoteCalls.get());
        assertEquals(List.of(), violations);
    }

    // ---- TermComparisonService ----

    @Test
    void カテゴリ比較のリモート取得はトランザクション外でコネクションを掴まない() {
        TermComparisonPage page = context.getBean(TermComparisonService.class).listCategoryComparison(PROJECT, 0, 50);
        assertEquals(1, page.totalCount());
        assertNoViolations(3);
    }

    @Test
    void タグ比較のリモート取得はトランザクション外でコネクションを掴まない() {
        context.getBean(TermComparisonService.class).listTagComparison(1L, 0, 50);
        assertNoViolations(3);
    }

    @Test
    void カテゴリ同期のリモート取得と適用はトランザクション外で行われる() {
        List<BulkOperationLog> logs = context.getBean(TermComparisonService.class).syncCategory(1L, "cat", 5L);
        assertEquals(2, logs.size());
        assertNoViolations(5);
    }

    @Test
    void タグ同期のリモート取得と適用はトランザクション外で行われる() {
        context.getBean(TermComparisonService.class).syncTag(1L, "tag", 5L);
        assertNoViolations(5);
    }

    @Test
    void カテゴリ全環境削除のリモート取得と適用はトランザクション外で行われる() {
        List<BulkOperationLog> logs = context.getBean(TermComparisonService.class)
                .deleteCategoryEverywhere(1L, "cat", 5L);
        assertEquals(3, logs.size());
        assertNoViolations(6);
    }

    @Test
    void タグ全環境削除はトランザクション外で行われる() {
        context.getBean(TermComparisonService.class).deleteTagEverywhere(1L, "tag", 5L);
        assertNoViolations(6);
    }

    @Test
    void カテゴリ編集と同期はトランザクション外で行われる() {
        List<BulkOperationLog> logs = context.getBean(TermComparisonService.class)
                .editCategoryAndSync(1L, "cat", "New", "new", null, "", 5L);
        assertEquals(3, logs.size());
        assertNoViolations(6);
    }

    @Test
    void タグ編集と同期はトランザクション外で行われる() {
        context.getBean(TermComparisonService.class).editTagAndSync(1L, "tag", "New", "new", null, "", 5L);
        assertNoViolations(6);
    }

    @Test
    void マスターへの全件同期はトランザクション外で行われる() {
        // マスター(test)にだけ存在する項目を作るため、testのみ別のスラッグを返す
        world.agentListings.categories("wp-20", new Term("OnlyMaster", "only-master", null, ""));
        List<BulkOperationLog> logs = context.getBean(TermComparisonService.class)
                .syncAllCategoriesToMaster(1L, 5L);
        assertFalse(logs.isEmpty());
        assertNoViolations(6);
    }

    @Test
    void タグの全件同期はトランザクション外で行われる() {
        context.getBean(TermComparisonService.class).syncAllTagsToMaster(1L, 5L);
        assertNoViolations(3);
    }

    // ---- PluginThemeComparisonService ----

    @Test
    void プラグイン比較とテーマ比較はトランザクション外で行われる() {
        PluginThemeComparisonService service = context.getBean(PluginThemeComparisonService.class);
        service.listPluginComparison(1L, 0, 50);
        service.listThemeComparison(1L, 0, 50);
        assertNoViolations(6);
    }

    @Test
    void プラグインとテーマの反映と削除はトランザクション外で行われる() {
        PluginThemeComparisonService service = context.getBean(PluginThemeComparisonService.class);
        service.reconcilePlugin(1L, "akismet", List.of(
                new com.letsblog.publishing.dto.ReconcileStateRequest.StateChangeRequest("local", "INACTIVE")), 5L);
        service.deletePluginEverywhere(1L, "akismet", 5L);
        service.deleteThemeEverywhere(1L, "twenty", 5L);
        assertNoViolations(8);
    }

    // ---- PostComparisonService ----

    @Test
    void 投稿の比較_削除_ステータス変更はトランザクション外で行われる() {
        PostComparisonService service = context.getBean(PostComparisonService.class);
        service.listComparison(SSH_PROJECT, "post", 0, 50);
        service.deleteEverywhere(SSH_PROJECT, "post", "slug", 5L);
        service.updateStatusEverywhere(SSH_PROJECT, "post", "slug", "draft", 5L);
        assertNoViolations(12);
    }

    // ---- BulkManagementService ----

    @Test
    void 一括管理の適用はトランザクション外で行われる() {
        BulkManagementService service = context.getBean(BulkManagementService.class);
        service.applyToEnvironment(1L, "local", BulkOperationType.CATEGORY_CREATE, "N", "n", null, "", null, 5L);
        service.applyToAllEnvironments(1L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L);
        assertNoViolations(4);
    }

    // ---- 並行実行 ----

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void 遅いリモートを挟んだ比較を20並行で実行しても他のDBアクセスは待たされない() throws Exception {
        CountDownLatch inRemote = new CountDownLatch(20);
        CountDownLatch release = new CountDownLatch(1);
        world.agent.on("POST", "/categories", request -> {
            inRemote.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Response.ok("{\"categories\":[{\"name\":\"Cat\",\"slug\":\"cat\"}]}");
        });

        TermComparisonService service = context.getBean(TermComparisonService.class);
        ExecutorService callers = Executors.newFixedThreadPool(20);
        List<java.util.concurrent.Future<TermComparisonPage>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(callers.submit(() -> service.listCategoryComparison(PROJECT, 0, 50)));
        }
        try {
            assertTrue(inRemote.await(15, TimeUnit.SECONDS), "20並行が遅いリモートに到達しなかった");
            assertEquals(0, dataSource.getHikariPoolMXBean().getActiveConnections(),
                    "リモート待ちの間にDBコネクションが保持されている");
            // 他のDBアクセス(ヘルスチェック相当)が待たされず成功する
            try (Connection connection = dataSource.getConnection()) {
                connection.createStatement().execute("SELECT 1");
            }
        } finally {
            release.countDown();
        }
        for (java.util.concurrent.Future<TermComparisonPage> future : futures) {
            assertEquals(1, future.get(15, TimeUnit.SECONDS).totalCount());
        }
        callers.shutdownNow();
    }

    // ---- 1環境の失敗は他環境を止めない ----

    @Test
    void 一括適用で1環境が失敗しても他環境の適用は続く() {
        world.agent.on("POST", "/bulk-management", request -> request.bodyText().contains("wp-10")
                ? Response.json(500, "{\"error\":\"boom\"}") : Response.ok("{\"status\":\"success\"}"));
        List<BulkOperationLog> logs = context.getBean(BulkManagementService.class)
                .applyToAllEnvironments(1L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L);
        assertEquals(3, logs.size());
        assertEquals(1, logs.stream().filter(l -> l.getStatus() == BulkOperationStatus.FAILED).count());
        assertEquals(2, logs.stream().filter(l -> l.getStatus() == BulkOperationStatus.SUCCESS).count());
        assertNoViolations(3);
    }
}
