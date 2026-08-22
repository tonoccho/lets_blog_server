package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.CustomTagTemplate;
import com.letsblog.api.dto.CloneCustomTagTemplateRequest;
import com.letsblog.api.dto.CustomTagTemplateRequest;
import com.letsblog.api.dto.CustomTagTemplateResponse;
import com.letsblog.api.repository.CustomTagTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CustomTagTemplateService {

    private final CustomTagTemplateRepository customTagTemplateRepository;
    private final CurrentActorService currentActorService;
    private final AdminAuthorizationService adminAuthorizationService;

    public CustomTagTemplateService(
            CustomTagTemplateRepository customTagTemplateRepository,
            CurrentActorService currentActorService,
            AdminAuthorizationService adminAuthorizationService) {
        this.customTagTemplateRepository = customTagTemplateRepository;
        this.currentActorService = currentActorService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @AuditLog(action = AuditLogAction.CUSTOM_TAG_CREATED, resourceType = "CUSTOM_TAG_TEMPLATE")
    @Transactional
    public CustomTagTemplateResponse create(CustomTagTemplateRequest request) {
        adminAuthorizationService.requireAdmin();

        CustomTagTemplate template = new CustomTagTemplate();
        template.setTemplateName(request.templateName());
        template.setDescription(request.description());
        template.setCategory(request.category());
        template.setHtmlTemplate(request.htmlTemplate());
        template.setCssContent(request.cssContent());
        template.setProjectId(request.projectId());
        template.setOriginalTagId(request.originalTagId());
        template.setCreatedBy(currentActorService.getCurrentActorId());
        template.setVersion(1);
        template.setIsPublished(false);

        return CustomTagTemplateResponse.from(customTagTemplateRepository.save(template));
    }

    @AuditLog(action = AuditLogAction.CUSTOM_TAG_UPDATED, resourceType = "CUSTOM_TAG_TEMPLATE")
    @Transactional
    public CustomTagTemplateResponse update(Long id, CustomTagTemplateRequest request) {
        adminAuthorizationService.requireAdmin();

        CustomTagTemplate template = customTagTemplateRepository.findById(id)
                .orElseThrow(() -> new CustomTagTemplateNotFoundException("id " + id + " のテンプレートは登録されていません"));

        template.setTemplateName(request.templateName());
        template.setDescription(request.description());
        template.setCategory(request.category());
        template.setHtmlTemplate(request.htmlTemplate());
        template.setCssContent(request.cssContent());

        return CustomTagTemplateResponse.from(customTagTemplateRepository.save(template));
    }

    @AuditLog(action = AuditLogAction.CUSTOM_TAG_UPDATED, resourceType = "CUSTOM_TAG_TEMPLATE")
    @Transactional
    public CustomTagTemplateResponse publish(Long id) {
        adminAuthorizationService.requireAdmin();

        CustomTagTemplate template = customTagTemplateRepository.findById(id)
                .orElseThrow(() -> new CustomTagTemplateNotFoundException("id " + id + " のテンプレートは登録されていません"));

        template.setIsPublished(true);

        return CustomTagTemplateResponse.from(customTagTemplateRepository.save(template));
    }

    @AuditLog(action = AuditLogAction.CUSTOM_TAG_UPDATED, resourceType = "CUSTOM_TAG_TEMPLATE")
    @Transactional
    public CustomTagTemplateResponse unpublish(Long id) {
        adminAuthorizationService.requireAdmin();

        CustomTagTemplate template = customTagTemplateRepository.findById(id)
                .orElseThrow(() -> new CustomTagTemplateNotFoundException("id " + id + " のテンプレートは登録されていません"));

        template.setIsPublished(false);

        return CustomTagTemplateResponse.from(customTagTemplateRepository.save(template));
    }

    @AuditLog(action = AuditLogAction.CUSTOM_TAG_CREATED, resourceType = "CUSTOM_TAG_TEMPLATE")
    @Transactional
    public CustomTagTemplateResponse clone(Long id, CloneCustomTagTemplateRequest request) {
        adminAuthorizationService.requireAdmin();

        CustomTagTemplate original = customTagTemplateRepository.findById(id)
                .orElseThrow(() -> new CustomTagTemplateNotFoundException("id " + id + " のテンプレートは登録されていません"));

        CustomTagTemplate cloned = new CustomTagTemplate();
        cloned.setTemplateName(request.newTemplateName());
        cloned.setDescription(request.description() != null ? request.description() : original.getDescription());
        cloned.setCategory(request.category() != null ? request.category() : original.getCategory());
        cloned.setHtmlTemplate(original.getHtmlTemplate());
        cloned.setCssContent(original.getCssContent());
        cloned.setVersion(1);
        cloned.setIsPublished(false);
        cloned.setProjectId(request.projectId() != null ? request.projectId() : original.getProjectId());
        cloned.setCreatedBy(currentActorService.getCurrentActorId());

        return CustomTagTemplateResponse.from(customTagTemplateRepository.save(cloned));
    }

    @AuditLog(action = AuditLogAction.CUSTOM_TAG_DELETED, resourceType = "CUSTOM_TAG_TEMPLATE")
    @Transactional
    public void delete(Long id) {
        adminAuthorizationService.requireAdmin();

        if (!customTagTemplateRepository.existsById(id)) {
            throw new CustomTagTemplateNotFoundException("id " + id + " のテンプレートは登録されていません");
        }

        customTagTemplateRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public CustomTagTemplateResponse getById(Long id) {
        CustomTagTemplate template = customTagTemplateRepository.findById(id)
                .orElseThrow(() -> new CustomTagTemplateNotFoundException("id " + id + " のテンプレートは登録されていません"));
        return CustomTagTemplateResponse.from(template);
    }

    @Transactional(readOnly = true)
    public List<CustomTagTemplateResponse> listPublished(Long projectId) {
        List<CustomTagTemplate> templates = projectId == null
                ? customTagTemplateRepository.findPublishedGlobalTemplates()
                : customTagTemplateRepository.findPublishedTemplatesByProject(projectId);
        return templates.stream().map(CustomTagTemplateResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<CustomTagTemplateResponse> list(Long projectId) {
        List<CustomTagTemplate> templates = projectId == null
                ? customTagTemplateRepository.findGlobalTemplates()
                : customTagTemplateRepository.findAllTemplatesByProject(projectId);
        return templates.stream().map(CustomTagTemplateResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<CustomTagTemplateResponse> searchByKeyword(String keyword, Long projectId) {
        List<CustomTagTemplate> templates = projectId == null
                ? customTagTemplateRepository.searchByKeyword(keyword)
                : customTagTemplateRepository.searchByKeywordAndProject(keyword, projectId);
        return templates.stream().map(CustomTagTemplateResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<CustomTagTemplateResponse> filterByCategory(String category, Long projectId) {
        List<CustomTagTemplate> templates = projectId == null
                ? customTagTemplateRepository.findByCategory(category)
                : customTagTemplateRepository.findByCategoryAndProject(category, projectId);
        return templates.stream().map(CustomTagTemplateResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<CustomTagTemplateResponse> getMyTemplates() {
        Long userId = currentActorService.getCurrentActorId();
        List<CustomTagTemplate> templates = customTagTemplateRepository.findByCreatedBy(userId);
        return templates.stream().map(CustomTagTemplateResponse::from).toList();
    }
}
