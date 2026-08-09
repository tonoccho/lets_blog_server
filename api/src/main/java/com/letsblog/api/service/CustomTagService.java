package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.CustomTag;
import com.letsblog.api.domain.CustomTagFormat;
import com.letsblog.api.dto.CustomTagRequest;
import com.letsblog.api.dto.CustomTagResponse;
import com.letsblog.api.repository.CustomTagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CustomTagService {

    private final CustomTagRepository customTagRepository;
    private final AdminAuthorizationService adminAuthorizationService;

    public CustomTagService(CustomTagRepository customTagRepository,
                             AdminAuthorizationService adminAuthorizationService) {
        this.customTagRepository = customTagRepository;
        this.adminAuthorizationService = adminAuthorizationService;
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
     */
    @Transactional(readOnly = true)
    public String buildCssBundle(Long projectId) {
        List<CustomTag> tags = projectId == null
                ? customTagRepository.findByProjectIdIsNull()
                : customTagRepository.findByProjectIdOrProjectIdIsNull(projectId);
        return buildCssFrom(tags);
    }

    /**
     * プロジェクト詳細のカスタムタグ画面向け。グローバルタグを含めず、指定プロジェクトのタグのみを返す。
     */
    @Transactional(readOnly = true)
    public List<CustomTagResponse> listByProject(Long projectId) {
        return customTagRepository.findByProjectId(projectId).stream().map(CustomTagResponse::from).toList();
    }

    /**
     * プロジェクト詳細向けの統合CSS。buildCssBundle()と異なりグローバルタグは含めず、
     * 指定プロジェクトのタグのみを連結する。
     */
    @Transactional(readOnly = true)
    public String buildProjectCssBundle(Long projectId) {
        return buildCssFrom(customTagRepository.findByProjectId(projectId));
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
