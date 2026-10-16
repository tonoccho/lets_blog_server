package com.letsblog.media.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.media.ai.AiServiceException;
import com.letsblog.media.service.CmsBridgeException;
import com.letsblog.media.testsupport.FakeHttpServer;
import com.letsblog.media.testsupport.FakeHttpServer.Response;
import com.letsblog.media.testsupport.ServiceTokenFixtures;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

/**
 * #1820: 他サービスが本文の無い200を返したとき、および呼び出し元のBearerが無い/空/有るときの分岐。
 * HTTPのサーバー側フェイクに対して、各{@code *Client}の実物を通す。
 */
class ClientEmptyResponseTest {

    private FakeHttpServer server;

    @BeforeEach
    void setUp() {
        server = new FakeHttpServer();
        server.json("POST", ServiceTokenFixtures.TOKEN_PATH,
                "{\"access_token\":\"" + ServiceTokenFixtures.TOKEN + "\",\"expires_in\":3600}");
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private CmsBridgeClient cms() {
        return new CmsBridgeClient(RestClient.builder(), server.baseUrl(),
                ServiceTokenFixtures.client(server.baseUrl()));
    }

    @Test
    void uploadMediaはContentTypeが無ければoctet_streamで送る() {
        server.json("POST", "/api/internal/publishing/sites/s1/media", "{\"url\":\"/m/a\"}");

        cms().uploadMedia("s1", "a.bin", null, new byte[] {1, 2}, "Bearer u");

        assertThat(server.last().header("Content-Type")).startsWith("multipart/form-data");
        assertThat(server.last().bodyText()).contains("application/octet-stream");
    }

    @Test
    void uploadMediaはContentTypeがあればそれで送る() {
        server.json("POST", "/api/internal/publishing/sites/s1/media", "{\"url\":\"/m/a\"}");

        cms().uploadMedia("s1", "a.png", "image/png", new byte[] {1, 2}, "Bearer u");

        assertThat(server.last().bodyText()).contains("image/png");
    }

    @Test
    void uploadMediaは本文の無い応答をCmsBridgeExceptionにする() {
        server.on("POST", "/api/internal/publishing/sites/s1/media", Response.empty(200));

        assertThatThrownBy(() -> cms().uploadMedia("s1", "a.png", "image/png", new byte[] {1}, "Bearer u"))
                .isInstanceOf(CmsBridgeException.class)
                .hasMessageContaining("空の応答");
    }

    @Test
    void resolveProjectIdBySiteKeyは本文の無い応答をCmsBridgeExceptionにする() {
        server.on("GET", "/api/internal/publishing/sites/s1/project-id", Response.empty(200));

        assertThatThrownBy(() -> cms().resolveProjectIdBySiteKey("s1", "Bearer u"))
                .isInstanceOf(CmsBridgeException.class)
                .hasMessageContaining("空の応答");
    }

    @Test
    void scanMediaは本文の無い応答をCmsBridgeExceptionにする() {
        server.on("GET", "/api/internal/publishing/projects/3/media-scan", Response.empty(200));

        assertThatThrownBy(() -> cms().scanMedia(3L, "test", "Bearer u"))
                .isInstanceOf(CmsBridgeException.class)
                .hasMessageContaining("空の応答");
    }

    @Test
    void AiGenerationClientのgenerateは本文の無い応答をAiServiceExceptionにする() {
        server.on("POST", "/api/internal/ai/generate", Response.empty(200));
        AiGenerationClient client = new AiGenerationClient(
                RestClient.builder(), server.baseUrl(),
                new OutboundAuthHeaders(new MockHttpServletRequest(), ServiceTokenFixtures.client(server.baseUrl())),
                CircuitBreakerRegistry.ofDefaults());

        assertThatThrownBy(() -> client.generate(1L, "p", null))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("空の応答");
    }

    @Test
    void PlatformServiceClientは本文の無い画像生成設定をIllegalStateExceptionにする() {
        server.on("GET", "/api/internal/platform/image-generation-config", Response.empty(200));
        PlatformServiceClient client = new PlatformServiceClient(
                RestClient.builder(), server.baseUrl(), ServiceTokenFixtures.client(server.baseUrl()),
                new AiServiceConnectionClient(
                        RestClient.builder(), server.baseUrl(), ServiceTokenFixtures.client(server.baseUrl())));

        assertThatThrownBy(() -> client.comfyUiBaseUrl(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("空の応答");
    }

    @Test
    void IdentityBridgeClientはBearerがnull_空_有りで送る内容が変わる() {
        server.json("GET", "/api/internal/identity/projects/1/members/2", "true");
        IdentityBridgeClient client = new IdentityBridgeClient(RestClient.builder(), server.baseUrl());

        assertThat(client.isProjectMember(1L, 2L, null)).isTrue();
        assertThat(server.last().header("Authorization")).isNull();
        assertThat(client.isProjectMember(1L, 2L, " ")).isTrue();
        assertThat(server.last().header("Authorization")).isNull();
        assertThat(client.isProjectMember(1L, 2L, "Bearer u")).isTrue();
        assertThat(server.last().header("Authorization")).isEqualTo("Bearer u");
    }
}
