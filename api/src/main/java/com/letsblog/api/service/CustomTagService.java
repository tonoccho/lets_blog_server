package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.CustomTag;
import com.letsblog.api.domain.CustomTagFormat;
import com.letsblog.api.domain.EmbedTagType;
import com.letsblog.api.dto.CustomTagRequest;
import com.letsblog.api.dto.CustomTagResponse;
import com.letsblog.api.dto.TagDesignColors;
import com.letsblog.api.repository.CustomTagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CustomTagService {

    private final CustomTagRepository customTagRepository;
    private final AdminAuthorizationService adminAuthorizationService;
    private final TagDesignSettingService tagDesignSettingService;
    private final TocStyleRenderService tocStyleRenderService;
    private final BlogCardTagRenderService blogCardTagRenderService;
    private final AmazonTagRenderService amazonTagRenderService;

    public CustomTagService(CustomTagRepository customTagRepository,
                             AdminAuthorizationService adminAuthorizationService,
                             TagDesignSettingService tagDesignSettingService,
                             TocStyleRenderService tocStyleRenderService,
                             BlogCardTagRenderService blogCardTagRenderService,
                             AmazonTagRenderService amazonTagRenderService) {
        this.customTagRepository = customTagRepository;
        this.adminAuthorizationService = adminAuthorizationService;
        this.tagDesignSettingService = tagDesignSettingService;
        this.tocStyleRenderService = tocStyleRenderService;
        this.blogCardTagRenderService = blogCardTagRenderService;
        this.amazonTagRenderService = amazonTagRenderService;
    }

    @AuditLog(action = AuditLogAction.CUSTOM_TAG_CREATED, resourceType = "CUSTOM_TAG")
    @Transactional
    public CustomTagResponse create(CustomTagRequest request) {
        adminAuthorizationService.requireAdmin();

        Long projectId = request.projectId();
        findDuplicate(request.tagName(), projectId).ifPresent(existing -> {
            throw new IllegalArgumentException("タグ名 '" + request.tagName() + "' は既に登録されています");
        });

        CustomTag tag = new CustomTag();
        tag.setTagName(request.tagName());
        tag.setHtmlTemplate(request.htmlTemplate());
        tag.setDescription(request.description());
        tag.setCssContent(request.cssContent());
        tag.setTagFormat(request.tagFormat() != null ? request.tagFormat() : CustomTagFormat.BLOCK);
        tag.setProjectId(projectId);

        return CustomTagResponse.from(customTagRepository.save(tag));
    }

    @AuditLog(action = AuditLogAction.CUSTOM_TAG_UPDATED, resourceType = "CUSTOM_TAG")
    @Transactional
    public CustomTagResponse update(Long id, CustomTagRequest request) {
        adminAuthorizationService.requireAdmin();

        CustomTag tag = customTagRepository.findById(id)
                .orElseThrow(() -> new CustomTagNotFoundException("id " + id + " のカスタムタグは登録されていません"));

        // projectIdの変更は許可しない(リクエストに含まれていても無視する)
        Long projectId = tag.getProjectId();
        findDuplicate(request.tagName(), projectId)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("タグ名 '" + request.tagName() + "' は既に登録されています");
                });

        tag.setTagName(request.tagName());
        tag.setHtmlTemplate(request.htmlTemplate());
        tag.setDescription(request.description());
        tag.setCssContent(request.cssContent());
        tag.setTagFormat(request.tagFormat() != null ? request.tagFormat() : CustomTagFormat.BLOCK);

        return CustomTagResponse.from(customTagRepository.save(tag));
    }

    @AuditLog(action = AuditLogAction.CUSTOM_TAG_DELETED, resourceType = "CUSTOM_TAG")
    @Transactional
    public void delete(Long id) {
        adminAuthorizationService.requireAdmin();

        if (!customTagRepository.existsById(id)) {
            throw new CustomTagNotFoundException("id " + id + " のカスタムタグは登録されていません");
        }
        customTagRepository.deleteById(id);
    }

    /**
     * projectId が null ならグローバルタグのみ、指定時はそのプロジェクトのタグ + グローバルタグを返す。
     */
    @Transactional(readOnly = true)
    public List<CustomTagResponse> list(Long projectId) {
        List<CustomTag> tags = projectId == null
                ? customTagRepository.findByProjectIdIsNull()
                : customTagRepository.findByProjectIdOrProjectIdIsNull(projectId);
        return tags.stream().map(CustomTagResponse::from).toList();
    }

    /**
     * WordPressへ一括貼り付けするための統合CSS。list()と同じスコープ規約(projectId未指定=グローバルのみ、
     * 指定時はそのプロジェクト+グローバル)で、cssContentが空でないタグのみを連結する。
     * テーマCSSは含めない(WordPress側に既存のため重複・競合の原因になるため)。
     * projectIdが指定されている場合、そのプロジェクトの[toc]/[blogcard]/[amazon]組み込みタグの
     * デザインCSSも先頭に含める(組み込みタグのデザインはプロジェクト単位のためprojectId未指定時は対象外)。
     */
    @Transactional(readOnly = true)
    public String buildCssBundle(Long projectId) {
        List<CustomTag> tags = projectId == null
                ? customTagRepository.findByProjectIdIsNull()
                : customTagRepository.findByProjectIdOrProjectIdIsNull(projectId);
        String embedTagCss = projectId == null ? "" : buildEmbedTagCss(projectId);
        return embedTagCss + buildCssFrom(tags);
    }

    /**
     * プロジェクト詳細のカスタムタグ画面向け。グローバルタグを含めず、指定プロジェクトのタグのみを返す。
     */
    @Transactional(readOnly = true)
    public List<CustomTagResponse> listByProject(Long projectId) {
        return customTagRepository.findByProjectId(projectId).stream().map(CustomTagResponse::from).toList();
    }

    /**
     * プロジェクト詳細/プロジェクト一覧向けの統合CSS。buildCssBundle()と異なりグローバルタグは含めず、
     * 指定プロジェクトのタグのみを連結する。プロジェクトの[toc]/[blogcard]/[amazon]組み込みタグの
     * デザインCSSも先頭に含める。
     */
    @Transactional(readOnly = true)
    public String buildProjectCssBundle(Long projectId) {
        return buildEmbedTagCss(projectId) + buildCssFrom(customTagRepository.findByProjectId(projectId));
    }

    /**
     * プロジェクトの[toc]/[blogcard]/[amazon]組み込みタグのデザインCSSを連結する。
     * 記事本文への注入(TocStyleRenderService等)と同じbuildStyle()を再利用し、
     * デザイン未保存のタグ種別もDesignPreset.DEFAULTのCSSとして含める。
     */
    private String buildEmbedTagCss(Long projectId) {
        StringBuilder sb = new StringBuilder();
        for (EmbedTagType tagType : EmbedTagType.values()) {
            TagDesignColors colors = tagDesignSettingService.resolveColors(projectId, tagType);
            String style = switch (tagType) {
                case TOC -> tocStyleRenderService.buildStyle(colors);
                case BLOGCARD -> blogCardTagRenderService.buildStyle(colors);
                case AMAZON -> amazonTagRenderService.buildStyle(colors);
            };
            sb.append("/* === ").append(tagType.name().toLowerCase()).append(" (組み込みタグ) === */\n");
            sb.append(style).append("\n\n");
        }
        return sb.toString();
    }

    private String buildCssFrom(List<CustomTag> tags) {
        StringBuilder sb = new StringBuilder();
        for (CustomTag tag : tags) {
            if (tag.getCssContent() == null || tag.getCssContent().isBlank()) {
                continue;
            }
            sb.append("/* === ").append(tag.getTagName()).append(" === */\n");
            sb.append(tag.getCssContent().strip()).append("\n\n");
        }
        return sb.toString();
    }

    private Optional<CustomTag> findDuplicate(String tagName, Long projectId) {
        return projectId == null
                ? customTagRepository.findByTagNameAndProjectIdIsNull(tagName)
                : customTagRepository.findByTagNameAndProjectId(tagName, projectId);
    }
}
