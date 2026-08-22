package com.letsblog.api.service;

import com.letsblog.api.domain.CustomTagTemplate;
import com.letsblog.api.dto.CloneCustomTagTemplateRequest;
import com.letsblog.api.dto.CustomTagTemplateRequest;
import com.letsblog.api.dto.CustomTagTemplateResponse;
import com.letsblog.api.repository.CustomTagTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomTagTemplateServiceTest {

    @Mock
    private CustomTagTemplateRepository repository;

    @Mock
    private CurrentActorService currentActorService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private CustomTagTemplateService service;

    @BeforeEach
    void setUp() {
        service = new CustomTagTemplateService(repository, currentActorService, adminAuthorizationService);
    }

    private CustomTagTemplate createTestTemplate(Long id, String name, String category) {
        CustomTagTemplate template = new CustomTagTemplate();
        template.setId(id);
        template.setTemplateName(name);
        template.setDescription("Test description");
        template.setCategory(category);
        template.setHtmlTemplate("<div>Test</div>");
        template.setCssContent("div { color: red; }");
        template.setVersion(1);
        template.setIsPublished(true);
        template.setCreatedBy(1L);
        template.setCreatedAt(LocalDateTime.now());
        template.setUpdatedAt(LocalDateTime.now());
        return template;
    }

    @Test
    void create_正常系() {
        CustomTagTemplateRequest request = new CustomTagTemplateRequest(
                "Test Template",
                "Description",
                "UI",
                "<div>Test</div>",
                "div { color: red; }",
                1L,
                null
        );

        when(currentActorService.getCurrentActorId()).thenReturn(1L);
        CustomTagTemplate savedTemplate = createTestTemplate(1L, "Test Template", "UI");
        when(repository.save(any(CustomTagTemplate.class))).thenReturn(savedTemplate);

        CustomTagTemplateResponse response = service.create(request);

        assertNotNull(response);
        assertEquals("Test Template", response.templateName());
        verify(adminAuthorizationService).requireAdmin();
        verify(repository).save(any(CustomTagTemplate.class));
    }

    @Test
    void create_管理者権限がない場合は例外() {
        CustomTagTemplateRequest request = new CustomTagTemplateRequest(
                "Test Template",
                "Description",
                "UI",
                "<div>Test</div>",
                "div { color: red; }",
                null,
                null
        );

        doThrow(new ForbiddenException("Admin required")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> service.create(request));
    }

    @Test
    void update_正常系() {
        CustomTagTemplateRequest request = new CustomTagTemplateRequest(
                "Updated Template",
                "Updated Description",
                "Component",
                "<div>Updated</div>",
                "div { color: blue; }",
                1L,
                null
        );

        CustomTagTemplate existingTemplate = createTestTemplate(1L, "Old Name", "UI");
        CustomTagTemplate updatedTemplate = createTestTemplate(1L, "Updated Template", "Component");

        when(repository.findById(1L)).thenReturn(Optional.of(existingTemplate));
        when(repository.save(any(CustomTagTemplate.class))).thenReturn(updatedTemplate);

        CustomTagTemplateResponse response = service.update(1L, request);

        assertNotNull(response);
        assertEquals("Updated Template", response.templateName());
        verify(adminAuthorizationService).requireAdmin();
        verify(repository).findById(1L);
        verify(repository).save(any(CustomTagTemplate.class));
    }

    @Test
    void update_テンプレートが見つからない場合は例外() {
        CustomTagTemplateRequest request = new CustomTagTemplateRequest(
                "Test",
                "Description",
                "UI",
                "<div>Test</div>",
                null,
                null,
                null
        );

        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(CustomTagTemplateNotFoundException.class, () -> service.update(999L, request));
    }

    @Test
    void publish_正常系() {
        CustomTagTemplate template = createTestTemplate(1L, "Test", "UI");
        template.setIsPublished(false);

        when(repository.findById(1L)).thenReturn(Optional.of(template));
        when(repository.save(any(CustomTagTemplate.class))).thenReturn(template);

        service.publish(1L);

        ArgumentCaptor<CustomTagTemplate> captor = ArgumentCaptor.forClass(CustomTagTemplate.class);
        verify(repository).save(captor.capture());
        assertTrue(captor.getValue().getIsPublished());
    }

    @Test
    void unpublish_正常系() {
        CustomTagTemplate template = createTestTemplate(1L, "Test", "UI");

        when(repository.findById(1L)).thenReturn(Optional.of(template));
        when(repository.save(any(CustomTagTemplate.class))).thenReturn(template);

        service.unpublish(1L);

        ArgumentCaptor<CustomTagTemplate> captor = ArgumentCaptor.forClass(CustomTagTemplate.class);
        verify(repository).save(captor.capture());
        assertFalse(captor.getValue().getIsPublished());
    }

    @Test
    void clone_正常系() {
        CloneCustomTagTemplateRequest request = new CloneCustomTagTemplateRequest(
                "Cloned Template",
                "Cloned Description",
                "UI",
                2L
        );

        CustomTagTemplate original = createTestTemplate(1L, "Original", "UI");
        CustomTagTemplate cloned = createTestTemplate(2L, "Cloned Template", "UI");

        when(repository.findById(1L)).thenReturn(Optional.of(original));
        when(currentActorService.getCurrentActorId()).thenReturn(1L);
        when(repository.save(any(CustomTagTemplate.class))).thenReturn(cloned);

        CustomTagTemplateResponse response = service.clone(1L, request);

        assertNotNull(response);
        assertEquals("Cloned Template", response.templateName());
        verify(adminAuthorizationService).requireAdmin();
        verify(repository).findById(1L);
        verify(repository).save(any(CustomTagTemplate.class));
    }

    @Test
    void delete_正常系() {
        when(repository.existsById(1L)).thenReturn(true);

        service.delete(1L);

        verify(adminAuthorizationService).requireAdmin();
        verify(repository).deleteById(1L);
    }

    @Test
    void delete_テンプレートが見つからない場合は例外() {
        when(repository.existsById(999L)).thenReturn(false);

        assertThrows(CustomTagTemplateNotFoundException.class, () -> service.delete(999L));
    }

    @Test
    void getById_正常系() {
        CustomTagTemplate template = createTestTemplate(1L, "Test", "UI");

        when(repository.findById(1L)).thenReturn(Optional.of(template));

        CustomTagTemplateResponse response = service.getById(1L);

        assertNotNull(response);
        assertEquals("Test", response.templateName());
    }

    @Test
    void getById_テンプレートが見つからない場合は例外() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(CustomTagTemplateNotFoundException.class, () -> service.getById(999L));
    }

    @Test
    void listPublished_プロジェクトIDなし() {
        List<CustomTagTemplate> templates = List.of(
                createTestTemplate(1L, "Template 1", "UI"),
                createTestTemplate(2L, "Template 2", "Component")
        );

        when(repository.findPublishedGlobalTemplates()).thenReturn(templates);

        List<CustomTagTemplateResponse> responses = service.listPublished(null);

        assertEquals(2, responses.size());
        verify(repository).findPublishedGlobalTemplates();
    }

    @Test
    void listPublished_プロジェクトID指定() {
        List<CustomTagTemplate> templates = List.of(
                createTestTemplate(1L, "Template 1", "UI")
        );

        when(repository.findPublishedTemplatesByProject(1L)).thenReturn(templates);

        List<CustomTagTemplateResponse> responses = service.listPublished(1L);

        assertEquals(1, responses.size());
        verify(repository).findPublishedTemplatesByProject(1L);
    }

    @Test
    void searchByKeyword_プロジェクトIDなし() {
        List<CustomTagTemplate> templates = List.of(
                createTestTemplate(1L, "Search Result", "UI")
        );

        when(repository.searchByKeyword("Search")).thenReturn(templates);

        List<CustomTagTemplateResponse> responses = service.searchByKeyword("Search", null);

        assertEquals(1, responses.size());
        verify(repository).searchByKeyword("Search");
    }

    @Test
    void filterByCategory_正常系() {
        List<CustomTagTemplate> templates = List.of(
                createTestTemplate(1L, "Template 1", "UI"),
                createTestTemplate(2L, "Template 2", "UI")
        );

        when(repository.findByCategory("UI")).thenReturn(templates);

        List<CustomTagTemplateResponse> responses = service.filterByCategory("UI", null);

        assertEquals(2, responses.size());
        verify(repository).findByCategory("UI");
    }

    @Test
    void getMyTemplates_正常系() {
        List<CustomTagTemplate> templates = List.of(
                createTestTemplate(1L, "My Template 1", "UI"),
                createTestTemplate(2L, "My Template 2", "Component")
        );

        when(currentActorService.getCurrentActorId()).thenReturn(1L);
        when(repository.findByCreatedBy(1L)).thenReturn(templates);

        List<CustomTagTemplateResponse> responses = service.getMyTemplates();

        assertEquals(2, responses.size());
        verify(repository).findByCreatedBy(1L);
    }
}
