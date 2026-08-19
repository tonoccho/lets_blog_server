package com.letsblog.api.service;

import com.letsblog.api.dto.ConnectedServiceStatusDetailResponse;
import com.letsblog.api.dto.ConnectedServiceStatusResponse;
import com.letsblog.api.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.api.render.PlantUmlEncoder;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ConnectedServiceStatusServiceの回帰テスト(issue #181, #197)。DB疎通確認と、外部連携3サービスへの
 * 専用ヘルスチェックエンドポイントでの到達確認(正常/5xx/接続不可)、LLM/Brave Search APIキー設定有無の
 * 判定を検証する。LLM(issue #376で外部ホスト型APIに置き換え)は、Brave Searchと同様にAPIキーが
 * 設定されているかどうかを稼働状況の代わりとして扱う。
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
                systemSettingService);
    }

    @BeforeEach
    void setUp() {
        service = buildService("test-llm-api-key");
    }

    private void respondSuccessToAll() {
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/system_stats")).andRespond(withSuccess());
        plantUmlServer.expect(requestTo(PLANTUML_URL + PLANTUML_HEALTHCHECK_PATH))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/health")).andRespond(withSuccess());
        penpotServer.expect(requestTo(PENPOT_URL + "/readyz")).andRespond(withSuccess());
    }

    @Test
    void checkAll_全サービスが正常に応答すればNORMALを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        respondSuccessToAll();
        when(systemSettingService.getBraveSearchApiKeyStatus())
                .thenReturn(new SystemSettingService.BraveSearchApiKeyStatus(
                        true, SystemSettingService.SettingSource.DATABASE));

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
        when(systemSettingService.getBraveSearchApiKeyStatus())
                .thenReturn(new SystemSettingService.BraveSearchApiKeyStatus(
                        true, SystemSettingService.SettingSource.DATABASE));

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.ERROR, toMapById(statuses).get("database"));
    }

    @Test
    void checkAll_LLM_APIキー未設定であればWARNINGを返す() throws SQLException {
        service = buildService("");
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        respondSuccessToAll();
        when(systemSettingService.getBraveSearchApiKeyStatus())
                .thenReturn(new SystemSettingService.BraveSearchApiKeyStatus(
                        true, SystemSettingService.SettingSource.DATABASE));

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
        when(systemSettingService.getBraveSearchApiKeyStatus())
                .thenReturn(new SystemSettingService.BraveSearchApiKeyStatus(
                        true, SystemSettingService.SettingSource.DATABASE));

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
        when(systemSettingService.getBraveSearchApiKeyStatus())
                .thenReturn(new SystemSettingService.BraveSearchApiKeyStatus(
                        true, SystemSettingService.SettingSource.DATABASE));

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
        when(systemSettingService.getBraveSearchApiKeyStatus())
                .thenReturn(new SystemSettingService.BraveSearchApiKeyStatus(
                        true, SystemSettingService.SettingSource.DATABASE));

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
        when(systemSettingService.getBraveSearchApiKeyStatus())
                .thenReturn(new SystemSettingService.BraveSearchApiKeyStatus(
                        true, SystemSettingService.SettingSource.DATABASE));

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
        when(systemSettingService.getBraveSearchApiKeyStatus())
                .thenReturn(new SystemSettingService.BraveSearchApiKeyStatus(
                        true, SystemSettingService.SettingSource.DATABASE));

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.ERROR, toMapById(statuses).get("penpot"));
    }

    @Test
    void checkAll_BraveSearchAPIキー未設定であればWARNINGを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        respondSuccessToAll();
        when(systemSettingService.getBraveSearchApiKeyStatus())
                .thenReturn(new SystemSettingService.BraveSearchApiKeyStatus(
                        false, SystemSettingService.SettingSource.NONE));

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.WARNING, toMapById(statuses).get("brave-search"));
    }

    @Test
    void checkAllDetailed_正常時は応答時間とチェック対象URLを含む() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        respondSuccessToAll();
        when(systemSettingService.getBraveSearchApiKeyStatus())
                .thenReturn(new SystemSettingService.BraveSearchApiKeyStatus(
                        true, SystemSettingService.SettingSource.DATABASE));

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
        when(systemSettingService.getBraveSearchApiKeyStatus())
                .thenReturn(new SystemSettingService.BraveSearchApiKeyStatus(
                        true, SystemSettingService.SettingSource.DATABASE));

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
