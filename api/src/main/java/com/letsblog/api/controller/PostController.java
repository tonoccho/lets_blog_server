package com.letsblog.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.domain.Post;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.PostLookupResponse;
import com.letsblog.api.dto.PostPublishCommand;
import com.letsblog.api.dto.PostPublishResponse;
import com.letsblog.api.dto.PostSummaryResponse;
import com.letsblog.api.repository.PostRepository;
import com.letsblog.api.repository.SiteRepository;
import com.letsblog.api.service.PostDeleteService;
import com.letsblog.api.service.PostNotFoundException;
import com.letsblog.api.service.PostPublishService;
import com.letsblog.api.service.SiteNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostPublishService postPublishService;
    private final PostDeleteService postDeleteService;
    private final PostRepository postRepository;
    private final SiteRepository siteRepository;
    private final ObjectMapper objectMapper;

    public PostController(PostPublishService postPublishService, PostDeleteService postDeleteService,
                           PostRepository postRepository, SiteRepository siteRepository, ObjectMapper objectMapper) {
        this.postPublishService = postPublishService;
        this.postDeleteService = postDeleteService;
        this.postRepository = postRepository;
        this.siteRepository = siteRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 投稿履歴一覧(Web管理フロントエンドの表示用)。
     */
    @GetMapping
    public List<PostSummaryResponse> list(
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {
        Map<Long, String> siteNamesById = siteRepository.findAll().stream()
                .collect(Collectors.toMap(Site::getId, Site::getName));

        List<Post> posts = postRepository.findAll();
        posts = sortPosts(posts, sortBy, sortOrder);

        return posts.stream()
                .map((Post post) -> new PostSummaryResponse(
                        post.getId(),
                        post.getSiteId(),
                        siteNamesById.getOrDefault(post.getSiteId(), "(不明なサイト)"),
                        post.getWpPostId(),
                        post.getSlug(),
                        post.getStatus(),
                        post.getLastPublishedAt(),
                        deserializeCategories(post.getCategories()),
                        post.getPublishScheduledAt()
                ))
                .toList();
    }

    private List<String> deserializeCategories(String categoriesJson) {
        if (categoriesJson == null || categoriesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(categoriesJson, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("カテゴリ情報のパースに失敗しました: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Post> sortPosts(List<Post> posts, String sortBy, String sortOrder) {
        boolean ascending = !"desc".equalsIgnoreCase(sortOrder);

        Comparator<Post> comparator = switch (sortBy == null ? "" : sortBy) {
            case "siteName" -> Comparator.comparing(post -> {
                Site site = siteRepository.findById(post.getSiteId()).orElse(null);
                return site != null ? site.getName() : "";
            });
            case "status" -> Comparator.comparing(Post::getStatus);
            case "lastPublishedAt" -> Comparator.nullsFirst(Comparator.comparing(Post::getLastPublishedAt));
            default -> Comparator.comparing(Post::getUpdatedAt);
        };

        if (!ascending) {
            comparator = comparator.reversed();
        }

        posts.sort(comparator);
        return posts;
    }

    /**
     * Markdown記事をWordPressへ新規投稿、または wpPostId 指定時は既存投稿を更新する。
     * VSCode拡張は本文中のローカル画像を images パートとして同梱する。Markdown本文中の画像参照
     * (例: "assets/eyecatch.png")はマルチパートのfilenameヘッダ経由では正しく往復しない
     * (サーバー/コンテナ側でパス区切りを含むfilenameがベース名のみに変換されてしまう場合があるため)、
     * imageReferences に images と同じ順序でMarkdown中の元の参照文字列を明示的に渡す。
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
            @RequestParam(value = "featuredImageFilename", required = false) String featuredImageFilename,
            @RequestParam(value = "imageReferences", required = false) List<String> imageReferences,
            @RequestParam(value = "publishScheduledAt", required = false) String publishScheduledAt,
            @RequestParam(value = "notifySns", required = false) Boolean notifySns
    ) {
        PostPublishCommand command = new PostPublishCommand(
                site, title, slug, status, categories, tags, wpPostId, markdown, images, featuredImageFilename,
                imageReferences, publishScheduledAt, notifySns);
        return postPublishService.publish(command);
    }

    /**
     * サイト+スラッグに対応する既存投稿を照会する(issue #505)。
     * VSCode拡張がfront matterのwp_post_ids(廃止)に頼らず、DB側の情報から既存投稿の
     * WordPress投稿IDを取得し、投稿の新規作成/更新を判断するために使う。該当が無ければ404。
     */
    @GetMapping("/{site}/by-slug/{slug}")
    public PostLookupResponse lookupBySlug(@PathVariable String site, @PathVariable String slug) {
        Site siteEntity = siteRepository.findBySiteKey(site)
                .orElseThrow(() -> new SiteNotFoundException("siteKey '" + site + "' は登録されていません"));
        Post post = postRepository.findFirstBySiteIdAndSlugOrderByUpdatedAtDesc(siteEntity.getId(), slug)
                .orElseThrow(() -> new PostNotFoundException(
                        "site '" + site + "', slug '" + slug + "' に対応する投稿は見つかりません"));
        return new PostLookupResponse(post.getWpPostId(), post.getStatus());
    }

    /**
     * 投稿を削除する(WordPressの場合、既定でゴミ箱へ移動する。完全削除は行わない)。
     */
    @DeleteMapping("/{site}/{wpPostId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String site, @PathVariable String wpPostId) {
        postDeleteService.delete(site, wpPostId);
    }
}
