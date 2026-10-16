package com.letsblog.identity.controller;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 初回セットアップ導線({@link AuthSetupController})。認証前に叩かれる公開パスなので、操作者(JWT)は無い。
 * 実物のコントローラと{@code UserService}・{@code KeycloakAdminClient}をMockMvcに載せ、DBのリポジトリ(Mockito)と
 * KeycloakのHTTP(サーバー側フェイク)だけを差し替える。
 */
class AuthSetupControllerTest {

    private IdentityWorld world;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        world = new IdentityWorld();
        mvc = world.mvc(new AuthSetupController(world.userService));
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void ユーザーが1人もいなければセットアップが必要() throws Exception {
        when(world.userRepository.count()).thenReturn(0L);

        mvc.perform(get("/api/auth/setup-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsSetup").value(true));
    }

    @Test
    void ユーザーがいればセットアップは不要() throws Exception {
        when(world.userRepository.count()).thenReturn(3L);

        mvc.perform(get("/api/auth/setup-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsSetup").value(false));
    }

    @Test
    void セットアップは初期管理者をKeycloakにもパスワードつきで作る() throws Exception {
        when(world.userRepository.count()).thenReturn(0L);
        when(world.userRepository.existsByEmail("first@example.com")).thenReturn(false);
        when(world.roleRepository.findByRoleName("ROLE_ADMIN")).thenReturn(Optional.of(new Role("ROLE_ADMIN", "管理者")));
        when(world.userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        world.keycloak.nextSub("kc-first");

        mvc.perform(post("/api/auth/setup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"first@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("admin"));

        assertEquals(List.of("createUser:first@example.com:false", "setPassword:kc-first:password123",
                "grant:kc-first:admin"), world.keycloak.ops());
    }

    @Test
    void セットアップ済みなら400() throws Exception {
        when(world.userRepository.count()).thenReturn(1L);

        mvc.perform(post("/api/auth/setup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"second@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest());
    }
}
