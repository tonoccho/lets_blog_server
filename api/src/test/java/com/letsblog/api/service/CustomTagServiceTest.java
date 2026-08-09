package com.letsblog.api.service;

import com.letsblog.api.domain.CustomTag;
import com.letsblog.api.dto.CustomTagRequest;
import com.letsblog.api.dto.CustomTagResponse;
import com.letsblog.api.repository.CustomTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomTagServiceTest {

    @Mock
    private CustomTagRepository customTagRepository;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private CustomTagService service;

    @BeforeEach
    void setUp() {
        service = new CustomTagService(customTagRepository, adminAuthorizationService);
    }

    @Test
    void create_admin権限がなければForbidden() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", null, null, null);

        assertThrows(ForbiddenException.class, () -> service.create(request));
        verify(customTagRepository, never()).save(any());
    }

    @Test
    void create_タグ名が重複していれば例外() {
        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", null, null, null);
        CustomTag existing = new CustomTag();
        existing.setId(2L);
        when(customTagRepository.findByTagNameAndProjectIdIsNull("alert")).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void create_正常にタグを作成する() {
        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", "注意書き", null, null);
        when(customTagRepository.findByTagNameAndProjectIdIsNull("alert")).thenReturn(Optional.empty());
        when(customTagRepository.save(any(CustomTag.class))).thenAnswer(invocation -> {
            CustomTag tag = invocation.getArgument(0);
            tag.setId(1L);
            return tag;
        });

        CustomTagResponse response = service.create(request);

        assertEquals("alert", response.tagName());
        assertEquals("<div>{{content}}</div>", response.htmlTemplate());
    }

    @Test
    void create_プロジェクトスコープ作成() {
        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", null, null, 5L);
        when(customTagRepository.findByTagNameAndProjectId("alert", 5L)).thenReturn(Optional.empty());
        when(customTagRepository.save(any(CustomTag.class))).thenAnswer(invocation -> {
            CustomTag tag = invocation.getArgument(0);
            tag.setId(1L);
            return tag;
        });

        CustomTagResponse response = service.create(request);

        assertEquals(5L, response.projectId());
    }

    @Test
    void create_同じプロジェクト内で同名タグは例外() {
        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", null, null, 5L);
        CustomTag existing = new CustomTag();
        existing.setId(2L);
        when(customTagRepository.findByTagNameAndProjectId("alert", 5L)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void create_異なるプロジェクト間では同名タグを許可() {
        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", null, null, 6L);
        when(customTagRepository.findByTagNameAndProjectId("alert", 6L)).thenReturn(Optional.empty());
        when(customTagRepository.save(any(CustomTag.class))).thenAnswer(invocation -> {
            CustomTag tag = invocation.getArgument(0);
            tag.setId(3L);
            return tag;
        });

        CustomTagResponse response = service.create(request);

        assertEquals(6L, response.projectId());
    }

    @Test
    void update_projectIdはリクエストの値を無視して既存値を維持() {
        CustomTag existing = new CustomTag();
        existing.setId(1L);
        existing.setTagName("alert");
        existing.setProjectId(5L);
        when(customTagRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(customTagRepository.findByTagNameAndProjectId("alert", 5L)).thenReturn(Optional.of(existing));
        when(customTagRepository.save(any(CustomTag.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CustomTagRequest request = new CustomTagRequest("alert", "<div>更新</div>", null, null, 999L);
        CustomTagResponse response = service.update(1L, request);

        assertEquals(5L, response.projectId());
    }

    @Test
    void update_存在しなければ例外() {
        when(customTagRepository.findById(99L)).thenReturn(Optional.empty());
        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", null, null, null);

        assertThrows(CustomTagNotFoundException.class, () -> service.update(99L, request));
    }

    @Test
    void delete_存在しなければ例外() {
        when(customTagRepository.existsById(99L)).thenReturn(false);

        assertThrows(CustomTagNotFoundException.class, () -> service.delete(99L));
    }

    @Test
    void list_projectIdなしはグローバルタグのみ() {
        CustomTag tag = new CustomTag();
        tag.setId(1L);
        tag.setTagName("alert");
        tag.setHtmlTemplate("<div>{{content}}</div>");
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(List.of(tag));

        assertEquals(1, service.list(null).size());
    }

    @Test
    void list_projectId指定時はプロジェクトタグとグローバルタグ() {
        CustomTag tag = new CustomTag();
        tag.setId(1L);
        tag.setTagName("alert");
        tag.setHtmlTemplate("<div>{{content}}</div>");
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(5L)).thenReturn(List.of(tag));

        assertEquals(1, service.list(5L).size());
    }

    @Test
    void buildCssBundle_空でないCSSのみを区切りコメント付きで連結する() {
        CustomTag withCss = new CustomTag();
        withCss.setTagName("alert");
        withCss.setCssContent(".alert { color: red; }");
        CustomTag withoutCss = new CustomTag();
        withoutCss.setTagName("plain");
        withoutCss.setCssContent(null);
        CustomTag blankCss = new CustomTag();
        blankCss.setTagName("blank");
        blankCss.setCssContent("   ");
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(List.of(withCss, withoutCss, blankCss));

        String bundle = service.buildCssBundle(null);

        assertEquals(true, bundle.contains("/* === alert === */"));
        assertEquals(true, bundle.contains(".alert { color: red; }"));
        assertEquals(false, bundle.contains("plain"));
        assertEquals(false, bundle.contains("blank"));
    }

    @Test
    void buildCssBundle_projectId指定時はプロジェクトタグとグローバルタグを結合する() {
        CustomTag tag = new CustomTag();
        tag.setTagName("project-tag");
        tag.setCssContent(".project { color: blue; }");
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(5L)).thenReturn(List.of(tag));

        String bundle = service.buildCssBundle(5L);

        assertEquals(true, bundle.contains(".project { color: blue; }"));
    }

    @Test
    void listByProject_グローバルタグを含まずプロジェクトのタグのみ返す() {
        CustomTag tag = new CustomTag();
        tag.setId(1L);
        tag.setTagName("project-tag");
        tag.setHtmlTemplate("<div>{{content}}</div>");
        tag.setProjectId(5L);
        when(customTagRepository.findByProjectId(5L)).thenReturn(List.of(tag));

        List<CustomTagResponse> result = service.listByProject(5L);

        assertEquals(1, result.size());
        assertEquals(5L, result.get(0).projectId());
        verify(customTagRepository, never()).findByProjectIdOrProjectIdIsNull(any());
    }

    @Test
    void buildProjectCssBundle_グローバルタグを含まずプロジェクトのCSSのみ連結する() {
        CustomTag tag = new CustomTag();
        tag.setTagName("project-tag");
        tag.setCssContent(".project { color: blue; }");
        when(customTagRepository.findByProjectId(5L)).thenReturn(List.of(tag));

        String bundle = service.buildProjectCssBundle(5L);

        assertEquals(true, bundle.contains(".project { color: blue; }"));
        verify(customTagRepository, never()).findByProjectIdOrProjectIdIsNull(any());
    }
}
