package com.letsblog.publishing.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationStatus;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.dto.TermComparisonPage;
import com.letsblog.publishing.dto.TermComparisonRow;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient.BulkApplyCommand;
import com.letsblog.publishing.testsupport.AgentListings.Term;
import com.letsblog.publishing.testsupport.FakeHttpServer.Response;
import com.letsblog.publishing.testsupport.FakeWordPress;
import com.letsblog.publishing.testsupport.PublishingWorld;
import com.letsblog.publishing.testsupport.PublishingWorld.SiteSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TermComparisonServiceの回帰テスト。
 *
 * <p>issue #1815で書き直し: 自前のクラス(SiteService・ProjectService・ProjectServiceClient・BulkManagementService・
 * WordPressBulkManagementClient・WordPressSshOperations・CmsAdapterFactory等)は実物を使い、外部との通信だけを
 * 差し替える。HTTP(project-service・provision-agent)は{@code FakeHttpServer}({@code AgentListings}が一覧の
 * 応答を持つ)、WordPressへのSSHは{@code FakeWordPress}。環境への書き込みは、provision-agentが実際に受け取った
 * 要求とWordPress(SSH)の状態で確かめる。
 */
class TermComparisonServiceTest {

    private PublishingWorld world;
    private TermComparisonService service;
    private ListAppender<ILoggingEvent> bulkLogs;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        service = newService(world.bulkManagementClient);
        world.agent.json("POST", "/bulk-management", "{\"status\":\"success\"}");
        bulkLogs = new ListAppender<>();
        bulkLogs.start();
        ((Logger) LoggerFactory.getLogger(BulkManagementService.class)).addAppender(bulkLogs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(BulkManagementService.class)).detachAppender(bulkLogs);
        MDC.clear();
        world.close();
    }

    private TermComparisonService newService(WordPressBulkManagementClient client) {
        BulkManagementService bulk = new BulkManagementService(client, world.bulkUploadStorageService,
                world.siteService, world.sshOperations, world.cmsAdapterFactory, world.projectService,
                world.imageResizeService, world.mediaSettingsBridgeClient);
        return new TermComparisonService(client, bulk, world.siteService, world.projectService,
                world.sshOperations);
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

    private void bindLocalAndTestManaged() {
        bind(managed(10L, "local-site"), managed(20L, "test-site"), null);
    }

    private void bindThreeManaged() {
        bind(managed(10L, "local-site"), managed(20L, "test-site"), managed(30L, "production-site"));
    }

    private static Term term(String name, String slug, String parent, String description) {
        return new Term(name, slug, parent, description);
    }

    private List<JsonNode> applied() {
        return world.agent.requests("POST", "/bulk-management").stream().map(r -> {
            try {
                return world.mapper.readTree(r.body());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private boolean wasApplied(String slug, String action, String value, String categorySlug, String parentSlug,
            String description, String targetSlug) {
        JsonNode expected = world.mapper.valueToTree(
                new BulkApplyCommand(slug, action, value, categorySlug, parentSlug, description, targetSlug));
        return applied().contains(expected);
    }

    /** 一覧取得の失敗として記録された警告(BulkManagementService#logFetchFailure)。 */
    private List<String> fetchFailureLogs() {
        return bulkLogs.list.stream()
                .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().startsWith("一覧取得に失敗しました"))
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    // ---- listCategoryComparison ----

    @Test
    void listCategoryComparison_スラッグでマージし未紐付け環境はavailable_falseになる() {
        bindLocalAndTestManaged();
        world.agentListings
                .categories("local-site", term("お知らせ(旧)", "oshirase", null, null))
                .categories("test-site", term("お知らせ", "oshirase", null, "説明"));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        // 名前は環境間で異なっていても、スラッグが同じなら1行にまとまり、
        // 表示名はマスター環境(test)の値が採用される
        assertEquals("お知らせ", page.items().get(0).name());
        assertEquals("oshirase", page.items().get(0).slug());
        assertEquals("test", page.masterEnvironment());
        assertTrue(page.items().get(0).local().available());
        assertEquals("oshirase", page.items().get(0).local().slug());
        assertEquals("oshirase", page.items().get(0).test().slug());
        assertFalse(page.items().get(0).production().available());
    }

    // ---- issue #1682: agent経由の一覧取得失敗はerror値になり作業ログに記録される ----

    @Test
    void listCategoryComparison_agentの読み取りタイムアウトはerror値になりCATEGORY_FETCHで記録する() {
        bindLocalAndTestManaged();
        // 読み取りタイムアウト1秒のクライアントで、2秒かけて応答するagentを呼ぶ。
        WordPressBulkManagementClient shortTimeout =
                new WordPressBulkManagementClient(world.agent.baseUrl(), PublishingWorld.PROVISION_TOKEN, 1, 1);
        TermComparisonService timeoutService = newService(shortTimeout);
        world.agent.on("POST", "/categories", request -> {
            if (request.bodyText().contains("local-site")) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return Response.ok("{\"categories\":[{\"name\":\"お知らせ\",\"slug\":\"oshirase\"}]}");
        });

        TermComparisonPage page = timeoutService.listCategoryComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertFalse(page.items().get(0).local().available());
        assertTrue(page.items().get(0).local().error());
        assertTrue(page.items().get(0).local().errorMessage().contains("I/O error"),
                page.items().get(0).local().errorMessage());
        assertTrue(page.items().get(0).test().available());
        assertEquals(1, fetchFailureLogs().size());
        assertTrue(fetchFailureLogs().get(0).contains("type=CATEGORY_FETCH"), fetchFailureLogs().get(0));
        assertTrue(fetchFailureLogs().get(0).contains("environment=local"), fetchFailureLogs().get(0));
    }

    @Test
    void listTagComparison_agentの読み取りタイムアウトはerror値になりTAG_FETCHで記録する() {
        bindLocalAndTestManaged();
        WordPressBulkManagementClient shortTimeout =
                new WordPressBulkManagementClient(world.agent.baseUrl(), PublishingWorld.PROVISION_TOKEN, 1, 1);
        TermComparisonService timeoutService = newService(shortTimeout);
        world.agent.on("POST", "/tags", request -> {
            if (request.bodyText().contains("local-site")) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return Response.ok("{\"tags\":[{\"name\":\"タグ\",\"slug\":\"tag\"}]}");
        });

        TermComparisonPage page = timeoutService.listTagComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertFalse(page.items().get(0).local().available());
        assertTrue(fetchFailureLogs().get(0).contains("type=TAG_FETCH"), fetchFailureLogs().get(0));
        assertTrue(fetchFailureLogs().get(0).contains("environment=local"), fetchFailureLogs().get(0));
    }

    @Test
    void listCategoryComparison_agentが5xxを返した環境もerror値になる() {
        bindLocalAndTestManaged();
        world.agentListings.categories("local-site", term("お知らせ", "oshirase", null, null))
                .fail("test-site", 502, "{\"error\":\"bad gateway\"}");

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertFalse(page.items().get(0).test().available());
        assertEquals(1, fetchFailureLogs().size());
        assertTrue(fetchFailureLogs().get(0).contains("environment=test"), fetchFailureLogs().get(0));
        assertTrue(fetchFailureLogs().get(0).contains("type=CATEGORY_FETCH"), fetchFailureLogs().get(0));
    }

    @Test
    void listTagComparison_agentが4xxやその他のRestClientExceptionでもerror値になる() {
        bindLocalAndTestManaged();
        world.agentListings.fail("local-site", 403, "{\"error\":\"forbidden\"}");
        // test-siteは、本文が読めない応答(JSONではない)を返す。
        world.agent.on("POST", "/tags", request -> request.bodyText().contains("test-site")
                ? Response.bytes(200, "application/json", "unexpected body".getBytes()) : null);

        TermComparisonPage page = service.listTagComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        assertEquals(2, fetchFailureLogs().size());
        assertTrue(fetchFailureLogs().stream().anyMatch(l -> l.contains("environment=local")));
        assertTrue(fetchFailureLogs().stream().anyMatch(l -> l.contains("environment=test")
                && l.contains("type=TAG_FETCH")));
    }

    @Test
    void listCategoryComparison_agentが正常に0件を返した環境はエラーにならない() {
        bindLocalAndTestManaged();
        world.agentListings.categories("test-site", term("お知らせ", "oshirase", null, null));
        // local-siteは0件(登録なし)。

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertTrue(page.items().get(0).local().available());
        assertFalse(page.items().get(0).local().error());
        assertNull(page.items().get(0).local().slug());
        assertTrue(fetchFailureLogs().isEmpty());
    }

    @Test
    void listCategoryComparison_スラッグが異なれば名前が同じでも別行になる() {
        bindLocalAndTestManaged();
        world.agentListings
                .categories("local-site", term("お知らせ", "oshirase-old", null, null))
                .categories("test-site", term("お知らせ", "oshirase", null, "説明"));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(2, page.items().size());
    }

    @Test
    void listCategoryComparison_マスターに存在しない項目はmaster列がmissingになる() {
        bindLocalAndTestManaged();
        world.agentListings.categories("local-site", term("ローカル限定", "local-only", null, null));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertTrue(page.items().get(0).local().available());
        assertTrue(page.items().get(0).test().available());
        assertNull(page.items().get(0).test().slug());
    }

    @Test
    void listCategoryComparison_20件単位でページングされる() {
        bind(managed(10L, "local-site"), null, null);
        Term[] categories = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> term(String.format("cat-%02d", i), "slug-" + i, null, null)).toArray(Term[]::new);
        world.agentListings.categories("local-site", categories);

        TermComparisonPage firstPage = service.listCategoryComparison(1L, 0, 20);
        TermComparisonPage secondPage = service.listCategoryComparison(1L, 1, 20);

        assertEquals(20, firstPage.items().size());
        assertEquals(5, secondPage.items().size());
        assertEquals(25, firstPage.totalCount());
    }

    // ---- syncCategory ----

    @Test
    void syncCategory_マスターに存在しなければ例外() {
        bindLocalAndTestManaged();

        assertThrows(IllegalArgumentException.class, () -> service.syncCategory(1L, "oshirase", 9L));
    }

    @Test
    void syncCategory_非マスター環境に存在しなければ作成_存在すれば更新する() {
        bindThreeManaged();
        world.agentListings
                .categories("test-site", term("お知らせ", "oshirase", null, "説明"))
                // productionは同じスラッグ(名寄せキー)だが表示名・説明が異なる = 同一項目として編集対象になる
                .categories("production-site", term("お知らせ(旧)", "oshirase", null, "旧説明"));

        service.syncCategory(1L, "oshirase", 9L);

        assertTrue(wasApplied("local-site", "category_create", "お知らせ", "oshirase", null, "説明", null));
        assertTrue(wasApplied("production-site", "category_edit", "お知らせ", "oshirase", null, "説明", "oshirase"));
        assertEquals(2, applied().size(), "マスター環境(test)には書き込まない");
        assertFalse(applied().stream().anyMatch(c -> c.path("slug").asText().equals("test-site")));
    }

    /** issue #1137レビュー対応: toValue()のerror()分岐のカバレッジを補う。 */
    @Test
    void listCategoryComparison_一部環境がエラーでも他環境のslugはerror値として表示される() {
        bind(managed(10L, "local-site"), ssh(20L, "test-site", "203.0.113.5", "/var/www/html/test"), null);
        world.agentListings.categories("local-site", term("お知らせ", "oshirase", null, null));
        world.ssh.when("term list", FakeWordPress.fail("Connection refused"));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertTrue(page.items().get(0).local().available());
        assertFalse(page.items().get(0).test().available());
        assertTrue(page.items().get(0).test().errorMessage().contains("Connection refused"),
                page.items().get(0).test().errorMessage());
    }

    // ---- deleteCategoryEverywhere ----

    @Test
    void deleteCategoryEverywhere_存在する環境のみ削除される() {
        bindLocalAndTestManaged();
        world.agentListings.categories("test-site", term("お知らせ", "oshirase", null, null));

        List<BulkOperationLog> results = service.deleteCategoryEverywhere(1L, "oshirase", 9L);

        assertEquals(1, results.size());
        assertTrue(wasApplied("test-site", "category_delete", "oshirase", null, null, null, "oshirase"));
        assertEquals(1, applied().size(), "存在しない環境(local)へは削除を送らない");
    }

    @Test
    void deleteCategoryEverywhere_どの環境にも存在しなければ例外() {
        bind(managed(10L, "local-site"), null, null);

        assertThrows(IllegalArgumentException.class, () -> service.deleteCategoryEverywhere(1L, "oshirase", 9L));
    }

    /** issue #1137レビュー対応: deleteTagEverywhereでのdeleteType三項演算子のカバレッジを補う。 */
    @Test
    void deleteTagEverywhere_存在する環境のみ削除される() {
        bindLocalAndTestManaged();
        world.agentListings.tags("test-site", term("新着", "shinchaku", null, null));

        List<BulkOperationLog> results = service.deleteTagEverywhere(1L, "shinchaku", 9L);

        assertEquals(1, results.size());
        assertTrue(wasApplied("test-site", "tag_delete", "shinchaku", null, null, null, "shinchaku"));
    }

    // ---- editCategoryAndSync ----

    @Test
    void editCategoryAndSync_マスターに存在しなければ新規作成する() {
        bindLocalAndTestManaged();

        List<BulkOperationLog> results = service.editCategoryAndSync(
                1L, "oshirase", "新お知らせ", "new-oshirase", null, null, 9L);

        assertEquals(2, results.size());
        assertTrue(wasApplied("test-site", "category_create", "新お知らせ", "new-oshirase", null, null, null));
        assertTrue(wasApplied("local-site", "category_create", "新お知らせ", "new-oshirase", null, null, null));
    }

    @Test
    void editCategoryAndSync_マスターを新しい値で更新し他の環境にも新しい値を反映する() {
        bindThreeManaged();
        world.agentListings
                .categories("test-site", term("お知らせ", "oshirase", null, "旧説明"))
                // localは同じスラッグ(名寄せキー)だが表示名は異なりうる = 同一項目として編集対象になる
                .categories("local-site", term("お知らせ(旧)", "oshirase", null, null));

        List<BulkOperationLog> results = service.editCategoryAndSync(
                1L, "oshirase", "新お知らせ", "new-oshirase", "parent-slug", "新説明", 9L);

        assertEquals(3, results.size());
        assertTrue(wasApplied("test-site", "category_edit", "新お知らせ", "new-oshirase", "parent-slug", "新説明",
                "oshirase"));
        assertTrue(wasApplied("local-site", "category_edit", "新お知らせ", "new-oshirase", "parent-slug", "新説明",
                "oshirase"));
        assertTrue(wasApplied("production-site", "category_create", "新お知らせ", "new-oshirase", "parent-slug",
                "新説明", null));
    }

    /** issue #1137レビュー対応: editTagAndSyncのBulkOperationType三項演算子のカバレッジを補う。 */
    @Test
    void editTagAndSync_タグ用のBulkOperationTypeで呼ばれる() {
        bindLocalAndTestManaged();

        List<BulkOperationLog> results = service.editTagAndSync(
                1L, "shinchaku", "新着", "shinchaku-new", null, null, 9L);

        assertEquals(2, results.size());
        assertTrue(wasApplied("test-site", "tag_create", "新着", "shinchaku-new", null, null, null));
    }

    @Test
    void editCategoryAndSync_取得できない環境には書き込まない() {
        bind(managed(10L, "local-site"), managed(20L, "test-site"), null);
        world.agentListings.fail("local-site", 500, "{}");

        List<BulkOperationLog> results = service.editCategoryAndSync(
                1L, "oshirase", "新お知らせ", "new-oshirase", null, null, 9L);

        assertEquals(1, results.size(), "マスター(test)だけ");
        assertFalse(applied().stream().anyMatch(c -> c.path("slug").asText().equals("local-site")));
    }

    // ---- syncAllCategoriesToMaster ----

    @Test
    void syncAllCategoriesToMaster_マスターと異なる項目のみ同期し差分なしはスキップする() {
        bindLocalAndTestManaged();
        world.agentListings
                .categories("test-site", term("お知らせ", "oshirase", null, "説明"), term("イベント", "event", null, null))
                .categories("local-site", term("お知らせ(旧)", "oshirase", null, null),
                        term("イベント", "event", null, null));

        List<BulkOperationLog> results = service.syncAllCategoriesToMaster(1L, 9L);

        assertEquals(1, results.size());
        assertTrue(wasApplied("local-site", "category_edit", "お知らせ", "oshirase", null, "説明", "oshirase"));
        assertEquals(1, applied().size());
    }

    @Test
    void syncAllCategoriesToMaster_差分がなければ空リストを返す() {
        bindLocalAndTestManaged();
        Term same = term("イベント", "event", null, null);
        world.agentListings.categories("test-site", same).categories("local-site", same);

        List<BulkOperationLog> results = service.syncAllCategoriesToMaster(1L, 9L);

        assertTrue(results.isEmpty());
        assertTrue(applied().isEmpty());
    }

    /**
     * issue #1137レビュー対応: マスター環境に項目自体が存在しない(missing()、available=true・slug=null)
     * 行は同期対象から除外される分岐のカバレッジを補う。
     */
    @Test
    void syncAllCategoriesToMaster_マスターに存在しない項目は同期対象外() {
        bindLocalAndTestManaged();
        world.agentListings.categories("local-site", term("ローカル限定", "local-only", null, null));

        List<BulkOperationLog> results = service.syncAllCategoriesToMaster(1L, 9L);

        assertTrue(results.isEmpty());
        assertTrue(applied().isEmpty());
    }

    /**
     * issue #1137レビュー対応: マスター環境自体が未紐付け(unavailable()、available=false)の場合は
     * 全行が同期対象外になる分岐のカバレッジを補う。
     */
    @Test
    void syncAllCategoriesToMaster_マスター環境が未紐付けなら全行が対象外() {
        bind(managed(10L, "local-site"), null, null);
        world.agentListings.categories("local-site", term("ローカル限定", "local-only", null, null));

        List<BulkOperationLog> results = service.syncAllCategoriesToMaster(1L, 9L);

        assertTrue(results.isEmpty());
        assertTrue(applied().isEmpty());
    }

    /** issue #1137レビュー対応: needsSyncのslug差分分岐のカバレッジを補う(大文字小文字違い)。 */
    @Test
    void syncAllCategoriesToMaster_slugの大文字小文字が異なれば同期対象になる() {
        bindLocalAndTestManaged();
        world.agentListings
                .categories("test-site", term("お知らせ", "oshirase", null, null))
                .categories("local-site", term("お知らせ", "Oshirase", null, null));

        List<BulkOperationLog> results = service.syncAllCategoriesToMaster(1L, 9L);

        assertEquals(1, results.size());
        assertTrue(wasApplied("local-site", "category_edit", "お知らせ", "oshirase", null, null, "Oshirase"));
    }

    /** issue #1137レビュー対応: needsSyncのparentSlug差分分岐のカバレッジを補う。 */
    @Test
    void syncAllCategoriesToMaster_parentSlugのみ異なれば同期対象になる() {
        bindLocalAndTestManaged();
        world.agentListings
                .categories("test-site", term("お知らせ", "oshirase", "oya", null))
                .categories("local-site", term("お知らせ", "oshirase", "chigau-oya", null));

        List<BulkOperationLog> results = service.syncAllCategoriesToMaster(1L, 9L);

        assertEquals(1, results.size());
        assertTrue(wasApplied("local-site", "category_edit", "お知らせ", "oshirase", "oya", null, "oshirase"));
    }

    @Test
    void syncAllCategoriesToMaster_説明のみ異なっても同期対象になり_取得できない環境は無視する() {
        bind(managed(10L, "local-site"), managed(20L, "test-site"), managed(30L, "production-site"));
        world.agentListings
                .categories("test-site", term("お知らせ", "oshirase", null, "説明"))
                .categories("local-site", term("お知らせ", "oshirase", null, "別の説明"))
                .fail("production-site", 500, "{}");

        List<BulkOperationLog> results = service.syncAllCategoriesToMaster(1L, 9L);

        assertEquals(1, results.size());
        assertTrue(wasApplied("local-site", "category_edit", "お知らせ", "oshirase", null, "説明", "oshirase"));
    }

    // ---- タグ(カテゴリと同じロジックを共有していることの確認) ----

    @Test
    void syncTag_タグ用のBulkOperationTypeで呼ばれる() {
        bindLocalAndTestManaged();
        world.agentListings.tags("test-site", term("新着", "shinchaku", null, null));

        service.syncTag(1L, "shinchaku", 9L);

        assertTrue(wasApplied("local-site", "tag_create", "新着", "shinchaku", null, null, null));
    }

    @Test
    void syncAllTagsToMaster_タグ用のBulkOperationTypeで呼ばれる() {
        bindLocalAndTestManaged();
        world.agentListings
                .tags("test-site", term("新着", "shinchaku", null, "説明"))
                .tags("local-site", term("新着", "shinchaku", null, null));

        List<BulkOperationLog> results = service.syncAllTagsToMaster(1L, 9L);

        assertEquals(1, results.size());
        assertTrue(wasApplied("local-site", "tag_edit", "新着", "shinchaku", null, "説明", "shinchaku"));
    }

    // ---- 非managedサイト: SSHホスト単位まとめ取得・エラー ----

    private static final String HOST = "203.0.113.5";

    @Test
    void listCategoryComparison_同一ホストのSSH環境は1回のfetchTermsForEnvironmentsにまとめる() {
        bind(null, ssh(20L, "test-site", HOST, "/var/www/html/test"),
                ssh(30L, "production-site", HOST, "/var/www/html/production"));
        world.ssh.site(HOST, "/var/www/html/test").addTerm("category", "1", "News", "news", null, "");
        world.ssh.site(HOST, "/var/www/html/production").addTerm("category", "1", "News", "news", null, "");

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertEquals("news", page.items().get(0).test().slug());
        assertEquals("news", page.items().get(0).production().slug());
        assertEquals(1, world.ssh.batches.size(), "同一ホストなので1回の接続にまとめる");
        assertEquals(2, world.ssh.batches.get(0).size());
    }

    @Test
    void listCategoryComparison_SSH取得失敗時はerror値になり作業ログに記録する() {
        bind(null, ssh(20L, "test-site", HOST, "/var/www/html/test"), null);
        world.ssh.when("term list", FakeWordPress.fail("Connection refused"));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        assertEquals(1, fetchFailureLogs().size());
        assertTrue(fetchFailureLogs().get(0).contains("type=CATEGORY_FETCH"), fetchFailureLogs().get(0));
        assertTrue(fetchFailureLogs().get(0).contains("environment=test"), fetchFailureLogs().get(0));
        assertTrue(fetchFailureLogs().get(0).contains("Connection refused"), fetchFailureLogs().get(0));
    }

    @Test
    void listCategoryComparison_SSH接続自体が失敗した環境はすべてerror値になる() {
        bind(null, ssh(20L, "test-site", HOST, "/var/www/html/test"),
                ssh(30L, "production-site", HOST, "/var/www/html/production"));
        world.ssh.rejectHost(HOST);

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        assertEquals(2, fetchFailureLogs().size());
    }

    /** issue #1137レビュー対応: タグ側のSSH取得("post_tag"分岐/タグ側のログ)のカバレッジを補う。 */
    @Test
    void listTagComparison_SSH取得失敗時はerror値になり作業ログに記録する() {
        bind(null, ssh(20L, "test-site", HOST, "/var/www/html/test"), null);
        world.ssh.when("term list", FakeWordPress.fail("Connection refused"));

        TermComparisonPage page = service.listTagComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        assertTrue(world.ssh.commands.stream().anyMatch(c -> c.contains("term list 'post_tag'")));
        assertEquals(1, fetchFailureLogs().size());
        assertTrue(fetchFailureLogs().get(0).contains("type=TAG_FETCH"), fetchFailureLogs().get(0));
    }

    /** issue #1137レビュー対応: 非managedかつSSHも使えないサイトはunavailableになる分岐のカバレッジを補う。 */
    @Test
    void listCategoryComparison_SSHも使えない非managedサイトはunavailableになる() {
        bind(SiteSpec.noSsh(10L, "local-site"), null, null);

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        assertTrue(world.ssh.commands.isEmpty());
    }

    /** issue #1137レビュー対応: sshPort未設定時は既定の22番ポートでホストキーをまとめる分岐のカバレッジを補う。 */
    @Test
    void listCategoryComparison_sshPort未設定なら既定の22番でホストをまとめる() {
        bind(null, ssh(20L, "test-site", HOST, "/var/www/html/test"),
                ssh(30L, "production-site", HOST, "/var/www/html/production"));
        // testの認証情報からsshPortを外す(未設定=既定の22番)。productionは明示的な22番。
        Map<String, String> withoutPort = new HashMap<>(PublishingWorld.sshCredentialsMap());
        withoutPort.put("sshHost", HOST);
        withoutPort.put("wpPath", "/var/www/html/test");
        withoutPort.remove("sshPort");
        world.stubCredentials("test-site", withoutPort);

        service.listCategoryComparison(1L, 0, 20);

        // 双方とも同一ホスト(203.0.113.5)かつポート未設定=既定22番なので、production側の
        // 明示的な22番指定と同一ホストキーになり1回のfetchにまとまる。
        assertEquals(1, world.ssh.batches.size());
    }

    @Test
    void listCategoryComparison_別ホストのSSH環境はホストごとに別の接続で取得する() {
        bind(null, ssh(20L, "test-site", "203.0.113.5", "/var/www/html/test"),
                ssh(30L, "production-site", "203.0.113.6", "/var/www/html/production"));

        service.listCategoryComparison(1L, 0, 20);

        assertEquals(2, world.ssh.batches.size());
    }

    // ---- issue #1474: agent経路の環境別取得の並列化 ----

    @Test
    void listCategoryComparison_agent経由の3環境の取得は並列に走る() {
        bindThreeManaged();
        // 3環境の取得が同時に走っていなければ、全員が揃う前にlatchの待機がタイムアウトする(直列実行では揃わない)
        CountDownLatch allStarted = new CountDownLatch(3);
        AtomicInteger timedOut = new AtomicInteger();
        world.agent.on("POST", "/categories", request -> {
            allStarted.countDown();
            try {
                if (!allStarted.await(3, TimeUnit.SECONDS)) {
                    timedOut.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Response.ok("{\"categories\":[{\"name\":\"お知らせ\",\"slug\":\"oshirase\"}]}");
        });

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(0, timedOut.get());
        assertEquals(1, page.items().size());
        assertTrue(page.items().get(0).local().available());
        assertTrue(page.items().get(0).test().available());
        assertTrue(page.items().get(0).production().available());
    }

    @Test
    void listCategoryComparison_完了順が逆でも結果は環境ごとの正しい列に入る() {
        bindThreeManaged();
        world.agentListings
                .categories("local-site", term("L", "only-local", null, null))
                .categories("test-site", term("T", "only-test", null, null))
                .categories("production-site", term("P", "only-production", null, null));
        // localが最後に、productionが最初に終わる
        world.agent.on("POST", "/categories", request -> {
            try {
                if (request.bodyText().contains("local-site")) {
                    Thread.sleep(300);
                } else if (request.bodyText().contains("test-site")) {
                    Thread.sleep(150);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null; // 一覧の応答はAgentListingsに任せる
        });

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(3, page.items().size());
        Map<String, TermComparisonRow> bySlug = new HashMap<>();
        page.items().forEach(row -> bySlug.put(row.slug(), row));
        assertTrue(bySlug.get("only-local").local().available());
        assertEquals("only-local", bySlug.get("only-local").local().slug());
        assertEquals("only-test", bySlug.get("only-test").test().slug());
        assertEquals("only-production", bySlug.get("only-production").production().slug());
    }

    @Test
    void listCategoryComparison_agentの取得が想定外の実行時例外なら並列化前と同じ例外がそのまま伝わる() {
        bind(managed(10L, "local-site"), managed(20L, "test-site"), null);
        // managedなのにwpSlugが無いサイト(project-serviceの登録不備)。リクエスト本文を作る段階でNullPointerExceptionになる。
        world.project.json("GET", "/api/internal/project/sites/20",
                "{\"id\":20,\"siteKey\":\"test-site\",\"name\":\"n\",\"baseUrl\":\"https://e.example\","
                        + "\"cmsType\":\"WORDPRESS\",\"managedWordpress\":true,\"wpSlug\":null}");

        assertThrows(NullPointerException.class, () -> service.listCategoryComparison(1L, 0, 20));
    }

    @Test
    void listCategoryComparison_managedとSSHが混在してもSSHは1回にまとめagentは並列に取得する() {
        bind(managed(10L, "local-site"), ssh(20L, "test-site", HOST, "/var/www/html/test"),
                ssh(30L, "production-site", HOST, "/var/www/html/production"));
        world.agentListings.categories("local-site", term("News", "news", null, null));
        world.ssh.when("term list", FakeWordPress.fail("refused"));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(1, world.ssh.batches.size());
        assertEquals("news", page.items().get(0).local().slug());
        assertTrue(page.items().get(0).test().errorMessage().contains("refused"));
    }

    // ---- issue #1732: 並列取得のワーカーも、リクエストの処理IDを引き継ぐ ----

    @Test
    void listCategoryComparison_並列取得のワーカーは投入元の処理IDを引き継ぎ_終了後は持ち越さない() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Logger interceptorLog = (Logger) LoggerFactory.getLogger(
                com.letsblog.common.client.ExternalCallLoggingInterceptor.class);
        ListAppender<ILoggingEvent> calls = new ListAppender<>();
        calls.start();
        interceptorLog.addAppender(calls);
        try {
            TermComparisonService withExecutor = new TermComparisonService(world.bulkManagementClient,
                    world.bulkManagementService, world.siteService, world.projectService, world.sshOperations,
                    executor);
            bindLocalAndTestManaged();
            MDC.put("correlationId", "cid-terms");

            withExecutor.listCategoryComparison(1L, 0, 20);

            List<String> seen = new CopyOnWriteArrayList<>();
            calls.list.stream().filter(e -> e.getFormattedMessage().contains("target=wordpress-bulk"))
                    .forEach(e -> seen.add(e.getMDCPropertyMap().get("correlationId")));
            assertEquals(List.of("cid-terms", "cid-terms"), seen);
            assertNull(executor.submit(() -> MDC.get("correlationId")).get(5, TimeUnit.SECONDS));
        } finally {
            interceptorLog.detachAppender(calls);
            executor.shutdownNow();
        }
    }
}
