package com.letsblog.api.controller;

import com.letsblog.api.client.ProjectServiceClient;
import com.letsblog.api.repository.ProjectUserRepository;
import com.letsblog.api.service.ProjectService;
import com.letsblog.api.service.RoleService;
import com.letsblog.api.service.SiteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ContentBridgeController#tagDesignの回帰テスト(issue #760)。content-serviceの
 * LegacyApiBridgeClient#resolveTagDesignはprojectId=nullでもこのエンドポイントを呼ぶ
 * (URIテンプレート展開の結果、クエリは{@code ?projectId=}(空文字)になる)。
 * required=trueだった頃はSpringが「パラメータ未指定」と判定して400を返し、content-serviceが502、
 * publishing-serviceの記事公開が500になっていた。
 */
@ExtendWith(MockitoExtension.class)
class ContentBridgeControllerTest {

    @Mock
    private ProjectUserRepository projectUserRepository;

    @Mock
    private RoleService roleService;

    @Mock
    private ProjectService projectService;

    @Mock
    private SiteService siteService;

    @Mock
    private ProjectServiceClient projectServiceClient;

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new ContentBridgeController(
                projectUserRepository, roleService, projectService, siteService, projectServiceClient)).build();
    }

    @Test
    void tagDesign_projectIdが空文字でも200でnullのままproject_serviceへ中継する() throws Exception {
        when(projectServiceClient.getTagDesign(null, "TOC")).thenReturn(
                new ProjectServiceClient.TagDesignBridge("#ffffff", "#1a1a1a", "#2563eb", null, null));

        mockMvc().perform(get("/api/internal/content/tag-design/{tagType}", "TOC").param("projectId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backgroundColor").value("#ffffff"))
                .andExpect(jsonPath("$.htmlTemplate").doesNotExist());

        verify(projectServiceClient).getTagDesign(null, "TOC");
    }

    @Test
    void tagDesign_projectId指定ありは従来どおりそのまま中継する() throws Exception {
        when(projectServiceClient.getTagDesign(7L, "BLOGCARD")).thenReturn(
                new ProjectServiceClient.TagDesignBridge("#111111", "#eeeeee", "#ff0000", ".x{}", "<div>{{title}}</div>"));

        mockMvc().perform(get("/api/internal/content/tag-design/{tagType}", "BLOGCARD").param("projectId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backgroundColor").value("#111111"))
                .andExpect(jsonPath("$.customCss").value(".x{}"))
                .andExpect(jsonPath("$.htmlTemplate").value("<div>{{title}}</div>"));

        verify(projectServiceClient).getTagDesign(7L, "BLOGCARD");
    }
}
