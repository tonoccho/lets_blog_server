package com.letsblog.api.service;

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
import com.letsblog.api.dto.PostPublishCommand;
import com.letsblog.api.dto.PostPublishResponse;
import com.letsblog.api.markdown.MarkdownRenderer;
import com.letsblog.api.repository.PostRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Markdown記事の投稿パイプライン:
 * 画像リネーム・アップロード → Markdown内の画像参照差し替え → HTML変換 → カテゴリ/タグ解決 → WordPress投稿 → posts テーブル反映
 */
@Service
public class PostPublishService {

    private final SiteService siteService;
    private final CmsAdapterFactory cmsAdapterFactory;
    private final MarkdownRenderer markdownRenderer;
    private final PostRepository postRepository;
    private final PlantUmlEmbedService plantUmlEmbedService;
    private final CustomTagRenderService customTagRenderService;
    private final ProjectService projectService;

    public PostPublishService(SiteService siteService, CmsAdapterFactory cmsAdapterFactory,
                               MarkdownRenderer markdownRenderer, PostRepository postRepository,
                               PlantUmlEmbedService plantUmlEmbedService,
                               CustomTagRenderService customTagRenderService,
                               ProjectService projectService) {
        this.siteService = siteService;
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.markdownRenderer = markdownRenderer;
        this.postRepository = postRepository;
        this.plantUmlEmbedService = plantUmlEmbedService;
        this.customTagRenderService = customTagRenderService;
        this.projectService = projectService;
    }

    @AuditLog(action = AuditLogAction.POST_PUBLISHED, resourceType = "POST")
    @Transactional
    public PostPublishResponse publish(PostPublishCommand command) {
        Site site = siteService.getBySiteKey(command.siteKey());
        CmsCredentials credentials = siteService.getCredentials(command.siteKey());
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());

        Long projectId = projectService.findProjectIdBySiteId(site.getId());
        String markdown = customTagRenderService.render(command.markdown(), projectId);
        markdown = plantUmlEmbedService.embedDiagrams(credentials, markdown);
        ImageReplacementResult imageResult = replaceImageReferences(
                cmsAdapter, credentials, markdown, command.images(),
                command.slug(), command.title(), command.featuredImageFilename());
        String html = markdownRenderer.render(imageResult.markdown());

        List<String> categoryIds = cmsAdapter.resolveCategories(credentials, command.categories());
        List<String> tagIds = cmsAdapter.resolveTags(credentials, command.tags());

        PostContent content = new PostContent(
                command.title(),
                command.slug(),
                html,
                command.status() == null ? "draft" : command.status(),
                categoryIds,
                tagIds,
                imageResult.featuredMediaId()
        );

        PostResult result = cmsAdapter.createOrUpdatePost(credentials, content, command.wpPostId());

        upsertPostRecord(site.getId(), result, command.slug());

        return new PostPublishResponse(result.id(), result.link(), result.status());
    }

    private ImageReplacementResult replaceImageReferences(
            CmsAdapter cmsAdapter, CmsCredentials credentials, String markdown, List<MultipartFile> images,
            String slug, String title, String featuredImageFilename) {
        if (images == null || images.isEmpty()) {
            return new ImageReplacementResult(markdown, null);
        }

        String finalSlug = generateSlugForFilename(slug, title);
        String rewritten = markdown;
        Map<String, String> filenameToUrl = new LinkedHashMap<>();
        String featuredMediaId = null;

        for (int i = 0; i < images.size(); i++) {
            MultipartFile image = images.get(i);
            String originalFilename = image.getOriginalFilename();
            if (originalFilename == null || originalFilename.isBlank()) {
                continue;
            }
            String renamedFilename = renameImageFile(originalFilename, finalSlug, i + 1);
            try {
                MediaUploadResult uploaded = cmsAdapter.uploadMedia(
                        credentials, renamedFilename, image.getContentType(), image.getBytes());
                filenameToUrl.put(originalFilename, uploaded.url());
                if (featuredImageFilename != null && featuredImageFilename.equals(originalFilename)) {
                    featuredMediaId = uploaded.id();
                }
            } catch (IOException e) {
                throw new CmsApiException("画像 '" + renamedFilename + "' のアップロードに失敗しました", e);
            }
        }

        for (Map.Entry<String, String> entry : filenameToUrl.entrySet()) {
            rewritten = rewritten.replace(entry.getKey(), entry.getValue());
        }
        return new ImageReplacementResult(rewritten, featuredMediaId);
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

    private void upsertPostRecord(Long siteId, PostResult result, String slug) {
        Post post = postRepository.findBySiteIdAndWpPostId(siteId, result.id())
                .orElseGet(Post::new);

        post.setSiteId(siteId);
        post.setWpPostId(result.id());
        post.setSlug(slug);
        post.setStatus(result.status());
        post.setLastPublishedAt(LocalDateTime.now());

        postRepository.save(post);
    }

    private record ImageReplacementResult(String markdown, String featuredMediaId) {
    }
}
