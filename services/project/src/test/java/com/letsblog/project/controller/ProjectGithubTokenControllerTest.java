package com.letsblog.project.controller;

import com.letsblog.project.config.GlobalExceptionHandler;
import com.letsblog.project.domain.Project;
import com.letsblog.project.testsupport.ProjectApp;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * プロジェクトのGitHubトークン(暗号化して保存)のAPI。issue #1818: ProjectService・CredentialCipher・
 * AdminAuthorizationService は実物。identity-serviceの代わりにフェイクHTTPサーバーが応答し、差し替えるのはDBのリポジトリだけ。
 */
class ProjectGithubTokenControllerTest {

    private static final String PATH = "/api/projects/1/api-keys/github-token";
    private static final String MEMBER = "/api/internal/identity/projects/1/members/5";

    private final ProjectApp app = new ProjectApp();
    private Project project;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        project = new Project();
        project.setId(1L);
        when(app.projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(app.projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));
        mockMvc = MockMvcBuilders.standaloneSetup(new ProjectGithubTokenController(
                        app.projectService, app.world.credentialCipher, app.world.adminAuthorizationService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void close() {
        app.close();
    }

    private void asMember() {
        app.world.actingAsUser(5L);
        app.world.http.json("GET", MEMBER, "true");
    }

    @Test
    void 状態は未設定_空_設定済みを区別して返す() throws Exception {
        asMember();

        mockMvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));

        project.setGithubTokenEncrypted(new byte[0]);
        mockMvc.perform(get(PATH)).andExpect(jsonPath("$.configured").value(false));

        project.setGithubTokenEncrypted(new byte[] {1, 2});
        mockMvc.perform(get(PATH)).andExpect(jsonPath("$.configured").value(true));
    }

    @Test
    void 設定すると暗号化して保存し_復号すると元のトークンに戻る() throws Exception {
        asMember();

        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"githubToken\":\"ghp_secret\"}"))
                .andExpect(status().isNoContent());

        assertEquals("ghp_secret", app.world.credentialCipher.decrypt(project.getGithubTokenEncrypted()));
        assertEquals(false, new String(project.getGithubTokenEncrypted()).contains("ghp_secret"));
    }

    @Test
    void 解除すると保存したトークンが消える() throws Exception {
        asMember();
        project.setGithubTokenEncrypted(new byte[] {1});

        mockMvc.perform(delete(PATH)).andExpect(status().isNoContent());

        assertNull(project.getGithubTokenEncrypted());
    }

    @Test
    void メンバーでもadminでもなければ403で何も読み書きしない() throws Exception {
        app.world.actingAsUser(5L);
        app.world.http.json("GET", MEMBER, "false");
        project.setGithubTokenEncrypted(new byte[] {1});

        mockMvc.perform(get(PATH)).andExpect(status().isForbidden());
        mockMvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"githubToken\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(PATH)).andExpect(status().isForbidden());

        assertArrayEquals(new byte[] {1}, project.getGithubTokenEncrypted());
        verify(app.projectRepository, never()).save(any());
    }
}
