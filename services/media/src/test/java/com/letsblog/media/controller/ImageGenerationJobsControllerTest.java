package com.letsblog.media.controller;

import com.letsblog.media.config.GlobalExceptionHandler;
import com.letsblog.media.service.ImageGenerationJobRunner;
import com.letsblog.media.service.ImageGenerationJobStarter;
import com.letsblog.media.testsupport.AsyncProxies;
import com.letsblog.media.testsupport.MediaWorld;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 画像生成の非同期受理口 {@code POST /api/ai/image/jobs}(issue #1405)。 */
/**
 * 認可・スターター・画像生成サービス・ジョブのクライアントは実物で、identity-service・ai-service・ComfyUIは
 * フェイクHTTPサーバーが演じる(issue #1820)。ランナーの{@code @Async}はSpringの実際のAOPプロキシで効かせ、
 * 実行は溜めるだけのExecutorで後に回す。
 */
@DisplayName("media-service: POST /api/ai/image/jobs(issue #1405)")
class ImageGenerationJobsControllerTest {

    private final MediaWorld world = new MediaWorld();
    private final List<Runnable> deferred = new ArrayList<>();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        world.actingAsAdmin(1L);
        ImageGenerationJobRunner asyncRunner =
                AsyncProxies.async(world.jobRunner, "imageGenerationExecutor", deferred::add);
        ImageGenerationJobStarter starter = new ImageGenerationJobStarter(
                world.imageGenerationService, asyncRunner, world.generationJobClient, world.objectMapper,
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
    void 受理するとジョブIDを202で即座に返す() throws Exception {
        mvc.perform(post("/api/ai/image/jobs").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"a cat\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.status").value("running"));

        assertEquals("image_generation", world.jobCreates().get(0).type());
        assertEquals("Bearer t", world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0)
                .header("Authorization"));
        assertEquals(1, deferred.size(), "生成はジョブの受理の後ろに回される");
        assertTrue(world.comfyWorkflows().isEmpty());
    }

    @Test
    void projectId指定時はメンバーなら受理する() throws Exception {
        world.actingAsUser(10L);
        world.http.json("GET", "/api/internal/identity/projects/5/members/10", "true");

        mvc.perform(post("/api/ai/image/jobs").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"a cat\",\"projectId\":5}"))
                .andExpect(status().isAccepted());

        assertEquals(1, world.jobCreates().size());
    }

    @Test
    void メンバーでなければ403でジョブを作らない() throws Exception {
        world.actingAsUser(10L);
        world.http.json("GET", "/api/internal/identity/projects/5/members/10", "false");

        mvc.perform(post("/api/ai/image/jobs").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"a cat\",\"projectId\":5}"))
                .andExpect(status().isForbidden());

        assertTrue(world.jobCreates().isEmpty());
    }

    @Test
    void 入力検証エラーは400でジョブを作らない() throws Exception {
        mvc.perform(post("/api/ai/image/jobs").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"\"}"))
                .andExpect(status().isBadRequest());

        assertTrue(world.jobCreates().isEmpty());
    }

    @Test
    void プロバイダの枚数上限超過は400で返る() throws Exception {
        world.providerIs(5L, "CHATGPT");

        mvc.perform(post("/api/ai/image/jobs").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a cat\",\"projectId\":5,\"batchSize\":11}"))
                .andExpect(status().isBadRequest());

        assertTrue(world.jobCreates().isEmpty());
    }

    @Test
    void 使えない参照画像は400で返る() throws Exception {
        mvc.perform(post("/api/ai/image/jobs").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a cat\",\"projectId\":1,\"referenceImageId\":5}"))
                .andExpect(status().isBadRequest());

        assertTrue(world.jobCreates().isEmpty());
    }

    @Test
    void ChatGPTの参照画像付き要求は未対応として400で返る() throws Exception {
        world.providerIs(1L, "CHATGPT");

        mvc.perform(post("/api/ai/image/jobs").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a cat\",\"projectId\":1,\"referenceImageId\":5}"))
                .andExpect(status().isBadRequest());

        assertTrue(world.jobCreates().isEmpty());
    }

    @Test
    void denoiseが範囲外なら400で返り受理しない() throws Exception {
        mvc.perform(post("/api/ai/image/jobs").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a cat\",\"projectId\":1,\"referenceImageId\":5,\"denoise\":1.5}"))
                .andExpect(status().isBadRequest());

        assertTrue(world.jobCreates().isEmpty());
    }

    @Test
    void 参照画像の要求はそのプロジェクトのメンバーであることを先に確かめる() throws Exception {
        world.actingAsUser(10L);
        world.http.json("GET", "/api/internal/identity/projects/1/members/10", "false");

        mvc.perform(post("/api/ai/image/jobs").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"a cat\",\"projectId\":1,\"referenceImageId\":5}"))
                .andExpect(status().isForbidden());

        assertTrue(world.jobCreates().isEmpty());
    }
}
