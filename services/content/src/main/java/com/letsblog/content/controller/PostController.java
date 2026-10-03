package com.letsblog.content.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.content.client.ProjectBridgeClient;
import com.letsblog.content.domain.Post;
import com.letsblog.content.dto.PostLookupResponse;
import com.letsblog.content.dto.PostSummaryResponse;
import com.letsblog.content.dto.UtcDateTimes;
import com.letsblog.content.repository.PostRepository;
import com.letsblog.content.service.CurrentActorService;
import com.letsblog.content.service.ForbiddenException;
import com.letsblog.content.service.AdminAuthorizationService;
import com.letsblog.content.service.PostNotFoundException;
import com.letsblog.content.service.SiteNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Set;
import java.util.Optional;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * legacy-apiのPostControllerのうち参照系(list/lookupBySlug)のみを移設する(issue #576)。
 * 公開(publish)・削除(delete)は、Site/CMSアダプタ(project-service/publishing-serviceがまだ
 * 抽出されていないドメイン)への深い依存があり、issue #575(publishing-service)の対象のため
 * legacy-apiに残した(PostPublishService/PostDeleteServiceは、postsテーブルの読み書きを
 * legacy-api側の新しい内部ブリッジ(ContentBridgeClient)経由で行うよう書き換えている)。
 *
 * <p>サイトの名前/キーはSite domain(project-service未抽出、legacy-apiに残る)にあるため、
 * {@link ProjectBridgeClient}経由の内部ブリッジで解決する。
 */
@Slf4j
@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostRepository postRepository;
    private final ProjectBridgeClient projectBridgeClient;
    private final CurrentActorService currentActorService;
    private final ObjectMapper objectMapper;
    private final AdminAuthorizationService adminAuthorizationService;

    public PostController(
            PostRepository postRepository, ProjectBridgeClient projectBridgeClient,
            CurrentActorService currentActorService, ObjectMapper objectMapper,
            AdminAuthorizationService adminAuthorizationService) {
        this.postRepository = postRepository;
        this.projectBridgeClient = projectBridgeClient;
        this.currentActorService = currentActorService;
        this.objectMapper = objectMapper;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /**
     * 投稿履歴一覧(Web管理フロントエンドの表示用)。
     *
     * <p><b>操作者が所属するプロジェクトのサイトの投稿だけ</b>を返す(issue #830)。admin は全件。
     * 以前は認可チェックが無く、認証済みなら誰でも全サイトの投稿履歴を列挙できた。
     */
    @GetMapping
    public List<PostSummaryResponse> list(
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortOrder) {
        String bearerToken = currentActorService.getAuthorizationHeader();
        Map<Long, String> siteNamesById = projectBridgeClient.listSites(bearerToken).stream()
                .collect(Collectors.toMap(
                        ProjectBridgeClient.SiteSummary::id, ProjectBridgeClient.SiteSummary::name));

        List<Post> posts = postRepository.findAll();
        Optional<Set<Long>> accessible = adminAuthorizationService.accessibleSiteIds();
        if (accessible.isPresent()) {
            Set<Long> siteIds = accessible.get();
            // sortPosts が list.sort() で並べ替えるため、可変リストを渡す必要がある。
            posts = new ArrayList<>(posts.stream().filter(post -> siteIds.contains(post.getSiteId())).toList());
        }
        posts = sortPosts(posts, sortBy, sortOrder, siteNamesById);

        return posts.stream()
                .map((Post post) -> new PostSummaryResponse(
                        post.getId(),
                        post.getSiteId(),
                        siteNamesById.getOrDefault(post.getSiteId(), "(不明なサイト)"),
                        post.getWpPostId(),
                        post.getSlug(),
                        post.getStatus(),
                        UtcDateTimes.toInstant(post.getLastPublishedAt()),
                        deserializeCategories(post.getCategories()),
                        UtcDateTimes.toInstant(post.getPublishScheduledAt())
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

    private List<Post> sortPosts(
            List<Post> posts, String sortBy, String sortOrder, Map<Long, String> siteNamesById) {
        boolean ascending = !"desc".equalsIgnoreCase(sortOrder);

        Comparator<Post> comparator = switch (sortBy == null ? "" : sortBy) {
            case "siteName" -> Comparator.comparing(
                    post -> siteNamesById.getOrDefault(post.getSiteId(), ""));
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
     * サイト+スラッグに対応する既存投稿を照会する(issue #505)。
     * VSCode拡張がfront matterのwp_post_ids(廃止)に頼らず、DB側の情報から既存投稿の
     * WordPress投稿IDを取得し、投稿の新規作成/更新を判断するために使う。該当が無ければ404。
     */
    @GetMapping("/{site}/by-slug/{slug}")
    public PostLookupResponse lookupBySlug(@PathVariable String site, @PathVariable String slug) {
        Long siteId = projectBridgeClient.resolveSiteIdByKey(site, currentActorService.getAuthorizationHeader());
        if (siteId == null) {
            throw new SiteNotFoundException("siteKey '" + site + "' は登録されていません");
        }
        // 自分が所属するプロジェクトのサイトでなければ、投稿の有無すら返さない(issue #830)。
        Optional<Set<Long>> accessible = adminAuthorizationService.accessibleSiteIds();
        if (accessible.isPresent() && !accessible.get().contains(siteId)) {
            throw new ForbiddenException("この操作にはプロジェクトメンバーまたはadmin権限が必要です");
        }
        Post post = postRepository.findFirstBySiteIdAndSlugOrderByUpdatedAtDesc(siteId, slug)
                .orElseThrow(() -> new PostNotFoundException(
                        "site '" + site + "', slug '" + slug + "' に対応する投稿は見つかりません"));
        return new PostLookupResponse(post.getWpPostId(), post.getStatus());
    }
}
