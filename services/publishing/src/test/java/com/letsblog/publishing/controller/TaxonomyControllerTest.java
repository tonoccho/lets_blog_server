package com.letsblog.publishing.controller;

import com.letsblog.publishing.dto.TaxonomyResolveRequest;
import com.letsblog.publishing.dto.TaxonomyResolveResponse;
import com.letsblog.publishing.service.ForbiddenException;
import com.letsblog.publishing.testsupport.PublishingWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * issue #708(legacy-apiのTaxonomyControllerをpublishing-serviceへ移設)。
 *
 * <p>issue #1816で書き直し: SiteService・CmsAdapterFactory・CmsAdapter・AdminAuthorizationServiceは実物を使い、
 * 外部との通信(project-service・identity-serviceへのHTTPは{@code FakeHttpServer}、WordPressへのSSHは
 * {@code FakeWordPress})だけを差し替える。
 */
class TaxonomyControllerTest {

    private PublishingWorld world;
    private TaxonomyController controller;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld().withMainSite();
        controller = new TaxonomyController(world.siteService, world.cmsAdapterFactory,
                world.adminAuthorizationService);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void resolve_サイトのCMSアダプタ経由でカテゴリ_タグを解決する() {
        world.stubActor("Bearer admin-token", 1L, "admin", "admin@example.com").asUser("Bearer admin-token");
        world.ssh.site().addTerm("category", "11", "お知らせ", "oshirase", null, null);
        world.ssh.site().addTerm("post_tag", "22", "新着", "shinchaku", null, null);

        TaxonomyResolveResponse response =
                controller.resolve(new TaxonomyResolveRequest("main", List.of("お知らせ"), List.of("新着")));

        assertEquals(List.of("11"), response.categoryIds());
        assertEquals(List.of("22"), response.tagIds());
    }

    @Test
    void resolve_サイトのプロジェクトメンバーでなければCMSへ問い合わせずに拒否する() {
        // issue #830: 対象サイトのWordPress上のカテゴリ/タグ一覧が「認証済みなら誰でも」見えていた。
        world.bindProject(1L, 7L, null, null, 1L);
        world.stubActor("Bearer member-token", 10L, "user", "m@example.com").stubProjectMember(7L, 10L, false);
        world.asUser("Bearer member-token");

        assertThrows(ForbiddenException.class,
                () -> controller.resolve(new TaxonomyResolveRequest("main", List.of("お知らせ"), List.of("新着"))));

        assertFalse(world.ssh.ran("term"), "WordPressへは問い合わせない: " + world.ssh.commands);
        assertEquals(0, world.project.requests("GET", "/api/internal/project/sites/main/credentials").size(),
                "認証情報の取得より前に拒否される");
    }
}
