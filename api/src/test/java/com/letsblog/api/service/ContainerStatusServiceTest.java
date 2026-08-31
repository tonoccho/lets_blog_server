package com.letsblog.api.service;

import com.letsblog.api.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.api.dto.ContainerStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ContainerStatusServiceの回帰テスト(issue #280)。docker-socket-proxy(Docker Engine API互換)の
 * GET /containers/json レスポンスから、lbs-プレフィックスのコンテナのみを抽出し、
 * State/Statusから正常/警告/エラーを判定できることを検証する。
 */
class ContainerStatusServiceTest {

    private static final String DOCKER_URL = "http://docker-socket-proxy.test";

    private MockRestServiceServer server;
    private ContainerStatusService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(DOCKER_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        service = new ContainerStatusService(builder);
    }

    @Test
    void testListAll_lbsプレフィックスのコンテナのみ名前順で抽出する() {
        server.expect(requestTo(DOCKER_URL + "/containers/json?all=true"))
                .andRespond(withSuccess(
                        "[{\"Names\":[\"/lbs-wordpress\"],\"State\":\"running\",\"Status\":\"Up 2 hours\"},"
                                + "{\"Names\":[\"/lbs-api\"],\"State\":\"running\",\"Status\":\"Up 2 hours (healthy)\"},"
                                + "{\"Names\":[\"/some-other-container\"],\"State\":\"running\",\"Status\":\"Up 1 hour\"}]",
                        MediaType.APPLICATION_JSON));

        List<ContainerStatusResponse> containers = service.listAll();

        assertEquals(2, containers.size());
        assertEquals("api", containers.get(0).name());
        assertEquals("wordpress", containers.get(1).name());
    }

    @Test
    void testListAll_runningは正常と判定する() {
        server.expect(requestTo(DOCKER_URL + "/containers/json?all=true"))
                .andRespond(withSuccess(
                        "[{\"Names\":[\"/lbs-mysql\"],\"State\":\"running\",\"Status\":\"Up 3 hours\"}]",
                        MediaType.APPLICATION_JSON));

        List<ContainerStatusResponse> containers = service.listAll();

        assertEquals(Status.NORMAL, containers.get(0).status());
        assertEquals("running", containers.get(0).state());
    }

    @Test
    void testListAll_unhealthyは警告と判定する() {
        server.expect(requestTo(DOCKER_URL + "/containers/json?all=true"))
                .andRespond(withSuccess(
                        "[{\"Names\":[\"/lbs-comfyui\"],\"State\":\"running\",\"Status\":\"Up 5 minutes (unhealthy)\"}]",
                        MediaType.APPLICATION_JSON));

        List<ContainerStatusResponse> containers = service.listAll();

        assertEquals(Status.WARNING, containers.get(0).status());
    }

    @Test
    void testListAll_停止中のコンテナはエラーと判定する() {
        server.expect(requestTo(DOCKER_URL + "/containers/json?all=true"))
                .andRespond(withSuccess(
                        "[{\"Names\":[\"/lbs-wordpress\"],\"State\":\"exited\",\"Status\":\"Exited (1) 3 minutes ago\"}]",
                        MediaType.APPLICATION_JSON));

        List<ContainerStatusResponse> containers = service.listAll();

        assertEquals(Status.ERROR, containers.get(0).status());
        assertTrue(containers.get(0).detail().contains("Exited"));
    }

    @Test
    void testListAll_docker_socket_proxy未到達時は空リストを返す() {
        server.expect(requestTo(DOCKER_URL + "/containers/json?all=true"))
                .andRespond(withServerError());

        List<ContainerStatusResponse> containers = service.listAll();

        assertEquals(List.of(), containers);
    }
}
