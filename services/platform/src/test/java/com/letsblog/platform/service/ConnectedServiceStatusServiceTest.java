package com.letsblog.platform.service;

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
    private static final String PLANTUML_HEALTHCHECK_PATH =
            "/png/" + PlantUmlEncoder.encode("@startuml\nA->B\n@enduml");

    @Mock
    private DataSource dataSource;
    @Mock
    private SystemSettingService systemSettingService;
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
    private ConnectedServiceStatusService service;

    private ConnectedServiceStatusService buildService(String llmApiKey) {
        RestClient.Builder comfyUiBuilder = RestClient.builder().baseUrl(COMFYUI_URL);
        RestClient.Builder plantUmlBuilder = RestClient.builder().baseUrl(PLANTUML_URL);
        RestClient.Builder wordpressBuilder = RestClient.builder().baseUrl(WORDPRESS_URL);
        RestClient.Builder penpotBuilder = RestClient.builder().baseUrl(PENPOT_URL);

        comfyUiServer = MockRestServiceServer.bindTo(comfyUiBuilder).build();
        plantUmlServer = MockRestServiceServer.bindTo(plantUmlBuilder).build();
        wordpressServer = MockRestServiceServer.bindTo(wordpressBuilder).build();
        penpotServer = MockRestServiceServer.bindTo(penpotBuilder).build();

        return new ConnectedServiceStatusService(
                dataSource,
                llmApiKey,
                comfyUiBuilder, COMFYUI_URL,
                plantUmlBuilder, PLANTUML_URL,
                wordpressBuilder, WORDPRESS_URL,
                penpotBuilder, PENPOT_URL,
                systemSettingService,
                letsBlogServiceStatusService,
                rabbitMqQueueStatusService);
    }

    @BeforeEach
    void setUp() {
        // #589 の2チェックは既定で「正常」に固定し、既存の検証(外部依存の疎通判定)へ影響させない。
        lenient().when(letsBlogServiceStatusService.checkAll()).thenReturn(List.of());
        lenient().when(letsBlogServiceStatusService.targetUrl()).thenReturn("http://gateway:8080/actuator/health");
        lenient().when(rabbitMqQueueStatusService.check())
                .thenReturn(new RabbitMqQueueStatusService.QueueStatus(true, false, null, "http://rabbitmq:15672/api/queues"));
        service = buildService("test-llm-api-key");
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

    @Test
    void checkAll_LLM_APIキー未設定であればWARNINGを返す() throws SQLException {
        service = buildService("");
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        respondSuccessToAll();
        mockBraveSearchConfigured(true);

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.WARNING, toMapById(statuses).get("llm"));
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

    private static Map<String, Status> toMapById(List<ConnectedServiceStatusResponse> statuses) {
        return statuses.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ConnectedServiceStatusResponse::id, ConnectedServiceStatusResponse::status));
    }
}
