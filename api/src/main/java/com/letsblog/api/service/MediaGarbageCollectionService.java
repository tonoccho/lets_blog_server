package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsMediaReferenceScan;
import com.letsblog.api.cms.CmsMediaSummary;
import com.letsblog.api.cms.CmsPostContentSummary;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.GenerationJobResponse;
import com.letsblog.api.dto.MediaGarbageCollectionScanResponse;
import com.letsblog.api.dto.UnreferencedMediaItem;
import com.letsblog.api.repository.GenerationJobRepository;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * プロジェクトが持つ環境(local/test/production)のWordPressサイトから、投稿本文・アイキャッチ・
 * 主要サイト設定のいずれからも参照されていないメディアを検出し、選択削除する(issue #500)。
 * 検出(scan)はそのサイトへの読み取りアクセスのみで完結するため同期実行、削除(startDelete)は
 * 件数次第で時間がかかりうるため{@link GenerationJob}+{@link MediaGarbageCollectionJobRunner}の
 * 非同期ジョブとして実行しフロントエンドがポーリングで進捗を追う({@link ComfyUiModelService}と同じ構成)。
 */
@Service
public class MediaGarbageCollectionService {

    private final ProjectRepository projectRepository;
    private final SiteRepository siteRepository;
    private final SiteService siteService;
    private final CmsAdapterFactory cmsAdapterFactory;
    private final GenerationJobRepository generationJobRepository;
    private final MediaGarbageCollectionJobRunner mediaGarbageCollectionJobRunner;
    private final ObjectMapper objectMapper;

    public MediaGarbageCollectionService(
            ProjectRepository projectRepository,
            SiteRepository siteRepository,
            SiteService siteService,
            CmsAdapterFactory cmsAdapterFactory,
            GenerationJobRepository generationJobRepository,
            MediaGarbageCollectionJobRunner mediaGarbageCollectionJobRunner,
            ObjectMapper objectMapper) {
        this.projectRepository = projectRepository;
        this.siteRepository = siteRepository;
        this.siteService = siteService;
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.generationJobRepository = generationJobRepository;
        this.mediaGarbageCollectionJobRunner = mediaGarbageCollectionJobRunner;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public MediaGarbageCollectionScanResponse scan(Long projectId, String environment) {
        Project project = getProject(projectId);
        Site site = resolveSite(project, environment);
        CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
        CmsAdapter adapter = cmsAdapterFactory.resolve(credentials.cmsType());

        List<CmsMediaSummary> allMedia = adapter.listMedia(credentials);
        CmsMediaReferenceScan refs = adapter.scanMediaReferences(credentials);
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

    @Transactional
    public GenerationJobResponse startDelete(Long projectId, String environment, List<String> mediaIds, Long actorId) {
        Project project = getProject(projectId);
        Site site = resolveSite(project, environment);

        GenerationJob job = new GenerationJob();
        job.setType("media_garbage_collection_delete");
        job.setStatus("running");
        job.setRequestPayload(toJson(Map.of(
                "projectId", projectId, "environment", environment, "mediaIds", mediaIds)));
        generationJobRepository.save(job);

        mediaGarbageCollectionJobRunner.runDelete(job.getId(), site.getId(), environment, projectId, mediaIds, actorId);
        return toResponse(job);
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

    private Site resolveSite(Project project, String environment) {
        Long siteId = switch (environment) {
            case "local" -> project.getLocalSiteId();
            case "test" -> project.getTestSiteId();
            case "production" -> project.getProductionSiteId();
            default -> throw new IllegalArgumentException("不正な環境です: " + environment);
        };
        if (siteId == null) {
            throw new IllegalArgumentException("環境 '" + environment + "' にはサイトが設定されていません");
        }
        return siteRepository.findById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("環境 '" + environment + "' にはサイトが設定されていません"));
    }

    private Project getProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }

    private GenerationJobResponse toResponse(GenerationJob job) {
        return new GenerationJobResponse(
                job.getId(), job.getType(), job.getStatus(), job.getCreatedAt(), job.getUpdatedAt());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
