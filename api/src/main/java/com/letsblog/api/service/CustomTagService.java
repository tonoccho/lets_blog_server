package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.CustomTag;
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

    private Optional<CustomTag> findDuplicate(String tagName, Long projectId) {
        return projectId == null
                ? customTagRepository.findByTagNameAndProjectIdIsNull(tagName)
                : customTagRepository.findByTagNameAndProjectId(tagName, projectId);
    }
}
