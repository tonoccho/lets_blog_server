package com.letsblog.identity.controller;

import com.letsblog.identity.domain.Permission;
import com.letsblog.identity.domain.Role;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.testsupport.IdentityWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link UserController}。実物のコントローラ・{@code UserService}・{@code RoleService}・認可サービスを
 * Spring MVCの実際のディスパッチ(MockMvc)に載せ、DBのリポジトリ(Mockito)とKeycloakのHTTP
 * (サーバー側フェイク)だけを外部との通信として差し替える。操作者は実際のJWT。
 *
 * <p>issue #653: GET /api/usersにadmin限定の認可チェックが欠落していた欠陥の回帰。
 */
class UserControllerTest {

    private IdentityWorld world;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        world = new IdentityWorld();
        mvc = world.mvc(new UserController(world.userService, world.roleService,
                world.permissionAuthorizationService, world.adminAuthorizationService));
        when(world.userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(world.userRepository.saveAndFlush(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private User user(long id, String role) {
        User user = new User();
        user.setId(id);
        user.setEmail("user" + id + "@example.com");
        user.setRole(role);
        user.setEnabled(true);
        return user;
    }

    // ------------------------------------------------------------------ list

    @Test
    void list_admin以外は403で一覧を取得しない() throws Exception {
        world.actAs(1L, "user");

        mvc.perform(get("/api/users")).andExpect(status().isForbidden());

        verify(world.userRepository, never()).findAll();
    }

    @Test
    void list_adminは従来通り全ユーザー一覧を取得できる() throws Exception {
        world.actAs(1L, "admin");
        when(world.userRepository.findAll()).thenReturn(List.of(user(1L, "admin"), user(2L, "user")));

        mvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].email").value("user2@example.com"));
    }

    // ------------------------------------------------------------------ create

    @Test
    void create_adminはKeycloakにも作成して201() throws Exception {
        world.actAs(1L, "admin");
        when(world.userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(world.roleRepository.findByRoleName("ROLE_VIEWER")).thenReturn(Optional.of(new Role("ROLE_VIEWER", "閲覧者")));
        world.keycloak.nextSub("kc-new");

        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\",\"password\":\"password123\",\"role\":\"user\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("new@example.com"))
                .andExpect(jsonPath("$.keycloakLinked").value(true));

        assertEquals(List.of("createUser:new@example.com:false"), world.keycloak.ops());
    }

    @Test
    void create_admin以外は403でKeycloakにも触れない() throws Exception {
        world.actAs(1L, "user");

        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\",\"password\":\"password123\",\"role\":\"admin\"}"))
                .andExpect(status().isForbidden());

        assertTrue(world.keycloak.ops().isEmpty());
    }

    // ------------------------------------------------------------------ update / delete / deactivate / reactivate

    @Test
    void update_adminは他人のroleを変更できる() throws Exception {
        world.actAs(1L, "admin");
        when(world.userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "user")));

        mvc.perform(patch("/api/users/2").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("admin"));
    }

    @Test
    void update_adminでも自分自身のadmin権限は外せない() throws Exception {
        world.actAs(1L, "admin");

        mvc.perform(patch("/api/users/1").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"user\"}"))
                .andExpect(status().isForbidden());

        verify(world.userRepository, never()).saveAndFlush(any());
    }

    @Test
    void update_admin以外は403() throws Exception {
        world.actAs(1L, "user");

        mvc.perform(patch("/api/users/1").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"admin\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_adminは他人を削除して204() throws Exception {
        world.actAs(1L, "admin");
        when(world.userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "user")));

        mvc.perform(delete("/api/users/2")).andExpect(status().isNoContent());

        verify(world.userRepository).deleteById(2L);
    }

    @Test
    void delete_自分自身は403() throws Exception {
        world.actAs(1L, "admin");

        mvc.perform(delete("/api/users/1")).andExpect(status().isForbidden());

        verify(world.userRepository, never()).deleteById(any());
    }

    @Test
    void deactivate_adminは他人を無効化できる() throws Exception {
        world.actAs(1L, "admin");
        User target = user(2L, "user");
        target.setKeycloakSub("kc-2");
        when(world.userRepository.findById(2L)).thenReturn(Optional.of(target));

        mvc.perform(post("/api/users/2/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        assertEquals(List.of("setEnabled:kc-2:false"), world.keycloak.ops());
    }

    @Test
    void deactivate_自分自身は403() throws Exception {
        world.actAs(1L, "admin");

        mvc.perform(post("/api/users/1/deactivate")).andExpect(status().isForbidden());
    }

    @Test
    void reactivate_adminは無効化済みユーザーを再有効化できる() throws Exception {
        world.actAs(1L, "admin");
        User target = user(2L, "user");
        target.setEnabled(false);
        when(world.userRepository.findById(2L)).thenReturn(Optional.of(target));

        mvc.perform(post("/api/users/2/reactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void reactivate_admin以外は403() throws Exception {
        world.actAs(1L, "user");

        mvc.perform(post("/api/users/2/reactivate")).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ migrate / reconcile

    @Test
    void migrateToKeycloak_本文が無ければ未移行の全ユーザーが対象() throws Exception {
        world.actAs(1L, "admin");
        User legacy = user(2L, "user");
        when(world.userRepository.findByKeycloakSubIsNull()).thenReturn(List.of(legacy));
        world.keycloak.nextSub("kc-legacy");

        mvc.perform(post("/api/users/migrate-to-keycloak"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migratedUserIds[0]").value(2));

        assertEquals("kc-legacy", legacy.getKeycloakSub());
    }

    @Test
    void migrateToKeycloak_userIdsを指定すればそのユーザーだけが対象() throws Exception {
        world.actAs(1L, "admin");
        User legacy = user(2L, "user");
        when(world.userRepository.findAllById(List.of(2L))).thenReturn(List.of(legacy));
        world.keycloak.nextSub("kc-legacy");

        mvc.perform(post("/api/users/migrate-to-keycloak").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migratedUserIds[0]").value(2));

        verify(world.userRepository, never()).findByKeycloakSubIsNull();
    }

    @Test
    void migrateToKeycloak_admin以外は403() throws Exception {
        world.actAs(1L, "user");

        mvc.perform(post("/api/users/migrate-to-keycloak")).andExpect(status().isForbidden());
    }

    @Test
    void reconcileKeycloak_Keycloakに存在しないユーザーを無効化して返す() throws Exception {
        world.actAs(1L, "admin");
        User orphan = user(2L, "user");
        orphan.setKeycloakSub("kc-gone");
        when(world.userRepository.findByKeycloakSubIsNotNull()).thenReturn(List.of(orphan));
        world.keycloak.userMissing("kc-gone");

        mvc.perform(post("/api/users/reconcile-keycloak"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deactivatedUserIds[0]").value(2));
    }

    @Test
    void reconcileKeycloak_admin以外は403() throws Exception {
        world.actAs(1L, "user");

        mvc.perform(post("/api/users/reconcile-keycloak")).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ profile

    @Test
    void getProfile_本人は取得できる() throws Exception {
        world.actAs(1L, "user");
        when(world.userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "user")));

        mvc.perform(get("/api/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user1@example.com"));
    }

    @Test
    void getProfile_他人のプロフィールは本人でもadminでもなければ403() throws Exception {
        world.actAs(1L, "user");

        mvc.perform(get("/api/users/2")).andExpect(status().isForbidden());
    }

    @Test
    void updateProfile_メールアドレス変更はadmin限定_issue1192() throws Exception {
        world.actAs(1L, "user");

        mvc.perform(put("/api/users/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\"}"))
                .andExpect(status().isForbidden());

        verify(world.userRepository, never()).save(any());
    }

    @Test
    void updateProfile_adminはメールアドレスを変更できる_issue1192() throws Exception {
        world.actAs(9L, "admin");
        User target = user(1L, "user");
        when(world.userRepository.findById(1L)).thenReturn(Optional.of(target));
        when(world.userRepository.existsByEmail("new@example.com")).thenReturn(false);

        mvc.perform(put("/api/users/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("new@example.com"));
    }

    @Test
    void updateProfile_メールアドレス無指定なら本人も従来どおり更新できる_issue1192() throws Exception {
        world.actAs(1L, "user");
        when(world.userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "user")));

        mvc.perform(put("/api/users/1").contentType(MediaType.APPLICATION_JSON).content("{\"firstName\":\"太郎\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("太郎"));
    }

    @Test
    void updateProfile_空白のメールアドレスは変更なしとして本人も更新できる_issue1192() throws Exception {
        world.actAs(1L, "user");
        when(world.userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "user")));

        mvc.perform(put("/api/users/1").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user1@example.com"));
    }

    @Test
    void updatePreferences_本人は更新できる() throws Exception {
        world.actAs(1L, "user");
        when(world.userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "user")));

        mvc.perform(patch("/api/users/1/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"ja\",\"timezone\":\"Asia/Tokyo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Tokyo"));
    }

    @Test
    void updatePreferences_他人は403() throws Exception {
        world.actAs(1L, "user");

        mvc.perform(patch("/api/users/2/preferences").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"ja\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateGithubToken_本人は更新できる() throws Exception {
        world.actAs(1L, "user");
        when(world.userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "user")));

        mvc.perform(put("/api/users/1/github-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubToken\":\"ghp_dummy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.githubTokenConfigured").value(true));
    }

    @Test
    void updateGithubToken_他人は403() throws Exception {
        world.actAs(1L, "user");

        mvc.perform(put("/api/users/2/github-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubToken\":\"ghp_dummy\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ roles(issue #798 / #955)

    private Role roleWith(String name, Permission... permissions) {
        Role role = new Role(name, name);
        role.getPermissions().addAll(List.of(permissions));
        return role;
    }

    /** 割り当て後にKeycloakのrealmロールを{@code users.role}へ整合させる(issue #955)。 */
    @Test
    void assignRole_割り当て後にKeycloakのrealmロールを整合させる_issue955() throws Exception {
        world.actAs(1L, "admin");
        User target = user(2L, "user");
        target.setKeycloakSub("kc-2");
        Role viewer = roleWith("ROLE_VIEWER", Permission.POST_READ);
        when(world.userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(world.roleRepository.findByRoleName("ROLE_VIEWER")).thenReturn(Optional.of(viewer));
        world.keycloak.alreadyAssigned("kc-2", "admin");

        mvc.perform(post("/api/users/2/roles/ROLE_VIEWER")).andExpect(status().isOk());

        assertTrue(target.getRoles().contains(viewer));
        // users.role=user なので、整合でrealmロールadminを剥奪する。
        assertEquals(List.of("revoke:kc-2:admin"), world.keycloak.ops("revoke"));
    }

    @Test
    void removeRole_解除後にKeycloakのrealmロールを整合させる_issue955() throws Exception {
        world.actAs(1L, "admin");
        User target = user(2L, "admin");
        target.setKeycloakSub("kc-2");
        Role viewer = roleWith("ROLE_VIEWER", Permission.POST_READ);
        target.getRoles().add(viewer);
        when(world.userRepository.findById(2L)).thenReturn(Optional.of(target));
        when(world.roleRepository.findByRoleName("ROLE_VIEWER")).thenReturn(Optional.of(viewer));

        mvc.perform(delete("/api/users/2/roles/ROLE_VIEWER")).andExpect(status().isOk());

        assertTrue(target.getRoles().isEmpty());
        assertEquals(List.of("grant:kc-2:admin"), world.keycloak.ops("grant"));
    }

    @Test
    void assignRole_特権ロールの付与はadmin以外なら403でロールも整合も行わない_issue955() throws Exception {
        // ROLE_MANAGEを持つ非adminでも、特権ロールの付与はできない。
        User actor = world.actAs(1L, "user");
        actor.getRoles().add(roleWith("ROLE_MANAGER", Permission.ROLE_MANAGE));
        when(world.userRepository.findById(1L)).thenReturn(Optional.of(actor));
        when(world.roleRepository.findByRoleName("ROLE_ADMIN"))
                .thenReturn(Optional.of(roleWith("ROLE_ADMIN", Permission.ROLE_MANAGE)));

        mvc.perform(post("/api/users/1/roles/ROLE_ADMIN")).andExpect(status().isForbidden());

        verify(world.userRepository, never()).save(any());
        assertTrue(world.keycloak.ops().isEmpty());
    }

    @Test
    void assignRole_特権でないロールはROLE_MANAGE権限を持つ非adminも付与できる() throws Exception {
        User actor = world.actAs(1L, "user");
        actor.getRoles().add(roleWith("ROLE_MANAGER", Permission.ROLE_MANAGE));
        when(world.userRepository.findById(1L)).thenReturn(Optional.of(actor));
        User target = user(2L, "user");
        when(world.userRepository.findById(2L)).thenReturn(Optional.of(target));
        Role viewer = roleWith("ROLE_VIEWER", Permission.POST_READ);
        when(world.roleRepository.findByRoleName("ROLE_VIEWER")).thenReturn(Optional.of(viewer));

        mvc.perform(post("/api/users/2/roles/ROLE_VIEWER")).andExpect(status().isOk());

        assertTrue(target.getRoles().contains(viewer));
    }

    @Test
    void assignRole_特権でないロールでもROLE_MANAGE権限が無ければ403() throws Exception {
        User actor = world.actAs(1L, "user");
        when(world.userRepository.findById(1L)).thenReturn(Optional.of(actor));
        when(world.roleRepository.findByRoleName("ROLE_VIEWER"))
                .thenReturn(Optional.of(roleWith("ROLE_VIEWER", Permission.POST_READ)));

        mvc.perform(post("/api/users/2/roles/ROLE_VIEWER")).andExpect(status().isForbidden());
    }

    @Test
    void assignRole_adminは特権ロールも付与できる() throws Exception {
        world.actAs(1L, "admin");
        User target = user(2L, "user");
        when(world.userRepository.findById(2L)).thenReturn(Optional.of(target));
        Role adminRole = roleWith("ROLE_ADMIN", Permission.ROLE_MANAGE);
        when(world.roleRepository.findByRoleName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));

        mvc.perform(post("/api/users/2/roles/ROLE_ADMIN")).andExpect(status().isOk());

        assertTrue(target.getRoles().contains(adminRole));
    }
}
