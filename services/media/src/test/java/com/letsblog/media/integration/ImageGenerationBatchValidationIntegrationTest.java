package com.letsblog.media.integration;

import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.media.ai.ChatGptImageClient;
import com.letsblog.media.ai.ComfyUiClient;
import com.letsblog.media.ai.ImageProvider;
import com.letsblog.media.client.GenerationJobClient;
import com.letsblog.media.service.GeneratedImageCreationService;
import com.letsblog.media.service.ImageModelService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1102: {@code POST /api/ai/image}のbatchSize/batchCountの上限が、
 * <b>HTTPの入口で400として弾かれ、生成が始まらない</b>ことを検証する統合テスト。
 *
 * <p>サービス単体テスト({@code ImageGenerationServiceTest})では、Bean Validation
 * ({@code @Max})を通らないため「上限を超えたリクエストが400になる」ことを確かめられない。
 * 逆にここでは、生成が始まらない=ComfyUI/ChatGPTクライアントと保存サービスが
 * 1度も呼ばれないことまで見る(受入基準「{@code generated_images}に行が増えない」)。
 *
 * <p>CHATGPTのプロバイダ別上限(n ≤ 10)だけはBean Validationでは表現できない
 * (プロバイダはプロジェクト設定から実行時に決まる)。上限超過が400になり、
 * メッセージにプロバイダ名と上限を含むことをここで確かめる。
 *
 * <p>外部境界(identity-service・ComfyUI・OpenAI・generation_jobs)は
 * {@code @MockitoBean}で置き換える(ADR-0006)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("media-service: batchSize/batchCountの上限とHTTPステータス(issue #1102)")
class ImageGenerationBatchValidationIntegrationTest {

    private static final String PATH = "/api/ai/image";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    @MockitoBean
    private ImageModelService imageModelService;

    @MockitoBean
    private ComfyUiClient comfyUiClient;

    @MockitoBean
    private ChatGptImageClient chatGptImageClient;

    @MockitoBean
    private GeneratedImageCreationService generatedImageCreationService;

    @MockitoBean
    private GenerationJobClient generationJobClient;

    private org.springframework.test.web.servlet.ResultActions postImage(String body) throws Exception {
        when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-1", "admin"));
        return mockMvc.perform(post(PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void assertGenerationNeverStarted() {
        verify(comfyUiClient, never()).generateImage(any());
        verify(chatGptImageClient, never()).generateImage(any());
        verify(generatedImageCreationService, never()).create(any());
        verify(generationJobClient, never()).create(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("batchSize=17は400で、生成は始まらない")
    void batchSize17は400() throws Exception {
        postImage("{\"prompt\":\"a cat\",\"batchSize\":17}").andExpect(status().isBadRequest());

        assertGenerationNeverStarted();
    }

    @Test
    @DisplayName("batchSize=16は上限内なので400にならない")
    void batchSize16は400にならない() throws Exception {
        when(imageModelService.getSelectedProvider(any())).thenReturn(ImageProvider.COMFYUI);
        when(comfyUiClient.generateImage(any())).thenReturn(java.util.List.of());
        when(generationJobClient.create(anyString(), anyString(), any()))
                .thenReturn(new com.letsblog.media.client.GenerationJobSummary(1L, "comfyui_image", "running", null, null));

        postImage("{\"prompt\":\"a cat\",\"batchSize\":16}")
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(400));
    }

    @Test
    @DisplayName("batchCount=0は400で、生成は始まらない")
    void batchCount0は400() throws Exception {
        postImage("{\"prompt\":\"a cat\",\"batchCount\":0}").andExpect(status().isBadRequest());

        assertGenerationNeverStarted();
    }

    @Test
    @DisplayName("batchCount=17は400で、生成は始まらない")
    void batchCount17は400() throws Exception {
        postImage("{\"prompt\":\"a cat\",\"batchCount\":17}").andExpect(status().isBadRequest());

        assertGenerationNeverStarted();
    }

    @Test
    @DisplayName("batchSize=16・batchCount=16は合計上限が無いので400にならない")
    void batchSize16かつbatchCount16は400にならない() throws Exception {
        when(imageModelService.getSelectedProvider(any())).thenReturn(ImageProvider.COMFYUI);
        when(comfyUiClient.generateImage(any())).thenReturn(java.util.List.of());
        when(generationJobClient.create(anyString(), anyString(), any()))
                .thenReturn(new com.letsblog.media.client.GenerationJobSummary(1L, "comfyui_image", "running", null, null));

        postImage("{\"prompt\":\"a cat\",\"batchSize\":16,\"batchCount\":16}")
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(400));
    }

    @Test
    @DisplayName("CHATGPTでbatchSize=11は400で、メッセージにプロバイダ名と上限10を含む")
    void chatgptでbatchSize11は400() throws Exception {
        when(imageModelService.getSelectedProvider(any())).thenReturn(ImageProvider.CHATGPT);

        String body = postImage("{\"prompt\":\"a cat\",\"batchSize\":11}")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("CHATGPT");
        assertThat(body).contains("10");
        assertGenerationNeverStarted();
    }
}
