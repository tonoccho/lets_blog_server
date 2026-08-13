package com.letsblog.api.service;

import com.letsblog.api.domain.CustomTag;
import com.letsblog.api.domain.EmbedTagType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.CustomTagRequest;
import com.letsblog.api.dto.CustomTagResponse;
import com.letsblog.api.dto.TagDesignColors;
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

    @Mock
    private TagDesignSettingService tagDesignSettingService;

    @Mock
    private TocStyleRenderService tocStyleRenderService;

    @Mock
    private BlogCardTagRenderService blogCardTagRenderService;

    @Mock
    private AmazonTagRenderService amazonTagRenderService;

    @Mock
    private ProjectService projectService;

    private CustomTagService service;

    @BeforeEach
    void setUp() {
        service = new CustomTagService(customTagRepository, adminAuthorizationService,
                tagDesignSettingService, tocStyleRenderService, blogCardTagRenderService, amazonTagRenderService,
                projectService);
    }

    private void stubEmbedTagCss(Long projectId) {
        TagDesignColors colors = new TagDesignColors("#ffffff", "#1a1a1a", "#2563eb", null);
        when(tagDesignSettingService.resolveColors(eq(projectId), any(EmbedTagType.class))).thenReturn(colors);
        when(tocStyleRenderService.buildStyle(colors)).thenReturn(".toc-css{}");
        when(blogCardTagRenderService.buildStyle(colors)).thenReturn(".blogcard-css{}");
        when(amazonTagRenderService.buildStyle(colors)).thenReturn(".amazon-css{}");
    }

    @Test
    void create_admin権限がなければForbidden() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", null, null, null, null);

        assertThrows(ForbiddenException.class, () -> service.create(request));
        verify(customTagRepository, never()).save(any());
    }

    @Test
    void create_タグ名が重複していれば例外() {
        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", null, null, null, null);
        CustomTag existing = new CustomTag();
        existing.setId(2L);
        when(customTagRepository.findByTagNameAndProjectIdIsNull("alert")).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void create_正常にタグを作成する() {
        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", "注意書き", null, null, null);
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
        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", null, null, null, 5L);
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
        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", null, null, null, 5L);
        CustomTag existing = new CustomTag();
        existing.setId(2L);
        when(customTagRepository.findByTagNameAndProjectId("alert", 5L)).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
    }

    @Test
    void create_異なるプロジェクト間では同名タグを許可() {
        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", null, null, null, 6L);
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

        CustomTagRequest request = new CustomTagRequest("alert", "<div>更新</div>", null, null, null, 999L);
        CustomTagResponse response = service.update(1L, request);

        assertEquals(5L, response.projectId());
    }

    @Test
    void update_存在しなければ例外() {
        when(customTagRepository.findById(99L)).thenReturn(Optional.empty());
        CustomTagRequest request = new CustomTagRequest("alert", "<div>{{content}}</div>", null, null, null, null);

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
    void buildCssBundle_projectId未指定時は組み込みタグのCSSを含めない() {
        when(customTagRepository.findByProjectIdIsNull()).thenReturn(List.of());

        service.buildCssBundle(null);

        verifyNoInteractions(tagDesignSettingService, tocStyleRenderService, blogCardTagRenderService, amazonTagRenderService);
    }

    @Test
    void buildCssBundle_projectId指定時はプロジェクトタグとグローバルタグを結合する() {
        CustomTag tag = new CustomTag();
        tag.setTagName("project-tag");
        tag.setCssContent(".project { color: blue; }");
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(5L)).thenReturn(List.of(tag));
        stubEmbedTagCss(5L);

        String bundle = service.buildCssBundle(5L);

        assertEquals(true, bundle.contains(".project { color: blue; }"));
    }

    @Test
    void buildCssBundle_projectId指定時は組み込みタグのデザインCSSも含める() {
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(5L)).thenReturn(List.of());
        stubEmbedTagCss(5L);

        String bundle = service.buildCssBundle(5L);

        assertEquals(true, bundle.contains(".toc-css{}"));
        assertEquals(true, bundle.contains(".blogcard-css{}"));
        assertEquals(true, bundle.contains(".amazon-css{}"));
    }

    @Test
    void buildCssBundle_projectId指定時はセレクタにプロジェクトのプリフィックスを付与する() {
        CustomTag tag = new CustomTag();
        tag.setTagName("alert");
        tag.setCssContent(".alert { color: red; }\n.alert .icon { width: 1em; }");
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(5L)).thenReturn(List.of(tag));
        stubEmbedTagCss(5L);
        Project project = new Project();
        project.setSlug("my-blog");
        when(projectService.getProjectEntity(5L)).thenReturn(project);
        when(projectService.resolveCssSelectorPrefix(project)).thenReturn("my-blog");

        String bundle = service.buildCssBundle(5L);

        assertEquals(true, bundle.contains(".my-blog .alert { color: red; }"));
        assertEquals(true, bundle.contains(".my-blog .alert .icon { width: 1em; }"));
    }

    @Test
    void buildProjectCssBundle_セレクタにプロジェクトのプリフィックスを付与する() {
        CustomTag tag = new CustomTag();
        tag.setTagName("alert");
        tag.setCssContent(".alert { color: red; }");
        when(customTagRepository.findByProjectId(5L)).thenReturn(List.of(tag));
        stubEmbedTagCss(5L);
        Project project = new Project();
        project.setSlug("my-blog");
        when(projectService.getProjectEntity(5L)).thenReturn(project);
        when(projectService.resolveCssSelectorPrefix(project)).thenReturn("custom-prefix");

        String bundle = service.buildProjectCssBundle(5L);

        assertEquals(true, bundle.contains(".custom-prefix .alert { color: red; }"));
    }

    @Test
    void buildProjectCssBundle_組み込みタグのデザインCSSにもプリフィックスを付与する() {
        // TocStyleRenderService等の出力は改行なしで複数ルールが連結されている(issue #307)
        when(customTagRepository.findByProjectId(5L)).thenReturn(List.of());
        TagDesignColors colors = new TagDesignColors("#ffffff", "#1a1a1a", "#2563eb", null);
        when(tagDesignSettingService.resolveColors(eq(5L), any(EmbedTagType.class))).thenReturn(colors);
        when(tocStyleRenderService.buildStyle(colors)).thenReturn(".lb-toc-list{margin:1em;}.lb-toc-list a{color:red;}");
        when(blogCardTagRenderService.buildStyle(colors)).thenReturn(".blogcard-css{}");
        when(amazonTagRenderService.buildStyle(colors)).thenReturn(".amazon-css{}");
        Project project = new Project();
        project.setSlug("my-blog");
        when(projectService.getProjectEntity(5L)).thenReturn(project);
        when(projectService.resolveCssSelectorPrefix(project)).thenReturn("my-blog");

        String bundle = service.buildProjectCssBundle(5L);

        assertEquals(true, bundle.contains(".my-blog .lb-toc-list {margin:1em;}"));
        assertEquals(true, bundle.contains(".my-blog .lb-toc-list a {color:red;}"));
        assertEquals(true, bundle.contains(".my-blog .blogcard-css {}"));
        assertEquals(true, bundle.contains(".my-blog .amazon-css {}"));
    }

    @Test
    void buildCssBundle_組み込みタグのデザインCSSにもプリフィックスを付与する() {
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(5L)).thenReturn(List.of());
        TagDesignColors colors = new TagDesignColors("#ffffff", "#1a1a1a", "#2563eb", null);
        when(tagDesignSettingService.resolveColors(eq(5L), any(EmbedTagType.class))).thenReturn(colors);
        when(tocStyleRenderService.buildStyle(colors)).thenReturn(".toc-css{}");
        when(blogCardTagRenderService.buildStyle(colors)).thenReturn(".blogcard-css{}");
        when(amazonTagRenderService.buildStyle(colors)).thenReturn(".amazon-css{}");
        Project project = new Project();
        project.setSlug("my-blog");
        when(projectService.getProjectEntity(5L)).thenReturn(project);
        when(projectService.resolveCssSelectorPrefix(project)).thenReturn("my-blog");

        String bundle = service.buildCssBundle(5L);

        assertEquals(true, bundle.contains(".my-blog .toc-css {}"));
        assertEquals(true, bundle.contains(".my-blog .blogcard-css {}"));
        assertEquals(true, bundle.contains(".my-blog .amazon-css {}"));
    }

    @Test
    void buildProjectCssBundle_複数行にまたがるセレクタリストにもプリフィックスを付与する() {
        CustomTag tag = new CustomTag();
        tag.setTagName("alert");
        tag.setCssContent(".alert,\n.warning {\n  color: red;\n}");
        when(customTagRepository.findByProjectId(5L)).thenReturn(List.of(tag));
        stubEmbedTagCss(5L);
        Project project = new Project();
        project.setSlug("my-blog");
        when(projectService.getProjectEntity(5L)).thenReturn(project);
        when(projectService.resolveCssSelectorPrefix(project)).thenReturn("my-blog");

        String bundle = service.buildProjectCssBundle(5L);

        assertEquals(true, bundle.contains(".my-blog .alert, .my-blog .warning {"));
    }

    @Test
    void buildProjectCssBundle_media内のセレクタにもプリフィックスを付与しつつatルール自体は変更しない() {
        CustomTag tag = new CustomTag();
        tag.setTagName("alert");
        tag.setCssContent("@media (max-width: 600px) { .alert { color: red; } }");
        when(customTagRepository.findByProjectId(5L)).thenReturn(List.of(tag));
        stubEmbedTagCss(5L);
        Project project = new Project();
        project.setSlug("my-blog");
        when(projectService.getProjectEntity(5L)).thenReturn(project);
        when(projectService.resolveCssSelectorPrefix(project)).thenReturn("my-blog");

        String bundle = service.buildProjectCssBundle(5L);

        assertEquals(true, bundle.contains("@media (max-width: 600px) {"));
        assertEquals(true, bundle.contains(".my-blog .alert { color: red; }"));
    }

    @Test
    void buildProjectCssBundle_keyframes内のパーセントセレクタにはプリフィックスを付与しない() {
        CustomTag tag = new CustomTag();
        tag.setTagName("spin");
        tag.setCssContent("@keyframes spin { 0% { opacity: 0; } 100% { opacity: 1; } }");
        when(customTagRepository.findByProjectId(5L)).thenReturn(List.of(tag));
        stubEmbedTagCss(5L);
        Project project = new Project();
        project.setSlug("my-blog");
        when(projectService.getProjectEntity(5L)).thenReturn(project);
        when(projectService.resolveCssSelectorPrefix(project)).thenReturn("my-blog");

        String bundle = service.buildProjectCssBundle(5L);

        assertEquals(true, bundle.contains("@keyframes spin { 0% { opacity: 0; } 100% { opacity: 1; } }"));
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
        stubEmbedTagCss(5L);

        String bundle = service.buildProjectCssBundle(5L);

        assertEquals(true, bundle.contains(".project { color: blue; }"));
        verify(customTagRepository, never()).findByProjectIdOrProjectIdIsNull(any());
    }

    @Test
    void buildProjectCssBundle_組み込みタグのデザインCSSを含める() {
        when(customTagRepository.findByProjectId(5L)).thenReturn(List.of());
        stubEmbedTagCss(5L);

        String bundle = service.buildProjectCssBundle(5L);

        assertEquals(true, bundle.contains(".toc-css{}"));
        assertEquals(true, bundle.contains(".blogcard-css{}"));
        assertEquals(true, bundle.contains(".amazon-css{}"));
    }

    @Test
    void previewCss_統合CSSバンドルと同じルールでプレフィックスを付与する() {
        Project project = new Project();
        project.setSlug("my-blog");
        when(projectService.getProjectEntity(5L)).thenReturn(project);
        when(projectService.resolveCssSelectorPrefix(project)).thenReturn("my-blog");

        String css = service.previewCss(".alert { color: red; }", 5L);

        assertEquals(".my-blog .alert { color: red; }", css);
    }
}
