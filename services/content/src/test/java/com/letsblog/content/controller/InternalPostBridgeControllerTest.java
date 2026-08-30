package com.letsblog.content.controller;

import com.letsblog.content.domain.Post;
import com.letsblog.content.dto.MarkTrashedRequest;
import com.letsblog.content.dto.PostBridgeResponse;
import com.letsblog.content.dto.PostUpsertRequest;
import com.letsblog.content.repository.PostRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #641: InternalPostBridgeControllerはPostエンティティに対する唯一のCRUD経路
 * (legacy-api側のPostPublishService/PostDeleteService/WordPressSiteProvisioningService向け内部
 * ブリッジ、issue #576)。deleteBySiteのクロスサイト分離は{@link InternalPostBridgeControllerDeleteBySiteIntegrationTest}
 * が実DBで検証するため、本クラスではそれ以外の3エンドポイント(findBySiteAndWpPostId/upsert/
 * markTrashed)の振る舞いをMockitoで検証する。
 */
@ExtendWith(MockitoExtension.class)
class InternalPostBridgeControllerTest {

    @Mock
    private PostRepository postRepository;

    private InternalPostBridgeController controller() {
        return new InternalPostBridgeController(postRepository);
    }

    private Post buildPost(Long siteId, String wpPostId) {
        Post post = new Post();
        post.setSiteId(siteId);
        post.setWpPostId(wpPostId);
        post.setSlug("my-article");
        post.setStatus("publish");
        return post;
    }

    @Test
    void findBySiteAndWpPostId_該当する投稿があれば200でPostBridgeResponseを返す() {
        Post post = buildPost(1L, "42");
        when(postRepository.findBySiteIdAndWpPostId(1L, "42")).thenReturn(Optional.of(post));

        ResponseEntity<PostBridgeResponse> response = controller().findBySiteAndWpPostId(1L, "42");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("42", response.getBody().wpPostId());
        assertEquals("publish", response.getBody().status());
    }

    @Test
    void findBySiteAndWpPostId_該当する投稿が無ければ404を返す() {
        when(postRepository.findBySiteIdAndWpPostId(1L, "unknown")).thenReturn(Optional.empty());

        ResponseEntity<PostBridgeResponse> response = controller().findBySiteAndWpPostId(1L, "unknown");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void upsert_既存投稿が無ければ新規作成する() {
        when(postRepository.findBySiteIdAndWpPostId(1L, "42")).thenReturn(Optional.empty());
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PostUpsertRequest request = new PostUpsertRequest(
                1L, "42", "new-slug", "publish", "{}", "[\"tech\"]", null);

        PostBridgeResponse response = controller().upsert(request);

        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(captor.capture());
        Post saved = captor.getValue();
        assertEquals(1L, saved.getSiteId());
        assertEquals("42", saved.getWpPostId());
        assertEquals("new-slug", saved.getSlug());
        assertEquals("publish", saved.getStatus());
        assertEquals("{}", saved.getUploadedImagesJson());
        assertEquals("[\"tech\"]", saved.getCategories());
        assertEquals("new-slug", response.slug());
    }

    @Test
    void upsert_既存投稿があれば同一行を更新する() {
        Post existing = buildPost(1L, "42");
        existing.setId(99L);
        when(postRepository.findBySiteIdAndWpPostId(1L, "42")).thenReturn(Optional.of(existing));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PostUpsertRequest request = new PostUpsertRequest(
                1L, "42", "updated-slug", "draft", null, null, LocalDateTime.of(2026, 1, 1, 0, 0));

        controller().upsert(request);

        ArgumentCaptor<Post> captor = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(captor.capture());
        Post saved = captor.getValue();
        assertEquals(99L, saved.getId());
        assertEquals("updated-slug", saved.getSlug());
        assertEquals("draft", saved.getStatus());
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), saved.getPublishScheduledAt());
    }

    @Test
    void markTrashed_該当する投稿があればstatusをtrashへ更新して200を返す() {
        Post existing = buildPost(1L, "42");
        existing.setStatus("publish");
        when(postRepository.findBySiteIdAndWpPostId(1L, "42")).thenReturn(Optional.of(existing));
        when(postRepository.save(any(Post.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<PostBridgeResponse> response = controller().markTrashed(new MarkTrashedRequest(1L, "42"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("trash", response.getBody().status());
        verify(postRepository, times(1)).save(existing);
    }

    @Test
    void markTrashed_該当する投稿が無ければ404を返しsaveは呼ばれない() {
        when(postRepository.findBySiteIdAndWpPostId(1L, "unknown")).thenReturn(Optional.empty());

        ResponseEntity<PostBridgeResponse> response = controller().markTrashed(new MarkTrashedRequest(1L, "unknown"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(postRepository, never()).save(any());
    }
}
