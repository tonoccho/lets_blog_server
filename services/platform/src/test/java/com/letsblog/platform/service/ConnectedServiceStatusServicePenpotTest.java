package com.letsblog.platform.service;

import com.letsblog.platform.ai.AiProvider;
import com.letsblog.platform.dto.ConnectedServiceStatusDetailResponse;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.platform.service.SystemSettingService.BraveSearchApiKeyStatus;
import com.letsblog.platform.service.SystemSettingService.SettingSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 連携サービスの状況のPenpot行(issue #1774)。接続先はDB(AppSettingService)で解決し、未設定は警告、
 * 到達できればNORMAL、できなければERRORと理由。他の行の判定は変えない。
 */
@ExtendWith(MockitoExtension.class)
class ConnectedServiceStatusServicePenpotTest {

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
    @Mock
    private PenpotReachabilityChecker penpotChecker;

    private ConnectedServiceStatusService service;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(letsBlogServiceStatusService.checkAll()).thenReturn(List.of());
        lenient().when(letsBlogServiceStatusService.targetUrl()).thenReturn("http://gateway:8080/actuator/health");
        lenient().when(rabbitMqQueueStatusService.check()).thenReturn(
                new RabbitMqQueueStatusService.QueueStatus(true, false, null, "http://rabbitmq:15672/api/queues"));
        lenient().when(appSettingService.getLlmProvider()).thenReturn(AiProvider.OPENAI);
        lenient().when(appSettingService.getComfyUiBaseUrl()).thenReturn("");
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.isValid(3)).thenReturn(true);
        lenient().when(systemSettingService.getBraveSearchApiKeyStatusInternal())
                .thenReturn(new BraveSearchApiKeyStatus(true, SettingSource.DATABASE));

        service = new ConnectedServiceStatusService(
                dataSource,
                baseUrl -> RestClient.builder().baseUrl(baseUrl),
                RestClient.builder().baseUrl("http://plantuml.invalid"), "http://plantuml.invalid",
                RestClient.builder().baseUrl("http://wordpress.invalid"), "http://wordpress.invalid",
                systemSettingService, appSettingService,
                letsBlogServiceStatusService, rabbitMqQueueStatusService,
                baseUrl -> RestClient.builder().baseUrl(baseUrl),
                penpotChecker);
    }

    private ConnectedServiceStatusDetailResponse penpotRow() {
        return service.checkAllDetailed().stream()
                .filter(r -> r.id().equals("penpot")).findFirst().orElseThrow();
    }

    @Test
    void 未設定なら接続を試みず未設定と分かる警告になる() {
        when(appSettingService.getPenpotBaseUrl()).thenReturn("");

        ConnectedServiceStatusDetailResponse row = penpotRow();

        assertEquals("Penpot", row.name());
        assertEquals(Status.WARNING, row.status());
        assertEquals("未設定(システム設定で設定してください)", row.errorMessage());
        assertNull(row.targetUrl());
        verify(penpotChecker, never()).check(anyString());
    }

    @Test
    void 接続先がnullでも未設定の警告になる() {
        when(appSettingService.getPenpotBaseUrl()).thenReturn(null);

        assertEquals(Status.WARNING, penpotRow().status());
    }

    @Test
    void 到達できればNORMAL() {
        when(appSettingService.getPenpotBaseUrl()).thenReturn("http://penpot.lan:9001");
        when(penpotChecker.check("http://penpot.lan:9001")).thenReturn(new PenpotReachabilityChecker.Result(
                Status.NORMAL, 200, null, "http://penpot.lan:9001/"));

        ConnectedServiceStatusDetailResponse row = penpotRow();

        assertEquals(Status.NORMAL, row.status());
        assertEquals(200, row.httpStatus());
        assertNull(row.errorMessage());
        assertEquals("http://penpot.lan:9001/", row.targetUrl());
    }

    @Test
    void 到達できなければERRORで理由が出る() {
        when(appSettingService.getPenpotBaseUrl()).thenReturn("http://penpot.lan:9001");
        when(penpotChecker.check("http://penpot.lan:9001")).thenReturn(new PenpotReachabilityChecker.Result(
                Status.ERROR, null, "Penpot に到達できません: timed out", "http://penpot.lan:9001/"));

        ConnectedServiceStatusDetailResponse row = penpotRow();

        assertEquals(Status.ERROR, row.status());
        assertEquals("Penpot に到達できません: timed out", row.errorMessage());
    }

    @Test
    void 簡易な一覧にもPenpotの行が含まれ他の行の判定はPenpotの結果に左右されない() {
        when(appSettingService.getPenpotBaseUrl()).thenReturn("http://penpot.lan:9001");
        when(penpotChecker.check("http://penpot.lan:9001")).thenReturn(new PenpotReachabilityChecker.Result(
                Status.ERROR, null, "Penpot に到達できません: x", "http://penpot.lan:9001/"));

        List<ConnectedServiceStatusResponse> rows = service.checkAll();

        assertTrue(rows.stream().anyMatch(r -> r.id().equals("penpot") && r.status() == Status.ERROR));
        String others = rows.stream().filter(r -> !r.id().equals("penpot"))
                .map(r -> r.id() + "=" + r.status()).collect(Collectors.joining(","));
        assertEquals("database=NORMAL,llm=NORMAL,comfyui=WARNING,plantuml=ERROR,"
                + "wordpress-provisioning=ERROR,brave-search=NORMAL,rabbitmq-queues=NORMAL", others);
        assertFalse(others.contains("penpot"));
    }
}
