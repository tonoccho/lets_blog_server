package com.letsblog.publishing.controller;

import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.testsupport.PublishingWorld;
import com.letsblog.publishing.testsupport.PublishingWorld.SiteSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AiExistingTaxonomyBridgeControllerの回帰テスト(issue #574でlegacy-apiの{@code AiBridgeController}
 * に新設、issue #711でpublishing-serviceへ移管、Epic #551 C6-5)。移管元のAiBridgeControllerが持っていた
 * フェイルオープン方針(サイト未紐付け・取得失敗時は空リスト)がそのまま引き継がれていることを検証する。
 *
 * <p>issue #1816で書き直し: ProjectService・SiteService・CmsAdapterFactory・CmsAdapterは実物を使い、
 * 外部との通信(project-serviceへのHTTPは{@code FakeHttpServer}、WordPressへのSSHは{@code FakeWordPress})
 * だけを差し替える。
 */
class AiExistingTaxonomyBridgeControllerTest {

    private static final SiteSpec TEST_SITE = SiteSpec.ssh(10L, "test-site", "ssh.example.com", "/var/www/html");
    private static final SiteSpec PROD_SITE = SiteSpec.ssh(20L, "prod-site", "ssh.example.com", "/var/www/html");

    private PublishingWorld world;
    private AiExistingTaxonomyBridgeController controller;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        controller = new AiExistingTaxonomyBridgeController(world.projectService, world.siteService,
                world.cmsAdapterFactory);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    /** プロジェクト1のマスター環境と、各環境に紐付けるサイトを登録する(null は未紐付け)。 */
    private void project(String masterEnvironment, SiteSpec local, SiteSpec test, SiteSpec production) {
        world.bindEnvironments(1L, masterEnvironment, local, test, production);
        // bindEnvironments は紐付けたサイトが1つも無いとプロジェクトを登録しないので、常に登録する。
        world.bindProject(10L, 1L, local == null ? null : local.id(), test == null ? null : test.id(),
                production == null ? null : production.id(), masterEnvironment);
    }

    @Test
    void existingCategories_マスターサイトのカテゴリ名一覧を返す() {
        project("test", null, TEST_SITE, null);
        world.ssh.site().addTerm("category", "11", "お知らせ", "oshirase", null, null);
        world.ssh.site().addTerm("category", "12", "技術", "tech", null, null);

        List<String> categories = controller.existingCategories(1L);

        assertEquals(List.of("お知らせ", "技術"), categories);
    }

    @Test
    void existingCategories_本番がマスター環境なら本番サイトのカテゴリを返す() {
        project("production", null, TEST_SITE, PROD_SITE);
        world.ssh.site().addTerm("category", "11", "お知らせ", "oshirase", null, null);

        assertEquals(List.of("お知らせ"), controller.existingCategories(1L));
        assertEquals(1, world.project.requests("GET", "/api/internal/project/sites/20").size(),
                "本番サイト(id=20)を引く");
        assertEquals(0, world.project.requests("GET", "/api/internal/project/sites/10").size());
    }

    @Test
    void existingCategories_マスター環境にサイトが未紐付けなら空リスト() {
        project("test", null, null, PROD_SITE);

        assertEquals(List.of(), controller.existingCategories(1L));
    }

    @Test
    void existingCategories_マスター環境がtestでもproductionでもなければ空リスト() {
        project("local", TEST_SITE, TEST_SITE, PROD_SITE);

        assertEquals(List.of(), controller.existingCategories(1L));
    }

    @Test
    void existingCategories_マスター環境のサイトがproject_serviceに無ければ空リスト() {
        project("test", null, TEST_SITE, null);
        world.project.status("GET", "/api/internal/project/sites/10", 404, "{}");

        assertEquals(List.of(), controller.existingCategories(1L));
    }

    @Test
    void existingCategories_プロジェクトが存在しない場合も空リスト() {
        // project-serviceはプロジェクト99を知らない(404)。
        assertEquals(List.of(), controller.existingCategories(99L));
    }

    @Test
    void existingCategoriesWithParents_親カテゴリ名付きで返す() {
        project("production", null, null, PROD_SITE);
        world.ssh.site().addTerm("category", "1", "親カテゴリ", "parent", null, null);
        world.ssh.site().addTerm("category", "2", "子カテゴリ", "child", "1", null);

        List<CmsAdapter.CategoryOption> result = controller.existingCategoriesWithParents(1L);

        assertEquals(List.of(new CmsAdapter.CategoryOption("親カテゴリ", null),
                new CmsAdapter.CategoryOption("子カテゴリ", "親カテゴリ")), result);
    }

    @Test
    void existingCategoriesWithParents_マスター環境にサイトが未紐付けなら空リスト() {
        project("test", null, null, null);

        assertEquals(List.of(), controller.existingCategoriesWithParents(1L));
    }

    @Test
    void existingCategoriesWithParents_取得中に例外が発生した場合も空リスト() {
        project("test", null, TEST_SITE, null);
        world.ssh.whenFail("term list", "boom");

        assertEquals(List.of(), controller.existingCategoriesWithParents(1L));
    }

    @Test
    void existingTags_マスターサイトのタグ名一覧を返す() {
        project("test", null, TEST_SITE, null);
        world.ssh.site().addTerm("post_tag", "21", "Java", "java", null, null);
        world.ssh.site().addTerm("post_tag", "22", "AWS", "aws", null, null);

        List<String> tags = controller.existingTags(1L);

        assertEquals(List.of("Java", "AWS"), tags);
    }

    @Test
    void existingTags_マスター環境にサイトが未紐付けなら空リスト() {
        project("test", null, null, null);

        assertEquals(List.of(), controller.existingTags(1L));
    }

    @Test
    void existingTags_取得中に例外が発生した場合も空リスト() {
        project("test", null, TEST_SITE, null);
        world.ssh.whenFail("term list", "CMSへの接続に失敗しました");

        List<String> tags = controller.existingTags(1L);

        assertEquals(List.of(), tags);
    }

    @Test
    void existingCategories_取得中に例外が発生した場合も空リスト() {
        project("test", null, TEST_SITE, null);
        world.ssh.whenFail("term list", "CMSへの接続に失敗しました");

        assertEquals(List.of(), controller.existingCategories(1L));
    }
}
