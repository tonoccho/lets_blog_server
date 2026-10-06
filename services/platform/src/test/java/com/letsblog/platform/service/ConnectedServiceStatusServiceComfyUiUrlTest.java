package com.letsblog.platform.service;

import com.letsblog.platform.ai.AiProvider;
import com.letsblog.platform.dto.ConnectedServiceStatusDetailResponse;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.platform.render.PlantUmlEncoder;
import com.letsblog.platform.service.SystemSettingService.BraveSearchApiKeyStatus;
import com.letsblog.platform.service.SystemSettingService.SettingSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * システムの連携状況のComfyUI行は、環境変数ではなくDB(AppSettingService)で解決した接続先へ疎通確認する
 * (issue #1567)。DBにもプロジェクトにも値が無い(未設定)ときは接続を試みず、設定を促す警告にする。
 */
@ExtendWith(MockitoExtension.class)
class ConnectedServiceStatusServiceComfyUiUrlTest {

    private static final String DB_COMFYUI_URL = "http://db-comfyui.test:8188";
    private static final String PLANTUML_URL = "http://plantuml.test";
    private static final String WORDPRESS_URL = "http://wordpress-provision.test";
    private static final String PENPOT_URL = "http://penpot.test";

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private AppSettingService appSettingService;
    @Mock
    private LetsBlogServiceStatusService letsBlogServiceStatusService;
    @Mock
    private RabbitMqQueueStatusService rabbitMqQueueStatusService;

    private MockRestServiceServer comfyUiServer;
    private MockRestServiceServer plantUmlServer;
    private MockRestServiceServer wordpressServer;
    private MockRestServiceServer penpotServer;
    private ConnectedServiceStatusService service;
    private final List<String> comfyUiFactoryBaseUrls = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(letsBlogServiceStatusService.checkAll()).thenReturn(List.of());
        lenient().when(letsBlogServiceStatusService.targetUrl()).thenReturn("http://gateway:8080/actuator/health");
        lenient().when(rabbitMqQueueStatusService.check()).thenReturn(
                new RabbitMqQueueStatusService.QueueStatus(true, false, null, "http://rabbitmq:15672/api/queues"));
        lenient().when(appSettingService.getLlmProvider()).thenReturn(AiProvider.OPENAI);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.isValid(3)).thenReturn(true);
        lenient().when(systemSettingService.getBraveSearchApiKeyStatusInternal())
                .thenReturn(new BraveSearchApiKeyStatus(true, SettingSource.DATABASE));

        RestClient.Builder comfyUiBuilder = RestClient.builder();
        RestClient.Builder plantUmlBuilder = RestClient.builder().baseUrl(PLANTUML_URL);
        RestClient.Builder wordpressBuilder = RestClient.builder().baseUrl(WORDPRESS_URL);
        RestClient.Builder penpotBuilder = RestClient.builder().baseUrl(PENPOT_URL);
        comfyUiServer = MockRestServiceServer.bindTo(comfyUiBuilder).build();
        plantUmlServer = MockRestServiceServer.bindTo(plantUmlBuilder).build();
        wordpressServer = MockRestServiceServer.bindTo(wordpressBuilder).build();
        penpotServer = MockRestServiceServer.bindTo(penpotBuilder).build();
        plantUmlServer.expect(requestTo(PLANTUML_URL + "/png/"
                        + PlantUmlEncoder.encode("@startuml\nA->B\n@enduml")))
                .andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG));
        wordpressServer.expect(requestTo(WORDPRESS_URL + "/health")).andRespond(withSuccess());
        penpotServer.expect(requestTo(PENPOT_URL + "/readyz")).andRespond(withSuccess());

        service = new ConnectedServiceStatusService(
                dataSource,
                baseUrl -> {
                    comfyUiFactoryBaseUrls.add(baseUrl);
                    return comfyUiBuilder.baseUrl(baseUrl);
                },
                plantUmlBuilder, PLANTUML_URL,
                wordpressBuilder, WORDPRESS_URL,
                penpotBuilder, PENPOT_URL,
                systemSettingService, appSettingService,
                letsBlogServiceStatusService, rabbitMqQueueStatusService,
                baseUrl -> RestClient.builder().baseUrl(baseUrl));
    }

    private ConnectedServiceStatusDetailResponse comfyUiRow() {
        return service.checkAllDetailed().stream()
                .filter(r -> r.id().equals("comfyui")).findFirst().orElseThrow();
    }

    @Test
    void ComfyUI行はDBで解決した接続先のsystem_statsへ疎通確認する() {
        when(appSettingService.getComfyUiBaseUrl()).thenReturn(DB_COMFYUI_URL);
        comfyUiServer.expect(requestTo(DB_COMFYUI_URL + "/system_stats")).andRespond(withSuccess());

        ConnectedServiceStatusDetailResponse row = comfyUiRow();

        assertEquals(Status.NORMAL, row.status());
        assertEquals(DB_COMFYUI_URL + "/system_stats", row.targetUrl());
        assertEquals(List.of(DB_COMFYUI_URL), comfyUiFactoryBaseUrls);
        comfyUiServer.verify();
    }

    @Test
    void ComfyUI行は接続先が未設定なら接続を試みず設定を促す警告になる() {
        when(appSettingService.getComfyUiBaseUrl()).thenReturn("");

        ConnectedServiceStatusDetailResponse row = comfyUiRow();

        assertEquals(Status.WARNING, row.status());
        assertNotNull(row.errorMessage());
        assertTrue(row.errorMessage().contains("接続先が設定されていません"), "実際: " + row.errorMessage());
        assertNull(row.targetUrl());
        assertTrue(comfyUiFactoryBaseUrls.isEmpty(), "未設定ではクライアントを作らない: " + comfyUiFactoryBaseUrls);
    }

    @Test
    void ComfyUI行は接続先がnullでも未設定として扱う() {
        when(appSettingService.getComfyUiBaseUrl()).thenReturn(null);

        assertEquals(Status.WARNING, comfyUiRow().status());
    }

    @Test
    void ComfyUI行は接続先が空白だけでも未設定として扱う() {
        when(appSettingService.getComfyUiBaseUrl()).thenReturn("   ");

        assertEquals(Status.WARNING, comfyUiRow().status());
    }
}
