package com.letsblog.platform.service;

import com.letsblog.platform.dto.ConnectedServiceStatusDetailResponse;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.platform.testsupport.FakeHttpServer;
import com.letsblog.platform.testsupport.StatusFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 連携サービスの状況のGitLab行(issue #1796)。接続先はDB(AppSettingService)で解決し、未設定は警告、
 * 2xxならNORMAL、できなければERRORと理由。行は未設定でも省かない。
 *
 * <p>issue #1825: 設定サービス・GitLabのチェッカー・gateway/RabbitMQ管理APIの判定はすべて実物で、
 * HTTPはサーバー側のフェイク、DB(DataSource/Connection)だけがMockitoの差し替え。
 */
class ConnectedServiceStatusServiceGitLabTest {

    private StatusFixture fixture;
    private ConnectedServiceStatusService service;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new StatusFixture();
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.isValid(3)).thenReturn(true);
        fixture.braveKey(true);
        service = StatusServices.create(fixture, dataSource);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    private ConnectedServiceStatusDetailResponse gitlabRow() {
        return service.checkAllDetailed().stream()
                .filter(r -> r.id().equals("gitlab")).findFirst().orElseThrow();
    }

    @Test
    void 未設定でも行は省かず接続を試みず未設定と分かる警告になる() {
        ConnectedServiceStatusDetailResponse row = gitlabRow();

        assertEquals("GitLab", row.name());
        assertEquals(Status.WARNING, row.status());
        assertEquals("未設定(システム設定で設定してください)", row.errorMessage());
        assertNull(row.targetUrl());
        assertTrue(fixture.http.requests("GET", "/gitlab/.*").isEmpty());
    }

    @Test
    void 応答が2xxならNORMAL() {
        fixture.setting("gitlab_base_url", fixture.gitlabUrl());

        ConnectedServiceStatusDetailResponse row = gitlabRow();

        assertEquals(Status.NORMAL, row.status());
        assertEquals(200, row.httpStatus());
        assertNull(row.errorMessage());
        assertEquals(fixture.gitlabUrl() + "/users/sign_in", row.targetUrl());
        assertEquals(1, fixture.http.requests("GET", "/gitlab/users/sign_in").size());
    }

    @Test
    void 到達できなければERRORで理由が出る() {
        fixture.setting("gitlab_base_url", "http://127.0.0.1:1");

        ConnectedServiceStatusDetailResponse row = gitlabRow();

        assertEquals(Status.ERROR, row.status());
        assertTrue(row.errorMessage().startsWith("GitLab に到達できません"), row.errorMessage());
    }

    @Test
    void 簡易な一覧にもGitLabの行が含まれる() {
        fixture.setting("gitlab_base_url", fixture.gitlabUrl());
        fixture.http.on("GET", "/gitlab/users/sign_in", FakeHttpServer.Response.json(503, "{}"));

        List<ConnectedServiceStatusResponse> rows = service.checkAll();

        assertTrue(rows.stream().anyMatch(r -> r.id().equals("gitlab") && r.status() == Status.ERROR));
    }
}
