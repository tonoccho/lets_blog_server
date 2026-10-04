package com.letsblog.publishing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.publishing.aop.AuditLog;
import com.letsblog.publishing.client.ContentServiceClient;
import com.letsblog.publishing.client.IdentityBridgeClient;
import com.letsblog.publishing.client.MediaSettingsBridgeClient;
import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.cms.AmbiguousPostSlugException;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Markdown記事の投稿パイプライン:
 * 画像リネーム・アップロード → Markdown内の画像参照差し替え → HTML変換 → カテゴリ/タグ解決 → WordPress投稿 → posts テーブル反映
 *
 * <p>legacy-apiの{@code PostPublishService}をpublishing-serviceへ移設したもの(issue #707、Epic #551
 * C6-1)。サイト本体・CMS認証情報はproject-service({@link ProjectServiceClient})、Markdown
 * レンダリング・postsテーブルの読み書きはcontent-service({@link ContentServiceClient})、著者マッピング
 * ({@code user_site_authors})はidentity-service({@link IdentityBridgeClient})への内部ブリッジ経由で行う
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
    private final IdentityBridgeClient identityBridgeClient;
    private final MediaSettingsBridgeClient mediaSettingsBridgeClient;
    private final ObjectMapper objectMapper;
    private final ImageResizeService imageResizeService;
    private final DomainEventPublisher domainEventPublisher;
    private final AdminAuthorizationService adminAuthorizationService;

    public PostPublishService(ProjectServiceClient projectServiceClient, CmsAdapterFactory cmsAdapterFactory,
                               ContentServiceClient contentServiceClient,
                               PlantUmlEmbedService plantUmlEmbedService,
                               PlantUmlTagRenderService plantUmlTagRenderService,
                               CurrentActorService currentActorService,
                               IdentityBridgeClient identityBridgeClient,
                               MediaSettingsBridgeClient mediaSettingsBridgeClient,
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
        this.identityBridgeClient = identityBridgeClient;
        this.mediaSettingsBridgeClient = mediaSettingsBridgeClient;
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
        // letsblogプラグインが使えない(未導入・要更新の)サイトへは投稿しない。CMSへ何かを書き込む前に
        // 理由と対処(再導入)を示して拒否する(issue #1557)。
        cmsAdapter.requireLetsblogPlugin(credentials);
        // カスタムタグ→[blogcard]→[amazon]→[recharts]の展開はcontent-serviceへ委譲する(issue #576)。
        // [recharts]タグの記法・データが不正な場合はInvalidRechartsTagExceptionが未捕捉のまま伝播し、
        // GlobalExceptionHandlerが400として返すことで投稿自体を拒否する(Issue #340、ContentServiceClient
        // が content-service側の400応答をこの例外へ変換して再送出する)。
        // wpPostId未指定でもCMS側に同じスラッグの記事があれば、それを更新対象にする(issue #1431)。
        String targetWpPostId = resolveTargetWpPostId(cmsAdapter, credentials, command);
        String markdown = contentServiceClient.renderPreImage(
                command.markdown(), projectId, isProductionSite(site, projectId));
        // 前回投稿時にアップロード済みの画像/ダイアグラムを再利用するキャッシュは、そのwpPostIdに紐づけて
        // 記憶している。wpPostId自体がCMS側で削除される等して実在しなくなっている場合、一緒にアップロードした
        // 画像も削除されている可能性が高く、キャッシュされたURLが既にリンク切れであることがある(issue #493)。
        String wpPostIdForImageCache = targetWpPostId;
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

        log.info("WordPress投稿リクエスト送信: wpPostId={}, featuredMediaId={}", targetWpPostId, content.featuredMediaId());
        PostResult result = cmsAdapter.createOrUpdatePost(credentials, content, targetWpPostId);
        log.info("WordPress投稿完了: postId={}, status={}", result.id(), result.status());

        upsertPostRecord(site.id(), result, command.slug(), imageResult.uploadedImages(),
                command.categories(), publishScheduledAt);
        domainEventPublisher.publishPostPublished(site.id(), projectId, result.id(), result.link(), result.status());

        return new PostPublishResponse(result.id(), result.link(), result.status());
    }

    /**
     * 更新対象のwpPostIdを決める。wpPostIdが指定されていればそのまま使う(#493/#529の挙動を変えない)。
     * 未指定でslugがあれば、ローカルDBではなくCMS側のスラッグを照会し、1件ならその投稿を更新対象とする。
     * 複数件は候補IDを示して中止し、照会の失敗は例外のまま伝播させる(いずれも新規作成へ進まない)。
     */
    private String resolveTargetWpPostId(CmsAdapter cmsAdapter, CmsCredentials credentials,
                                         PostPublishCommand command) {
        String wpPostId = command.wpPostId();
        if (wpPostId != null && !wpPostId.isBlank()) {
            return wpPostId;
        }
        String slug = command.slug();
        if (slug == null || slug.isBlank()) {
            return wpPostId;
        }
        List<String> found = cmsAdapter.findPostIdsBySlug(credentials, slug);
        if (found.isEmpty()) {
            return wpPostId;
        }
        if (found.size() > 1) {
            throw new AmbiguousPostSlugException(slug, found);
        }
        log.info("スラッグ '{}' の既存投稿をCMS側で確認したため更新します: wpPostId={}", slug, found.get(0));
        return found.get(0);
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
     * {@link IdentityBridgeClient}経由で参照し、無ければ従来通りメールアドレスでの動的検索に
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
            Optional<String> cached = identityBridgeClient.findUserSiteAuthor(actorId, siteId);
            if (cached.isPresent()) {
                return cached.get();
            }

            String email = currentActorService.getCurrentActorEmail();
            if (email == null) {
                return null;
            }
            Optional<String> resolved = cmsAdapter.findAuthorIdByEmail(credentials, email);
            resolved.ifPresent(cmsAuthorId -> identityBridgeClient.cacheUserSiteAuthor(actorId, siteId, cmsAuthorId));
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
        int articleImageLongEdgePx = mediaSettingsBridgeClient.resolveArticleImageLongEdgePx(projectId);

        // 先に全画像をリサイズ/変換してsha256を求める。WordPress側の内容ハッシュ照会を、画像の枚数によらず
        // 1回で済ませるため(issue #1432)。
        List<PreparedImage> prepared = new java.util.ArrayList<>();
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
                prepared.add(new PreparedImage(i, reference, resized, sha256Hex(resized.data())));
            } catch (IOException e) {
                throw new CmsApiException("画像 '" + reference + "' の読み込み/アップロードに失敗しました", e);
            }
        }

        // WordPress側の既存メディア(sha256 -> メディア)。ローカルのキャッシュで再利用できない画像が
        // 初めて出たときに、全画像分をまとめて1回だけ照会する。
        Map<String, MediaUploadResult> remoteMedia = null;

        for (PreparedImage item : prepared) {
            String reference = item.reference();
            ImageResizeService.ResizeResult resized = item.resized();
            String sha256 = item.sha256();
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
                if (remoteMedia == null) {
                    remoteMedia = lookupRemoteMedia(cmsAdapter, credentials, prepared);
                }
                MediaUploadResult existing = remoteMedia.get(sha256);
                if (existing != null) {
                    current = new UploadedImageInfo(sha256, existing.url(), existing.id());
                    log.info("画像 '{}' はWordPress側に同一内容(sha256一致)のメディアがあるため再利用します: "
                            + "mediaId={}, url={}", reference, current.mediaId(), current.url());
                } else {
                    String renamedFilename = renameImageFile(reference, finalSlug, item.index() + 1, resized.mimeType());
                    MediaUploadResult uploaded = cmsAdapter.uploadMedia(
                            credentials, renamedFilename, resized.mimeType(), resized.data());
                    current = new UploadedImageInfo(sha256, uploaded.url(), uploaded.id());
                    log.info("画像 '{}' を新規アップロードしました: mediaId={}, url={}",
                            reference, current.mediaId(), current.url());
                }
            }
            referenceToUrl.put(reference, current.url());
            updatedUploads.put(reference, current);
            if (featuredImageFilename != null && featuredImageFilename.equals(reference)) {
                featuredMediaId = current.mediaId();
                log.info("画像 '{}' はfeaturedImageFilenameと一致したためfeaturedMediaId={}を設定します",
                        reference, featuredMediaId);
            }
        }

        rewritten = replaceReferencesInSinglePass(rewritten, referenceToUrl);
        if (featuredImageFilename != null && featuredMediaId == null) {
            log.warn("featuredImageFilename='{}' がimages中のどの参照とも一致しなかったため、featuredMediaIdはnullのままです(imageReferences={})",
                    featuredImageFilename, imageReferences);
        }
        log.info("アイキャッチ解決結果: featuredMediaId={}", featuredMediaId);
        return new ImageReplacementResult(rewritten, featuredMediaId, updatedUploads);
    }

    /** リサイズ/変換済みの画像とそのsha256(記事中の並び順indexを保持する。連番のファイル名に使う)。 */
    private record PreparedImage(int index, String reference, ImageResizeService.ResizeResult resized,
                                 String sha256) {
    }

    /**
     * 全画像のsha256をまとめて1回WordPress側へ照会する(issue #1432)。照会に失敗した場合は空として扱い、
     * 従来どおり新規アップロードして投稿を続行する(メディアの重複は記事の重複より実害が小さいため)。
     */
    private Map<String, MediaUploadResult> lookupRemoteMedia(
            CmsAdapter cmsAdapter, CmsCredentials credentials, List<PreparedImage> prepared) {
        Set<String> hashes = prepared.stream().map(PreparedImage::sha256)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        try {
            Map<String, MediaUploadResult> found = cmsAdapter.findMediaBySha256(credentials, hashes);
            return found == null ? Map.of() : found;
        } catch (RuntimeException e) {
            log.warn("WordPress側の同一内容メディアの照会に失敗しました(新規アップロードして続行します): {}",
                    e.getMessage());
            return Map.of();
        }
    }

    /**
     * 本文中の画像参照(referenceToUrlのキー)を、対応するアップロード後URLへ1回の走査で同時に置換する
     * (issue #1060)。
     *
     * <p>{@code referenceToUrl}のキーは{@code imageReferences}由来の生の参照文字列であり、Markdownの
     * {@code ![...](参照)}記法の括弧内に限らず、本文中の任意の位置に現れうる(収集側 :272-274 が
     * Markdown構文を一切解釈していないため)。したがって置換側もMarkdown構文を前提にできず、収集側と
     * 同じ「本文中の生の文字列一致」で置換する必要がある。
     *
     * <p>単純な{@code String#replace}を参照ごとに繰り返すと、ある参照が別の参照の部分文字列である場合
     * (例: {@code eyecatch.png}は{@code assets/eyecatch.png}の部分文字列)、先に短い方を置換すると
     * 長い方の出現内部を壊してしまい、後続の置換対象が本文から消えて置換されないまま残る(#1060)。
     * これを避けるため、全参照を1つの正規表現の選択(alternation)にまとめ、{@link Matcher}で本文を
     * 1回だけ左から右へ走査しながら{@link Matcher#appendReplacement}で書き換える。走査は既に書き換えた
     * (置換後URLを含む)領域へは戻らないため、置換結果の中に別の参照文字列が偶然含まれていても
     * 再置換されない。同じ開始位置で複数の参照が候補になる場合(部分文字列関係にある場合)は、
     * 長い参照を優先させるため、参照文字列は長さの降順で選択に並べる。
     */
    private String replaceReferencesInSinglePass(String markdown, Map<String, String> referenceToUrl) {
        if (referenceToUrl.isEmpty()) {
            return markdown;
        }
        List<String> referencesLongestFirst = new java.util.ArrayList<>(referenceToUrl.keySet());
        referencesLongestFirst.sort(Comparator.comparingInt(String::length).reversed());
        String alternation = referencesLongestFirst.stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        Matcher matcher = Pattern.compile(alternation).matcher(markdown);

        StringBuilder result = new StringBuilder();
        Set<String> matchedReferences = new java.util.HashSet<>();
        while (matcher.find()) {
            String reference = matcher.group();
            matchedReferences.add(reference);
            matcher.appendReplacement(result, Matcher.quoteReplacement(referenceToUrl.get(reference)));
        }
        matcher.appendTail(result);

        for (String reference : referenceToUrl.keySet()) {
            if (!matchedReferences.contains(reference)) {
                log.warn("画像参照 '{}' が本文中に見つからなかったため、URLへの置換は行われませんでした", reference);
            }
        }
        return result.toString();
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
