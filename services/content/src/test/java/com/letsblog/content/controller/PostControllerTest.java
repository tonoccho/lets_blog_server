package com.letsblog.content.controller;

import com.letsblog.content.domain.Post;
import com.letsblog.content.dto.PostLookupResponse;
import com.letsblog.content.dto.PostSummaryResponse;
import com.letsblog.content.repository.PostRepository;
import com.letsblog.content.service.ForbiddenException;
import com.letsblog.content.service.IdentityServiceUnavailableException;
import com.letsblog.content.service.PostNotFoundException;
import com.letsblog.content.service.SiteNotFoundException;
import com.letsblog.content.testsupport.FakeHttpServer;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * legacy-apiのPostControllerTestのうち、content-serviceへ移設した参照系(list/lookupBySlug)の
 * 振る舞いを引き継いだテスト(issue #576)。
 *
 * <p>{@link PostController}・{@code ProjectBridgeClient}・{@code CurrentActorService}・
 * {@code AdminAuthorizationService}・{@code ObjectMapper}は実物で、差し替えるのは外部との通信だけ:
 * project-service・identity-serviceのHTTP({@link FakeHttpServer})とDB({@link PostRepository})。
 */
class PostControllerTest {

    private ContentWorld world;
    private PostRepository postRepository;
    private PostController controller;

    @BeforeEach
    void setUp() {
        world = new ContentWorld();
        postRepository = mock(PostRepository.class);
        controller = new PostController(postRepository, world.projectBridgeClient, world.currentActorService,
                world.objectMapper, world.adminAuthorizationService);
        world.actingAsAdmin(1L);
        sites("[]");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private void sites(String json) {
        world.http.json("GET", "/api/internal/project/sites", json);
    }

    private Post buildPost(long id, LocalDateTime updatedAt) {
        Post post = new Post();
        post.setId(id);
        post.setSiteId(1L);
        post.setStatus("draft");
        post.setUpdatedAt(updatedAt);
        return post;
    }

    private void posts(Post... posts) {
        when(postRepository.findAll()).thenReturn(new ArrayList<>(List.of(posts)));
    }

    private static List<Long> ids(List<PostSummaryResponse> result) {
        return result.stream().map(PostSummaryResponse::id).toList();
    }

    @Test
    void list_sortByパラメータ省略時もNullPointerExceptionを投げず既定順のupdatedAtで返す() {
        LocalDateTime older = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime newer = LocalDateTime.of(2026, 2, 1, 0, 0);
        posts(buildPost(1L, older), buildPost(2L, newer));

        List<PostSummaryResponse> result = assertDoesNotThrow(() -> controller.list(null, null));

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).id());
        assertEquals(2L, result.get(1).id());
    }

    @Test
    void list_sortOrderがdescなら逆順で返す() {
        posts(buildPost(1L, LocalDateTime.of(2026, 1, 1, 0, 0)), buildPost(2L, LocalDateTime.of(2026, 2, 1, 0, 0)));

        assertEquals(List.of(2L, 1L), ids(controller.list(null, "desc")));
        assertEquals(List.of(1L, 2L), ids(controller.list(null, "asc")));
    }

    @Test
    void list_siteNameで並べ_サイト名はproject_serviceが返した名前を使う() {
        sites("[{\"id\":1,\"siteKey\":\"a\",\"name\":\"B社\"},{\"id\":2,\"siteKey\":\"b\",\"name\":\"A社\"}]");
        Post first = buildPost(1L, LocalDateTime.now());
        Post second = buildPost(2L, LocalDateTime.now());
        second.setSiteId(2L);
        posts(first, second);

        List<PostSummaryResponse> result = controller.list("siteName", null);

        assertEquals(List.of(2L, 1L), ids(result));
        assertEquals("A社", result.get(0).siteName());
        assertEquals("B社", result.get(1).siteName());
    }

    @Test
    void list_project_serviceが知らないサイトの投稿は不明なサイトとして返す() {
        posts(buildPost(1L, LocalDateTime.now()));

        assertEquals("(不明なサイト)", controller.list(null, null).get(0).siteName());
    }

    @Test
    void list_statusで並べる() {
        Post published = buildPost(1L, LocalDateTime.now());
        published.setStatus("publish");
        Post draft = buildPost(2L, LocalDateTime.now());
        posts(published, draft);

        assertEquals(List.of(2L, 1L), ids(controller.list("status", "asc")));
    }

    @Test
    void list_lastPublishedAtで並べる() {
        Post early = buildPost(1L, LocalDateTime.now());
        early.setLastPublishedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        Post late = buildPost(2L, LocalDateTime.now());
        late.setLastPublishedAt(LocalDateTime.of(2026, 3, 1, 0, 0));
        posts(late, early);

        assertEquals(List.of(1L, 2L), ids(controller.list("lastPublishedAt", "asc")));
        assertEquals(List.of(2L, 1L), ids(controller.list("lastPublishedAt", "desc")));
    }

    @Test
    void list_カテゴリのJSONは配列にし_空やnullは空配列_壊れていても空配列で返す() {
        Post withCategories = buildPost(1L, LocalDateTime.of(2026, 1, 1, 0, 0));
        withCategories.setCategories("[\"技術\",\"日記\"]");
        Post blank = buildPost(2L, LocalDateTime.of(2026, 1, 2, 0, 0));
        blank.setCategories("  ");
        Post missing = buildPost(3L, LocalDateTime.of(2026, 1, 3, 0, 0));
        Post broken = buildPost(4L, LocalDateTime.of(2026, 1, 4, 0, 0));
        broken.setCategories("{not json");
        posts(withCategories, blank, missing, broken);

        List<PostSummaryResponse> result = controller.list(null, null);

        assertEquals(List.of("技術", "日記"), result.get(0).categories());
        assertEquals(List.of(), result.get(1).categories());
        assertEquals(List.of(), result.get(2).categories());
        assertEquals(List.of(), result.get(3).categories());
    }

    @Test
    void list_公開日時と公開予定日時をUTCの時刻へ変換して返す() {
        Post post = buildPost(1L, LocalDateTime.now());
        post.setLastPublishedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        post.setPublishScheduledAt(LocalDateTime.of(2026, 1, 2, 9, 0));
        posts(post);

        PostSummaryResponse result = controller.list(null, null).get(0);

        assertEquals(java.time.Instant.parse("2026-01-01T09:00:00Z"), result.lastPublishedAt());
        assertEquals(java.time.Instant.parse("2026-01-02T09:00:00Z"), result.publishScheduledAt());
    }

    @Test
    void lookupBySlug_該当する投稿があればwpPostIdとstatusを返す() {
        world.http.json("GET", "/api/internal/project/sites/by-key/main", "{\"id\":1,\"siteKey\":\"main\",\"name\":\"n\"}");
        Post post = buildPost(1L, LocalDateTime.now());
        post.setWpPostId("42");
        post.setSlug("my-article");
        post.setStatus("publish");
        when(postRepository.findFirstBySiteIdAndSlugOrderByUpdatedAtDesc(1L, "my-article"))
                .thenReturn(Optional.of(post));

        PostLookupResponse result = controller.lookupBySlug("main", "my-article");

        assertEquals("42", result.wpPostId());
        assertEquals("publish", result.status());
    }

    @Test
    void lookupBySlug_該当する投稿が無ければPostNotFoundExceptionを投げる() {
        world.http.json("GET", "/api/internal/project/sites/by-key/main", "{\"id\":1,\"siteKey\":\"main\",\"name\":\"n\"}");
        when(postRepository.findFirstBySiteIdAndSlugOrderByUpdatedAtDesc(1L, "unknown-slug"))
                .thenReturn(Optional.empty());

        assertThrows(PostNotFoundException.class, () -> controller.lookupBySlug("main", "unknown-slug"));
    }

    @Test
    void lookupBySlug_サイトが存在しなければSiteNotFoundExceptionを投げる() {
        // project-serviceが空の本文(null)を返す = 未登録のsiteKey。
        world.http.on("GET", "/api/internal/project/sites/by-key/unknown-site", FakeHttpServer.Response.empty(200));

        assertThrows(SiteNotFoundException.class, () -> controller.lookupBySlug("unknown-site", "my-article"));
    }

    @Test
    void lookupBySlug_project_serviceが障害ならIdentityServiceUnavailableExceptionを投げる() {
        world.http.status("GET", "/api/internal/project/sites/by-key/main", 503, "{\"error\":\"down\"}");

        assertThrows(IdentityServiceUnavailableException.class, () -> controller.lookupBySlug("main", "slug"));
    }

    // ---- issue #830: 自分が所属するプロジェクトのサイトの投稿だけ ----

    @Test
    void list_非adminはアクセスできないサイトの投稿を返さない() {
        // buildPost の siteId は 1。アクセス可能が {9} なら落ちる。
        world.actingAsUser(2L).accessibleSites(2L, 9L);
        posts(buildPost(1L, LocalDateTime.now()));

        assertEquals(List.of(), controller.list(null, null));
    }

    @Test
    void list_非adminはアクセスできるサイトの投稿を返す() {
        world.actingAsUser(2L).accessibleSites(2L, 1L);
        Post other = buildPost(3L, LocalDateTime.now());
        other.setSiteId(9L);
        posts(buildPost(1L, LocalDateTime.now()), other);

        assertEquals(List.of(1L), ids(controller.list(null, null)));
    }

    @Test
    void list_adminは全件を返す() {
        posts(buildPost(1L, LocalDateTime.now()));

        assertEquals(1, controller.list(null, null).size());
    }

    @Test
    void lookupBySlug_アクセスできないサイトは投稿の有無すら返さない() {
        world.actingAsUser(2L).accessibleSites(2L, 9L);
        world.http.json("GET", "/api/internal/project/sites/by-key/main", "{\"id\":1,\"siteKey\":\"main\",\"name\":\"n\"}");

        assertThrows(ForbiddenException.class, () -> controller.lookupBySlug("main", "slug"));
    }

    @Test
    void lookupBySlug_非adminでもアクセスできるサイトなら投稿を返す() {
        world.actingAsUser(2L).accessibleSites(2L, 1L);
        world.http.json("GET", "/api/internal/project/sites/by-key/main", "{\"id\":1,\"siteKey\":\"main\",\"name\":\"n\"}");
        Post post = buildPost(1L, LocalDateTime.now());
        post.setWpPostId("7");
        when(postRepository.findFirstBySiteIdAndSlugOrderByUpdatedAtDesc(1L, "s")).thenReturn(Optional.of(post));

        assertEquals("7", controller.lookupBySlug("main", "s").wpPostId());
    }
}
