package com.letsblog.identity.controller;

import com.letsblog.identity.domain.ProjectUser;
import com.letsblog.identity.domain.Role;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.domain.UserSiteAuthor;
import com.letsblog.identity.testsupport.IdentityWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 他サービス向けの内部ブリッジ({@link InternalProjectUserController})。実物のコントローラと
 * {@code ProjectUserSyncService}・{@code RoleService}・{@code UserService}をMockMvcに載せ、
 * DBのリポジトリ(Mockito)と、内部ブリッジの先(project-service / publishing-serviceのHTTPフェイク)だけを差し替える。
 */
class InternalProjectUserControllerTest {

    private IdentityWorld world;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        world = new IdentityWorld();
        mvc = world.mvc(new InternalProjectUserController(
                world.projectUserSyncService, world.roleService, world.userService));
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void プロジェクトメンバー判定はメンバーならtrue() throws Exception {
        when(world.projectUserRepository.findByProjectIdAndUserId(1L, 2L))
                .thenReturn(Optional.of(new ProjectUser(1L, 2L, "author")));

        mvc.perform(get("/api/internal/identity/projects/1/members/2"))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));
    }

    @Test
    void プロジェクトメンバー判定はメンバーでなければfalse() throws Exception {
        when(world.projectUserRepository.findByProjectIdAndUserId(1L, 3L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/internal/identity/projects/1/members/3"))
                .andExpect(status().isOk())
                .andExpect(content().string("false"));
    }

    @Test
    void 所属プロジェクトIDの一覧を返す() throws Exception {
        when(world.projectUserRepository.findByUserId(2L))
                .thenReturn(List.of(new ProjectUser(1L, 2L, "author"), new ProjectUser(5L, 2L, "editor")));

        mvc.perform(get("/api/internal/identity/users/2/project-ids"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(1))
                .andExpect(jsonPath("$[1]").value(5));
    }

    @Test
    void ロール一覧を返す() throws Exception {
        when(world.roleRepository.findAll()).thenReturn(List.of(new Role("ROLE_ADMIN", "管理者")));

        mvc.perform(get("/api/internal/identity/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roleName").value("ROLE_ADMIN"));
    }

    @Test
    void 著者IDの対応があれば返し無ければ404() throws Exception {
        when(world.userSiteAuthorRepository.findByUserIdAndSiteId(2L, 10L))
                .thenReturn(Optional.of(new UserSiteAuthor(2L, 10L, "wp-77")));
        when(world.userSiteAuthorRepository.findByUserIdAndSiteId(2L, 11L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/internal/identity/user-site-authors/2/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cmsAuthorId").value("wp-77"));
        mvc.perform(get("/api/internal/identity/user-site-authors/2/11")).andExpect(status().isNotFound());
    }

    @Test
    void 著者IDのキャッシュ書き込みは対応表へ保存して204() throws Exception {
        when(world.userSiteAuthorRepository.findByUserIdAndSiteId(2L, 10L)).thenReturn(Optional.empty());

        mvc.perform(post("/api/internal/identity/user-site-authors").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":2,\"siteId\":10,\"cmsAuthorId\":\"wp-77\"}"))
                .andExpect(status().isNoContent());

        verify(world.userSiteAuthorRepository).save(any(UserSiteAuthor.class));
    }

    @Test
    void 環境同期後のロール再整合はメンバーのロールを同期先サイトへ上書きして204() throws Exception {
        world.bridges.json("GET", "/api/internal/project/sites/30",
                "{\"id\":30,\"siteKey\":\"site-30\",\"name\":\"本番\",\"baseUrl\":\"https://example.com\"}");
        world.bridges.json("POST", "/api/internal/publishing/sites/site-30/authors", "{\"cmsAuthorId\":\"wp-1\"}");
        User member = new User();
        member.setId(2L);
        member.setEmail("m@example.com");
        when(world.projectUserRepository.findByProjectId(1L)).thenReturn(List.of(new ProjectUser(1L, 2L, "editor")));
        when(world.userRepository.findById(2L)).thenReturn(Optional.of(member));
        when(world.userSiteAuthorRepository.findByUserIdAndSiteId(2L, 30L)).thenReturn(Optional.empty());

        mvc.perform(post("/api/internal/identity/project-users/1/sites/30/reconcile-roles"))
                .andExpect(status().isNoContent());

        org.junit.jupiter.api.Assertions.assertEquals(1,
                world.bridges.requests("POST", "/api/internal/publishing/sites/site-30/authors").size());
    }

    @Test
    void GitHubトークンが設定済みなら暗号化したまま返す() throws Exception {
        User user = new User();
        user.setId(2L);
        user.setGithubTokenEncrypted(world.credentialCipher.encrypt("ghp_x"));
        when(world.userRepository.findById(2L)).thenReturn(Optional.of(user));

        mvc.perform(get("/api/internal/identity/users/2/github-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.encryptedToken").isNotEmpty());
    }

    @Test
    void GitHubトークンが未設定ならconfiguredはfalse() throws Exception {
        User user = new User();
        user.setId(2L);
        when(world.userRepository.findById(2L)).thenReturn(Optional.of(user));

        mvc.perform(get("/api/internal/identity/users/2/github-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.encryptedToken").doesNotExist());
    }

    @Test
    void GitHubトークンが空のバイト列でもconfiguredはfalse() throws Exception {
        User user = new User();
        user.setId(2L);
        user.setGithubTokenEncrypted(new byte[0]);
        when(world.userRepository.findById(2L)).thenReturn(Optional.of(user));

        mvc.perform(get("/api/internal/identity/users/2/github-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void GitHubトークンの対象ユーザーが存在しなければ404() throws Exception {
        when(world.userRepository.findById(9L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/internal/identity/users/9/github-token")).andExpect(status().isNotFound());
    }
}
