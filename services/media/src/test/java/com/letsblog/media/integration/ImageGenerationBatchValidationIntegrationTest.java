package com.letsblog.media.integration;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.media.domain.ProjectImageSettings;
import com.letsblog.media.repository.GeneratedImageRepository;
import com.letsblog.media.repository.ProjectImageSettingsRepository;
import com.letsblog.media.testsupport.ServiceStubbedIntegrationTest;
import com.letsblog.media.testsupport.ServiceStubs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
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
 * <p>issue #1820: 外部境界(identity-service・platform-service・ai-service・ComfyUI・OpenAI)はフェイクHTTPサーバー
 * ({@code ServiceStubs})が演じ、実物のクライアント・サービスが通信する。生成が始まらないことは、フェイクが
 * ComfyUI・OpenAI・{@code generation_jobs}への要求を1件も受けていないことと、{@code generated_images}の行が
 * 増えていないことで確かめる。プロバイダの選択は実DBの{@code project_image_settings}で用意する。
 */
@DisplayName("media-service: batchSize/batchCountの上限とHTTPステータス(issue #1102)")
class ImageGenerationBatchValidationIntegrationTest extends ServiceStubbedIntegrationTest {

    private static final String PATH = "/api/ai/image";
    private static final long CHATGPT_PROJECT_ID = 110201L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private GeneratedImageRepository generatedImageRepository;
    @Autowired
    private ProjectImageSettingsRepository projectImageSettingsRepository;

    private long imagesBefore;

    @BeforeEach
    void setUpStubs() {
        imagesBefore = generatedImageRepository.count();
        ServiceStubs.HTTP.json("GET", "/api/internal/platform/image-generation-config",
                "{\"comfyUiBaseUrl\":\"" + ServiceStubs.HTTP.baseUrl() + "\"}");
        ServiceStubs.HTTP.json("POST", "/api/internal/ai/generation-jobs",
                "{\"id\":1,\"type\":\"comfyui_image\",\"status\":\"running\"}");
        ServiceStubs.HTTP.json("GET", "/api/internal/project/projects/" + CHATGPT_PROJECT_ID,
                "{\"id\":" + CHATGPT_PROJECT_ID + ",\"name\":\"p\",\"slug\":\"p\"}");
    }

    @AfterEach
    void cleanUp() {
        projectImageSettingsRepository.findByProjectId(CHATGPT_PROJECT_ID)
                .ifPresent(projectImageSettingsRepository::delete);
    }

    private org.springframework.test.web.servlet.ResultActions postImage(String body) throws Exception {
        when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-1", "admin"));
        ServiceStubs.profile("Bearer admin-jwt", 1L, "admin");
        return mockMvc.perform(post(PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void useChatGptFor(long projectId) {
        ProjectImageSettings settings = new ProjectImageSettings(projectId);
        settings.setImageProvider("CHATGPT");
        projectImageSettingsRepository.save(settings);
    }

    private void assertGenerationNeverStarted() {
        assertThat(ServiceStubs.HTTP.requests("POST", "/prompt")).isEmpty();
        assertThat(ServiceStubs.HTTP.requests("POST", "/images/(generations|edits)")).isEmpty();
        assertThat(ServiceStubs.HTTP.requests("POST", "/api/internal/ai/generation-jobs")).isEmpty();
        assertThat(generatedImageRepository.count()).isEqualTo(imagesBefore);
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
        postImage("{\"prompt\":\"a cat\",\"batchSize\":16,\"batchCount\":16}")
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(400));
    }

    @Test
    @DisplayName("CHATGPTでbatchSize=11は400で、メッセージにプロバイダ名と上限10を含む")
    void chatgptでbatchSize11は400() throws Exception {
        useChatGptFor(CHATGPT_PROJECT_ID);

        String body = postImage("{\"prompt\":\"a cat\",\"projectId\":" + CHATGPT_PROJECT_ID + ",\"batchSize\":11}")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("CHATGPT");
        assertThat(body).contains("10");
        assertGenerationNeverStarted();
    }
}
