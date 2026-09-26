package com.letsblog.media.controller;

import com.letsblog.media.client.GenerationJobSummary;
import com.letsblog.media.config.GlobalExceptionHandler;
import com.letsblog.media.dto.AiImageRequest;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.ForbiddenException;
import com.letsblog.media.service.ImageGenerationJobStarter;
import com.letsblog.media.service.ImageGenerationService;
import com.letsblog.media.service.UnsupportedBatchSizeException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 画像生成の非同期受理口 {@code POST /api/ai/image/jobs}(issue #1405)。 */
@DisplayName("media-service: POST /api/ai/image/jobs(issue #1405)")
class ImageGenerationJobsControllerTest {

    private ImageGenerationJobStarter starter;
    private AdminAuthorizationService authorization;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        starter = mock(ImageGenerationJobStarter.class);
        authorization = mock(AdminAuthorizationService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new ImageGenerationController(mock(ImageGenerationService.class), authorization, starter))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void 受理するとジョブIDを202で即座に返す() throws Exception {
        when(starter.start(any(AiImageRequest.class), eq("Bearer t"))).thenReturn(
                new GenerationJobSummary(7L, "image_generation", "running", LocalDateTime.now(), LocalDateTime.now()));

        mvc.perform(post("/api/ai/image/jobs").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"a cat\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.type").value("image_generation"))
                .andExpect(jsonPath("$.status").value("running"));
    }

    @Test
    void projectId指定時はメンバー判定を行う() throws Exception {
        when(starter.start(any(), any())).thenReturn(
                new GenerationJobSummary(7L, "image_generation", "running", null, null));

        mvc.perform(post("/api/ai/image/jobs").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"a cat\",\"projectId\":5}"))
                .andExpect(status().isAccepted());

        verify(authorization).requireProjectMemberOrAdmin(5L);
    }

    @Test
    void メンバーでなければ403でジョブを作らない() throws Exception {
        doThrow(new ForbiddenException("no")).when(authorization).requireProjectMemberOrAdmin(5L);

        mvc.perform(post("/api/ai/image/jobs").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"a cat\",\"projectId\":5}"))
                .andExpect(status().isForbidden());

        verify(starter, never()).start(any(), any());
    }

    @Test
    void 入力検証エラーは400でジョブを作らない() throws Exception {
        mvc.perform(post("/api/ai/image/jobs").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(starter, never()).start(any(), any());
    }

    @Test
    void プロバイダの枚数上限超過は400で返る() throws Exception {
        when(starter.start(any(), any())).thenThrow(new UnsupportedBatchSizeException("CHATGPT 10"));

        mvc.perform(post("/api/ai/image/jobs").header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prompt\":\"a cat\",\"batchSize\":11}"))
                .andExpect(status().isBadRequest());
    }
}
