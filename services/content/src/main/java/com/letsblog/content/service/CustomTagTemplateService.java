package com.letsblog.content.service;

import com.letsblog.content.aop.AuditLog;
import com.letsblog.content.domain.AuditLogAction;
import com.letsblog.content.domain.CustomTagTemplate;
import com.letsblog.content.dto.CloneCustomTagTemplateRequest;
import com.letsblog.content.dto.CustomTagTemplateRequest;
import com.letsblog.content.dto.CustomTagTemplateResponse;
import com.letsblog.content.repository.CustomTagTemplateRepository;
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

    /**
     * issue #1220: 認可チェックが無く、非メンバーがprojectIdをidから逆引きするだけで他プロジェクトの
     * 未公開テンプレートを読めていた。{@link #list}/{@link #buildCssBundle}(issue #1057)と同じ方針
     * (isPublished=false かつ projectId 指定時のみプロジェクトメンバー判定)を適用する。公開済み
     * テンプレートは projectId を問わず誰でも参照できる既存の挙動を変えない。projectId 未指定
     * (グローバルテンプレート)は他メソッドと同じ既存の規約でプロジェクト単位の判定対象がそもそも
     * 無いため、未公開でもプロジェクトメンバー判定は経由しない。
     */
    @Transactional(readOnly = true)
    public CustomTagTemplateResponse getById(Long id) {
        CustomTagTemplate template = customTagTemplateRepository.findById(id)
                .orElseThrow(() -> new CustomTagTemplateNotFoundException("id " + id + " のテンプレートは登録されていません"));
        if (!Boolean.TRUE.equals(template.getIsPublished()) && template.getProjectId() != null) {
            adminAuthorizationService.requireProjectMemberOrAdmin(template.getProjectId());
        }
        return CustomTagTemplateResponse.from(template);
    }

    @Transactional(readOnly = true)
    public List<CustomTagTemplateResponse> listPublished(Long projectId) {
        List<CustomTagTemplate> templates = projectId == null
                ? customTagTemplateRepository.findPublishedGlobalTemplates()
                : customTagTemplateRepository.findPublishedTemplatesByProject(projectId);
        return templates.stream().map(CustomTagTemplateResponse::from).toList();
    }

    /**
     * {@code showAll=true}経路。公開状態を問わず全件返す({@code findAllTemplatesByProject}は
     * {@code isPublished}を条件にしない)。projectId指定時はプロジェクトメンバーまたはadminのみに
     * 絞る(issue #1057)。以前はここに認可チェックが無く、非メンバーでもprojectIdを指定するだけで
     * 他プロジェクトの未公開テンプレートまで読めていた。
     *
     * <p>projectIdがnullの場合はグローバルテンプレートのみが対象で、プロジェクト単位の判定対象が
     * そもそも存在しないためプロジェクトメンバー判定は行わない({@link #listPublished}/
     * {@link #searchByKeyword}/{@link #filterByCategory}と同じ、projectId=nullを「グローバル」
     * として扱う既存の規約)。
     */
    @Transactional(readOnly = true)
    public List<CustomTagTemplateResponse> list(Long projectId) {
        if (projectId != null) {
            adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        }
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
