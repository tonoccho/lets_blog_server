package com.letsblog.api.controller;

import com.letsblog.api.domain.Post;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.PostPublishCommand;
import com.letsblog.api.dto.PostPublishResponse;
import com.letsblog.api.dto.PostSummaryResponse;
import com.letsblog.api.repository.PostRepository;
import com.letsblog.api.repository.SiteRepository;
import com.letsblog.api.service.PostPublishService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostPublishService postPublishService;
    private final PostRepository postRepository;
    private final SiteRepository siteRepository;

    public PostController(PostPublishService postPublishService, PostRepository postRepository,
                           SiteRepository siteRepository) {
        this.postPublishService = postPublishService;
        this.postRepository = postRepository;
        this.siteRepository = siteRepository;
    }

    /**
     * 投稿履歴一覧(Web管理フロントエンドの表示用)。
     */
    @GetMapping
    public List<PostSummaryResponse> list() {
        Map<Long, String> siteNamesById = siteRepository.findAll().stream()
                .collect(Collectors.toMap(Site::getId, Site::getName));

        return postRepository.findAll().stream()
                .sorted(Comparator.comparing(Post::getUpdatedAt).reversed())
                .map((Post post) -> new PostSummaryResponse(
                        post.getId(),
                        post.getSiteId(),
                        siteNamesById.getOrDefault(post.getSiteId(), "(不明なサイト)"),
                        post.getWpPostId(),
                        post.getSlug(),
                        post.getStatus(),
                        post.getLastPublishedAt()
                ))
                .toList();
    }

    /**
     * Markdown記事をWordPressへ新規投稿、または wpPostId 指定時は既存投稿を更新する。
     * VSCode拡張は本文中のローカル画像を images パートとして同梱し、
     * Markdown本文中ではそのファイル名(例: eyecatch.png)をそのまま画像参照として書く想定。
     */
    @PostMapping(value = "/publish", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PostPublishResponse publish(
            @RequestParam("site") String site,
            @RequestParam("title") String title,
            @RequestParam(value = "slug", required = false) String slug,
            @RequestParam(value = "status", defaultValue = "draft") String status,
            @RequestParam(value = "categories", required = false) List<String> categories,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "wpPostId", required = false) String wpPostId,
            @RequestParam("markdown") String markdown,
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "featuredImageFilename", required = false) String featuredImageFilename
    ) {
        PostPublishCommand command = new PostPublishCommand(
                site, title, slug, status, categories, tags, wpPostId, markdown, images, featuredImageFilename);
        return postPublishService.publish(command);
    }
}
