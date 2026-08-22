package com.letsblog.api.service;

import com.letsblog.api.domain.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RenderedContentWrapperServiceTest {

    @Mock
    private ProjectService projectService;

    private RenderedContentWrapperService service;

    @BeforeEach
    void setUp() {
        service = new RenderedContentWrapperService(projectService);
    }

    @Test
    void wrap_projectId指定時はcssSelectorPrefixもクラスに含める() {
        Project project = new Project();
        project.setSlug("my-blog");
        when(projectService.getProjectEntity(5L)).thenReturn(project);
        when(projectService.resolveCssSelectorPrefix(project)).thenReturn("my-blog");

        String result = service.wrap("<p>本文</p>", 5L);

        assertEquals("<div class=\"lets-blog-rendered my-blog\"><p>本文</p></div>", result);
    }

    @Test
    void wrap_projectId未指定時は固定クラスのみ付与しProjectServiceを呼ばない() {
        String result = service.wrap("<p>本文</p>", null);

        assertEquals("<div class=\"lets-blog-rendered\"><p>本文</p></div>", result);
        verifyNoInteractions(projectService);
    }

    @Test
    void wrap_nullとから文字列はそのまま返す() {
        assertNull(service.wrap(null, 5L));
        assertEquals("", service.wrap("", 5L));
        verifyNoInteractions(projectService);
    }
}
