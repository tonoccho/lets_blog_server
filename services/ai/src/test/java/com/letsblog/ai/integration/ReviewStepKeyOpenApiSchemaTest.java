package com.letsblog.ai.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.ai.client.IdentityBridgeClient;
import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.domain.ReviewStepKey;
import com.letsblog.common.client.IdentityClient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1222 レビュー(2026-09-28)対応: {@code AiController}/{@code ProjectLlmModelController}の
 * {@code stepKey}パス変数を{@code @PathVariable ReviewStepKey}から生の{@code String}へ変更した際、
 * springdocはJavaの型からスキーマを推論するため、{@code @Schema}/{@code @Parameter}で明示しない限り
 * 生成される{@code /v3/api-docs}(ひいては再生成した{@code openapi/ai.json})から旧enum制約(5値)が
 * 失われる。実サービス(springdoc)から取得したspecが引き続きenum制約を持つことを検証する
 * (JwtDecoder/IdentityClient等はこのテストの関心事ではない外部境界のためモックする)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ai-service: stepKeyのOpenAPIスキーマがenum制約を保持すること(issue #1222)")
class ReviewStepKeyOpenApiSchemaTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    @MockitoBean
    private IdentityBridgeClient identityBridgeClient;

    @MockitoBean
    private PlatformServiceClient platformServiceClient;

    @Test
    @DisplayName("設定保存API(PUT .../review-steps/{stepKey})のstepKeyはReviewStepKeyの5値をenumとして持つ")
    void 設定保存APIのstepKeyスキーマはenum制約を持つ() throws Exception {
        JsonNode schema = stepKeyParameterSchema(
                "/api/projects/{id}/ai-models/llm/review-steps/{stepKey}", "put");

        assertThat(enumValues(schema)).containsExactlyInAnyOrderElementsOf(reviewStepKeyNames());
    }

    @Test
    @DisplayName("指摘生成API(POST .../review-steps/{stepKey}/suggestions)のstepKeyはReviewStepKeyの5値をenumとして持つ")
    void 指摘生成APIのstepKeyスキーマはenum制約を持つ() throws Exception {
        JsonNode schema = stepKeyParameterSchema(
                "/api/projects/{projectId}/ai/review-steps/{stepKey}/suggestions", "post");

        assertThat(enumValues(schema)).containsExactlyInAnyOrderElementsOf(reviewStepKeyNames());
    }

    private JsonNode stepKeyParameterSchema(String path, String httpMethod) throws Exception {
        String raw = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(raw);
        JsonNode parameters = root.path("paths").path(path).path(httpMethod).path("parameters");
        assertThat(parameters.isArray()).as("parameters for %s %s", httpMethod, path).isTrue();
        for (JsonNode parameter : parameters) {
            if ("stepKey".equals(parameter.path("name").asText())) {
                return parameter.path("schema");
            }
        }
        throw new AssertionError("stepKeyパラメータが見つかりません: " + httpMethod + " " + path);
    }

    private List<String> enumValues(JsonNode schema) {
        List<String> values = new ArrayList<>();
        schema.path("enum").forEach(node -> values.add(node.asText()));
        return values;
    }

    private List<String> reviewStepKeyNames() {
        return Arrays.stream(ReviewStepKey.values()).map(Enum::name).toList();
    }
}
