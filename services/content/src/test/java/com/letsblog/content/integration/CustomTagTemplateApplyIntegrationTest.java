package com.letsblog.content.integration;

import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.content.client.IdentityBridgeClient;
import com.letsblog.content.client.ProjectBridgeClient;
import com.letsblog.content.domain.CustomTagTemplate;
import com.letsblog.content.repository.CustomTagRepository;
import com.letsblog.content.repository.CustomTagTemplateRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1131: {@code POST /api/custom-tag-templates/{id}/apply} は、テンプレートの HTML/CSS から
 * 対象プロジェクトの {@code custom_tags} 行を作り、記事で {@code [tagname]} として使える状態にする。
 * 従来の {@code /clone} はテンプレート間の複製で、{@code custom_tags} には触れない。
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("content-service: テンプレートのプロジェクト適用(issue #1131)")
class CustomTagTemplateApplyIntegrationTest {

    private static final long PROJECT_ID = 91131L;
    private static final String TAG_NAME = "applied1131";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomTagTemplateRepository customTagTemplateRepository;
    @Autowired
    private CustomTagRepository customTagRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private IdentityClient identityClient;
    @MockitoBean
    private IdentityBridgeClient identityBridgeClient;
    @MockitoBean
    private ProjectBridgeClient projectBridgeClient;

    @AfterEach
    void cleanUp() {
        customTagRepository.findByProjectId(PROJECT_ID).forEach(customTagRepository::delete);
        customTagTemplateRepository.deleteAll();
    }

    private CustomTagTemplate persistTemplate() {
        CustomTagTemplate template = new CustomTagTemplate();
        template.setTemplateName("apply-template");
        template.setHtmlTemplate("<div class=\"tpl1131\">{{content}}</div>");
        template.setCssContent(".tpl1131 { color: red; }");
        template.setCreatedBy(1L);
        template.setVersion(1);
        template.setIsPublished(true);
        return customTagTemplateRepository.save(template);
    }

    private String adminHeader() throws Exception {
        when(jwtDecoder.decode("admin-jwt-1131")).thenReturn(JwtTestFixtures.jwt("sub-1131", "admin"));
        when(identityClient.lookupProfile("Bearer admin-jwt-1131"))
                .thenReturn(Optional.of(new ActorProfile(1L, "admin")));
        when(projectBridgeClient.resolveTagDesign(anyLong(), anyString(), anyString()))
                .thenReturn(new ProjectBridgeClient.TagDesignResponse(null, null, null, null, null));
        return "Bearer admin-jwt-1131";
    }

    private String body() {
        return "{\"projectId\":" + PROJECT_ID + ",\"tagName\":\"" + TAG_NAME + "\"}";
    }

    @Test
    @DisplayName("適用するとプロジェクトのcustom_tags一覧に現れ、記事で[tagname]として描画される")
    void 適用するとタグが作られ記事で使える() throws Exception {
        String auth = adminHeader();
        CustomTagTemplate template = persistTemplate();

        mockMvc.perform(post("/api/custom-tag-templates/" + template.getId() + "/apply")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tagName").value(TAG_NAME))
                .andExpect(jsonPath("$.projectId").value(PROJECT_ID));

        mockMvc.perform(get("/api/projects/" + PROJECT_ID + "/custom-tags")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].tagName", hasItem(TAG_NAME)));

        mockMvc.perform(post("/api/projects/" + PROJECT_ID + "/preview/render")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"markdown\":\"[" + TAG_NAME + "]\\n本文1131\\n[/" + TAG_NAME + "]\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.html").value(containsString("<div class=\"tpl1131\">")))
                .andExpect(jsonPath("$.html").value(containsString("本文1131")));
    }

    @Test
    @DisplayName("同名タグが既にあれば409で、既存タグは上書きされない")
    void 同名タグは409() throws Exception {
        String auth = adminHeader();
        CustomTagTemplate template = persistTemplate();
        String applyPath = "/api/custom-tag-templates/" + template.getId() + "/apply";

        mockMvc.perform(post(applyPath).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isCreated());

        template.setHtmlTemplate("<p>別の内容</p>");
        customTagTemplateRepository.save(template);

        mockMvc.perform(post(applyPath).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString(TAG_NAME)))
                .andExpect(content().string(containsString("既に登録されています")));

        mockMvc.perform(get("/api/projects/" + PROJECT_ID + "/custom-tags")
                        .header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(jsonPath("$[?(@.tagName=='" + TAG_NAME + "')].htmlTemplate")
                        .value(hasItem("<div class=\"tpl1131\">{{content}}</div>")));
    }

    @Test
    @DisplayName("対象プロジェクトの非メンバーは403")
    void 非メンバーは403() throws Exception {
        CustomTagTemplate template = persistTemplate();
        when(jwtDecoder.decode("user-jwt-1131")).thenReturn(JwtTestFixtures.jwt("sub-u1131", "user"));
        when(identityClient.lookupProfile("Bearer user-jwt-1131"))
                .thenReturn(Optional.of(new ActorProfile(20L, "user")));
        when(identityBridgeClient.isProjectMember(PROJECT_ID, 20L, "Bearer user-jwt-1131")).thenReturn(false);

        mockMvc.perform(post("/api/custom-tag-templates/" + template.getId() + "/apply")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt-1131")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("タグ名の形式が不正なら400")
    void タグ名不正は400() throws Exception {
        String auth = adminHeader();
        CustomTagTemplate template = persistTemplate();

        mockMvc.perform(post("/api/custom-tag-templates/" + template.getId() + "/apply")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":" + PROJECT_ID + ",\"tagName\":\"1 bad\"}"))
                .andExpect(status().isBadRequest());
    }
}
