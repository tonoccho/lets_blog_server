package com.letsblog.publishing.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationStatus;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.dto.PostComparisonPage;
import com.letsblog.publishing.dto.PostEnvironmentValue;
import com.letsblog.publishing.testsupport.FakeWordPress;
import com.letsblog.publishing.testsupport.PublishingWorld;
import com.letsblog.publishing.testsupport.PublishingWorld.SiteSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostComparisonServiceの回帰テスト。
 *
 * <p>issue #1815で書き直し: 自前のクラス(SiteService・ProjectService・ProjectServiceClient・CmsAdapterFactory・
 * WordPressAdapter・BulkManagementService等)は実物を使い、外部との通信だけを差し替える。HTTP(project-service)は
 * {@code FakeHttpServer}、WordPressへのSSHは{@code FakeWordPress}(環境ごとのホストに別のWordPressを置く)。
 * 削除・ステータス変更は、WordPress(SSH)の状態で確かめる。
 */
class PostComparisonServiceTest {

    private PublishingWorld world;
    private PostComparisonService service;
    private ListAppender<ILoggingEvent> bulkLogs;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        service = new PostComparisonService(world.siteService, world.projectService, world.cmsAdapterFactory,
                world.bulkManagementService);
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

    private static SiteSpec ssh(long id, String key) {
        return SiteSpec.ssh(id, key, "ssh-" + key, "/var/www/" + key);
    }

    private void bind(SiteSpec local, SiteSpec test, SiteSpec production) {
        world.bindEnvironments(1L, "test", local, test, production);
    }

    private FakeWordPress.Site remote(String key) {
        return world.ssh.site("ssh-" + key, "/var/www/" + key);
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
    void listComparison_slugで名寄せして環境ごとの値を返す() {
        bind(ssh(10L, "local-site"), ssh(20L, "test-site"), null);
        remote("local-site").addPost("101", "post", "こんにちは", "hello", "publish");

        PostComparisonPage page = service.listComparison(1L, "post", 0, 20);

        assertEquals(1, page.items().size());
        assertEquals("hello", page.items().get(0).slug());
        PostEnvironmentValue local = page.items().get(0).local();
        assertTrue(local.available());
        assertEquals("101", local.postId());
        assertEquals("publish", local.status());
        PostEnvironmentValue test = page.items().get(0).test();
        assertTrue(test.available());
        assertEquals(null, test.postId());
        PostEnvironmentValue production = page.items().get(0).production();
        assertTrue(!production.available() && !production.error());
    }

    @Test
    void listComparison_固定ページはpostTypeで絞って取得する() {
        bind(ssh(10L, "local-site"), null, null);
        remote("local-site").addPost("101", "post", "投稿", "a-post", "publish");
        remote("local-site").addPost("102", "page", "固定ページ", "a-page", "publish");

        PostComparisonPage page = service.listComparison(1L, "page", 0, 20);

        assertEquals(1, page.items().size());
        assertEquals("a-page", page.items().get(0).slug());
        assertEquals("page", page.postType());
    }

    @Test
    void listComparison_20件単位でページングされる() {
        bind(ssh(10L, "local-site"), null, null);
        for (int i = 0; i < 25; i++) {
            remote("local-site").addPost(String.valueOf(100 + i), "post", "t" + i, String.format("slug-%02d", i),
                    "publish");
        }

        PostComparisonPage first = service.listComparison(1L, "post", 0, 20);
        PostComparisonPage second = service.listComparison(1L, "post", 1, 20);

        assertEquals(20, first.items().size());
        assertEquals(5, second.items().size());
        assertEquals(25, first.totalCount());
    }

    @Test
    void listComparison_取得失敗はエラーとして扱いログに記録する() {
        bind(ssh(10L, "local-site"), null, null);
        world.ssh.rejectHost("ssh-local-site");

        PostComparisonPage page = service.listComparison(1L, "post", 0, 20);

        assertEquals(0, page.items().size());
        assertEquals(1, fetchFailureLogs().size());
        assertTrue(fetchFailureLogs().get(0).contains("type=POST_FETCH"), fetchFailureLogs().get(0));
        assertTrue(fetchFailureLogs().get(0).contains("environment=local"), fetchFailureLogs().get(0));
        assertTrue(fetchFailureLogs().get(0).contains("接続に失敗しました"), fetchFailureLogs().get(0));
    }

    /** issue #1137レビュー対応: toValue()のerror()分岐のカバレッジを補う。 */
    @Test
    void listComparison_一部環境がエラーでも他環境のslugはerror値として表示される() {
        bind(ssh(10L, "local-site"), ssh(20L, "test-site"), null);
        remote("local-site").addPost("101", "post", "こんにちは", "hello", "publish");
        world.ssh.rejectHost("ssh-test-site");

        PostComparisonPage page = service.listComparison(1L, "post", 0, 20);

        assertEquals(1, page.items().size());
        assertTrue(page.items().get(0).local().available());
        PostEnvironmentValue test = page.items().get(0).test();
        assertTrue(test.error());
        assertTrue(test.errorMessage().contains("接続に失敗しました"), test.errorMessage());
    }

    @Test
    void listComparison_SSH接続情報の無いサイトの一覧取得失敗もerror値になる() {
        bind(SiteSpec.noSsh(10L, "local-site"), null, null);

        PostComparisonPage page = service.listComparison(1L, "post", 0, 20);

        assertEquals(0, page.items().size());
        assertEquals(1, fetchFailureLogs().size());
    }

    @Test
    void deleteEverywhere_見つかった環境のみ削除しログを返す() {
        bind(ssh(10L, "local-site"), ssh(20L, "test-site"), null);
        remote("local-site").addPost("101", "post", "こんにちは", "hello", "publish");

        List<BulkOperationLog> results = service.deleteEverywhere(1L, "post", "hello", 9L);

        assertEquals(1, results.size());
        assertEquals("local", results.get(0).getEnvironment());
        assertEquals(BulkOperationStatus.SUCCESS, results.get(0).getStatus());
        assertEquals(BulkOperationType.POST_DELETE, results.get(0).getOperationType());
        assertEquals("trash", remote("local-site").posts.get("101").status);
        assertFalse(world.ssh.commands.stream().anyMatch(c -> c.contains("/var/www/test-site") && c.contains("post delete")),
                "見つからなかった環境(test)では削除しない");
    }

    @Test
    void deleteEverywhere_どの環境にも見つからなければ例外() {
        bind(ssh(10L, "local-site"), null, null);

        assertThrows(IllegalArgumentException.class, () -> service.deleteEverywhere(1L, "post", "missing", 9L));
    }

    @Test
    void updateStatusEverywhere_見つかった環境のみステータス変更する() {
        bind(ssh(10L, "local-site"), null, null);
        remote("local-site").addPost("101", "post", "こんにちは", "hello", "draft");

        List<BulkOperationLog> results = service.updateStatusEverywhere(1L, "post", "hello", "publish", 9L);

        assertEquals(1, results.size());
        assertEquals(BulkOperationType.POST_STATUS_UPDATE, results.get(0).getOperationType());
        assertEquals("publish", remote("local-site").posts.get("101").status);
    }

    /** issue #1137レビュー対応: updateStatusEverywhereでslugが見つからない環境の分岐のカバレッジを補う。 */
    @Test
    void updateStatusEverywhere_一致するslugが無い環境はスキップする() {
        bind(ssh(10L, "local-site"), ssh(20L, "test-site"), null);
        remote("local-site").addPost("101", "post", "こんにちは", "hello", "draft");
        remote("test-site").addPost("202", "post", "別の投稿", "other-slug", "draft");

        List<BulkOperationLog> results = service.updateStatusEverywhere(1L, "post", "hello", "publish", 9L);

        assertEquals(1, results.size());
        assertEquals("draft", remote("test-site").posts.get("202").status);
    }

    /** issue #1137レビュー対応: updateStatusEverywhereで対象が1件も無い場合の分岐のカバレッジを補う。 */
    @Test
    void updateStatusEverywhere_どの環境にも見つからなければ例外() {
        bind(ssh(10L, "local-site"), null, null);

        assertThrows(IllegalArgumentException.class,
                () -> service.updateStatusEverywhere(1L, "post", "missing", "publish", 9L));
    }

    // ---- issue #1687: 環境間の並列実行 ----

    @Test
    void deleteEverywhere_一覧取得も削除も環境間で重なって走り_結果はENVIRONMENT_ORDER順() {
        bind(ssh(10L, "local-site"), ssh(20L, "test-site"), ssh(30L, "production-site"));
        for (String key : List.of("local-site", "test-site", "production-site")) {
            remote(key).addPost("101", "post", "こんにちは", "hello", "publish");
        }
        OverlapProbe fetchProbe = new OverlapProbe(3);
        world.ssh.onCommand("post list", params -> fetchProbe.enter());
        OverlapProbe deleteProbe = new OverlapProbe(3);
        world.ssh.onCommand("--field=post_status", params -> {
            if (params.host().equals("ssh-local-site")) {
                sleep(150);
            }
            deleteProbe.enter();
        });

        List<BulkOperationLog> results = service.deleteEverywhere(1L, "post", "hello", 9L);

        assertTrue(fetchProbe.allOverlapped(), "3環境の一覧取得が重なって呼ばれていない(逐次実行)");
        assertTrue(deleteProbe.allOverlapped(), "3環境の削除が重なって呼ばれていない(逐次実行)");
        assertEquals(List.of("local", "test", "production"),
                results.stream().map(BulkOperationLog::getEnvironment).toList());
    }

    @Test
    void deleteEverywhere_1環境の削除が失敗しても他環境の削除は最後まで実行される() {
        bind(ssh(10L, "local-site"), ssh(20L, "test-site"), null);
        remote("local-site").addPost("101", "post", "こんにちは", "hello", "publish");
        remote("test-site").addPost("201", "post", "こんにちは", "hello", "publish");
        // 一覧取得の後、local側のSSHが落ちて削除が失敗する。
        world.ssh.onCommand("--field=post_status", params -> {
            if (params.host().equals("ssh-local-site")) {
                world.ssh.rejectHost("ssh-local-site");
                throw new com.letsblog.publishing.cms.ssh.SshOperationException("削除中にSSHが切断されました");
            }
            sleep(100);
        });

        List<BulkOperationLog> results = service.deleteEverywhere(1L, "post", "hello", 9L);

        assertEquals(List.of(BulkOperationStatus.FAILED, BulkOperationStatus.SUCCESS),
                results.stream().map(BulkOperationLog::getStatus).toList());
        assertEquals("trash", remote("test-site").posts.get("201").status);
    }

    @Test
    void deleteEverywhere_一覧取得に失敗した環境は他環境の削除を止めずスキップされる() {
        bind(ssh(10L, "local-site"), ssh(20L, "test-site"), null);
        world.ssh.rejectHost("ssh-local-site");
        remote("test-site").addPost("201", "post", "こんにちは", "hello", "publish");

        List<BulkOperationLog> results = service.deleteEverywhere(1L, "post", "hello", 9L);

        assertEquals(1, results.size());
        assertEquals("test", results.get(0).getEnvironment());
        assertEquals(1, fetchFailureLogs().size());
        assertTrue(fetchFailureLogs().get(0).contains("environment=local"), fetchFailureLogs().get(0));
    }

    @Test
    void 一覧取得_後続環境の解決が失敗しても_投入済みの取得を待ち終えてから例外を投げる() {
        bind(ssh(10L, "local-site"), ssh(20L, "test-site"), null);
        // 後続の環境(test)のサイト解決がproject-serviceの障害で失敗する。
        world.project.status("GET", "/api/internal/project/sites/20", 500, "{\"error\":\"down\"}");
        AtomicBoolean fetchFinished = new AtomicBoolean();
        world.ssh.onCommand("post list", params -> {
            sleep(300);
            fetchFinished.set(true);
        });

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.listComparison(1L, "post", 0, 20));

        assertTrue(thrown.getMessage().contains("サイト照会"), thrown.getMessage());
        assertTrue(fetchFinished.get(), "投入済みの取得が終わる前に例外が呼び出し元へ伝わった(ワーカーが孤児になる)");
    }
}
