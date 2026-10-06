package com.letsblog.ai.integration;

import com.letsblog.ai.client.IdentityBridgeClient;
import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.domain.ReviewStepKey;
import com.letsblog.ai.repository.GenerationJobRepository;
import com.letsblog.ai.repository.ProjectReviewStepSettingRepository;
import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1222: レビュー関連API(#1211のステップ別モデル設定API、#1213のステップ別指摘生成API)の
 * 入力検証を、実際のControllerを通して検証する統合テスト。単体テスト
 * ({@link com.letsblog.ai.controller.ProjectLlmModelControllerTest}等)は正当なenum値だけを
 * 使ってサービスへ委譲することしか確認できず、「パス変数に未知の文字列を渡す」というAC1/AC2の
 * 入力そのものを再現できない(コンパイル時にReviewStepKeyのenum定数しか渡せないため)ため、
 * MockMvc経由の統合テストとして追加する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ai-service: レビュー関連APIの入力検証統合テスト(issue #1222)")
class ReviewApiInputValidationIntegrationTest {

    private static final String ADMIN_TOKEN = "review-validation-admin-token";
    private static final String MEMBER_TOKEN = "review-validation-member-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectReviewStepSettingRepository reviewStepSettingRepository;

    @Autowired
    private GenerationJobRepository generationJobRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    @MockitoBean
    private IdentityBridgeClient identityBridgeClient;

    @MockitoBean
    private PlatformServiceClient platformServiceClient;

    private void stubAdmin() {
        when(jwtDecoder.decode(ADMIN_TOKEN)).thenReturn(JwtTestFixtures.jwt("sub-admin-1222", "admin"));
        when(identityClient.lookupProfile("Bearer " + ADMIN_TOKEN))
                .thenReturn(Optional.of(new ActorProfile(900L, "admin")));
        // listSettings()がllmConfigProvider.availableModels()経由でplatform-serviceへ問い合わせるため、
        // 実際のHTTP到達先が無いテスト環境(application-test.yml)では未モックだと接続失敗が
        // IllegalStateException→409に化けてしまう(GlobalExceptionHandler#handleIllegalState)。
        // 本テストが検証したいのは入力検証(400/409の作り分け)であり、この経路の可用性ではないため
        // モックする。
        when(platformServiceClient.resolveLlmConfig(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new PlatformServiceClient.LlmConfig(
                        "OPENAI", "https://api.openai.com", "test-api-key", "gpt-4o-mini", List.of("gpt-4o-mini"), 30));
    }

    private void stubProjectMember(long projectId) {
        when(jwtDecoder.decode(MEMBER_TOKEN)).thenReturn(JwtTestFixtures.jwt("sub-member-1222", "user"));
        when(identityClient.lookupProfile("Bearer " + MEMBER_TOKEN))
                .thenReturn(Optional.of(new ActorProfile(901L, "user")));
        when(identityBridgeClient.isProjectMember(projectId, 901L, "Bearer " + MEMBER_TOKEN)).thenReturn(true);
    }

    @Test
    @DisplayName("AC1: 未知のステップキーを指定した設定の保存は400を返し、設定行が作られない")
    void 設定保存で未知のステップキーは400を返し設定行を作らない() throws Exception {
        stubAdmin();
        long projectId = 91001L;
        assertThat(reviewStepSettingRepository.findByProjectId(projectId)).isEmpty();

        mockMvc.perform(put("/api/projects/" + projectId + "/ai-models/llm/review-steps/NOT_A_REAL_STEP")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\": \"OPENAI\", \"model\": \"gpt-4o\"}"))
                .andExpect(status().isBadRequest());

        assertThat(reviewStepSettingRepository.findByProjectId(projectId)).isEmpty();
    }

    @Test
    @DisplayName("AC2: 未知のステップキーを指定した指摘生成は400を返し、generation_jobsに失敗記録が残る")
    void 指摘生成で未知のステップキーは400を返しgenerationJobsに失敗記録を残す() throws Exception {
        long projectId = 91002L;
        stubProjectMember(projectId);

        mockMvc.perform(post("/api/projects/" + projectId + "/ai/review-steps/NOT_A_REAL_STEP/suggestions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MEMBER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"本文\"}"))
                .andExpect(status().isBadRequest());

        // issue #1222 Requirements「上記のいずれの場合も、generation_jobsに失敗として記録が残る」:
        // 未知のステップキーによる検証失敗はジョブが開かれる前にコントローラで弾かれていたため、
        // このAC2のケースだけ記録が残らないという指摘(レビュー2026-09-28)への対応。
        GenerationJob latestJob = generationJobRepository
                .findAllByOrderByCreatedAtDesc(PageRequest.of(0, 1))
                .getContent()
                .get(0);
        assertThat(latestJob.getStatus()).isEqualTo("failed");
        assertThat(latestJob.getRequestPayload()).contains("NOT_A_REAL_STEP");
    }

    @Test
    @DisplayName("AC3: 未知のプロバイダー名を指定した設定の保存は400を返す")
    void 設定保存で未知のプロバイダー名は400を返す() throws Exception {
        stubAdmin();
        long projectId = 91003L;

        mockMvc.perform(put("/api/projects/" + projectId + "/ai-models/llm/review-steps/" + ReviewStepKey.JAPANESE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\": \"NOT_A_REAL_PROVIDER\", \"model\": \"gpt-4o\"}"))
                .andExpect(status().isBadRequest());

        assertThat(reviewStepSettingRepository.findByProjectId(projectId)).isEmpty();
    }

    @Test
    @DisplayName("正常系(#1211): 既知のステップキー・プロバイダーでの保存は引き続き200")
    void 設定保存で既知のステップキーとプロバイダーは成功する() throws Exception {
        stubAdmin();
        long projectId = 91004L;

        mockMvc.perform(put("/api/projects/" + projectId + "/ai-models/llm/review-steps/" + ReviewStepKey.JAPANESE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\": \"OPENAI\", \"model\": \"gpt-4o\"}"))
                .andExpect(status().isOk());

        assertThat(reviewStepSettingRepository.findByProjectId(projectId)).hasSize(1);
    }

    @Test
    @DisplayName("正常系(#1211): provider/modelを空文字にすると上書きを解除できる(400にならない)")
    void 設定保存で空文字を渡すと上書き解除として成功する() throws Exception {
        stubAdmin();
        long projectId = 91005L;

        mockMvc.perform(put("/api/projects/" + projectId + "/ai-models/llm/review-steps/" + ReviewStepKey.STYLE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\": \"\", \"model\": \"\"}"))
                .andExpect(status().isOk());
    }
}
