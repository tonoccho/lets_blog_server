package com.letsblog.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.letsblog.common.auth.ServiceTokenUnavailableException;
import com.letsblog.common.auth.ToolsTokenProvider;
import com.letsblog.platform.dto.ConnectedServiceStatusDetailResponse;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.platform.render.PlantUmlEncoder;
import com.letsblog.platform.testsupport.StatusFixture;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 連携サービスの状況(Ollama・ComfyUI・PlantUMLの疎通確認)で付けるトークン(#1804)。
 * 接続先はすべてシステム設定(と`PLANTUML_BASE_URL`)なので、有効なら必ず付く。取れないときは送らずに
 * 該当の行をERRORにして、理由(`errorMessage`)に出す。
 */
@ExtendWith(MockitoExtension.class)
class ConnectedServiceStatusServiceToolsTokenTest {

    private static final String COMFYUI_URL = "http://comfyui.test";
    private static final String PLANTUML_URL = "http://plantuml.test";
    private static final String WORDPRESS_URL = "http://wordpress-provision.test";
    private static final String OLLAMA_URL = "http://ollama.test/v1";
    private static final String PLANTUML_PATH = "/png/" + PlantUmlEncoder.encode("@startuml\nA->B\n@enduml");

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    /** 設定サービス・gateway・RabbitMQ管理APIは実物(HTTPはフェイク、DBはメモリ上のリポジトリ)。issue #1825 */
    private StatusFixture fixture;

    private MockRestServiceServer comfyUiServer;
    private MockRestServiceServer plantUmlServer;
    private MockRestServiceServer ollamaServer;

    @BeforeEach
    void setUp() throws SQLException {
        fixture = new StatusFixture();
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.isValid(3)).thenReturn(true);
        fixture.llmProvider("OLLAMA");
        fixture.setting("llm_ollama_base_url", OLLAMA_URL);
        fixture.setting("comfyui_base_url", COMFYUI_URL);
        fixture.braveKey(true);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        fixture.close();
    }

    private List<ConnectedServiceStatusDetailResponse> check(
            ToolsTokenProvider tools, boolean expectRequests, String expectedAuthorization) {
        RestClient.Builder comfyUi = RestClient.builder().baseUrl(COMFYUI_URL);
        RestClient.Builder plantUml = RestClient.builder().baseUrl(PLANTUML_URL);
        RestClient.Builder wordpress = RestClient.builder().baseUrl(WORDPRESS_URL);
        RestClient.Builder ollama = RestClient.builder().baseUrl(OLLAMA_URL);
        comfyUiServer = MockRestServiceServer.bindTo(comfyUi).ignoreExpectOrder(true).build();
        plantUmlServer = MockRestServiceServer.bindTo(plantUml).ignoreExpectOrder(true).build();
        MockRestServiceServer wordpressServer = MockRestServiceServer.bindTo(wordpress).build();
        ollamaServer = MockRestServiceServer.bindTo(ollama).ignoreExpectOrder(true).build();
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/health")).andRespond(withSuccess());
        if (expectRequests) {
            expect(comfyUiServer, COMFYUI_URL + "/system_stats", expectedAuthorization);
            expect(plantUmlServer, PLANTUML_URL + PLANTUML_PATH, expectedAuthorization);
            expect(ollamaServer, OLLAMA_URL + "/models", expectedAuthorization);
            expect(ollamaServer, OLLAMA_URL + "/api/ps", expectedAuthorization);
        }
        return new ConnectedServiceStatusService(
                dataSource, baseUrl -> comfyUi, plantUml, PLANTUML_URL, wordpress, WORDPRESS_URL,
                fixture.systemSettingService, fixture.appSettingService, fixture.letsBlogServiceStatusService,
                fixture.rabbitMqQueueStatusService,
                baseUrl -> ollama,
                tools).checkAllDetailed();
    }

    private static void expect(MockRestServiceServer server, String url, String authorization) {
        var expectation = server.expect(requestTo(url));
        if (authorization == null) {
            expectation.andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION));
        } else {
            expectation.andExpect(header(HttpHeaders.AUTHORIZATION, authorization));
        }
        expectation.andRespond(withSuccess(new byte[] {1}, MediaType.IMAGE_PNG));
    }

    private static ConnectedServiceStatusDetailResponse row(List<ConnectedServiceStatusDetailResponse> rows, String id) {
        return rows.stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("有効なら、Ollama・ComfyUI・PlantUMLの疎通確認に専用クライアントのトークンが付く")
    void 有効ならトークンが付く() {
        List<ConnectedServiceStatusDetailResponse> rows = check(ToolsTokenProvider.fromSupplier(() -> "tok-1"), true, "Bearer tok-1");

        for (String id : List.of("llm", "comfyui", "plantuml")) {
            assertThat(row(rows, id).status()).as(id).isEqualTo(Status.NORMAL);
        }
        comfyUiServer.verify();
        plantUmlServer.verify();
        ollamaServer.verify();
    }

    @Test
    @DisplayName("secret未設定(無効)なら付かない。従来と同じ")
    void 無効なら付かない() {
        List<ConnectedServiceStatusDetailResponse> rows = check(ToolsTokenProvider.disabled(), true, null);

        assertThat(row(rows, "llm").status()).isEqualTo(Status.NORMAL);
    }

    @Test
    @DisplayName("トークンが取れないときは、送らずに該当の行をERRORにして理由を出す")
    void 取れないときは行に理由を出す() {
        List<ConnectedServiceStatusDetailResponse> rows = check(ToolsTokenProvider.fromSupplier(() -> {
            throw new ServiceTokenUnavailableException("サービストークンの取得に失敗しました(client_id=letsblog-tools-client): 401");
        }), false, null);

        for (String id : List.of("llm", "comfyui", "plantuml")) {
            ConnectedServiceStatusDetailResponse row = row(rows, id);
            assertThat(row.status()).as(id).isEqualTo(Status.ERROR);
            assertThat(row.errorMessage()).as(id).contains("KEYCLOAK_TOOLS_CLIENT_SECRET").contains("401");
        }
    }
}
