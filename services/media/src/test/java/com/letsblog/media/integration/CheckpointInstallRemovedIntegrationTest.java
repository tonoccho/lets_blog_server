package com.letsblog.media.integration;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import java.util.stream.Stream;
import com.letsblog.media.testsupport.ServiceStubbedIntegrationTest;
import com.letsblog.media.testsupport.ServiceStubs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1746: ComfyUIはGPU PCで動く外部サーバーを指す構成になったため、Let's Blogの側から
 * チェックポイントを導入・削除するAPIは持たない(ComfyUIに追加・削除のAPIが無く、共有ボリュームへ
 * 直接書く方式は別PCでは効かない)。管理者として認証済みでも、4つのAPIは404になる。
 * 一覧と選択(GET .../checkpoints と PUT .../checkpoints/selection)は残る。
 */
@DisplayName("media-service: チェックポイントの導入・削除APIは無い(issue #1746)")
class CheckpointInstallRemovedIntegrationTest extends ServiceStubbedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    static Stream<Arguments> removedEndpoints() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/comfyui/checkpoints/install"),
                Arguments.of(HttpMethod.POST, "/api/comfyui/checkpoints/delete"),
                Arguments.of(HttpMethod.POST, "/api/projects/1/ai-models/comfyui/checkpoints/install"),
                Arguments.of(HttpMethod.DELETE, "/api/projects/1/ai-models/comfyui/checkpoints/model.safetensors"));
    }

    @ParameterizedTest(name = "[{index}] {0} {1}")
    @MethodSource("removedEndpoints")
    @DisplayName("管理者でも404")
    void 導入と削除のAPIは管理者でも404(HttpMethod method, String path) throws Exception {
        when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-admin", "admin"));
        ServiceStubs.profile("Bearer admin-jwt", 1L, "admin");

        mockMvc.perform(request(method, path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"downloadUrl\":\"https://example.com/m.safetensors\","
                                + "\"fileName\":\"m.safetensors\",\"jobId\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("選択のAPIは残っている(404ではない)")
    void 選択のAPIは残る() throws Exception {
        when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-admin", "admin"));
        ServiceStubs.profile("Bearer admin-jwt", 1L, "admin");

        mockMvc.perform(request(HttpMethod.PUT, "/api/projects/1/ai-models/comfyui/checkpoints/selection")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
