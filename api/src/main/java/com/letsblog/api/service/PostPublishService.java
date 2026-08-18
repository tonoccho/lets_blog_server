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
import com.letsblog.api.domain.Project;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
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
    private final RechartsTagRenderService rechartsTagRenderService;
    private final PlantUmlTagRenderService plantUmlTagRenderService;
    private final TocStyleRenderService tocStyleRenderService;
    private final RenderedContentWrapperService renderedContentWrapperService;
    private final ProjectService projectService;
    private final CurrentActorService currentActorService;
    private final UserRepository userRepository;
    private final UserSiteAuthorRepository userSiteAuthorRepository;
    private final ObjectMapper objectMapper;
    private final ImageResizeService imageResizeService;
    private final BufferNotificationService bufferNotificationService;

    public PostPublishService(SiteService siteService, CmsAdapterFactory cmsAdapterFactory,
                               MarkdownRenderer markdownRenderer, PostRepository postRepository,
                               PlantUmlEmbedService plantUmlEmbedService,
                               CustomTagRenderService customTagRenderService,
                               BlogCardTagRenderService blogCardTagRenderService,
                               AmazonTagRenderService amazonTagRenderService,
                               RechartsTagRenderService rechartsTagRenderService,
                               PlantUmlTagRenderService plantUmlTagRenderService,
                               TocStyleRenderService tocStyleRenderService,
                               RenderedContentWrapperService renderedContentWrapperService,
                               ProjectService projectService,
                               CurrentActorService currentActorService,
                               UserRepository userRepository,
                               UserSiteAuthorRepository userSiteAuthorRepository,
                               ObjectMapper objectMapper,
                               ImageResizeService imageResizeService,
                               BufferNotificationService bufferNotificationService) {
        this.siteService = siteService;
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.markdownRenderer = markdownRenderer;
        this.postRepository = postRepository;
        this.plantUmlEmbedService = plantUmlEmbedService;
        this.customTagRenderService = customTagRenderService;
        this.blogCardTagRenderService = blogCardTagRenderService;
        this.amazonTagRenderService = amazonTagRenderService;
        this.rechartsTagRenderService = rechartsTagRenderService;
        this.plantUmlTagRenderService = plantUmlTagRenderService;
        this.tocStyleRenderService = tocStyleRenderService;
        this.renderedContentWrapperService = renderedContentWrapperService;
        this.projectService = projectService;
        this.currentActorService = currentActorService;
        this.userRepository = userRepository;
        this.userSiteAuthorRepository = userSiteAuthorRepository;
        this.objectMapper = objectMapper;
        this.imageResizeService = imageResizeService;
        this.bufferNotificationService = bufferNotificationService;
    }

    @AuditLog(action = AuditLogAction.POST_PUBLISHED, resourceType = "POST")
    @Transactional
    public PostPublishResponse publish(PostPublishCommand command) {
        Site site = siteService.getBySiteKey(command.siteKey());
        CmsCredentials credentials = siteService.getCredentials(command.siteKey());
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());

        Long projectId = projectService.findProjectIdBySiteId(site.getId());
        String markdown = customTagRenderService.render(command.markdown(), projectId);
        markdown = blogCardTagRenderService.render(markdown, projectId);
        markdown = amazonTagRenderService.render(markdown, projectId, isProductionSite(site, projectId));
        // [recharts]タグの記法・データが不正な場合はInvalidRechartsTagExceptionを未捕捉のまま伝播させ、
        // GlobalExceptionHandlerが400として返すことで投稿自体を拒否する(Issue #340)。
        markdown = rechartsTagRenderService.render(markdown);
        // [plantuml]〜[/plantuml]組み込みタグも同じ方針(Issue #344)。既存の```plantumlフェンスコード
        // ブロック記法(次行のplantUmlEmbedService)とは併存し、置き換えない。
        markdown = plantUmlTagRenderService.render(credentials, markdown);
        markdown = plantUmlEmbedService.embedDiagrams(credentials, markdown);
        // 前回投稿時にアップロード済みの画像を再利用するキャッシュは、そのwpPostIdに紐づけて記憶している。
        // wpPostId自体がCMS側で削除される等して実在しなくなっている場合、一緒にアップロードした画像も
        // 削除されている可能性が高く、キャッシュされたURLが既にリンク切れであることがある(issue #493)。
        // 投稿自体の作成/更新時のフォールバック(createOrUpdatePost実装内)とは別に、画像再利用の可否を
        // 先に判定する必要がある(画像URLは投稿本文の組み立てに使うため、投稿作成より前に確定させるため)。
        String wpPostIdForImageCache = command.wpPostId();
        if (wpPostIdForImageCache != null && !cmsAdapter.postExists(credentials, wpPostIdForImageCache)) {
            log.info("wpPostId={} はCMS側に存在しないため、前回アップロード画像の再利用キャッシュは使用しません",
                    wpPostIdForImageCache);
            wpPostIdForImageCache = null;
        }
        Map<String, UploadedImageInfo> priorUploads = loadPriorUploadedImages(site.getId(), wpPostIdForImageCache);
        ImageReplacementResult imageResult = replaceImageReferences(
                cmsAdapter, credentials, markdown, command.images(), command.imageReferences(),
                command.slug(), command.title(), command.featuredImageFilename(), priorUploads, projectId);
        String html = markdownRenderer.render(imageResult.markdown());
        html = tocStyleRenderService.applyHtmlTemplate(html, projectId);
        html = renderedContentWrapperService.wrap(html, projectId);

        List<String> categoryIds = cmsAdapter.resolveCategories(credentials, command.categories());
        List<String> tagIds = cmsAdapter.resolveTags(credentials, command.tags());
        String authorId = resolveAuthorId(cmsAdapter, credentials, site.getId());

        Instant publishScheduledAt = resolvePublishScheduledAt(command.publishScheduledAt(), site, projectId);
        // 予約投稿はWordPressの"future"ステータスで表現する(指定日時にCMS側が自動公開する)。
        String status = publishScheduledAt != null
                ? "future"
                : (command.status() == null ? "draft" : command.status());

        PostContent content = new PostContent(
                command.title(),
                command.slug(),
                html,
                status,
                categoryIds,
                tagIds,
                imageResult.featuredMediaId(),
                authorId,
                publishScheduledAt
        );

        log.info("WordPress投稿リクエスト送信: wpPostId={}, featuredMediaId={}", command.wpPostId(), content.featuredMediaId());
        PostResult result = cmsAdapter.createOrUpdatePost(credentials, content, command.wpPostId());
        log.info("WordPress投稿完了: postId={}, status={}", result.id(), result.status());

        Post post = upsertPostRecord(site.getId(), result, command.slug(), imageResult.uploadedImages());

        if (shouldNotifySns(command, site, projectId, status)) {
            bufferNotificationService.notifyAsync(post.getId(), site.getId(), projectId, command.title(), result.link());
        }

        return new PostPublishResponse(result.id(), result.link(), result.status());
    }

    /**
     * BufferによるSNS通知を行うかどうか。下書きや本番以外のサイトへの投稿では通知しない
     * (issue #379の「プレビュー/下書きでは通知しない」という考慮事項に対応)。
     * notifySns=falseが明示された場合は呼び出し元(投稿単位)の指定を優先する。
     */
    private boolean shouldNotifySns(PostPublishCommand command, Site site, Long projectId, String status) {
        if (Boolean.FALSE.equals(command.notifySns())) {
            return false;
        }
        if ("draft".equals(status)) {
            return false;
        }
        return isProductionSite(site, projectId);
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
            Map<String, UploadedImageInfo> priorUploads, Long projectId) {
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
        int articleImageLongEdgePx = projectService.resolveArticleImageLongEdgePx(projectId);

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
                // アップロード前に長編基準でリサイズする(issue #291)。あわせて、透過を持たないPNGは
                // JPEGへ変換してファイルサイズを削減する(issue #468)。sha256計算より前に行うことで、
                // 前回投稿時と同じリサイズ/変換結果であれば再アップロードをスキップする再利用判定が働く。
                ImageResizeService.ResizeResult resized = imageResizeService.resizeToLongEdge(
                        image.getBytes(), image.getContentType(), articleImageLongEdgePx, true);
                byte[] bytes = resized.data();
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
                    String renamedFilename = renameImageFile(reference, finalSlug, i + 1, resized.mimeType());
                    MediaUploadResult uploaded = cmsAdapter.uploadMedia(
                            credentials, renamedFilename, resized.mimeType(), bytes);
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

    private String renameImageFile(String originalFilename, String slug, int index, String mimeType) {
        String extension = extensionForMimeType(mimeType, originalFilename);
        String number = String.format("%04d", index);
        return slug + "-" + number + extension;
    }

    /**
     * JPEG変換(issue #468)によりmimeTypeが元のファイル名の拡張子と異なりうるため、実際に
     * アップロードするバイト列のmimeTypeを優先して拡張子を決める。未知のmimeTypeの場合は
     * 元のファイル名から推測する(従来どおりの挙動)。
     */
    private String extensionForMimeType(String mimeType, String fallbackFilename) {
        if ("image/jpeg".equalsIgnoreCase(mimeType)) {
            return ".jpg";
        }
        if ("image/png".equalsIgnoreCase(mimeType)) {
            return ".png";
        }
        return getFileExtension(fallbackFilename);
    }

    private Post upsertPostRecord(Long siteId, PostResult result, String slug, Map<String, UploadedImageInfo> uploadedImages) {
        Post post = postRepository.findBySiteIdAndWpPostId(siteId, result.id())
                .orElseGet(Post::new);

        post.setSiteId(siteId);
        post.setWpPostId(result.id());
        post.setSlug(slug);
        post.setStatus(result.status());
        post.setLastPublishedAt(LocalDateTime.now());
        post.setUploadedImagesJson(serializeUploadedImages(uploadedImages));

        postRepository.save(post);
        return post;
    }

    private record ImageReplacementResult(
            String markdown, String featuredMediaId, Map<String, UploadedImageInfo> uploadedImages) {
    }

    /** アップロード済み画像1件分の情報。sha256は再投稿時に内容が変わっていないかの判定に使う。 */
    private record UploadedImageInfo(String sha256, String url, String mediaId) {
    }

    /**
     * front matterのpublish_scheduled_atを検証し、実際に適用する公開予定日時を返す。
     *
     * 予約投稿は本番(live)サイトでのみ有効とする。ローカル/テスト環境は動作確認用途で
     * 即時に結果を見たいため、予約指定があっても無視して通常どおり投稿する。
     * 形式不正や過去日時は、利用者が意図と異なる公開状態に気付けないまま進むのを防ぐため
     * エラーとして扱う。
     */
    private Instant resolvePublishScheduledAt(String raw, Site site, Long projectId) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        Instant scheduledAt;
        try {
            scheduledAt = OffsetDateTime.parse(raw.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "publish_scheduled_at はISO 8601形式(例: 2026-12-25T09:00:00Z)で指定してください: " + raw);
        }
        if (!scheduledAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("publish_scheduled_at には未来の日時を指定してください: " + raw);
        }

        if (!isProductionSite(site, projectId)) {
            log.info("本番サイト以外への投稿のため、publish_scheduled_at({})を無視します: siteKey={}",
                    raw, site.getSiteKey());
            return null;
        }
        return scheduledAt;
    }

    /** 投稿先がプロジェクトの本番(live)サイトかどうか。 */
    private boolean isProductionSite(Site site, Long projectId) {
        if (projectId == null) {
            return false;
        }
        Project project = projectService.getProjectEntity(projectId);
        return project != null && site.getId().equals(project.getProductionSiteId());
    }
}
