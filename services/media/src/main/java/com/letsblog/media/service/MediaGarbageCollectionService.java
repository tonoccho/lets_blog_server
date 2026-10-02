package com.letsblog.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.client.CmsBridgeClient;
import com.letsblog.media.client.CmsMediaReferenceScan;
import com.letsblog.media.client.CmsMediaSummary;
import com.letsblog.media.client.CmsPostContentSummary;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.media.client.MediaGcScanResult;
import com.letsblog.media.dto.MediaGarbageCollectionScanResponse;
import com.letsblog.media.dto.UnreferencedMediaItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * legacy-apiから移設(issue #573 stage3)。プロジェクトが持つ環境(local/test/production)の
 * WordPressサイトから、投稿本文・アイキャッチ・主要サイト設定のいずれからも参照されていない
 * メディアを検出し、選択削除する(issue #500)。
 *
 * <p>元の実装は{@code Project}/{@code Site}/{@code CmsAdapter}へ直接アクセスしていたが、これらは
 * このissueの移設対象ではない(project-service、C8/#577が未着手)ため、実際のCMS(WordPress)との
 * やり取り(メディア一覧・参照スキャン・削除の実行)は{@link CmsBridgeClient}経由でlegacy-apiへ
 * 委譲する。未参照判定の実際のロジック(正規表現によるコンテンツ参照抽出、
 * {@link #extractReferencedIds}/{@link #extractContentReferences})はmedia-serviceが引き続き
 * 所有する(このメディア関連の判断ロジック自体はCMS接続情報を必要としないため)。
 */
@Service
public class MediaGarbageCollectionService {

    private final CmsBridgeClient cmsBridgeClient;
    private final GenerationJobClient generationJobClient;
    private final MediaGarbageCollectionJobRunner mediaGarbageCollectionJobRunner;
    private final ObjectMapper objectMapper;

    public MediaGarbageCollectionService(
            CmsBridgeClient cmsBridgeClient,
            GenerationJobClient generationJobClient,
            MediaGarbageCollectionJobRunner mediaGarbageCollectionJobRunner,
            ObjectMapper objectMapper) {
        this.cmsBridgeClient = cmsBridgeClient;
        this.generationJobClient = generationJobClient;
        this.mediaGarbageCollectionJobRunner = mediaGarbageCollectionJobRunner;
        this.objectMapper = objectMapper;
    }

    public MediaGarbageCollectionScanResponse scan(Long projectId, String environment, String bearerToken) {
        MediaGcScanResult result = cmsBridgeClient.scanMedia(projectId, environment, bearerToken);
        List<CmsMediaSummary> allMedia = result.media();
        CmsMediaReferenceScan refs = result.refs();
        Set<String> referencedIds = extractReferencedIds(refs);

        List<CmsMediaSummary> unreferenced = allMedia.stream()
                .filter(m -> !referencedIds.contains(m.id()))
                .filter(m -> !isReferencedByUrl(m.guid(), refs))
                .sorted(Comparator.comparing(CmsMediaSummary::uploadedAt).reversed())
                .toList();

        List<UnreferencedMediaItem> items = unreferenced.stream()
                .map(m -> new UnreferencedMediaItem(m.id(), m.guid(), m.title(), m.mimeType(), m.uploadedAt()))
                .toList();

        return new MediaGarbageCollectionScanResponse(
                environment, items, allMedia.size(), allMedia.size() - unreferenced.size(), unreferenced.size());
    }

    public GenerationJobSummary startDelete(Long projectId, String environment, List<String> mediaIds, Long actorId,
            String actorKeycloakSub, String bearerToken) {
        GenerationJobSummary job = generationJobClient.create(
                "media_garbage_collection_delete",
                toJson(Map.of("projectId", projectId, "environment", environment, "mediaIds", mediaIds)),
                bearerToken);

        mediaGarbageCollectionJobRunner.runDelete(
                job.id(), projectId, environment, mediaIds, actorId, actorKeycloakSub, bearerToken);
        return job;
    }

    /**
     * 正規表現ベースの一次判定(wp-image-Nクラス/Gutenbergブロックid属性/ギャラリーショートコード)
     * で拾えない参照(WordPressのメディアピッカーを経由しない手動貼り付け等)を補うための二次チェック。
     * 一次判定で「未参照」と残った候補のみに対して行うため、投稿全文への都度の部分文字列検索でも
     * コストは小さい(誤ってメディアを削除してしまう=データ消失のリスクを避けることを優先する)。
     */
    private boolean isReferencedByUrl(String guid, CmsMediaReferenceScan refs) {
        if (guid == null || guid.isBlank()) {
            return false;
        }
        for (CmsPostContentSummary post : refs.posts()) {
            String content = post.content();
            if (content != null && content.contains(guid)) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern WP_IMAGE_CLASS = Pattern.compile("wp-image-(\\d+)");
    private static final Pattern GUTENBERG_BLOCK = Pattern.compile(
            "<!--\\s*wp:(?:image|gallery|cover|media-text|file|audio|video)\\s*(\\{[^}]*\\})?\\s*/?-->");
    private static final Pattern BLOCK_ATTR_ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern GALLERY_SHORTCODE_IDS = Pattern.compile("\\[gallery[^\\]]*\\bids=\"([\\d,]+)\"");

    Set<String> extractReferencedIds(CmsMediaReferenceScan refs) {
        Set<String> ids = new HashSet<>();
        refs.settingsMediaIds().values().forEach(v -> addIfValid(ids, v));
        for (CmsPostContentSummary post : refs.posts()) {
            addIfValid(ids, post.thumbnailId());
            ids.addAll(extractContentReferences(post.content()));
        }
        return ids;
    }

    Set<String> extractContentReferences(String content) {
        Set<String> ids = new HashSet<>();
        if (content == null) {
            return ids;
        }
        Matcher wpImageMatcher = WP_IMAGE_CLASS.matcher(content);
        while (wpImageMatcher.find()) {
            ids.add(wpImageMatcher.group(1));
        }
        Matcher blockMatcher = GUTENBERG_BLOCK.matcher(content);
        while (blockMatcher.find()) {
            String attrs = blockMatcher.group(1);
            if (attrs != null) {
                Matcher idMatcher = BLOCK_ATTR_ID.matcher(attrs);
                if (idMatcher.find()) {
                    ids.add(idMatcher.group(1));
                }
            }
        }
        Matcher galleryMatcher = GALLERY_SHORTCODE_IDS.matcher(content);
        while (galleryMatcher.find()) {
            ids.addAll(List.of(galleryMatcher.group(1).split(",")));
        }
        return ids;
    }

    private void addIfValid(Set<String> ids, String value) {
        if (value != null && !value.isBlank() && !value.equals("0")) {
            ids.add(value);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
