package com.letsblog.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.client.PlatformServiceClient.AiConnectionsConfig;
import com.letsblog.ai.client.PlatformServiceClient.ProviderConnectionConfig;
import com.letsblog.ai.dto.AiConnectionResponse;
import com.letsblog.ai.dto.AiConnectionResponse.Provider;
import com.letsblog.ai.dto.AiConnectionResponse.Source;
import com.letsblog.ai.dto.AiConnectionResponse.Status;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * AiConnectionService(issue #1499)。OpenAI/Claudeは実HTTPを送らずAPIキーの有無だけで判定し、
 * Ollama/ComfyUIだけをConnectedServiceStatusServiceと同じ規約(4xxも到達可能、5xxは警告、
 * 接続不可はエラー)で疎通確認することを検証する。
 */
@ExtendWith(MockitoExtension.class)
class AiConnectionServiceTest {

    private static final String OLLAMA = "http://ollama:11434/v1";
    private static final String COMFY = "http://comfy:8188";

    @Mock
    private PlatformServiceClient platformServiceClient;
    @Mock
    private CurrentActorService currentActorService;
    @Mock
    private ProjectAiSettingsService projectAiSettingsService;

    /** 疎通確認のために組み立てたbaseUrlの記録。OpenAI/Anthropicへの実リクエストが無いことの確認に使う。 */
    private final List<String> builtBaseUrls = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer t");
    }

    private Function<String, RestClient.Builder> mockFactory() {
        return baseUrl -> {
            builtBaseUrls.add(baseUrl);
            return RestClient.builder().baseUrl(baseUrl);
        };
    }

    private AiConnectionService service(Function<String, RestClient.Builder> factory, Duration timeout) {
        return new AiConnectionService(
                platformServiceClient, currentActorService, projectAiSettingsService, factory, timeout);
    }

    private AiConnectionService service() {
        return service(mockFactory(), Duration.ofSeconds(3));
    }

    private static ProviderConnectionConfig cfg(String baseUrl, String source, boolean configured) {
        return new ProviderConnectionConfig(baseUrl, source, configured);
    }

    private void platformReturns(
            ProviderConnectionConfig ollama, ProviderConnectionConfig comfy,
            ProviderConnectionConfig openai, ProviderConnectionConfig claude) {
        when(platformServiceClient.resolveAiConnectionsConfig("Bearer t"))
                .thenReturn(new AiConnectionsConfig(ollama, comfy, openai, claude));
    }

    private void allConfigured() {
        platformReturns(
                cfg(OLLAMA, "ENVIRONMENT", true), cfg(COMFY, "DATABASE", true),
                cfg(null, "DATABASE", true), cfg(null, "ENVIRONMENT", true));
    }

    private static AiConnectionResponse row(List<AiConnectionResponse> rows, Provider provider) {
        return rows.stream().filter(r -> r.provider() == provider).findFirst().orElseThrow();
    }

    /** 接続先ごとに、指定した結果(ok/404/500/それ以外=接続拒否)を返すMockRestServiceServerを束縛する。 */
    private Function<String, RestClient.Builder> factoryRespondingTo(
            String ollamaResult, String comfyResult) {
        return baseUrl -> {
            builtBaseUrls.add(baseUrl);
            RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            String result = baseUrl.equals(OLLAMA) ? ollamaResult : comfyResult;
            String path = baseUrl.equals(OLLAMA) ? "/models" : "/system_stats";
            server.expect(requestTo(baseUrl + path)).andExpect(method(HttpMethod.GET))
                    .andRespond(switch (result) {
                        case "ok" -> withStatus(HttpStatus.OK);
                        case "404" -> withStatus(HttpStatus.NOT_FOUND);
                        case "500" -> withStatus(HttpStatus.INTERNAL_SERVER_ERROR);
                        default -> withException(new IOException("Connection refused"));
                    });
            return builder;
        };
    }

    @Test
    void 未設定のプロバイダーも含め4件が固定順で必ず返る() {
        platformReturns(cfg(null, "NONE", false), cfg(null, "NONE", false),
                cfg(null, "NONE", false), cfg(null, "NONE", false));

        List<AiConnectionResponse> rows = service().listConnections(1L);

        assertEquals(
                List.of(Provider.OLLAMA, Provider.COMFYUI, Provider.OPENAI, Provider.CLAUDE),
                rows.stream().map(AiConnectionResponse::provider).toList());
        assertTrue(rows.stream().noneMatch(AiConnectionResponse::configured));
        assertEquals("Ollama", row(rows, Provider.OLLAMA).displayName());
        assertEquals("ComfyUI", row(rows, Provider.COMFYUI).displayName());
        assertEquals("ChatGPT", row(rows, Provider.OPENAI).displayName());
        assertEquals("Claude", row(rows, Provider.CLAUDE).displayName());
    }

    @Test
    void プロジェクトの上書きがあればその接続先をPROJECTとして疎通確認する() {
        allConfigured();
        when(projectAiSettingsService.getOllamaBaseUrl(1L)).thenReturn("http://gpu:11434/v1");
        when(projectAiSettingsService.getComfyuiBaseUrl(1L)).thenReturn("http://gpu:8188");
        Function<String, RestClient.Builder> factory = baseUrl -> {
            builtBaseUrls.add(baseUrl);
            RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            String path = baseUrl.contains("11434") ? "/models" : "/system_stats";
            server.expect(requestTo(baseUrl + path)).andRespond(withStatus(HttpStatus.OK));
            return builder;
        };

        List<AiConnectionResponse> rows = service(factory, Duration.ofSeconds(3)).listConnections(1L);

        assertEquals(Source.PROJECT, row(rows, Provider.OLLAMA).source());
        assertEquals("http://gpu:11434/v1/models", row(rows, Provider.OLLAMA).targetUrl());
        assertEquals(Source.PROJECT, row(rows, Provider.COMFYUI).source());
        assertEquals("http://gpu:8188/system_stats", row(rows, Provider.COMFYUI).targetUrl());
        assertTrue(builtBaseUrls.containsAll(List.of("http://gpu:11434/v1", "http://gpu:8188")));
        assertFalse(builtBaseUrls.contains(OLLAMA));
    }

    @Test
    void OllamaとComfyUIのURL未設定なら疎通確認せず警告にする() {
        platformReturns(cfg("", "NONE", false), cfg(null, "NONE", false),
                cfg(null, "NONE", false), cfg(null, "NONE", false));

        List<AiConnectionResponse> rows = service().listConnections(1L);

        AiConnectionResponse ollama = row(rows, Provider.OLLAMA);
        assertEquals(Status.WARNING, ollama.status());
        assertFalse(ollama.configured());
        assertNull(ollama.targetUrl());
        assertNotNull(ollama.detail());
        assertEquals(Status.WARNING, row(rows, Provider.COMFYUI).status());
        assertTrue(builtBaseUrls.isEmpty(), "URL未設定では疎通確認を行わない");
    }

    @Test
    void 到達可能ならNORMALで詳細はnullかつ接続先は確認に使うURL() {
        allConfigured();

        List<AiConnectionResponse> rows = service(factoryRespondingTo("ok", "ok"), Duration.ofSeconds(3))
                .listConnections(1L);

        AiConnectionResponse ollama = row(rows, Provider.OLLAMA);
        assertEquals(Status.NORMAL, ollama.status());
        assertNull(ollama.detail());
        assertEquals(OLLAMA + "/models", ollama.targetUrl());
        assertEquals(Source.ENVIRONMENT, ollama.source());
        assertTrue(ollama.configured());
        AiConnectionResponse comfy = row(rows, Provider.COMFYUI);
        assertEquals(Status.NORMAL, comfy.status());
        assertEquals(COMFY + "/system_stats", comfy.targetUrl());
        assertEquals(Source.DATABASE, comfy.source());
    }

    @Test
    void 接続拒否ならERRORで理由を返し他の行に影響しない() {
        allConfigured();

        List<AiConnectionResponse> rows = service(factoryRespondingTo("refused", "ok"), Duration.ofSeconds(3))
                .listConnections(1L);

        AiConnectionResponse ollama = row(rows, Provider.OLLAMA);
        assertEquals(Status.ERROR, ollama.status());
        assertTrue(ollama.detail().contains("Connection refused"), ollama.detail());
        assertEquals(Status.NORMAL, row(rows, Provider.COMFYUI).status());
        assertEquals(Status.NORMAL, row(rows, Provider.OPENAI).status());
        assertEquals(Status.NORMAL, row(rows, Provider.CLAUDE).status());
    }

    @Test
    void 応答が4xxでも到達可能とみなしNORMAL() {
        allConfigured();

        List<AiConnectionResponse> rows = service(factoryRespondingTo("404", "404"), Duration.ofSeconds(3))
                .listConnections(1L);

        assertEquals(Status.NORMAL, row(rows, Provider.OLLAMA).status());
        assertNull(row(rows, Provider.OLLAMA).detail());
        assertEquals(Status.NORMAL, row(rows, Provider.COMFYUI).status());
    }

    @Test
    void 応答が5xxならWARNINGで理由を返す() {
        allConfigured();

        List<AiConnectionResponse> rows = service(factoryRespondingTo("500", "ok"), Duration.ofSeconds(3))
                .listConnections(1L);

        AiConnectionResponse ollama = row(rows, Provider.OLLAMA);
        assertEquals(Status.WARNING, ollama.status());
        assertNotNull(ollama.detail());
        assertEquals(Status.NORMAL, row(rows, Provider.COMFYUI).status());
    }

    @Test
    void ComfyUIが接続拒否でもOllamaは影響を受けない() {
        allConfigured();

        List<AiConnectionResponse> rows = service(factoryRespondingTo("ok", "refused"), Duration.ofSeconds(3))
                .listConnections(1L);

        assertEquals(Status.NORMAL, row(rows, Provider.OLLAMA).status());
        assertEquals(Status.ERROR, row(rows, Provider.COMFYUI).status());
        assertNotNull(row(rows, Provider.COMFYUI).detail());
    }

    @Test
    void APIキー設定済みならconfiguredでNORMAL_未設定ならWARNINGで実リクエストは送らない() {
        platformReturns(cfg(null, "NONE", false), cfg(null, "NONE", false),
                cfg(null, "DATABASE", true), cfg(null, "NONE", false));

        List<AiConnectionResponse> rows = service().listConnections(1L);

        AiConnectionResponse openai = row(rows, Provider.OPENAI);
        assertTrue(openai.configured());
        assertEquals(Status.NORMAL, openai.status());
        assertNull(openai.detail());
        assertNull(openai.targetUrl());
        assertEquals(Source.DATABASE, openai.source());
        AiConnectionResponse claude = row(rows, Provider.CLAUDE);
        assertFalse(claude.configured());
        assertEquals(Status.WARNING, claude.status());
        assertNotNull(claude.detail());
        assertNull(claude.targetUrl());
        assertEquals(Source.NONE, claude.source());
        assertTrue(builtBaseUrls.isEmpty(), "OpenAI/Claudeでは一切HTTPクライアントを組み立てない: " + builtBaseUrls);
    }

    @Test
    void 応答全文に設定済みAPIキーの値が含まれない() throws Exception {
        allConfigured();

        String json = new ObjectMapper().writeValueAsString(
                service(factoryRespondingTo("ok", "refused"), Duration.ofSeconds(3)).listConnections(1L));

        assertFalse(json.contains("sk-"), json);
        // 4件すべての項目名が期待どおりで、キーを入れうるフィールドが無い。
        for (String field : List.of("provider", "displayName", "targetUrl", "source", "status", "detail",
                "configured")) {
            assertTrue(json.contains("\"" + field + "\""), field);
        }
        assertFalse(json.toLowerCase().contains("apikey"), json);
    }

    @Test
    void 取得元の文字列をSourceへ変換し不明や欠落はNONEにする() {
        platformReturns(cfg(OLLAMA, "DATABASE", true), cfg(COMFY, "ENVIRONMENT", true),
                cfg(null, "something-else", true), cfg(null, null, false));

        List<AiConnectionResponse> rows = service(factoryRespondingTo("ok", "ok"), Duration.ofSeconds(3))
                .listConnections(1L);

        assertEquals(Source.DATABASE, row(rows, Provider.OLLAMA).source());
        assertEquals(Source.ENVIRONMENT, row(rows, Provider.COMFYUI).source());
        assertEquals(Source.NONE, row(rows, Provider.OPENAI).source());
        assertEquals(Source.NONE, row(rows, Provider.CLAUDE).source());
    }

    @Test
    void 設定オブジェクトの項目が欠けていても4件返す() {
        when(platformServiceClient.resolveAiConnectionsConfig("Bearer t"))
                .thenReturn(new AiConnectionsConfig(null, null, null, null));

        List<AiConnectionResponse> rows = service().listConnections(1L);

        assertEquals(4, rows.size());
        assertTrue(rows.stream().noneMatch(AiConnectionResponse::configured));
        assertTrue(rows.stream().allMatch(r -> r.status() == Status.WARNING));
        assertTrue(rows.stream().allMatch(r -> r.source() == Source.NONE));
    }

    @Test
    void 遅いプロバイダーはタイムアウトで打ち切られERRORになり他は正常に返る() {
        allConfigured();
        Function<String, RestClient.Builder> slowOllama = baseUrl -> {
            RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
            if (baseUrl.equals(OLLAMA)) {
                builder.requestFactory((uri, httpMethod) -> {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IOException("too slow");
                });
            } else {
                MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
                server.expect(requestTo(baseUrl + "/system_stats")).andRespond(withStatus(HttpStatus.OK));
            }
            return builder;
        };

        long started = System.currentTimeMillis();
        List<AiConnectionResponse> rows = service(slowOllama, Duration.ofMillis(200)).listConnections(1L);
        long elapsed = System.currentTimeMillis() - started;

        assertTrue(elapsed < 2500, "タイムアウトで打ち切られている: " + elapsed + "ms");
        AiConnectionResponse ollama = row(rows, Provider.OLLAMA);
        assertEquals(Status.ERROR, ollama.status());
        assertTrue(ollama.detail().contains("タイムアウト"), ollama.detail());
        assertEquals(Status.NORMAL, row(rows, Provider.COMFYUI).status());
        assertEquals(4, rows.size());
    }

    @Test
    void platform_serviceから設定を取得できなければ例外を伝播する() {
        when(platformServiceClient.resolveAiConnectionsConfig("Bearer t"))
                .thenThrow(new IllegalStateException("down"));

        assertThrows(IllegalStateException.class, () -> service().listConnections(1L));
    }
}
