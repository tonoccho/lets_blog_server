package com.letsblog.api.service;

import com.letsblog.api.dto.ConnectedServiceStatusResponse;
import com.letsblog.api.dto.ConnectedServiceStatusResponse.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ConnectedServiceStatusServiceの回帰テスト(issue #181)。DB疎通確認と、外部連携4サービスへの
 * HTTP到達確認(正常/5xx/接続不可)、Brave Search APIキー設定有無の判定を検証する。
 */
@ExtendWith(MockitoExtension.class)
class ConnectedServiceStatusServiceTest {

    private static final String OLLAMA_URL = "http://ollama.test";
    private static final String COMFYUI_URL = "http://comfyui.test";
    private static final String PLANTUML_URL = "http://plantuml.test";
    private static final String WORDPRESS_URL = "http://wordpress-provision.test";

    @Mock
    private DataSource dataSource;
    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private Connection connection;

    private MockRestServiceServer ollamaServer;
    private MockRestServiceServer comfyUiServer;
    private MockRestServiceServer plantUmlServer;
    private MockRestServiceServer wordpressServer;
    private ConnectedServiceStatusService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder ollamaBuilder = RestClient.builder().baseUrl(OLLAMA_URL);
        RestClient.Builder comfyUiBuilder = RestClient.builder().baseUrl(COMFYUI_URL);
        RestClient.Builder plantUmlBuilder = RestClient.builder().baseUrl(PLANTUML_URL);
        RestClient.Builder wordpressBuilder = RestClient.builder().baseUrl(WORDPRESS_URL);

        ollamaServer = MockRestServiceServer.bindTo(ollamaBuilder).build();
        comfyUiServer = MockRestServiceServer.bindTo(comfyUiBuilder).build();
        plantUmlServer = MockRestServiceServer.bindTo(plantUmlBuilder).build();
        wordpressServer = MockRestServiceServer.bindTo(wordpressBuilder).build();

        service = new ConnectedServiceStatusService(
                dataSource, ollamaBuilder, comfyUiBuilder, plantUmlBuilder, wordpressBuilder, systemSettingService);
    }

    private void respondSuccessToAll() {
        ollamaServer.expect(requestTo(OLLAMA_URL + "/")).andRespond(withSuccess());
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/")).andRespond(withSuccess());
        plantUmlServer.expect(requestTo(PLANTUML_URL + "/")).andRespond(withSuccess());
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/")).andRespond(withSuccess());
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
        assertEquals(Status.NORMAL, byId.get("ollama"));
        assertEquals(Status.NORMAL, byId.get("comfyui"));
        assertEquals(Status.NORMAL, byId.get("plantuml"));
        assertEquals(Status.NORMAL, byId.get("wordpress-provisioning"));
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
    void checkAll_接続不可の外部サービスはERRORを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        ollamaServer.expect(requestTo(OLLAMA_URL + "/")).andRespond(request -> {
            throw new java.io.IOException("connection refused");
        });
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/")).andRespond(withSuccess());
        plantUmlServer.expect(requestTo(PLANTUML_URL + "/")).andRespond(withSuccess());
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/")).andRespond(withSuccess());
        when(systemSettingService.getBraveSearchApiKeyStatus())
                .thenReturn(new SystemSettingService.BraveSearchApiKeyStatus(
                        true, SystemSettingService.SettingSource.DATABASE));

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.ERROR, toMapById(statuses).get("ollama"));
    }

    @Test
    void checkAll_5xx応答の外部サービスはWARNINGを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        ollamaServer.expect(requestTo(OLLAMA_URL + "/")).andRespond(withServerError());
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/")).andRespond(withSuccess());
        plantUmlServer.expect(requestTo(PLANTUML_URL + "/")).andRespond(withSuccess());
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/")).andRespond(withSuccess());
        when(systemSettingService.getBraveSearchApiKeyStatus())
                .thenReturn(new SystemSettingService.BraveSearchApiKeyStatus(
                        true, SystemSettingService.SettingSource.DATABASE));

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.WARNING, toMapById(statuses).get("ollama"));
    }

    @Test
    void checkAll_4xx応答の外部サービスはNORMALを返す() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);
        ollamaServer.expect(requestTo(OLLAMA_URL + "/")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        comfyUiServer.expect(requestTo(COMFYUI_URL + "/")).andRespond(withSuccess());
        plantUmlServer.expect(requestTo(PLANTUML_URL + "/")).andRespond(withSuccess());
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/")).andRespond(withSuccess());
        when(systemSettingService.getBraveSearchApiKeyStatus())
                .thenReturn(new SystemSettingService.BraveSearchApiKeyStatus(
                        true, SystemSettingService.SettingSource.DATABASE));

        List<ConnectedServiceStatusResponse> statuses = service.checkAll();

        assertEquals(Status.NORMAL, toMapById(statuses).get("ollama"));
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

    private static Map<String, Status> toMapById(List<ConnectedServiceStatusResponse> statuses) {
        return statuses.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ConnectedServiceStatusResponse::id, ConnectedServiceStatusResponse::status));
    }
}
