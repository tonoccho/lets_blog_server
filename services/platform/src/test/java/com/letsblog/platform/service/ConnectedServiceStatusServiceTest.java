package com.letsblog.platform.service;

import com.letsblog.platform.ai.AiProvider;
import com.letsblog.platform.dto.ConnectedServiceStatusDetailResponse;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.platform.render.PlantUmlEncoder;
import com.letsblog.platform.service.SystemSettingService.BraveSearchApiKeyStatus;
import com.letsblog.platform.service.SystemSettingService.SettingSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * legacy-apiから移設(issue #695、C10-3)。ConnectedServiceStatusServiceの回帰テスト
 * (元は issue #181, #197)。DB疎通確認と、外部連携3サービスへの専用ヘルスチェックエンドポイントでの
 * 到達確認(正常/5xx/接続不可)、LLM/Brave Search APIキー設定有無の判定を検証する。
 * LLM(issue #376で外部ホスト型APIに置き換え)は、Brave Searchと同様にAPIキーが設定されているか
 * どうかを稼働状況の代わりとして扱う。
 *
 * <p>移設に伴い、Brave Search APIキー設定有無の問い合わせ先はPlatformServiceClient(サービス間HTTP
 * ブリッジ)から、同一プロセス内の{@link SystemSettingService}へ変わった(クラスJavadoc参照)。
 */
@ExtendWith(MockitoExtension.class)
class ConnectedServiceStatusServiceTest {

    private static final String COMFYUI_URL = "http://comfyui.test";
    private static final String PLANTUML_URL = "http://plantuml.test";
    private static final String WORDPRESS_URL = "http://wordpress-provision.test";
    private static final String PENPOT_URL = "http://penpot.test";
    private static final String OLLAMA_URL = "http://ollama.test/v1";
    private static final String PLANTUML_HEALTHCHECK_PATH =
            "/png/" + PlantUmlEncoder.encode("@startuml\nA->B\n@enduml");

    @Mock
    private DataSource dataSource;
    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private Connection connection;

    // issue #589 で追加した2つのチェックは、このテストの関心(外部依存の疎通判定)の外なので
    // モックで固定する。それぞれの判定ロジックは専用のテストで検証する。
    @Mock
    private LetsBlogServiceStatusService letsBlogServiceStatusService;

    @Mock
    private RabbitMqQueueStatusService rabbitMqQueueStatusService;

    private MockRestServiceServer comfyUiServer;
    private MockRestServiceServer plantUmlServer;
    private MockRestServiceServer wordpressServer;
    private MockRestServiceServer penpotServer;
    private MockRestServiceServer ollamaServer;
    private ConnectedServiceStatusService service;
    /** Ollamaクライアント生成関数へ渡されたbaseUrlの履歴(/v1除去の検証用)。 */
    private final List<String> ollamaFactoryBaseUrls = new java.util.ArrayList<>();

    private ConnectedServiceStatusService buildService() {
        RestClient.Builder comfyUiBuilder = RestClient.builder().baseUrl(COMFYUI_URL);
        RestClient.Builder plantUmlBuilder = RestClient.builder().baseUrl(PLANTUML_URL);
        RestClient.Builder wordpressBuilder = RestClient.builder().baseUrl(WORDPRESS_URL);
        RestClient.Builder penpotBuilder = RestClient.builder().baseUrl(PENPOT_URL);
        RestClient.Builder ollamaBuilder = RestClient.builder().baseUrl(OLLAMA_URL);

        comfyUiServer = MockRestServiceServer.bindTo(comfyUiBuilder).build();
        plantUmlServer = MockRestServiceServer.bindTo(plantUmlBuilder).build();
        wordpressServer = MockRestServiceServer.bindTo(wordpressBuilder).build();
        penpotServer = MockRestServiceServer.bindTo(penpotBuilder).build();
        ollamaServer = MockRestServiceServer.bindTo(ollamaBuilder).build();

        return new ConnectedServiceStatusService(
                dataSource,
                comfyUiBuilder, COMFYUI_URL,
                plantUmlBuilder, PLANTUML_URL,
                wordpressBuilder, WORDPRESS_URL,
                penpotBuilder, PENPOT_URL,
                systemSettingService,
                appSettingService,
                letsBlogServiceStatusService,
                rabbitMqQueueStatusService,
                baseUrl -> {
                    ollamaFactoryBaseUrls.add(baseUrl);
                    return ollamaBuilder;
                });
    }

    @BeforeEach
    void setUp() {
        // #589 の2チェックは既定で「正常」に固定し、既存の検証(外部依存の疎通判定)へ影響させない。
        lenient().when(letsBlogServiceStatusService.checkAll()).thenReturn(List.of());
        lenient().when(letsBlogServiceStatusService.targetUrl()).thenReturn("http://gateway:8080/actuator/health");
        lenient().when(rabbitMqQueueStatusService.check())
                .thenReturn(new RabbitMqQueueStatusService.QueueStatus(true, false, null, "http://rabbitmq:15672/api/queues"));
        // LLMチェックの既定はOPENAI。個々のテストで上書きする。
        lenient().when(appSettingService.getLlmProvider()).thenReturn(AiProvider.OPENAI);
        service = buildService();
    }

    private void respondSuccessToAll() {
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/system_stats")).andRespond(withSuccess());
        plantUmlServer.expect(requestTo(PLANTUML_URL + PLANTUML_HEALTHCHECK_PATH))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/health")).andRespond(withSuccess());
        penpotServer.expect(requestTo(PENPOT_URL + "/readyz")).andRespond(withSuccess());
    }

    private void mockBraveSearchConfigured(boolean configured) {
        when(systemSettingService.getBraveSearchApiKeyStatusInternal()).thenReturn(
                new BraveSearchApiKeyStatus(configured, configured ? SettingSource.DATABASE : SettingSource.NONE));
    }

    @Test
    void checkAll_全サービスが正常に応答すればNORMALを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        respondSuccessToAll();
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        Map<String, Status> byId = toMapById(statuses);
        assertEquals(Status.NORMAL, byId.get("database"));
        assertEquals(Status.NORMAL, byId.get("llm"));
        assertEquals(Status.NORMAL, byId.get("comfyui"));
        assertEquals(Status.NORMAL, byId.get("plantuml"));
        assertEquals(Status.NORMAL, byId.get("wordpress-provisioning"));
        assertEquals(Status.NORMAL, byId.get("penpot"));
        assertEquals(Status.NORMAL, byId.get("brave-search"));
    }

    @Test
    void checkAll_DB接続でSQLExceptionが発生すればERRORを返す() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));
        respondSuccessToAll();
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.ERROR, toMapById(statuses).get("database"));
    }

    // ChatGPT / ClaudeのAPIキーはプロジェクト単位だけで、システム全体には「設定の有無」が無い(issue #1568)。
    // 第三者の有料APIなので実リクエストは送らず、システム側のキー有無で警告も出さない。
    @Test
    void checkAll_LLM_provider_OPENAIはシステム側にキーが無くても警告にしない() throws SQLException {
        when(appSettingService.getLlmProvider()).thenReturn(AiProvider.OPENAI);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        respondSuccessToAll();
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.NORMAL, toMapById(statuses).get("llm"));
    }

    @Test
    void checkAll_LLM_provider_CLAUDEはシステム側にキーが無くても警告にしない() throws SQLException {
        when(appSettingService.getLlmProvider()).thenReturn(AiProvider.CLAUDE);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        respondSuccessToAll();
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.NORMAL, toMapById(statuses).get("llm"));
    }

    @Test
    void checkAll_LLM_provider_OLLAMAで疎通確認できればAPIキーに関わらずNORMALを返す() throws SQLException {
        when(appSettingService.getLlmProvider()).thenReturn(AiProvider.OLLAMA);
        when(appSettingService.getLlmOllamaBaseUrl()).thenReturn(OLLAMA_URL);
        ollamaServer.expect(requestTo(OLLAMA_URL + "/models")).andRespond(withSuccess());
        ollamaServer.expect(requestTo(OLLAMA_URL + "/api/ps")).andRespond(withSuccess());
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        respondSuccessToAll();
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.NORMAL, toMapById(statuses).get("llm"));
    }

    @Test
    void checkAll_LLM_provider_OLLAMAで疎通不可であればAPIキーに関わらずERRORを返す() throws SQLException {
        when(appSettingService.getLlmProvider()).thenReturn(AiProvider.OLLAMA);
        when(appSettingService.getLlmOllamaBaseUrl()).thenReturn(OLLAMA_URL);
        ollamaServer.expect(requestTo(OLLAMA_URL + "/models")).andRespond(request -> {
            throw new java.io.IOException("connection refused");
        });
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        respondSuccessToAll();
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.ERROR, toMapById(statuses).get("llm"));
    }

    @Test
    void checkAll_接続不可の外部サービスはERRORを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/system_stats")).andRespond(request -> {
            throw new java.io.IOException("connection refused");
        });
        plantUmlServer.expect(requestTo(PLANTUML_URL + PLANTUML_HEALTHCHECK_PATH))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/health")).andRespond(withSuccess());
        penpotServer.expect(requestTo(PENPOT_URL + "/readyz")).andRespond(withSuccess());
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.ERROR, toMapById(statuses).get("comfyui"));
    }

    @Test
    void checkAll_5xx応答の外部サービスはWARNINGを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/system_stats")).andRespond(withServerError());
        plantUmlServer.expect(requestTo(PLANTUML_URL + PLANTUML_HEALTHCHECK_PATH))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/health")).andRespond(withSuccess());
        penpotServer.expect(requestTo(PENPOT_URL + "/readyz")).andRespond(withSuccess());
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.WARNING, toMapById(statuses).get("comfyui"));
    }

    @Test
    void checkAll_4xx応答の外部サービスはNORMALを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/system_stats")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        plantUmlServer.expect(requestTo(PLANTUML_URL + PLANTUML_HEALTHCHECK_PATH))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/health")).andRespond(withSuccess());
        penpotServer.expect(requestTo(PENPOT_URL + "/readyz")).andRespond(withSuccess());
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.NORMAL, toMapById(statuses).get("comfyui"));
    }

    @Test
    void checkAll_PlantUMLが空のレスポンスを返せばWARNINGを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/system_stats")).andRespond(withSuccess());
        plantUmlServer.expect(requestTo(PLANTUML_URL + PLANTUML_HEALTHCHECK_PATH))
                .andRespond(withSuccess(new byte[0], MediaType.IMAGE_PNG));
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/health")).andRespond(withSuccess());
        penpotServer.expect(requestTo(PENPOT_URL + "/readyz")).andRespond(withSuccess());
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.WARNING, toMapById(statuses).get("plantuml"));
    }

    @Test
    void checkAll_Penpotが接続不可であればERRORを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/system_stats")).andRespond(withSuccess());
        plantUmlServer.expect(requestTo(PLANTUML_URL + PLANTUML_HEALTHCHECK_PATH))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/health")).andRespond(withSuccess());
        penpotServer.expect(requestTo(PENPOT_URL + "/readyz")).andRespond(request -> {
            throw new java.io.IOException("connection refused");
        });
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.ERROR, toMapById(statuses).get("penpot"));
    }

    @Test
    void checkAll_BraveSearchAPIキー未設定であればWARNINGを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        respondSuccessToAll();
        mockBraveSearchConfigured(false);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.WARNING, toMapById(statuses).get("brave-search"));
    }

    @Test
    void checkAllDetailed_正常時は応答時間とチェック対象URLを含む() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        respondSuccessToAll();
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusDetailResponse> details = service.checkAllDetailed();

        Map<String, ConnectedServiceStatusDetailResponse> byId = details.stream()
                .collect(java.util.stream.Collectors.toMap(ConnectedServiceStatusDetailResponse::id, d -> d));
        ConnectedServiceStatusDetailResponse llm = byId.get("llm");
        assertEquals(Status.NORMAL, llm.status());
        assertNull(llm.errorMessage());
        assertTrue(llm.responseTimeMs() >= 0);
        assertNotNull(llm.checkedAt());

        ConnectedServiceStatusDetailResponse database = byId.get("database");
        assertEquals(Status.NORMAL, database.status());
        assertNull(database.targetUrl());
    }

    @Test
    void checkAllDetailed_接続不可の外部サービスはエラー内容を含む() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/system_stats")).andRespond(request -> {
            throw new java.io.IOException("connection refused");
        });
        plantUmlServer.expect(requestTo(PLANTUML_URL + PLANTUML_HEALTHCHECK_PATH))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/health")).andRespond(withSuccess());
        penpotServer.expect(requestTo(PENPOT_URL + "/readyz")).andRespond(withSuccess());
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusDetailResponse> details = service.checkAllDetailed();

        ConnectedServiceStatusDetailResponse comfyui = details.stream()
                .filter(d -> d.id().equals("comfyui"))
                .findFirst()
                .orElseThrow();
        assertEquals(Status.ERROR, comfyui.status());
        assertNotNull(comfyui.errorMessage());
        assertNull(comfyui.httpStatus());
    }

    @Test
    void checkAll_DB接続検証に失敗すればERRORを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(false);
        respondSuccessToAll();
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.ERROR, toMapById(statuses).get("database"));
    }

    @Test
    void checkAll_RabbitMqキューが滞留していればWARNINGを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        when(rabbitMqQueueStatusService.check())
                .thenReturn(new RabbitMqQueueStatusService.QueueStatus(
                        false, true, "キューが滞留しています", "http://rabbitmq:15672/api/queues"));
        respondSuccessToAll();
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.WARNING, toMapById(statuses).get("rabbitmq-queues"));
    }

    @Test
    void checkAll_RabbitMq疎通確認自体が失敗すればERRORを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        when(rabbitMqQueueStatusService.check())
                .thenReturn(new RabbitMqQueueStatusService.QueueStatus(
                        false, false, "接続できません", "http://rabbitmq:15672/api/queues"));
        respondSuccessToAll();
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.ERROR, toMapById(statuses).get("rabbitmq-queues"));
    }

    @Test
    void checkAll_LetsBlog内部サービスが停止していればERRORを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        when(letsBlogServiceStatusService.checkAll()).thenReturn(List.of(
                new LetsBlogServiceStatusService.ServiceHealth(
                        "media-service", "media-service", false, "画像アップロード不可", "接続できません")));
        respondSuccessToAll();
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.ERROR, toMapById(statuses).get("media-service"));
    }

    @Test
    void checkAll_LetsBlog内部サービスが正常であればNORMALを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        when(letsBlogServiceStatusService.checkAll()).thenReturn(List.of(
                new LetsBlogServiceStatusService.ServiceHealth(
                        "media-service", "media-service", true, null, null)));
        respondSuccessToAll();
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.NORMAL, toMapById(statuses).get("media-service"));
    }

    @Test
    void checkAll_PlantUMLが5xxを返せばWARNINGを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/system_stats")).andRespond(withSuccess());
        plantUmlServer.expect(requestTo(PLANTUML_URL + PLANTUML_HEALTHCHECK_PATH)).andRespond(withServerError());
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/health")).andRespond(withSuccess());
        penpotServer.expect(requestTo(PENPOT_URL + "/readyz")).andRespond(withSuccess());
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.WARNING, toMapById(statuses).get("plantuml"));
    }

    @Test
    void checkAll_PlantUMLが4xxを返せばNORMALを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/system_stats")).andRespond(withSuccess());
        plantUmlServer.expect(requestTo(PLANTUML_URL + PLANTUML_HEALTHCHECK_PATH))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/health")).andRespond(withSuccess());
        penpotServer.expect(requestTo(PENPOT_URL + "/readyz")).andRespond(withSuccess());
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.NORMAL, toMapById(statuses).get("plantuml"));
    }

    @Test
    void checkAll_PlantUMLが接続不可であればERRORを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/system_stats")).andRespond(withSuccess());
        plantUmlServer.expect(requestTo(PLANTUML_URL + PLANTUML_HEALTHCHECK_PATH)).andRespond(request -> {
            throw new java.io.IOException("connection refused");
        });
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/health")).andRespond(withSuccess());
        penpotServer.expect(requestTo(PENPOT_URL + "/readyz")).andRespond(withSuccess());
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.ERROR, toMapById(statuses).get("plantuml"));
    }

    // ---------------- issue #1397: 演算デバイス ----------------

    private static final String COMFYUI_STATS_CPU =
            "{\"system\":{\"os\":\"linux\"},\"devices\":[{\"name\":\"e2e-stub\",\"type\":\"cpu\"}]}";
    private static final String COMFYUI_STATS_CUDA =
            "{\"devices\":[{\"name\":\"cuda:0 NVIDIA GeForce RTX 5070 Ti\",\"type\":\"cuda\"}]}";

    private void respondToAllExceptComfyUi() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        plantUmlServer.expect(requestTo(PLANTUML_URL + PLANTUML_HEALTHCHECK_PATH))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/health")).andRespond(withSuccess());
        penpotServer.expect(requestTo(PENPOT_URL + "/readyz")).andRespond(withSuccess());
        mockBraveSearchConfigured(true);
    }

    private ConnectedServiceStatusDetailResponse detailOf(String id) {
        return service.checkAllDetailed().stream()
                .filter(d -> d.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private void comfyUiRespondsWith(String body) {
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/system_stats"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void checkAllDetailed_ComfyUIはsystem_statsのdevices先頭のtypeを演算デバイスとして返す() throws SQLException {
        respondToAllExceptComfyUi();
        comfyUiRespondsWith(COMFYUI_STATS_CUDA);

        ConnectedServiceStatusDetailResponse comfyui = detailOf("comfyui");

        assertEquals("cuda", comfyui.computeDevice());
        assertEquals(Status.NORMAL, comfyui.status());
    }

    @Test
    void checkAllDetailed_ComfyUIがcpuを返せばcpuを演算デバイスとして返す() throws SQLException {
        respondToAllExceptComfyUi();
        comfyUiRespondsWith(COMFYUI_STATS_CPU);

        assertEquals("cpu", detailOf("comfyui").computeDevice());
    }

    @Test
    void checkAllDetailed_ComfyUIの疎通確認は演算デバイス取得のために追加のHTTPリクエストを発行しない() throws SQLException {
        respondToAllExceptComfyUi();
        // 期待は1件(既定のExpectedCount.once())だけ。2件目を発行すればMockRestServiceServerが失敗する。
        comfyUiRespondsWith(COMFYUI_STATS_CPU);

        detailOf("comfyui");

        comfyUiServer.verify();
    }

    @Test
    void checkAllDetailed_ComfyUIの本文が空なら演算デバイスはnullでも状態はNORMAL() throws SQLException {
        respondToAllExceptComfyUi();
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/system_stats")).andRespond(withSuccess());

        ConnectedServiceStatusDetailResponse comfyui = detailOf("comfyui");

        assertNull(comfyui.computeDevice());
        assertEquals(Status.NORMAL, comfyui.status());
    }

    @Test
    void checkAllDetailed_ComfyUIの本文がJSONでなければ演算デバイスはnullでも状態はNORMAL() throws SQLException {
        respondToAllExceptComfyUi();
        comfyUiRespondsWith("not json {");

        ConnectedServiceStatusDetailResponse comfyui = detailOf("comfyui");

        assertNull(comfyui.computeDevice());
        assertEquals(Status.NORMAL, comfyui.status());
    }

    @Test
    void checkAllDetailed_ComfyUIのdevicesが空配列なら演算デバイスはnull() throws SQLException {
        respondToAllExceptComfyUi();
        comfyUiRespondsWith("{\"devices\":[]}");

        assertNull(detailOf("comfyui").computeDevice());
    }

    @Test
    void checkAllDetailed_ComfyUIのdevicesが無ければ演算デバイスはnull() throws SQLException {
        respondToAllExceptComfyUi();
        comfyUiRespondsWith("{\"system\":{}}");

        assertNull(detailOf("comfyui").computeDevice());
    }

    @Test
    void checkAllDetailed_ComfyUIのdevices先頭にtypeが無ければ演算デバイスはnull() throws SQLException {
        respondToAllExceptComfyUi();
        comfyUiRespondsWith("{\"devices\":[{\"name\":\"x\"}]}");

        assertNull(detailOf("comfyui").computeDevice());
    }

    @Test
    void checkAllDetailed_ComfyUIのtypeが空文字なら演算デバイスはnull() throws SQLException {
        respondToAllExceptComfyUi();
        comfyUiRespondsWith("{\"devices\":[{\"type\":\" \"}]}");

        assertNull(detailOf("comfyui").computeDevice());
    }

    @Test
    void checkAllDetailed_ComfyUIが5xxなら演算デバイスはnullで従来どおりWARNING() throws SQLException {
        respondToAllExceptComfyUi();
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/system_stats")).andRespond(withServerError());

        ConnectedServiceStatusDetailResponse comfyui = detailOf("comfyui");

        assertNull(comfyui.computeDevice());
        assertEquals(Status.WARNING, comfyui.status());
    }

    @Test
    void checkAllDetailed_ComfyUIに接続できなければ演算デバイスはnullで従来どおりERROR() throws SQLException {
        respondToAllExceptComfyUi();
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/system_stats")).andRespond(request -> {
            throw new java.io.IOException("connection refused");
        });

        ConnectedServiceStatusDetailResponse comfyui = detailOf("comfyui");

        assertNull(comfyui.computeDevice());
        assertEquals(Status.ERROR, comfyui.status());
    }

    @Test
    void checkAllDetailed_ComfyUI以外のサービスには演算デバイスが付かない() throws SQLException {
        respondToAllExceptComfyUi();
        comfyUiRespondsWith(COMFYUI_STATS_CPU);

        for (ConnectedServiceStatusDetailResponse d : service.checkAllDetailed()) {
            if (!d.id().equals("comfyui")) {
                assertNull(d.computeDevice(), d.id());
            }
        }
    }

    @Test
    void checkAll_一般向けの応答には演算デバイスのフィールドを含めない() throws Exception {
        respondToAllExceptComfyUi();
        comfyUiRespondsWith(COMFYUI_STATS_CPU);

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(service.checkAll());

        assertTrue(!json.contains("computeDevice"), json);
        assertTrue(!json.contains("cpu"), json);
    }

    private void ollamaWithPsBody(String psBody) throws SQLException {
        ollamaWithPsBody(OLLAMA_URL, psBody);
    }

    private void ollamaWithPsBody(String configuredBaseUrl, String psBody) throws SQLException {
        when(appSettingService.getLlmProvider()).thenReturn(AiProvider.OLLAMA);
        when(appSettingService.getLlmOllamaBaseUrl()).thenReturn(configuredBaseUrl);
        ollamaServer.expect(requestTo(OLLAMA_URL + "/models")).andRespond(withSuccess());
        ollamaServer.expect(requestTo(OLLAMA_URL + "/api/ps"))
                .andRespond(withSuccess(psBody, MediaType.APPLICATION_JSON));
        respondToAllExceptComfyUi();
        comfyUiRespondsWith(COMFYUI_STATS_CPU);
    }

    @Test
    void checkAllDetailed_Ollamaはsize_vramが0のモデルがあればcpuを返す() throws SQLException {
        ollamaWithPsBody("{\"models\":[{\"name\":\"m\",\"size\":1000,\"size_vram\":0}]}");

        assertEquals("cpu", detailOf("llm").computeDevice());
    }

    @Test
    void checkAllDetailed_Ollamaはsize_vramがsize以上ならgpuを返す() throws SQLException {
        ollamaWithPsBody("{\"models\":[{\"name\":\"m\",\"size\":1000,\"size_vram\":1000}]}");

        assertEquals("gpu", detailOf("llm").computeDevice());
    }

    @Test
    void checkAllDetailed_Ollamaはsize_vramがsizeより小さく0より大きければgpu_cpuを返す() throws SQLException {
        ollamaWithPsBody("{\"models\":[{\"name\":\"m\",\"size\":1000,\"size_vram\":400}]}");

        assertEquals("gpu+cpu", detailOf("llm").computeDevice());
    }

    @Test
    void checkAllDetailed_Ollamaは複数モデルのsizeとsize_vramを合算して判定する() throws SQLException {
        ollamaWithPsBody("{\"models\":[{\"size\":1000,\"size_vram\":1000},{\"size\":1000,\"size_vram\":0}]}");

        assertEquals("gpu+cpu", detailOf("llm").computeDevice());
    }

    @Test
    void checkAllDetailed_Ollamaはモデル未ロードならunknownを返し状態はNORMAL() throws SQLException {
        ollamaWithPsBody("{\"models\":[]}");

        ConnectedServiceStatusDetailResponse llm = detailOf("llm");

        assertEquals("unknown", llm.computeDevice());
        assertEquals(Status.NORMAL, llm.status());
    }

    @Test
    void checkAllDetailed_Ollamaはmodelsキーが無ければunknownを返す() throws SQLException {
        ollamaWithPsBody("{}");

        assertEquals("unknown", detailOf("llm").computeDevice());
    }

    @Test
    void checkAllDetailed_Ollamaのsizeが0のモデルしか無ければunknownを返す() throws SQLException {
        ollamaWithPsBody("{\"models\":[{\"size\":0,\"size_vram\":0}]}");

        assertEquals("unknown", detailOf("llm").computeDevice());
    }

    @Test
    void checkAllDetailed_Ollamaのps本文がJSONでなければnullでも状態はNORMAL() throws SQLException {
        ollamaWithPsBody("not json {");

        ConnectedServiceStatusDetailResponse llm = detailOf("llm");

        assertNull(llm.computeDevice());
        assertEquals(Status.NORMAL, llm.status());
    }

    @Test
    void checkAllDetailed_Ollamaのps取得が5xxなら演算デバイスはnullで状態はNORMALのまま() throws SQLException {
        when(appSettingService.getLlmProvider()).thenReturn(AiProvider.OLLAMA);
        when(appSettingService.getLlmOllamaBaseUrl()).thenReturn(OLLAMA_URL);
        ollamaServer.expect(requestTo(OLLAMA_URL + "/models")).andRespond(withSuccess());
        ollamaServer.expect(requestTo(OLLAMA_URL + "/api/ps")).andRespond(withServerError());
        respondToAllExceptComfyUi();
        comfyUiRespondsWith(COMFYUI_STATS_CPU);

        ConnectedServiceStatusDetailResponse llm = detailOf("llm");

        assertNull(llm.computeDevice());
        assertEquals(Status.NORMAL, llm.status());
    }

    @Test
    void checkAllDetailed_Ollamaのps取得で接続エラーでも演算デバイスはnullで状態はNORMALのまま() throws SQLException {
        when(appSettingService.getLlmProvider()).thenReturn(AiProvider.OLLAMA);
        when(appSettingService.getLlmOllamaBaseUrl()).thenReturn(OLLAMA_URL);
        ollamaServer.expect(requestTo(OLLAMA_URL + "/models")).andRespond(withSuccess());
        ollamaServer.expect(requestTo(OLLAMA_URL + "/api/ps")).andRespond(request -> {
            throw new java.io.IOException("connection refused");
        });
        respondToAllExceptComfyUi();
        comfyUiRespondsWith(COMFYUI_STATS_CPU);

        ConnectedServiceStatusDetailResponse llm = detailOf("llm");

        assertNull(llm.computeDevice());
        assertEquals(Status.NORMAL, llm.status());
    }

    @Test
    void checkAllDetailed_Ollamaが疎通不可ならpsを呼ばず演算デバイスはnullで従来どおりERROR() throws SQLException {
        when(appSettingService.getLlmProvider()).thenReturn(AiProvider.OLLAMA);
        when(appSettingService.getLlmOllamaBaseUrl()).thenReturn(OLLAMA_URL);
        // /api/ps の期待は置かない。呼べばMockRestServiceServerが失敗する。
        ollamaServer.expect(requestTo(OLLAMA_URL + "/models")).andRespond(request -> {
            throw new java.io.IOException("connection refused");
        });
        respondToAllExceptComfyUi();
        comfyUiRespondsWith(COMFYUI_STATS_CPU);

        ConnectedServiceStatusDetailResponse llm = detailOf("llm");

        assertNull(llm.computeDevice());
        assertEquals(Status.ERROR, llm.status());
    }

    @Test
    void checkAllDetailed_Ollamaのpsはベースurlから末尾の_v1を除いたurlで呼ぶ() throws SQLException {
        ollamaWithPsBody("http://ollama:11434/v1/", "{\"models\":[]}");

        detailOf("llm");

        assertEquals(List.of("http://ollama:11434/v1/", "http://ollama:11434"), ollamaFactoryBaseUrls);
    }

    @Test
    void checkAllDetailed_Ollamaのベースurlが_v1で終わらなければそのままpsを呼ぶ() throws SQLException {
        ollamaWithPsBody("http://ollama:11434", "{\"models\":[]}");

        detailOf("llm");

        assertEquals(List.of("http://ollama:11434", "http://ollama:11434"), ollamaFactoryBaseUrls);
    }

    @Test
    void checkAllDetailed_LLMがOLLAMA以外なら演算デバイスはnull() throws SQLException {
        respondToAllExceptComfyUi();
        comfyUiRespondsWith(COMFYUI_STATS_CPU);

        assertNull(detailOf("llm").computeDevice());
    }

    private static Map<String, Status> toMapById(List<ConnectedServiceStatusResponse> statuses) {
        return statuses.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ConnectedServiceStatusResponse::id, ConnectedServiceStatusResponse::status));
    }
}
