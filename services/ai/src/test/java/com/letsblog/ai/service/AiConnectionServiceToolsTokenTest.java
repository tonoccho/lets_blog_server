package com.letsblog.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.client.PlatformServiceClient.AiConnectionsConfig;
import com.letsblog.ai.client.PlatformServiceClient.ProviderConnectionConfig;
import com.letsblog.ai.dto.AiConnectionResponse;
import com.letsblog.ai.dto.AiConnectionResponse.Provider;
import com.letsblog.ai.dto.AiConnectionResponse.Status;
import com.letsblog.common.auth.ServiceTokenUnavailableException;
import com.letsblog.common.auth.ToolsTokenProvider;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 接続テスト(Ollama・ComfyUIの疎通確認)で付けるトークン(#1804)。 */
class AiConnectionServiceToolsTokenTest {

    private static final String OLLAMA = "http://ollama:11434/v1";
    private static final String COMFY = "http://comfy:8188";

    private final PlatformServiceClient platform = mock(PlatformServiceClient.class);
    private final CurrentActorService actor = mock(CurrentActorService.class);
    private final ProjectAiSettingsService settings = mock(ProjectAiSettingsService.class);
    private final AtomicInteger requests = new AtomicInteger();

    private AiConnectionService service(String ollamaSource, String comfySource, ToolsTokenProvider tools, boolean expectToken) {
        when(actor.getAuthorizationHeader()).thenReturn("Bearer t");
        when(platform.resolveAiConnectionsConfig("Bearer t")).thenReturn(new AiConnectionsConfig(
                new ProviderConnectionConfig(OLLAMA, ollamaSource, true),
                new ProviderConnectionConfig(COMFY, comfySource, true),
                new ProviderConnectionConfig(null, "NONE", false),
                new ProviderConnectionConfig(null, "NONE", false)));
        return new AiConnectionService(platform, actor, settings, baseUrl -> {
            RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
            String path = baseUrl.equals(OLLAMA) ? "/models" : "/system_stats";
            String source = baseUrl.equals(OLLAMA) ? ollamaSource : comfySource;
            var expectation = server.expect(requestTo(baseUrl + path));
            if (expectToken && !"PROJECT".equals(source)) {
                expectation.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-1"));
            } else {
                expectation.andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION));
            }
            expectation.andRespond(r -> {
                requests.incrementAndGet();
                return withStatus(HttpStatus.OK).createResponse(r);
            });
            return builder;
        }, Duration.ofSeconds(3), tools);
    }

    private static AiConnectionResponse row(List<AiConnectionResponse> rows, Provider provider) {
        return rows.stream().filter(r -> r.provider() == provider).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("システム設定(DB・環境変数)の接続先の疎通確認に、専用クライアントのトークンが付く")
    void システム設定の接続先にはトークンが付く() {
        List<AiConnectionResponse> rows = service("DATABASE", "ENVIRONMENT",
                ToolsTokenProvider.fromSupplier(() -> "tok-1"), true).listConnections(1L);

        assertThat(row(rows, Provider.OLLAMA).status()).isEqualTo(Status.NORMAL);
        assertThat(row(rows, Provider.COMFYUI).status()).isEqualTo(Status.NORMAL);
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("プロジェクト単位の上書き先には付かない")
    void 上書き先には付かない() {
        List<AiConnectionResponse> rows = service("PROJECT", "PROJECT",
                ToolsTokenProvider.fromSupplier(() -> "tok-1"), true).listConnections(1L);

        assertThat(row(rows, Provider.OLLAMA).status()).isEqualTo(Status.NORMAL);
        assertThat(row(rows, Provider.COMFYUI).status()).isEqualTo(Status.NORMAL);
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("secret未設定(無効)なら付かない")
    void 無効なら付かない() {
        List<AiConnectionResponse> rows = service("DATABASE", "DATABASE", ToolsTokenProvider.disabled(), false)
                .listConnections(1L);

        assertThat(row(rows, Provider.OLLAMA).status()).isEqualTo(Status.NORMAL);
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("トークンが取れないときは、送らずにエラーとして理由を結果に出す")
    void 取れないときは結果に理由を出す() {
        List<AiConnectionResponse> rows = service("DATABASE", "DATABASE", ToolsTokenProvider.fromSupplier(() -> {
            throw new ServiceTokenUnavailableException("サービストークンの取得に失敗しました(client_id=letsblog-tools-client): 401");
        }), true).listConnections(1L);

        for (Provider provider : List.of(Provider.OLLAMA, Provider.COMFYUI)) {
            AiConnectionResponse row = row(rows, provider);
            assertThat(row.status()).isEqualTo(Status.ERROR);
            assertThat(row.detail()).contains("KEYCLOAK_TOOLS_CLIENT_SECRET").contains("401");
        }
        assertThat(requests.get()).isZero();
    }
}
