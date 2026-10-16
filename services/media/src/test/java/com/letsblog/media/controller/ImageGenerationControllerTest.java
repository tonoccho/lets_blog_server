package com.letsblog.media.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.letsblog.media.config.GlobalExceptionHandler;
import com.letsblog.media.service.ImageGenerationJobStarter;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 同期の画像生成と生成オプション({@code POST /api/ai/image}・{@code GET /api/ai/image-options})の、projectId指定の有無による
 * 認可の分岐(issue #830、#1820)。認可・画像生成サービスは実物で、identity-service・ComfyUIはフェイクHTTPサーバーが演じる。
 */
@DisplayName("media-service: /api/ai/image と /api/ai/image-options の認可(issue #830)")
class ImageGenerationControllerTest {

    private final MediaWorld world = new MediaWorld();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        world.actingAsAdmin(1L);
        ImageGenerationJobStarter starter = new ImageGenerationJobStarter(
                world.imageGenerationService, world.jobRunner, world.generationJobClient, world.objectMapper,
                world.tracker);
        mvc = MockMvcBuilders.standaloneSetup(
                        new ImageGenerationController(
                                world.imageGenerationService, world.adminAuthorizationService, starter))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    void image_projectId未指定ならメンバー判定なしで生成する() throws Exception {
        mvc.perform(post("/api/ai/image").contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"a cat\"}"))
                .andExpect(status().isOk());

        assertFalse(world.comfyWorkflows().isEmpty());
        assertEquals(0, world.http.requests("GET", "/api/internal/identity/projects/.*").size());
    }

    @Test
    void image_projectId指定時はメンバーなら生成する() throws Exception {
        world.actingAsUser(10L);
        world.http.json("GET", "/api/internal/identity/projects/5/members/10", "true");

        mvc.perform(post("/api/ai/image").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a cat\",\"projectId\":5}"))
                .andExpect(status().isOk());

        assertFalse(world.comfyWorkflows().isEmpty());
    }

    @Test
    void image_projectId指定時にメンバーでなければ403で生成しない() throws Exception {
        world.actingAsUser(10L);
        world.http.json("GET", "/api/internal/identity/projects/5/members/10", "false");

        mvc.perform(post("/api/ai/image").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a cat\",\"projectId\":5}"))
                .andExpect(status().isForbidden());

        assertEquals(0, world.comfyWorkflows().size());
    }

    @Test
    void imageOptions_projectId未指定ならメンバー判定なしで返す() throws Exception {
        mvc.perform(get("/api/ai/image-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoints[0]").value("a.safetensors"));
    }

    @Test
    void imageOptions_projectId指定時はメンバーなら返す() throws Exception {
        world.actingAsUser(10L);
        world.http.json("GET", "/api/internal/identity/projects/5/members/10", "true");

        mvc.perform(get("/api/ai/image-options").param("projectId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.samplers[0]").value("euler"));
    }

    @Test
    void imageOptions_projectId指定時にメンバーでなければ403() throws Exception {
        world.actingAsUser(10L);
        world.http.json("GET", "/api/internal/identity/projects/5/members/10", "false");

        mvc.perform(get("/api/ai/image-options").param("projectId", "5"))
                .andExpect(status().isForbidden());
    }
}
