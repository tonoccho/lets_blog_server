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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 連携サービスの状況のGitLab行(issue #1796)。接続先はDB(AppSettingService)で解決し、未設定は警告、
 * 2xxならNORMAL、できなければERRORと理由。行は未設定でも省かない。
 */
@ExtendWith(MockitoExtension.class)
class ConnectedServiceStatusServiceGitLabTest {

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
    @Mock
    private GitLabReachabilityChecker gitlabChecker;

    private ConnectedServiceStatusService service;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(letsBlogServiceStatusService.checkAll()).thenReturn(List.of());
        lenient().when(letsBlogServiceStatusService.targetUrl()).thenReturn("http://gateway:8080/actuator/health");
        lenient().when(rabbitMqQueueStatusService.check()).thenReturn(
                new RabbitMqQueueStatusService.QueueStatus(true, false, null, "http://rabbitmq:15672/api/queues"));
        lenient().when(appSettingService.getLlmProvider()).thenReturn(AiProvider.OPENAI);
        lenient().when(appSettingService.getComfyUiBaseUrl()).thenReturn("");
        lenient().when(appSettingService.getPenpotBaseUrl()).thenReturn("");
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
                penpotChecker, gitlabChecker);
    }

    private ConnectedServiceStatusDetailResponse gitlabRow() {
        return service.checkAllDetailed().stream()
                .filter(r -> r.id().equals("gitlab")).findFirst().orElseThrow();
    }

    @Test
    void 未設定でも行は省かず接続を試みず未設定と分かる警告になる() {
        when(appSettingService.getGitLabBaseUrl()).thenReturn("");

        ConnectedServiceStatusDetailResponse row = gitlabRow();

        assertEquals("GitLab", row.name());
        assertEquals(Status.WARNING, row.status());
        assertEquals("未設定(システム設定で設定してください)", row.errorMessage());
        assertNull(row.targetUrl());
        verify(gitlabChecker, never()).check(anyString());
    }

    @Test
    void 接続先がnullでも未設定の警告になる() {
        when(appSettingService.getGitLabBaseUrl()).thenReturn(null);

        assertEquals(Status.WARNING, gitlabRow().status());
    }

    @Test
    void 応答が2xxならNORMAL() {
        when(appSettingService.getGitLabBaseUrl()).thenReturn("http://lbx-gitlab");
        when(gitlabChecker.check("http://lbx-gitlab")).thenReturn(new GitLabReachabilityChecker.Result(
                Status.NORMAL, 200, null, "http://lbx-gitlab/users/sign_in"));

        ConnectedServiceStatusDetailResponse row = gitlabRow();

        assertEquals(Status.NORMAL, row.status());
        assertEquals(200, row.httpStatus());
        assertNull(row.errorMessage());
        assertEquals("http://lbx-gitlab/users/sign_in", row.targetUrl());
    }

    @Test
    void 到達できなければERRORで理由が出る() {
        when(appSettingService.getGitLabBaseUrl()).thenReturn("http://lbx-gitlab");
        when(gitlabChecker.check("http://lbx-gitlab")).thenReturn(new GitLabReachabilityChecker.Result(
                Status.ERROR, null, "GitLab に到達できません: timed out", "http://lbx-gitlab/users/sign_in"));

        ConnectedServiceStatusDetailResponse row = gitlabRow();

        assertEquals(Status.ERROR, row.status());
        assertEquals("GitLab に到達できません: timed out", row.errorMessage());
    }

    @Test
    void 簡易な一覧にもGitLabの行が含まれる() {
        when(appSettingService.getGitLabBaseUrl()).thenReturn("http://lbx-gitlab");
        when(gitlabChecker.check("http://lbx-gitlab")).thenReturn(new GitLabReachabilityChecker.Result(
                Status.ERROR, 503, "GitLab が 2xx 以外を返しました(HTTP 503)", "http://lbx-gitlab/users/sign_in"));

        List<ConnectedServiceStatusResponse> rows = service.checkAll();

        assertTrue(rows.stream().anyMatch(r -> r.id().equals("gitlab") && r.status() == Status.ERROR));
    }
}
