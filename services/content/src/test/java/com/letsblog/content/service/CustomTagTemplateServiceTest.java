package com.letsblog.content.service;

import com.letsblog.content.domain.CustomTag;
import com.letsblog.content.domain.CustomTagFormat;
import com.letsblog.content.domain.CustomTagTemplate;
import com.letsblog.content.dto.ApplyCustomTagTemplateRequest;
import com.letsblog.content.dto.CustomTagResponse;
import com.letsblog.content.dto.ValidationResult;
import com.letsblog.content.repository.CustomTagRepository;
import com.letsblog.content.dto.CloneCustomTagTemplateRequest;
import com.letsblog.content.dto.CustomTagTemplateRequest;
import com.letsblog.content.dto.CustomTagTemplateResponse;
import com.letsblog.content.repository.CustomTagTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #656: #641のレビューで、CustomTag*サービス群のうちCustomTagTemplateServiceが未テストの
 * まま残っていたことを受けて追加。主な責務(作成・公開/非公開・複製・一覧/検索/フィルタ)の主経路を検証する。
 */
@ExtendWith(MockitoExtension.class)
class CustomTagTemplateServiceTest {

    @Mock
    private CustomTagTemplateRepository customTagTemplateRepository;
    @Mock
    private CustomTagRepository customTagRepository;
    @Mock
    private CustomTagValidationService customTagValidationService;
    @Mock
    private CurrentActorService currentActorService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private CustomTagTemplateService service;

    @BeforeEach
    void setUp() {
        service = new CustomTagTemplateService(
                customTagTemplateRepository, customTagRepository, customTagValidationService,
                currentActorService, adminAuthorizationService);
    }

    private CustomTagTemplateRequest buildRequest() {
        return new CustomTagTemplateRequest(
                "note-template", "desc", "alert", "<div>{{content}}</div>", ".note{color:red;}", null, null);
    }

    private CustomTagTemplate buildTemplate(Long id) {
        CustomTagTemplate template = new CustomTagTemplate();
        template.setId(id);
        template.setTemplateName("note-template");
        template.setVersion(1);
        template.setIsPublished(false);
        return template;
    }

    @Test
    void create_バージョン1未公開の新規テンプレートとして保存する() {
        when(currentActorService.getCurrentActorId()).thenReturn(7L);
        when(customTagTemplateRepository.save(any(CustomTagTemplate.class))).thenAnswer(invocation -> {
            CustomTagTemplate saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        CustomTagTemplateResponse response = service.create(buildRequest());

        assertEquals(1L, response.id());
        assertEquals("note-template", response.templateName());
        assertEquals(1, response.version());
        assertFalse(response.isPublished());
        assertEquals(7L, response.createdBy());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void update_存在するテンプレートを更新する() {
        CustomTagTemplate existing = buildTemplate(1L);
        when(customTagTemplateRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(customTagTemplateRepository.save(any(CustomTagTemplate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomTagTemplateResponse response = service.update(1L, buildRequest());

        assertEquals("note-template", response.templateName());
    }

    @Test
    void update_存在しないidはCustomTagTemplateNotFoundException() {
        when(customTagTemplateRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(CustomTagTemplateNotFoundException.class, () -> service.update(99L, buildRequest()));
    }

    @Test
    void publish_isPublishedをtrueにする() {
        CustomTagTemplate existing = buildTemplate(1L);
        when(customTagTemplateRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(customTagTemplateRepository.save(any(CustomTagTemplate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomTagTemplateResponse response = service.publish(1L);

        assertTrue(response.isPublished());
    }

    @Test
    void publish_存在しないidはCustomTagTemplateNotFoundException() {
        when(customTagTemplateRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(CustomTagTemplateNotFoundException.class, () -> service.publish(99L));
    }

    @Test
    void unpublish_isPublishedをfalseにする() {
        CustomTagTemplate existing = buildTemplate(1L);
        existing.setIsPublished(true);
        when(customTagTemplateRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(customTagTemplateRepository.save(any(CustomTagTemplate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomTagTemplateResponse response = service.unpublish(1L);

        assertFalse(response.isPublished());
    }

    @Test
    void clone_元テンプレートのHTML_CSSを引き継ぎバージョン1未公開の新規テンプレートを作る() {
        CustomTagTemplate original = buildTemplate(1L);
        original.setHtmlTemplate("<div>{{content}}</div>");
        original.setCssContent(".note{color:red;}");
        original.setDescription("元の説明");
        original.setCategory("alert");
        original.setProjectId(5L);
        original.setVersion(3);
        original.setIsPublished(true);
        when(customTagTemplateRepository.findById(1L)).thenReturn(Optional.of(original));
        when(currentActorService.getCurrentActorId()).thenReturn(9L);
        when(customTagTemplateRepository.save(any(CustomTagTemplate.class))).thenAnswer(invocation -> {
            CustomTagTemplate saved = invocation.getArgument(0);
            saved.setId(2L);
            return saved;
        });

        CloneCustomTagTemplateRequest request = new CloneCustomTagTemplateRequest("cloned-template", null, null, null);
        CustomTagTemplateResponse response = service.clone(1L, request);

        assertEquals("cloned-template", response.templateName());
        assertEquals("<div>{{content}}</div>", response.htmlTemplate());
        assertEquals(".note{color:red;}", response.cssContent());
        assertEquals("元の説明", response.description());
        assertEquals("alert", response.category());
        assertEquals(5L, response.projectId());
        assertEquals(1, response.version());
        assertFalse(response.isPublished());
        assertEquals(9L, response.createdBy());
    }

    @Test
    void clone_リクエストで指定された値は元テンプレートより優先される() {
        CustomTagTemplate original = buildTemplate(1L);
        original.setDescription("元の説明");
        original.setCategory("alert");
        original.setProjectId(5L);
        when(customTagTemplateRepository.findById(1L)).thenReturn(Optional.of(original));
        when(currentActorService.getCurrentActorId()).thenReturn(9L);
        when(customTagTemplateRepository.save(any(CustomTagTemplate.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CloneCustomTagTemplateRequest request =
                new CloneCustomTagTemplateRequest("cloned-template", "新しい説明", "info", 8L);
        CustomTagTemplateResponse response = service.clone(1L, request);

        assertEquals("新しい説明", response.description());
        assertEquals("info", response.category());
        assertEquals(8L, response.projectId());
    }

    @Test
    void clone_存在しないidはCustomTagTemplateNotFoundException() {
        when(customTagTemplateRepository.findById(99L)).thenReturn(Optional.empty());

        CloneCustomTagTemplateRequest request = new CloneCustomTagTemplateRequest("cloned", null, null, null);
        assertThrows(CustomTagTemplateNotFoundException.class, () -> service.clone(99L, request));
    }

    @Test
    void delete_存在するテンプレートを削除する() {
        when(customTagTemplateRepository.existsById(1L)).thenReturn(true);

        service.delete(1L);

        verify(customTagTemplateRepository).deleteById(1L);
    }

    @Test
    void delete_存在しないidはCustomTagTemplateNotFoundException() {
        when(customTagTemplateRepository.existsById(99L)).thenReturn(false);

        assertThrows(CustomTagTemplateNotFoundException.class, () -> service.delete(99L));
        verify(customTagTemplateRepository, never()).deleteById(any());
    }

    @Test
    void getById_存在するテンプレートを返す() {
        when(customTagTemplateRepository.findById(1L)).thenReturn(Optional.of(buildTemplate(1L)));

        CustomTagTemplateResponse response = service.getById(1L);

        assertEquals(1L, response.id());
    }

    @Test
    void getById_存在しないidはCustomTagTemplateNotFoundException() {
        when(customTagTemplateRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(CustomTagTemplateNotFoundException.class, () -> service.getById(99L));
    }

    /**
     * issue #1220: GET /api/custom-tag-templates/{id} には認可チェックが無く、非メンバーが
     * 他プロジェクトの未公開テンプレートを読めていた。#1057と同じ方針
     * (isPublished=false かつ projectId 指定時のみプロジェクトメンバー判定)を適用する。
     */
    @Test
    void getById_非公開かつprojectId指定時はプロジェクトメンバー判定を行う() {
        CustomTagTemplate template = buildTemplate(1L);
        template.setIsPublished(false);
        template.setProjectId(5L);
        when(customTagTemplateRepository.findById(1L)).thenReturn(Optional.of(template));

        service.getById(1L);

        verify(adminAuthorizationService).requireProjectMemberOrAdmin(5L);
    }

    @Test
    void getById_非公開テンプレートで他プロジェクトのメンバーでなければForbiddenExceptionが伝播する() {
        CustomTagTemplate template = buildTemplate(1L);
        template.setIsPublished(false);
        template.setProjectId(5L);
        when(customTagTemplateRepository.findById(1L)).thenReturn(Optional.of(template));
        doThrow(new ForbiddenException("not a member"))
                .when(adminAuthorizationService).requireProjectMemberOrAdmin(5L);

        assertThrows(ForbiddenException.class, () -> service.getById(1L));
    }

    @Test
    void getById_公開済みならプロジェクトメンバー判定を行わない() {
        CustomTagTemplate template = buildTemplate(1L);
        template.setIsPublished(true);
        template.setProjectId(5L);
        when(customTagTemplateRepository.findById(1L)).thenReturn(Optional.of(template));

        CustomTagTemplateResponse response = service.getById(1L);

        assertTrue(response.isPublished());
        verify(adminAuthorizationService, never()).requireProjectMemberOrAdmin(any());
    }

    /**
     * projectId未指定(グローバルテンプレート)は list()/listPublished() 等と同じ既存の規約
     * (projectId=nullは「グローバル」としてプロジェクト単位の判定対象がそもそも無い)ため、
     * 未公開でもプロジェクトメンバー判定は経由しない。
     */
    @Test
    void getById_非公開でもprojectId未指定ならプロジェクトメンバー判定を行わない() {
        CustomTagTemplate template = buildTemplate(1L);
        template.setIsPublished(false);
        template.setProjectId(null);
        when(customTagTemplateRepository.findById(1L)).thenReturn(Optional.of(template));

        service.getById(1L);

        verify(adminAuthorizationService, never()).requireProjectMemberOrAdmin(any());
    }

    @Test
    void listPublished_projectId未指定ならグローバル公開テンプレートのみ() {
        when(customTagTemplateRepository.findPublishedGlobalTemplates()).thenReturn(List.of(buildTemplate(1L)));

        List<CustomTagTemplateResponse> result = service.listPublished(null);

        assertEquals(1, result.size());
    }

    @Test
    void listPublished_projectId指定時はプロジェクト固有とグローバルの公開テンプレート() {
        when(customTagTemplateRepository.findPublishedTemplatesByProject(5L))
                .thenReturn(List.of(buildTemplate(1L), buildTemplate(2L)));

        List<CustomTagTemplateResponse> result = service.listPublished(5L);

        assertEquals(2, result.size());
    }

    @Test
    void list_projectId未指定ならグローバルテンプレートのみ() {
        when(customTagTemplateRepository.findGlobalTemplates()).thenReturn(List.of(buildTemplate(1L)));

        List<CustomTagTemplateResponse> result = service.list(null);

        assertEquals(1, result.size());
    }

    @Test
    void list_projectId指定時はプロジェクトの全テンプレート() {
        when(customTagTemplateRepository.findAllTemplatesByProject(5L)).thenReturn(List.of(buildTemplate(1L)));

        List<CustomTagTemplateResponse> result = service.list(5L);

        assertEquals(1, result.size());
    }

    /**
     * issue #1057: GET /api/custom-tag-templates?projectId=&showAll=true は、公開状態を問わず
     * 全件返す(findAllTemplatesByProject は isPublished を条件にしない)にもかかわらず
     * プロジェクトメンバー判定が無く、非メンバーが他プロジェクトの未公開テンプレートを読めていた。
     * searchByKeyword/filterByCategoryは isPublished=true を条件にしたクエリのため対象外
     * (このIssueの対象は list() のみ)。
     */
    @Test
    void list_projectId指定時はプロジェクトメンバー判定を行う() {
        when(customTagTemplateRepository.findAllTemplatesByProject(5L)).thenReturn(List.of());

        service.list(5L);

        verify(adminAuthorizationService).requireProjectMemberOrAdmin(5L);
    }

    @Test
    void list_プロジェクトメンバーでなければForbiddenExceptionが伝播する() {
        doThrow(new ForbiddenException("not a member"))
                .when(adminAuthorizationService).requireProjectMemberOrAdmin(5L);

        assertThrows(ForbiddenException.class, () -> service.list(5L));
        verify(customTagTemplateRepository, never()).findAllTemplatesByProject(any());
    }

    /**
     * projectId 未指定(グローバルテンプレートのみ)はプロジェクト単位の判定対象がそもそも無いため、
     * requireProjectMemberOrAdmin は経由しない(list() の null 分岐は元から変更していない)。
     */
    @Test
    void list_projectId未指定ならプロジェクトメンバー判定を行わない() {
        when(customTagTemplateRepository.findGlobalTemplates()).thenReturn(List.of());

        service.list(null);

        verify(adminAuthorizationService, never()).requireProjectMemberOrAdmin(any());
    }

    @Test
    void searchByKeyword_projectId未指定ならグローバル検索() {
        when(customTagTemplateRepository.searchByKeyword("note")).thenReturn(List.of(buildTemplate(1L)));

        List<CustomTagTemplateResponse> result = service.searchByKeyword("note", null);

        assertEquals(1, result.size());
    }

    @Test
    void searchByKeyword_projectId指定時はプロジェクトとグローバルで検索() {
        when(customTagTemplateRepository.searchByKeywordAndProject("note", 5L)).thenReturn(List.of(buildTemplate(1L)));

        List<CustomTagTemplateResponse> result = service.searchByKeyword("note", 5L);

        assertEquals(1, result.size());
    }

    @Test
    void filterByCategory_projectId未指定ならグローバルフィルタ() {
        when(customTagTemplateRepository.findByCategory("alert")).thenReturn(List.of(buildTemplate(1L)));

        List<CustomTagTemplateResponse> result = service.filterByCategory("alert", null);

        assertEquals(1, result.size());
    }

    @Test
    void filterByCategory_projectId指定時はプロジェクトとグローバルでフィルタ() {
        when(customTagTemplateRepository.findByCategoryAndProject("alert", 5L)).thenReturn(List.of(buildTemplate(1L)));

        List<CustomTagTemplateResponse> result = service.filterByCategory("alert", 5L);

        assertEquals(1, result.size());
    }

    @Test
    void getMyTemplates_ログイン中ユーザーが作成したテンプレートのみ() {
        when(currentActorService.getCurrentActorId()).thenReturn(7L);
        when(customTagTemplateRepository.findByCreatedBy(7L)).thenReturn(List.of(buildTemplate(1L)));

        List<CustomTagTemplateResponse> result = service.getMyTemplates();

        assertEquals(1, result.size());
    }

    // ---- issue #1131: テンプレートをプロジェクトの custom_tags 行として適用する(apply) ----

    private CustomTagTemplate applicableTemplate(boolean published, Long templateProjectId) {
        CustomTagTemplate template = buildTemplate(1L);
        template.setHtmlTemplate("<div class=\"note\">{{content}}</div>");
        template.setCssContent(".note{color:red;}");
        template.setDescription("注意書き");
        template.setProjectId(templateProjectId);
        template.setIsPublished(published);
        return template;
    }

    @Test
    void apply_テンプレートのHTML_CSSから対象プロジェクトのcustom_tags行を作る() {
        when(customTagTemplateRepository.findById(1L)).thenReturn(Optional.of(applicableTemplate(true, null)));
        when(customTagValidationService.validate(any(), any())).thenReturn(ValidationResult.valid());
        when(customTagRepository.findByTagNameAndProjectId("note", 5L)).thenReturn(Optional.empty());
        when(customTagRepository.save(any(CustomTag.class))).thenAnswer(invocation -> {
            CustomTag saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });

        CustomTagResponse response = service.apply(1L, new ApplyCustomTagTemplateRequest(5L, "note"));

        assertEquals(10L, response.id());
        assertEquals("note", response.tagName());
        assertEquals(5L, response.projectId());
        assertEquals("<div class=\"note\">{{content}}</div>", response.htmlTemplate());
        assertEquals(".note{color:red;}", response.cssContent());
        assertEquals("注意書き", response.description());
        assertEquals(CustomTagFormat.BLOCK, response.tagFormat());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(5L);
    }

    @Test
    void apply_同名タグが既にあれば上書きせずIllegalArgumentException() {
        when(customTagTemplateRepository.findById(1L)).thenReturn(Optional.of(applicableTemplate(true, null)));
        when(customTagValidationService.validate(any(), any())).thenReturn(ValidationResult.valid());
        when(customTagRepository.findByTagNameAndProjectId("note", 5L)).thenReturn(Optional.of(new CustomTag()));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.apply(1L, new ApplyCustomTagTemplateRequest(5L, "note")));

        assertTrue(e.getMessage().contains("note"));
        assertTrue(e.getMessage().contains("既に登録されています"));
        verify(customTagRepository, never()).save(any(CustomTag.class));
    }

    @Test
    void apply_存在しないテンプレートはCustomTagTemplateNotFoundException() {
        when(customTagTemplateRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(CustomTagTemplateNotFoundException.class,
                () -> service.apply(99L, new ApplyCustomTagTemplateRequest(5L, "note")));
        verify(customTagRepository, never()).save(any(CustomTag.class));
    }

    @Test
    void apply_対象プロジェクトのメンバーでなければForbiddenで何も作らない() {
        doThrow(new ForbiddenException("no")).when(adminAuthorizationService).requireProjectMemberOrAdmin(5L);

        assertThrows(ForbiddenException.class,
                () -> service.apply(1L, new ApplyCustomTagTemplateRequest(5L, "note")));
        verify(customTagRepository, never()).save(any(CustomTag.class));
    }

    @Test
    void apply_他プロジェクトの未公開テンプレートはそのプロジェクトのメンバーでなければ適用できない() {
        when(customTagTemplateRepository.findById(1L)).thenReturn(Optional.of(applicableTemplate(false, 8L)));
        doNothing().when(adminAuthorizationService).requireProjectMemberOrAdmin(5L);
        doThrow(new ForbiddenException("no")).when(adminAuthorizationService).requireProjectMemberOrAdmin(8L);

        assertThrows(ForbiddenException.class,
                () -> service.apply(1L, new ApplyCustomTagTemplateRequest(5L, "note")));
        verify(customTagRepository, never()).save(any(CustomTag.class));
    }

    @Test
    void apply_未公開でもグローバルテンプレートならテンプレート側のメンバー判定は行わない() {
        when(customTagTemplateRepository.findById(1L)).thenReturn(Optional.of(applicableTemplate(false, null)));
        when(customTagValidationService.validate(any(), any())).thenReturn(ValidationResult.valid());
        when(customTagRepository.findByTagNameAndProjectId("note", 5L)).thenReturn(Optional.empty());
        when(customTagRepository.save(any(CustomTag.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.apply(1L, new ApplyCustomTagTemplateRequest(5L, "note"));

        verify(adminAuthorizationService).requireProjectMemberOrAdmin(5L);
        verify(adminAuthorizationService, never()).requireProjectMemberOrAdmin(8L);
    }

    @Test
    void apply_HTMLがセキュリティ検証に通らなければInvalidCustomTagContentExceptionで何も作らない() {
        when(customTagTemplateRepository.findById(1L)).thenReturn(Optional.of(applicableTemplate(true, null)));
        when(customTagValidationService.validate(any(), any())).thenReturn(
                ValidationResult.invalid(List.of(com.letsblog.content.dto.ValidationError.of("E", "bad", "ERROR")), List.of()));

        assertThrows(InvalidCustomTagContentException.class,
                () -> service.apply(1L, new ApplyCustomTagTemplateRequest(5L, "note")));
        verify(customTagRepository, never()).save(any(CustomTag.class));
    }
}
