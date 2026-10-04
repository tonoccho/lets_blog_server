package com.letsblog.publishing.service;

import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.CmsPostSummary;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.cms.ssh.WordPressSshOperations;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationStatus;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.domain.Site;
import com.letsblog.publishing.dto.TermComparisonPage;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient.BulkApplyResult;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient.CategoryInfo;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient.PluginThemeInfo;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * issue #1124: 比較/一括管理サービスのリモートI/O(HTTP/SSH)が、トランザクションの外で、
 * つまりHikariCPのコネクションを掴まずに行われることを、実際のSpringトランザクションプロキシ
 * (DataSourceTransactionManager + HikariCP/H2)越しに検証する。
 *
 * <p>受け入れ基準はWeb UIからは観測できない内部のトランザクション境界(DBコネクション占有)であり、
 * Gherkinシナリオでは表現できないため、CLAUDE.mdの「Web UIから到達できない基準」の例外として
 * サービスレベルのテストで表す。リモート呼び出しを模したモックの応答内で、
 * {@code TransactionSynchronizationManager.isActualTransactionActive()}とHikariCPの
 * {@code active}を記録する。
 */
class RemoteIoOutsideTransactionTest {

    private static final String MASTER = "test";

    private final List<String> violations = new CopyOnWriteArrayList<>();
    private final AtomicInteger remoteCalls = new AtomicInteger();

    private HikariDataSource dataSource;
    private AnnotationConfigApplicationContext context;

    private WordPressBulkManagementClient client;
    private WordPressSshOperations ssh;
    private ProjectService projectService;
    private SiteService siteService;
    private CmsAdapterFactory cmsAdapterFactory;
    private CmsAdapter cmsAdapter;

    @BeforeEach
    void setUp() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:txboundary" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(10);
        config.setConnectionTimeout(5_000);
        dataSource = new HikariDataSource(config);

        client = mock(WordPressBulkManagementClient.class);
        ssh = mock(WordPressSshOperations.class);
        projectService = mock(ProjectService.class);
        siteService = mock(SiteService.class);
        cmsAdapterFactory = mock(CmsAdapterFactory.class);
        cmsAdapter = mock(CmsAdapter.class);

        Project project = new Project();
        project.setId(1L);
        project.setLocalSiteId(10L);
        project.setTestSiteId(20L);
        project.setProductionSiteId(30L);
        project.setMasterEnvironment(MASTER);
        when(projectService.getProjectEntity(anyLong())).thenReturn(project);
        for (long id : new long[] {10L, 20L, 30L}) {
            Site site = new Site();
            site.setId(id);
            site.setSiteKey("site-" + id);
            site.setCmsType(CmsType.WORDPRESS);
            site.setManagedWordpress(true);
            site.setWpSlug("wp-" + id);
            when(siteService.getById(id)).thenReturn(Optional.of(site));
        }
        when(siteService.getCredentials(anyString())).thenReturn(
                new CmsCredentials.WordPressCredentials("http://example.invalid", "u", "AGENT"));
        when(cmsAdapterFactory.resolve(any())).thenReturn(cmsAdapter);

        // 「リモート呼び出し」を模した応答。呼ばれた瞬間のトランザクション/コネクション状態を記録する。
        when(client.listCategories(anyString())).thenAnswer(inv -> {
            probe("listCategories");
            return List.of(new CategoryInfo("Cat", "cat", null, ""));
        });
        when(client.listTags(anyString())).thenAnswer(inv -> {
            probe("listTags");
            return List.of(new CategoryInfo("Tag", "tag", null, ""));
        });
        when(client.listPlugins(anyString())).thenAnswer(inv -> {
            probe("listPlugins");
            return List.of(new PluginThemeInfo("akismet", "active"));
        });
        when(client.listThemes(anyString())).thenAnswer(inv -> {
            probe("listThemes");
            return List.of(new PluginThemeInfo("twenty", "active"));
        });
        when(client.apply(any())).thenAnswer(inv -> {
            probe("apply");
            return new BulkApplyResult(BulkOperationStatus.SUCCESS.name(), null, null);
        });
        when(cmsAdapter.listPosts(any(), anyString())).thenAnswer(inv -> {
            probe("listPosts");
            return List.of(new CmsPostSummary("1", "T", "slug", "publish", "post"));
        });
        org.mockito.Mockito.doAnswer(inv -> {
            probe("deletePost");
            return null;
        }).when(cmsAdapter).deletePost(any(), anyString(), anyString());
        org.mockito.Mockito.doAnswer(inv -> {
            probe("updatePostStatus");
            return null;
        }).when(cmsAdapter).updatePostStatus(any(), anyString(), anyString(), anyString());

        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource);
        context.register(TxConfig.class);
        context.registerBean(WordPressBulkManagementClient.class, () -> client);
        context.registerBean(WordPressSshOperations.class, () -> ssh);
        context.registerBean(ProjectService.class, () -> projectService);
        context.registerBean(SiteService.class, () -> siteService);
        context.registerBean(CmsAdapterFactory.class, () -> cmsAdapterFactory);
        // 本番の共有Executorは並列度に上限がある(issue #1474)。このテストの関心は「リモート待ちの間にDBを
        // 保持しないこと」なので、20並行の呼び出しが全員リモートへ届けるよう十分大きいプールを使う。
        context.registerBean("environmentFetchExecutor", ExecutorService.class,
                () -> Executors.newFixedThreadPool(60));
        context.registerBean(BulkUploadStorageService.class, () -> mock(BulkUploadStorageService.class));
        context.registerBean(ImageResizeService.class, () -> mock(ImageResizeService.class));
        context.registerBean(
                com.letsblog.publishing.client.MediaSettingsBridgeClient.class,
                () -> mock(com.letsblog.publishing.client.MediaSettingsBridgeClient.class));
        context.register(BulkManagementService.class, TermComparisonService.class,
                PluginThemeComparisonService.class, PostComparisonService.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        context.close();
        dataSource.close();
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
        TermComparisonPage page = context.getBean(TermComparisonService.class).listCategoryComparison(1L, 0, 50);
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
        when(client.listCategories("wp-20")).thenAnswer(inv -> {
            probe("listCategories");
            return List.of(new CategoryInfo("OnlyMaster", "only-master", null, ""));
        });
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
        service.listComparison(1L, "post", 0, 50);
        service.deleteEverywhere(1L, "post", "slug", 5L);
        service.updateStatusEverywhere(1L, "post", "slug", "draft", 5L);
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
        when(client.listCategories(anyString())).thenAnswer(inv -> {
            inRemote.countDown();
            release.await(20, TimeUnit.SECONDS);
            return List.of(new CategoryInfo("Cat", "cat", null, ""));
        });

        TermComparisonService service = context.getBean(TermComparisonService.class);
        ExecutorService callers = Executors.newFixedThreadPool(20);
        List<java.util.concurrent.Future<TermComparisonPage>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(callers.submit(() -> service.listCategoryComparison(1L, 0, 50)));
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
        when(client.apply(any())).thenAnswer(inv -> {
            probe("apply");
            WordPressBulkManagementClient.BulkApplyCommand command = inv.getArgument(0);
            return "wp-10".equals(command.slug())
                    ? new BulkApplyResult(BulkOperationStatus.FAILED.name(), "boom", null)
                    : new BulkApplyResult(BulkOperationStatus.SUCCESS.name(), null, null);
        });
        List<BulkOperationLog> logs = context.getBean(BulkManagementService.class)
                .applyToAllEnvironments(1L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L);
        assertEquals(3, logs.size());
        assertEquals(1, logs.stream().filter(l -> l.getStatus() == BulkOperationStatus.FAILED).count());
        assertEquals(2, logs.stream().filter(l -> l.getStatus() == BulkOperationStatus.SUCCESS).count());
        assertNoViolations(3);
    }
}
