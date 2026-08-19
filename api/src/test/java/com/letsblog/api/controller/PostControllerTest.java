package com.letsblog.api.controller;

import com.letsblog.api.domain.Post;
import com.letsblog.api.dto.PostSummaryResponse;
import com.letsblog.api.repository.PostRepository;
import com.letsblog.api.repository.SiteRepository;
import com.letsblog.api.service.PostDeleteService;
import com.letsblog.api.service.PostPublishService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
