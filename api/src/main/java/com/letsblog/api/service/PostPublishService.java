package com.letsblog.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsApiException;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.cms.PostContent;
import com.letsblog.api.cms.PostResult;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.Post;
import com.letsblog.api.domain.Site;
import com.letsblog.api.domain.User;
import com.letsblog.api.domain.UserSiteAuthor;
import com.letsblog.api.dto.PostPublishCommand;
import com.letsblog.api.dto.PostPublishResponse;
import com.letsblog.api.markdown.MarkdownRenderer;
import com.letsblog.api.repository.PostRepository;
import com.letsblog.api.repository.UserRepository;
import com.letsblog.api.repository.UserSiteAuthorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Markdown記事の投稿パイプライン:
 * 画像リネーム・アップロード → Markdown内の画像参照差し替え → HTML変換 → カテゴリ/タグ解決 → WordPress投稿 → posts テーブル反映
 */
@Service
@Slf4j
public class PostPublishService {

    private final SiteService siteService;
    private final CmsAdapterFactory cmsAdapterFactory;
    private final MarkdownRenderer markdownRenderer;
    private final PostRepository postRepository;
    private final PlantUmlEmbedService plantUmlEmbedService;
    private final CustomTagRenderService customTagRenderService;
    private final BlogCardTagRenderService blogCardTagRenderService;
    private final AmazonTagRenderService amazonTagRenderService;
    private final ProjectService projectService;
    private final CurrentActorService currentActorService;
    private final UserRepository userRepository;
    private final UserSiteAuthorRepository userSiteAuthorRepository;
    private final ObjectMapper objectMapper;

    public PostPublishService(SiteService siteService, CmsAdapterFactory cmsAdapterFactory,
                               MarkdownRenderer markdownRenderer, PostRepository postRepository,
                               PlantUmlEmbedService plantUmlEmbedService,
                               CustomTagRenderService customTagRenderService,
                               BlogCardTagRenderService blogCardTagRenderService,
                               AmazonTagRenderService amazonTagRenderService,
                               ProjectService projectService,
                               CurrentActorService currentActorService,
                               UserRepository userRepository,
                               UserSiteAuthorRepository userSiteAuthorRepository,
                               ObjectMapper objectMapper) {
        this.siteService = siteService;
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.markdownRenderer = markdownRenderer;
        this.postRepository = postRepository;
        this.plantUmlEmbedService = plantUmlEmbedService;
        this.customTagRenderService = customTagRenderService;
        this.blogCardTagRenderService = blogCardTagRenderService;
        this.amazonTagRenderService = amazonTagRenderService;
        this.projectService = projectService;
        this.currentActorService = currentActorService;
        this.userRepository = userRepository;
        this.userSiteAuthorRepository = userSiteAuthorRepository;
        this.objectMapper = objectMapper;
    }

    @AuditLog(action = AuditLogAction.POST_PUBLISHED, resourceType = "POST")
    @Transactional
    public PostPublishResponse publish(PostPublishCommand command) {
        Site site = siteService.getBySiteKey(command.siteKey());
        CmsCredentials credentials = siteService.getCredentials(command.siteKey());
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());

        Long projectId = projectService.findProjectIdBySiteId(site.getId());
        String markdown = customTagRenderService.render(command.markdown(), projectId);
        markdown = blogCardTagRenderService.render(markdown);
        markdown = amazonTagRenderService.render(markdown);
        markdown = plantUmlEmbedService.embedDiagrams(credentials, markdown);
        Map<String, UploadedImageInfo> priorUploads = loadPriorUploadedImages(site.getId(), command.wpPostId());
        ImageReplacementResult imageResult = replaceImageReferences(
                cmsAdapter, credentials, markdown, command.images(), command.imageReferences(),
                command.slug(), command.title(), command.featuredImageFilename(), priorUploads);
        String html = markdownRenderer.render(imageResult.markdown());

        List<String> categoryIds = cmsAdapter.resolveCategories(credentials, command.categories());
        List<String> tagIds = cmsAdapter.resolveTags(credentials, command.tags());
        String authorId = resolveAuthorId(cmsAdapter, credentials, site.getId());

        PostContent content = new PostContent(
                command.title(),
                command.slug(),
                html,
                command.status() == null ? "draft" : command.status(),
                categoryIds,
                tagIds,
                imageResult.featuredMediaId(),
                authorId
        );

        log.info("WordPress投稿リクエスト送信: wpPostId={}, featuredMediaId={}", command.wpPostId(), content.featuredMediaId());
        PostResult result = cmsAdapter.createOrUpdatePost(credentials, content, command.wpPostId());
        log.info("WordPress投稿完了: postId={}, status={}", result.id(), result.status());

        upsertPostRecord(site.getId(), result, command.slug(), imageResult.uploadedImages());

        return new PostPublishResponse(result.id(), result.link(), result.status());
    }

    /** サイト+既存wpPostIdに紐づくPost行から、前回投稿時にアップロード済みの画像情報を読み込む。 */
    private Map<String, UploadedImageInfo> loadPriorUploadedImages(Long siteId, String wpPostId) {
        if (wpPostId == null) {
            return Map.of();
        }
        return postRepository.findBySiteIdAndWpPostId(siteId, wpPostId)
                .map(Post::getUploadedImagesJson)
                .map(this::parseUploadedImages)
                .orElse(Map.of());
    }

    private Map<String, UploadedImageInfo> parseUploadedImages(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, UploadedImageInfo>>() {
            });
        } catch (IOException e) {
            log.warn("投稿済み画像情報のパースに失敗しました(再アップロードして続行します): {}", e.getMessage());
            return Map.of();
        }
    }

    private String serializeUploadedImages(Map<String, UploadedImageInfo> uploadedImages) {
        if (uploadedImages == null || uploadedImages.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(uploadedImages);
        } catch (JsonProcessingException e) {
            log.warn("投稿済み画像情報のシリアライズに失敗しました: {}", e.getMessage());
            return null;
        }
    }

    private String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256アルゴリズムが利用できません", e);
        }
    }

    /**
     * 投稿者(X-Actor-Idヘッダで識別されるLet's Blogユーザー)に対応する、投稿先サイト上の
     * 既存WordPressユーザーIDを解決する。まずuser_site_authors(ProjectUserSyncServiceが
     * プロジェクトメンバー追加/ロール変更のたびにprovisionAuthorの結果を書き込む対応表)を参照し、
     * 無ければ従来通りメールアドレスでの動的検索にフォールバックする(見つかればその場でキャッシュする)。
     * 見つからない・解決に失敗した場合はnullを返し、投稿自体は従来通り(authorId未指定)続行する
     * (著者解決の失敗で投稿全体を失敗させない)。
     */
    private String resolveAuthorId(CmsAdapter cmsAdapter, CmsCredentials credentials, Long siteId) {
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null) {
            return null;
        }
        try {
            Optional<UserSiteAuthor> mapping = userSiteAuthorRepository.findByUserIdAndSiteId(actorId, siteId);
            if (mapping.isPresent()) {
                return mapping.get().getCmsAuthorId();
            }

            Optional<User> user = userRepository.findById(actorId);
            if (user.isEmpty()) {
                return null;
            }
            Optional<String> resolved = cmsAdapter.findAuthorIdByEmail(credentials, user.get().getEmail());
            resolved.ifPresent(cmsAuthorId ->
                    userSiteAuthorRepository.save(new UserSiteAuthor(actorId, siteId, cmsAuthorId)));
            return resolved.orElse(null);
        } catch (RuntimeException e) {
            log.warn("投稿者のWordPressユーザーID解決に失敗しました(著者未設定のまま投稿を続行します): {}", e.getMessage());
            return null;
        }
    }

    private ImageReplacementResult replaceImageReferences(
            CmsAdapter cmsAdapter, CmsCredentials credentials, String markdown, List<MultipartFile> images,
            List<String> imageReferences, String slug, String title, String featuredImageFilename,
            Map<String, UploadedImageInfo> priorUploads) {
        Map<String, UploadedImageInfo> updatedUploads = new LinkedHashMap<>(priorUploads);
        log.info("アイキャッチ解決開始: featuredImageFilename={}, images={}件, imageReferences={}",
                featuredImageFilename, images == null ? 0 : images.size(), imageReferences);
        if (images == null || images.isEmpty()) {
            if (featuredImageFilename != null) {
                log.warn("featuredImageFilenameが指定されていますが、imagesが空のためアップロード/featured_media解決を行いません: {}",
                        featuredImageFilename);
            }
            return new ImageReplacementResult(markdown, null, updatedUploads);
        }

        String finalSlug = generateSlugForFilename(slug, title);
        String rewritten = markdown;
        Map<String, String> referenceToUrl = new LinkedHashMap<>();
        String featuredMediaId = null;

        for (int i = 0; i < images.size(); i++) {
            MultipartFile image = images.get(i);
            // imageReferencesはMarkdown中に実際に書かれている参照文字列(例: "assets/eyecatch.png")。
            // マルチパートのoriginalFilenameはパス区切りを含む場合にコンテナ/サーバー側でベース名のみに
            // 変換されうるため、置換対象の特定にはimageReferencesを優先し、未指定時のみフォールバックする。
            String reference = (imageReferences != null && i < imageReferences.size() && imageReferences.get(i) != null)
                    ? imageReferences.get(i)
                    : image.getOriginalFilename();
            if (reference == null || reference.isBlank()) {
                continue;
            }
            try {
                byte[] bytes = image.getBytes();
                String sha256 = sha256Hex(bytes);
                UploadedImageInfo prior = priorUploads.get(reference);
                UploadedImageInfo current;
                if (prior != null && prior.sha256().equals(sha256)) {
                    // 前回投稿時と内容(sha256)が同じ画像は再アップロードせず、既存のURL/media IDを再利用する
                    // (再投稿のたびに同じ画像が重複アップロードされWordPressのメディアライブラリが
                    // 肥大化するのを防ぐ)。
                    current = prior;
                    log.info("画像 '{}' は前回投稿時と同一内容(sha256一致)のため再利用します: mediaId={}, url={}",
                            reference, current.mediaId(), current.url());
                } else {
                    String renamedFilename = renameImageFile(reference, finalSlug, i + 1);
                    MediaUploadResult uploaded = cmsAdapter.uploadMedia(
                            credentials, renamedFilename, image.getContentType(), bytes);
                    current = new UploadedImageInfo(sha256, uploaded.url(), uploaded.id());
                    log.info("画像 '{}' を新規アップロードしました: mediaId={}, url={}",
                            reference, current.mediaId(), current.url());
                }
                referenceToUrl.put(reference, current.url());
                updatedUploads.put(reference, current);
                if (featuredImageFilename != null && featuredImageFilename.equals(reference)) {
                    featuredMediaId = current.mediaId();
                    log.info("画像 '{}' はfeaturedImageFilenameと一致したためfeaturedMediaId={}を設定します",
                            reference, featuredMediaId);
                }
            } catch (IOException e) {
                throw new CmsApiException("画像 '" + reference + "' の読み込み/アップロードに失敗しました", e);
            }
        }

        for (Map.Entry<String, String> entry : referenceToUrl.entrySet()) {
            rewritten = rewritten.replace(entry.getKey(), entry.getValue());
        }
        if (featuredImageFilename != null && featuredMediaId == null) {
            log.warn("featuredImageFilename='{}' がimages中のどの参照とも一致しなかったため、featuredMediaIdはnullのままです(imageReferences={})",
                    featuredImageFilename, imageReferences);
        }
        log.info("アイキャッチ解決結果: featuredMediaId={}", featuredMediaId);
        return new ImageReplacementResult(rewritten, featuredMediaId, updatedUploads);
    }

    /**
     * 投稿画像ファイル名用のslugを決定する。providedSlugがあればそれを使い、なければtitleを簡易スラッグ化する
     * (英数字・ハイフン以外を除去、連続する区切り文字をハイフン1個に統一)。結果が空文字列なら"post"を既定値とする。
     */
    private String generateSlugForFilename(String providedSlug, String title) {
        if (providedSlug != null && !providedSlug.isBlank()) {
            return providedSlug;
        }
        String slugified = (title == null ? "" : title)
                .replaceAll("[^\\w\\s-]", "")
                .replaceAll("[\\s]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        return slugified.isBlank() ? "post" : slugified.toLowerCase();
    }

    private String getFileExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return ".bin";
        }
        int lastDot = originalFilename.lastIndexOf('.');
        return lastDot >= 0 ? originalFilename.substring(lastDot) : ".bin";
    }

    private String renameImageFile(String originalFilename, String slug, int index) {
        String extension = getFileExtension(originalFilename);
        String number = String.format("%04d", index);
        return slug + "-" + number + extension;
    }

    private void upsertPostRecord(Long siteId, PostResult result, String slug, Map<String, UploadedImageInfo> uploadedImages) {
        Post post = postRepository.findBySiteIdAndWpPostId(siteId, result.id())
                .orElseGet(Post::new);

        post.setSiteId(siteId);
        post.setWpPostId(result.id());
        post.setSlug(slug);
        post.setStatus(result.status());
        post.setLastPublishedAt(LocalDateTime.now());
        post.setUploadedImagesJson(serializeUploadedImages(uploadedImages));

        postRepository.save(post);
    }

    private record ImageReplacementResult(
            String markdown, String featuredMediaId, Map<String, UploadedImageInfo> uploadedImages) {
    }

    /** アップロード済み画像1件分の情報。sha256は再投稿時に内容が変わっていないかの判定に使う。 */
    private record UploadedImageInfo(String sha256, String url, String mediaId) {
    }
}
