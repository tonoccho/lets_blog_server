package com.letsblog.api.controller;

import com.letsblog.api.domain.Post;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.PostLookupResponse;
import com.letsblog.api.dto.PostSummaryResponse;
import com.letsblog.api.repository.PostRepository;
import com.letsblog.api.repository.SiteRepository;
import com.letsblog.api.service.PostDeleteService;
import com.letsblog.api.service.PostNotFoundException;
import com.letsblog.api.service.PostPublishService;
import com.letsblog.api.service.SiteNotFoundException;
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

@ExtendWith(MockitoExtension.class)
class PostControllerTest {

    @Mock
    private PostPublishService postPublishService;

    @Mock
    private PostDeleteService postDeleteService;

    @Mock
    private PostRepository postRepository;

    @Mock
    private SiteRepository siteRepository;

    private PostController controller() {
        return new PostController(postPublishService, postDeleteService, postRepository, siteRepository,
                new com.fasterxml.jackson.databind.ObjectMapper());
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
        when(siteRepository.findAll()).thenReturn(List.of());

        List<PostSummaryResponse> result = assertDoesNotThrow(() -> controller.list(null, null));

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).id());
        assertEquals(2L, result.get(1).id());
    }

    private Site buildSite(long id, String siteKey) {
        Site site = new Site();
        site.setId(id);
        site.setSiteKey(siteKey);
        return site;
    }

    @Test
    void lookupBySlug_該当する投稿があればwpPostIdとstatusを返す() {
        PostController controller = controller();
        when(siteRepository.findBySiteKey("main")).thenReturn(Optional.of(buildSite(1L, "main")));
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
        when(siteRepository.findBySiteKey("main")).thenReturn(Optional.of(buildSite(1L, "main")));
        when(postRepository.findFirstBySiteIdAndSlugOrderByUpdatedAtDesc(1L, "unknown-slug"))
                .thenReturn(Optional.empty());

        assertThrows(PostNotFoundException.class, () -> controller.lookupBySlug("main", "unknown-slug"));
    }

    @Test
    void lookupBySlug_サイトが存在しなければSiteNotFoundExceptionを投げる() {
        PostController controller = controller();
        when(siteRepository.findBySiteKey("unknown-site")).thenReturn(Optional.empty());

        assertThrows(SiteNotFoundException.class, () -> controller.lookupBySlug("unknown-site", "my-article"));
    }
}
