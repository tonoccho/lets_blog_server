package com.letsblog.content.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.content.client.LegacyApiBridgeClient;
import com.letsblog.content.domain.Post;
import com.letsblog.content.dto.PostLookupResponse;
import com.letsblog.content.dto.PostSummaryResponse;
import com.letsblog.content.repository.PostRepository;
import com.letsblog.content.service.CurrentActorService;
import com.letsblog.content.service.PostNotFoundException;
import com.letsblog.content.service.SiteNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * legacy-apiのPostControllerTestのうち、content-serviceへ移設した参照系(list/lookupBySlug)の
 * 振る舞いを引き継いだテスト(issue #576)。SiteRepositoryの代わりにLegacyApiBridgeClient経由の
 * サイト解決をモックする。
 */
@ExtendWith(MockitoExtension.class)
class PostControllerTest {

    @Mock
    private PostRepository postRepository;

    @Mock
    private LegacyApiBridgeClient legacyApiBridgeClient;

    @Mock
    private CurrentActorService currentActorService;

    private PostController controller() {
        return new PostController(postRepository, legacyApiBridgeClient, currentActorService, new ObjectMapper());
    }

    private Post buildPost(long id, LocalDateTime updatedAt) {
        Post post = new Post();
        post.setId(id);
        post.setSiteId(1L);
        post.setStatus("draft");
        post.setUpdatedAt(updatedAt);
        return post;
    }

    @Test
    void list_sortByパラメータ省略時もNullPointerExceptionを投げず既定順のupdatedAtで返す() {
        PostController controller = controller();
        LocalDateTime older = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime newer = LocalDateTime.of(2026, 2, 1, 0, 0);
        when(postRepository.findAll()).thenReturn(new ArrayList<>(List.of(buildPost(1L, older), buildPost(2L, newer))));
        when(legacyApiBridgeClient.listSites(null)).thenReturn(List.of());

        List<PostSummaryResponse> result = assertDoesNotThrow(() -> controller.list(null, null));

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).id());
        assertEquals(2L, result.get(1).id());
    }

    @Test
    void lookupBySlug_該当する投稿があればwpPostIdとstatusを返す() {
        PostController controller = controller();
        when(legacyApiBridgeClient.resolveSiteIdByKey("main", null)).thenReturn(1L);
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
        PostController controller = controller();
        when(legacyApiBridgeClient.resolveSiteIdByKey("main", null)).thenReturn(1L);
        when(postRepository.findFirstBySiteIdAndSlugOrderByUpdatedAtDesc(1L, "unknown-slug"))
                .thenReturn(Optional.empty());

        assertThrows(PostNotFoundException.class, () -> controller.lookupBySlug("main", "unknown-slug"));
    }

    @Test
    void lookupBySlug_サイトが存在しなければSiteNotFoundExceptionを投げる() {
        PostController controller = controller();
        when(legacyApiBridgeClient.resolveSiteIdByKey("unknown-site", null)).thenReturn(null);

        assertThrows(SiteNotFoundException.class, () -> controller.lookupBySlug("unknown-site", "my-article"));
    }
}
