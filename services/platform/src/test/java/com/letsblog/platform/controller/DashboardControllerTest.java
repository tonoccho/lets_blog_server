package com.letsblog.platform.controller;

import com.letsblog.platform.config.GlobalExceptionHandler;
import com.letsblog.platform.service.ConnectedServiceStatusBroadcaster;
import com.letsblog.platform.service.ContainerStatusBroadcaster;
import com.letsblog.platform.service.StatusServices;
import com.letsblog.platform.testsupport.FakeHttpServer;
import com.letsblog.platform.testsupport.StatusFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * legacy-apiから移設(issue #695、C10-3)。DashboardControllerの回帰テスト(元は issue #199, #280)。
 * 詳細診断エンドポイントがadmin限定であることと、コンテナ稼働状況エンドポイントが
 * ContainerStatusServiceへ委譲することを検証する。
 *
 * <p>issue #1825: コントローラと、状況サービス・SSE配信・AdminAuthorizationServiceはすべて実物。
 * Springの{@link MockMvc}(スタンドアロン)を通し、HTTP(identity-service・docker-socket-proxy・gateway等)は
 * サーバー側のフェイク、DB(DataSource/Connection)だけをMockitoで差し替える。
 */
class DashboardControllerTest {

    private StatusFixture fixture;
    private FakeHttpServer docker;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new StatusFixture();
        fixture.world.actingAsAdmin(1L);
        docker = new FakeHttpServer();
        docker.json("GET", "/containers/json",
                "[{\"Names\":[\"/lbs-api\"],\"State\":\"running\",\"Status\":\"Up 2 hours\"}]");
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        Mockito.when(dataSource.getConnection()).thenReturn(connection);
        Mockito.when(connection.isValid(3)).thenReturn(true);
        var statusService = StatusServices.create(fixture, dataSource);
        var containerService = StatusServices.containerStatusService(docker.baseUrl());
        DashboardController controller = new DashboardController(
                statusService, new ConnectedServiceStatusBroadcaster(statusService),
                containerService, new ContainerStatusBroadcaster(containerService),
                fixture.world.adminAuthorizationService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(
                        new org.springframework.boot.servlet.autoconfigure.MultipartProperties()))
                .build();
    }

    @AfterEach
    void tearDown() {
        docker.close();
        fixture.close();
    }

    @Test
    void getServiceStatus_稼働状況の3値のみを返す() throws Exception {
        mvc.perform(get("/api/dashboard/service-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("database"))
                .andExpect(jsonPath("$[0].status").value("NORMAL"))
                .andExpect(jsonPath("$[0].responseTimeMs").doesNotExist());
    }

    @Test
    void getServiceStatusDetail_admin権限があれば詳細診断情報を返す() throws Exception {
        mvc.perform(get("/api/dashboard/service-status/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("database"))
                .andExpect(jsonPath("$[0].responseTimeMs").exists());
    }

    @Test
    void getServiceStatusDetail_admin権限がなければ403() throws Exception {
        fixture.world.actingAsUser(2L);

        mvc.perform(get("/api/dashboard/service-status/detail")).andExpect(status().isForbidden());
    }

    @Test
    void getContainerStatus_admin権限があればコンテナ稼働状況を返す() throws Exception {
        mvc.perform(get("/api/dashboard/container-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("api"))
                .andExpect(jsonPath("$[0].status").value("NORMAL"))
                .andExpect(jsonPath("$[0].state").value("running"));
    }

    // ---- issue #830: コンテナ名と稼働状況はインフラの構成情報なのでadmin限定にした ----

    @Test
    void getContainerStatus_admin権限がなければコンテナ一覧を読まずに拒否する() throws Exception {
        // #816 のQAで、無効化された利用者に全コンテナ名と稼働状況が見え続けることが確認されている。
        fixture.world.actingAsUser(2L);

        mvc.perform(get("/api/dashboard/container-status")).andExpect(status().isForbidden());

        assertTrue(docker.requests().isEmpty(), "docker-socket-proxyには問い合わせない");
    }

    @Test
    void streamContainerStatus_admin権限がなければ購読させない() throws Exception {
        fixture.world.actingAsUser(2L);

        mvc.perform(get("/api/dashboard/container-status/stream")).andExpect(status().isForbidden());

        assertTrue(docker.requests().isEmpty());
    }

    @Test
    void streamContainerStatus_admin権限があれば購読できる() throws Exception {
        mvc.perform(get("/api/dashboard/container-status/stream"))
                .andExpect(request().asyncStarted());

        assertTrue(docker.requests("GET", "/containers/json").size() == 1);
    }

    @Test
    void streamServiceStatus_ログインしていれば購読できる() throws Exception {
        mvc.perform(get("/api/dashboard/service-status/stream"))
                .andExpect(request().asyncStarted());
    }

    @Test
    void getServiceStatus_admin以外でも要約は返す() throws Exception {
        // 詳細(getServiceStatusDetail)はadmin限定だが、要約は共通ダッシュボード向けなので開けておく。
        fixture.world.actingAsUser(2L);

        mvc.perform(get("/api/dashboard/service-status")).andExpect(status().isOk());

        assertTrue(fixture.http.requests("GET", "/api/identity/me").isEmpty(), "要約は認可を求めない");
    }
}
