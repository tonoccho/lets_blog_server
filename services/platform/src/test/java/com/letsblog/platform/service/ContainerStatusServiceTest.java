package com.letsblog.platform.service;

import com.letsblog.platform.dto.ConnectedServiceStatusResponse.Status;
import com.letsblog.platform.dto.ContainerStatusResponse;
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
 * legacy-apiから移設(issue #695、C10-3)。ContainerStatusServiceの回帰テスト(元は issue #280)。
 * docker-socket-proxy(Docker Engine API互換)のGET /containers/json レスポンスから、
 * lbs-プレフィックスのコンテナのみを抽出し、State/Statusから正常/警告/エラーを判定できることを
 * 検証する。
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

    // ------------------------------------------------------------------
    // issue #725: 停止中コンテナの扱い。ワンショットジョブの正常完了と、
    // 継続稼働が期待されるサービスの停止を区別する。
    // ------------------------------------------------------------------

    /** 終了コード0かつ再起動ポリシーno = 正常に完了したワンショットジョブ(legacy-schema-migrate等)。 */
    @Test
    void testListAll_正常終了したワンショットジョブはエラーにしない() {
        expectList("[{\"Id\":\"abc123\",\"Names\":[\"/lbs-legacy-schema-migrate\"],"
                + "\"State\":\"exited\",\"Status\":\"Exited (0) 2 hours ago\"}]");
        expectInspect("abc123", 0, "no");

        List<ContainerStatusResponse> containers = service.listAll();

        assertEquals(Status.NORMAL, containers.get(0).status());
        assertEquals("exited", containers.get(0).state());
    }

    /**
     * 終了コードが0でも、再起動ポリシーがunless-stopped(=継続稼働が期待されるサービス)なら
     * エラーのまま。docker compose stop での正常停止をNORMALと表示すると、停止に気付けなくなる。
     */
    @Test
    void testListAll_継続稼働サービスの正常停止はエラーのまま() {
        expectList("[{\"Id\":\"def456\",\"Names\":[\"/lbs-mysql\"],"
                + "\"State\":\"exited\",\"Status\":\"Exited (0) 5 minutes ago\"}]");
        expectInspect("def456", 0, "unless-stopped");

        List<ContainerStatusResponse> containers = service.listAll();

        assertEquals(Status.ERROR, containers.get(0).status());
    }

    /** ワンショットジョブでも異常終了(exit != 0)ならエラー。 */
    @Test
    void testListAll_異常終了したワンショットジョブはエラー() {
        expectList("[{\"Id\":\"ghi789\",\"Names\":[\"/lbs-legacy-schema-migrate\"],"
                + "\"State\":\"exited\",\"Status\":\"Exited (1) 2 hours ago\"}]");
        expectInspect("ghi789", 1, "no");

        List<ContainerStatusResponse> containers = service.listAll();

        assertEquals(Status.ERROR, containers.get(0).status());
    }

    /** 詳細が取れないときは判定できないので、異常を隠さずエラーのままにする。 */
    @Test
    void testListAll_詳細取得に失敗したらエラーのまま() {
        expectList("[{\"Id\":\"jkl012\",\"Names\":[\"/lbs-legacy-schema-migrate\"],"
                + "\"State\":\"exited\",\"Status\":\"Exited (0) 2 hours ago\"}]");
        server.expect(requestTo(DOCKER_URL + "/containers/jkl012/json")).andRespond(withServerError());

        List<ContainerStatusResponse> containers = service.listAll();

        assertEquals(Status.ERROR, containers.get(0).status());
    }

    /** 稼働中のコンテナだけなら詳細取得は行わない(往復を増やさない)。 */
    @Test
    void testListAll_稼働中のみなら詳細取得を行わない() {
        expectList("[{\"Id\":\"mno345\",\"Names\":[\"/lbs-mysql\"],"
                + "\"State\":\"running\",\"Status\":\"Up 3 hours\"}]");

        List<ContainerStatusResponse> containers = service.listAll();

        assertEquals(Status.NORMAL, containers.get(0).status());
        // 追加のリクエストを期待していないので、verify()が通れば詳細取得は行われていない。
        server.verify();
    }

    /**
     * 再起動ポリシーが読めなかった場合は完了扱いにしない。ExitCodeのasInt(-1)と同じく
     * 「判定できなければ異常のまま」に倒す(レビュー指摘)。
     */
    @Test
    void testListAll_再起動ポリシーが読めなければエラーのまま() {
        expectList("[{\"Id\":\"pqr678\",\"Names\":[\"/lbs-legacy-schema-migrate\"],"
                + "\"State\":\"exited\",\"Status\":\"Exited (0) 2 hours ago\"}]");
        server.expect(requestTo(DOCKER_URL + "/containers/pqr678/json"))
                .andRespond(withSuccess("{\"State\":{\"ExitCode\":0}}", MediaType.APPLICATION_JSON));

        List<ContainerStatusResponse> containers = service.listAll();

        assertEquals(Status.ERROR, containers.get(0).status());
    }

    /** always/on-failureも継続稼働が期待されるポリシーなので、exit 0でもエラーのまま。 */
    @Test
    void testListAll_alwaysポリシーの停止はエラーのまま() {
        expectList("[{\"Id\":\"stu901\",\"Names\":[\"/lbs-keycloak\"],"
                + "\"State\":\"exited\",\"Status\":\"Exited (0) 1 minute ago\"}]");
        expectInspect("stu901", 0, "always");

        List<ContainerStatusResponse> containers = service.listAll();

        assertEquals(Status.ERROR, containers.get(0).status());
    }

    /** exited以外の停止系状態(created/restarting/dead等)では詳細取得を行わずエラーにする。 */
    @Test
    void testListAll_exited以外の状態では詳細取得を行わない() {
        expectList("[{\"Id\":\"vwx234\",\"Names\":[\"/lbs-ai\"],"
                + "\"State\":\"restarting\",\"Status\":\"Restarting (1) 5 seconds ago\"}]");

        List<ContainerStatusResponse> containers = service.listAll();

        assertEquals(Status.ERROR, containers.get(0).status());
        server.verify();
    }

    /**
     * Idが無いレスポンス(既存フィクスチャの形)でも詳細取得を行わずエラーにする。
     * 既存テストがこの経路を暗黙に通っているので、明示的に固定しておく。
     */
    @Test
    void testListAll_Idが無ければ詳細取得を行わない() {
        expectList("[{\"Names\":[\"/lbs-wordpress\"],"
                + "\"State\":\"exited\",\"Status\":\"Exited (0) 3 minutes ago\"}]");

        List<ContainerStatusResponse> containers = service.listAll();

        assertEquals(Status.ERROR, containers.get(0).status());
        server.verify();
    }

    /** 停止中が複数あれば、それぞれについて詳細を取得し個別に判定する。 */
    @Test
    void testListAll_停止中が複数あればそれぞれ判定する() {
        expectList("[{\"Id\":\"one\",\"Names\":[\"/lbs-legacy-schema-migrate\"],"
                + "\"State\":\"exited\",\"Status\":\"Exited (0) 2 hours ago\"},"
                + "{\"Id\":\"two\",\"Names\":[\"/lbs-mysql\"],"
                + "\"State\":\"exited\",\"Status\":\"Exited (0) 1 minute ago\"}]");
        expectInspect("one", 0, "no");
        expectInspect("two", 0, "unless-stopped");

        List<ContainerStatusResponse> containers = service.listAll();

        // 名前順にソートされるので legacy-schema-migrate, mysql の順。
        assertEquals("legacy-schema-migrate", containers.get(0).name());
        assertEquals(Status.NORMAL, containers.get(0).status());
        assertEquals("mysql", containers.get(1).name());
        assertEquals(Status.ERROR, containers.get(1).status());
    }

    private void expectList(String json) {
        server.expect(requestTo(DOCKER_URL + "/containers/json?all=true"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private void expectInspect(String id, int exitCode, String restartPolicy) {
        server.expect(requestTo(DOCKER_URL + "/containers/" + id + "/json"))
                .andRespond(withSuccess(
                        "{\"State\":{\"ExitCode\":" + exitCode + "},"
                                + "\"HostConfig\":{\"RestartPolicy\":{\"Name\":\"" + restartPolicy + "\"}}}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void testListAll_docker_socket_proxy未到達時は空リストを返す() {
        server.expect(requestTo(DOCKER_URL + "/containers/json?all=true"))
                .andRespond(withServerError());

        List<ContainerStatusResponse> containers = service.listAll();

        assertEquals(List.of(), containers);
    }
}
