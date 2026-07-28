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

        if (customTagRepository.existsByTagName(request.tagName())) {
            throw new IllegalArgumentException("タグ名 '" + request.tagName() + "' は既に登録されています");
        }

        CustomTag tag = new CustomTag();
        tag.setTagName(request.tagName());
        tag.setHtmlTemplate(request.htmlTemplate());
        tag.setDescription(request.description());
        tag.setCssContent(request.cssContent());

        return CustomTagResponse.from(customTagRepository.save(tag));
    }

    @AuditLog(action = AuditLogAction.CUSTOM_TAG_UPDATED, resourceType = "CUSTOM_TAG")
    @Transactional
    public CustomTagResponse update(Long id, CustomTagRequest request) {
        adminAuthorizationService.requireAdmin();

        CustomTag tag = customTagRepository.findById(id)
                .orElseThrow(() -> new CustomTagNotFoundException("id " + id + " のカスタムタグは登録されていません"));

        customTagRepository.findByTagName(request.tagName())
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

    @Transactional(readOnly = true)
    public List<CustomTagResponse> list() {
        return customTagRepository.findAll().stream().map(CustomTagResponse::from).toList();
    }
}
