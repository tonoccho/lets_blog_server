package com.letsblog.publishing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.publishing.aop.AuditLog;
import com.letsblog.publishing.client.ContentServiceClient;
import com.letsblog.publishing.client.LegacyApiBridgeClient;
import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsApiException;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.MediaUploadResult;
import com.letsblog.publishing.cms.PostContent;
import com.letsblog.publishing.cms.PostResult;
import com.letsblog.publishing.domain.AuditLogAction;
import com.letsblog.publishing.dto.PostPublishCommand;
import com.letsblog.publishing.dto.PostPublishResponse;
import com.letsblog.publishing.messaging.DomainEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Markdown記事の投稿パイプライン:
 * 画像リネーム・アップロード → Markdown内の画像参照差し替え → HTML変換 → カテゴリ/タグ解決 → WordPress投稿 → posts テーブル反映
 *
 * <p>legacy-apiの{@code PostPublishService}をpublishing-serviceへ移設したもの(issue #707、Epic #551
 * C6-1)。サイト本体・CMS認証情報はproject-service({@link ProjectServiceClient})、Markdown
 * レンダリング・postsテーブルの読み書きはcontent-service({@link ContentServiceClient})、著者マッピング
 * ({@code user_site_authors})はlegacy-api({@link LegacyApiBridgeClient})への内部ブリッジ経由で行う
 * (#575設計判断1・2・4)。[plantuml]/```plantumlの埋め込み(CMSメディアライブラリへのアップロードを
 * 伴う)は、CmsAdapter/CmsCredentialsへの依存が強いため引き続きこのクラス自身が行う。
 */
@Service
@Slf4j
public class PostPublishService {

    private final ProjectServiceClient projectServiceClient;
    private final CmsAdapterFactory cmsAdapterFactory;
    private final ContentServiceClient contentServiceClient;
    private final PlantUmlEmbedService plantUmlEmbedService;
    private final PlantUmlTagRenderService plantUmlTagRenderService;
    private final CurrentActorService currentActorService;
    private final LegacyApiBridgeClient legacyApiBridgeClient;
    private final ObjectMapper objectMapper;
    private final ImageResizeService imageResizeService;
    private final DomainEventPublisher domainEventPublisher;
    private final AdminAuthorizationService adminAuthorizationService;

    public PostPublishService(ProjectServiceClient projectServiceClient, CmsAdapterFactory cmsAdapterFactory,
                               ContentServiceClient contentServiceClient,
                               PlantUmlEmbedService plantUmlEmbedService,
                               PlantUmlTagRenderService plantUmlTagRenderService,
                               CurrentActorService currentActorService,
                               LegacyApiBridgeClient legacyApiBridgeClient,
                               ObjectMapper objectMapper,
                               ImageResizeService imageResizeService,
                               DomainEventPublisher domainEventPublisher,
                               AdminAuthorizationService adminAuthorizationService) {
        this.projectServiceClient = projectServiceClient;
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.contentServiceClient = contentServiceClient;
        this.plantUmlEmbedService = plantUmlEmbedService;
        this.plantUmlTagRenderService = plantUmlTagRenderService;
        this.currentActorService = currentActorService;
        this.legacyApiBridgeClient = legacyApiBridgeClient;
        this.objectMapper = objectMapper;
        this.imageResizeService = imageResizeService;
        this.domainEventPublisher = domainEventPublisher;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @AuditLog(action = AuditLogAction.POST_PUBLISHED, resourceType = "POST")
    public PostPublishResponse publish(PostPublishCommand command) {
        ProjectServiceClient.SiteBridge site = projectServiceClient.getSiteByKey(command.siteKey());
        CmsCredentials credentials = projectServiceClient.getCredentials(command.siteKey()).toCmsCredentials();
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());

        Long projectId = projectServiceClient.findProjectIdBySiteId(site.id());
        // WordPressへの公開は「認証済みなら誰でも」ではなく、そのサイトが属するプロジェクトの
        // メンバー(またはadmin)に限定する(issue #830)。CMSへの副作用が始まる前に判定する。
        adminAuthorizationService.requireProjectMemberOrAdminForSite(projectId);
        // カスタムタグ→[blogcard]→[amazon]→[recharts]の展開はcontent-serviceへ委譲する(issue #576)。
        // [recharts]タグの記法・データが不正な場合はInvalidRechartsTagExceptionが未捕捉のまま伝播し、
        // GlobalExceptionHandlerが400として返すことで投稿自体を拒否する(Issue #340、ContentServiceClient
        // が content-service側の400応答をこの例外へ変換して再送出する)。
        String markdown = contentServiceClient.renderPreImage(
                command.markdown(), projectId, isProductionSite(site, projectId));
        // 前回投稿時にアップロード済みの画像/ダイアグラムを再利用するキャッシュは、そのwpPostIdに紐づけて
        // 記憶している。wpPostId自体がCMS側で削除される等して実在しなくなっている場合、一緒にアップロードした
        // 画像も削除されている可能性が高く、キャッシュされたURLが既にリンク切れであることがある(issue #493)。
        String wpPostIdForImageCache = command.wpPostId();
        if (wpPostIdForImageCache != null && !cmsAdapter.postExists(credentials, wpPostIdForImageCache)) {
            log.info("wpPostId={} はCMS側に存在しないため、前回アップロード画像の再利用キャッシュは使用しません",
                    wpPostIdForImageCache);
            wpPostIdForImageCache = null;
        }
        Map<String, UploadedImageInfo> priorUploads = loadPriorUploadedImages(site.id(), wpPostIdForImageCache);

        // [plantuml]〜[/plantuml]組み込みタグも同じ方針(Issue #344)。既存の```plantumlフェンスコード
        // ブロック記法(次行のplantUmlEmbedService)とは併存し、置き換えない。
        DiagramEmbedResult tagResult = plantUmlTagRenderService.render(credentials, markdown, priorUploads);
        DiagramEmbedResult embedResult = plantUmlEmbedService.embedDiagrams(
                credentials, tagResult.markdown(), tagResult.uploadedImages());
        markdown = embedResult.markdown();

        ImageReplacementResult imageResult = replaceImageReferences(
                cmsAdapter, credentials, markdown, command.images(), command.imageReferences(),
                command.slug(), command.title(), command.featuredImageFilename(),
                embedResult.uploadedImages(), projectId);
        // Markdown→HTML変換 + [toc]カスタムHTMLテンプレート適用 + 統合CSSラッパー適用もcontent-service
        // へ委譲する(issue #576)。
        String html = contentServiceClient.finalizeHtml(imageResult.markdown(), projectId);

        List<String> categoryIds = cmsAdapter.resolveCategories(credentials, command.categories());
        List<String> tagIds = cmsAdapter.resolveTags(credentials, command.tags());
        String authorId = resolveAuthorId(cmsAdapter, credentials, site.id());

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

        upsertPostRecord(site.id(), result, command.slug(), imageResult.uploadedImages(),
                command.categories(), publishScheduledAt);
        domainEventPublisher.publishPostPublished(site.id(), projectId, result.id(), result.link(), result.status());

        return new PostPublishResponse(result.id(), result.link(), result.status());
    }

    /** サイト+既存wpPostIdに紐づくPost行から、前回投稿時にアップロード済みの画像情報を読み込む。 */
    private Map<String, UploadedImageInfo> loadPriorUploadedImages(Long siteId, String wpPostId) {
        if (wpPostId == null) {
            return Map.of();
        }
        return contentServiceClient.findPost(siteId, wpPostId)
                .map(ContentServiceClient.PostBridgeResponse::uploadedImagesJson)
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
     * 投稿者(CurrentActorServiceが解決するLet's Blogユーザー)に対応する、投稿先サイト上の
     * 既存WordPressユーザーIDを解決する。まずuser_site_authors(legacy-apiが所有し、
     * ProjectUserSyncServiceがプロジェクトメンバー追加/ロール変更のたびに書き込む対応表)を
     * {@link LegacyApiBridgeClient}経由で参照し、無ければ従来通りメールアドレスでの動的検索に
     * フォールバックする(見つかればlegacy-apiへその場でキャッシュ書き込みを依頼する。#575設計判断4)。
     * 見つからない・解決に失敗した場合はnullを返し、投稿自体は従来通り(authorId未指定)続行する
     * (著者解決の失敗で投稿全体を失敗させない)。
     */
    private String resolveAuthorId(CmsAdapter cmsAdapter, CmsCredentials credentials, Long siteId) {
        Long actorId = currentActorService.getCurrentActorId();
        if (actorId == null) {
            return null;
        }
        try {
            Optional<String> cached = legacyApiBridgeClient.findUserSiteAuthor(actorId, siteId);
            if (cached.isPresent()) {
                return cached.get();
            }

            String email = currentActorService.getCurrentActorEmail();
            if (email == null) {
                return null;
            }
            Optional<String> resolved = cmsAdapter.findAuthorIdByEmail(credentials, email);
            resolved.ifPresent(cmsAuthorId -> legacyApiBridgeClient.cacheUserSiteAuthor(actorId, siteId, cmsAuthorId));
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
        int articleImageLongEdgePx = legacyApiBridgeClient.resolveArticleImageLongEdgePx(projectId);

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
                boolean hashMatches = prior != null && prior.sha256().equals(sha256);
                boolean reusePrior = hashMatches && cmsAdapter.mediaExists(credentials, prior.mediaId());
                if (hashMatches && !reusePrior) {
                    log.info("画像 '{}' は前回投稿時と同一内容(sha256一致)ですが、CMS側のメディア(mediaId={})が"
                            + "実在しないため再アップロードします", reference, prior.mediaId());
                }
                if (reusePrior) {
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

    private String extensionForMimeType(String mimeType, String fallbackFilename) {
        if ("image/jpeg".equalsIgnoreCase(mimeType)) {
            return ".jpg";
        }
        if ("image/png".equalsIgnoreCase(mimeType)) {
            return ".png";
        }
        return getFileExtension(fallbackFilename);
    }

    private void upsertPostRecord(Long siteId, PostResult result, String slug, Map<String, UploadedImageInfo> uploadedImages,
                                   List<String> categories, Instant publishScheduledAt) {
        contentServiceClient.upsertPost(
                siteId,
                result.id(),
                slug,
                result.status(),
                serializeUploadedImages(uploadedImages),
                serializeCategories(categories),
                publishScheduledAt == null ? null : LocalDateTime.ofInstant(publishScheduledAt, ZoneOffset.UTC));
    }

    private String serializeCategories(List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(categories);
        } catch (JsonProcessingException e) {
            log.warn("カテゴリ情報のシリアライズに失敗しました: {}", e.getMessage());
            return null;
        }
    }

    private record ImageReplacementResult(
            String markdown, String featuredMediaId, Map<String, UploadedImageInfo> uploadedImages) {
    }

    /**
     * front matterのpublish_scheduled_atを検証し、実際に適用する公開予定日時を返す。
     * 予約投稿は本番(live)サイトでのみ有効とする(issue #520)。
     */
    private Instant resolvePublishScheduledAt(String raw, ProjectServiceClient.SiteBridge site, Long projectId) {
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

        if (!isProductionSite(site, projectId)) {
            log.info("本番サイト以外への投稿のため、publish_scheduled_at({})を無視します: siteKey={}",
                    raw, site.siteKey());
            return null;
        }

        if (!scheduledAt.isAfter(Instant.now())) {
            log.info("publish_scheduled_at({})が過去日時のため無視し、指定のstatusで投稿します: siteKey={}",
                    raw, site.siteKey());
            return null;
        }
        return scheduledAt;
    }

    /** 投稿先がプロジェクトの本番(live)サイトかどうか。 */
    private boolean isProductionSite(ProjectServiceClient.SiteBridge site, Long projectId) {
        if (projectId == null) {
            return false;
        }
        ProjectServiceClient.ProjectBridge project = projectServiceClient.getProject(projectId);
        return project != null && site.id().equals(project.productionSiteId());
    }
}
