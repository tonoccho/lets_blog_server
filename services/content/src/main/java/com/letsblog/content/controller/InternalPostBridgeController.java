package com.letsblog.content.controller;

import com.letsblog.content.domain.Post;
import com.letsblog.content.dto.MarkTrashedRequest;
import com.letsblog.content.dto.PostBridgeResponse;
import com.letsblog.content.dto.PostUpsertRequest;
import com.letsblog.content.repository.PostRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * legacy-api側のPostPublishService/PostDeleteService/WordPressSiteProvisioningService(いずれも
 * issue #575まで引き続きlegacy-apiに残る)向けの内部ブリッジ(issue #576)。postsテーブルの所有権が
 * content-serviceへ移った(ADR-0004、lbs_contentスキーマ)ため、legacy-api側はJPAでの直接アクセスが
 * できなくなり、この内部ブリッジ経由で読み書きする。
 */
@RestController
public class InternalPostBridgeController {

    private final PostRepository postRepository;

    public InternalPostBridgeController(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    /**
     * PostPublishService#loadPriorUploadedImages/PostDeleteService#deleteが使う、サイト+wpPostIdに
     * 紐づく既存投稿の照会。該当が無ければ404。
     */
    @GetMapping("/api/internal/content/posts")
    public ResponseEntity<PostBridgeResponse> findBySiteAndWpPostId(
            @RequestParam Long siteId, @RequestParam String wpPostId) {
        return postRepository.findBySiteIdAndWpPostId(siteId, wpPostId)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** PostPublishService#upsertPostRecordが使う、公開結果のposts行への反映(新規作成/更新)。 */
    @PutMapping("/api/internal/content/posts")
    @Transactional
    public PostBridgeResponse upsert(@RequestBody PostUpsertRequest request) {
        Post post = postRepository.findBySiteIdAndWpPostId(request.siteId(), request.wpPostId())
                .orElseGet(Post::new);

        post.setSiteId(request.siteId());
        post.setWpPostId(request.wpPostId());
        post.setSlug(request.slug());
        post.setStatus(request.status());
        post.setLastPublishedAt(java.time.LocalDateTime.now());
        post.setUploadedImagesJson(request.uploadedImagesJson());
        post.setCategories(request.categories());
        post.setPublishScheduledAt(request.publishScheduledAt());

        return toResponse(postRepository.save(post));
    }

    /** PostDeleteService#deleteが使う、投稿の削除(ゴミ箱移動、statusを"trash"に更新)。該当が無ければ404。 */
    @PostMapping("/api/internal/content/posts/mark-trashed")
    @Transactional
    public ResponseEntity<PostBridgeResponse> markTrashed(@RequestBody MarkTrashedRequest request) {
        return postRepository.findBySiteIdAndWpPostId(request.siteId(), request.wpPostId())
                .map(post -> {
                    post.setStatus("trash");
                    return ResponseEntity.ok(toResponse(postRepository.save(post)));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** WordPressSiteProvisioningService#deleteSiteが使う、サイト削除時のposts一括削除。 */
    @DeleteMapping("/api/internal/content/posts/by-site/{siteId}")
    @Transactional
    public ResponseEntity<Void> deleteBySite(@PathVariable Long siteId) {
        postRepository.deleteBySiteId(siteId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    private PostBridgeResponse toResponse(Post post) {
        return new PostBridgeResponse(
                post.getSiteId(), post.getWpPostId(), post.getSlug(), post.getStatus(),
                post.getUploadedImagesJson(), post.getCategories(), post.getPublishScheduledAt(),
                post.getLastPublishedAt());
    }
}
