package com.letsblog.platform.service;

import com.letsblog.platform.service.DockerEngineClient.ContainerRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * docker-socket-proxy経由のDocker Engine API呼び出し(issue #1399)。使うのは
 * GET /containers/json・POST /containers/{id}/start・POST /containers/{id}/stop だけである。
 */
class RestDockerEngineClientTest {

    private static final String URL = "http://docker-socket-proxy.test";
    private static final String LABEL = "com.docker.compose.project";

    private MockRestServiceServer server;
    private RestDockerEngineClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestDockerEngineClient(builder, "lets_blog_server");
    }

    private static String item(String id, String name, String state, String project) {
        return "{\"Id\":\"" + id + "\",\"Names\":[\"/" + name + "\"],\"State\":\"" + state
                + "\",\"Labels\":{\"" + LABEL + "\":\"" + project + "\"}}";
    }

    @Test
    void 一覧は自プロジェクトのコンテナだけを名前の先頭のスラッシュ無しで返す() {
        server.expect(requestTo(URL + "/containers/json?all=true"))
                .andRespond(withSuccess("[" + item("a1", "lbs-comfyui", "running", "lets_blog_server") + ","
                        + item("b2", "lbs-comfyui-cpu", "exited", "other_project") + ","
                        + "{\"Id\":\"c3\",\"Names\":[],\"State\":\"running\"},"
                        + "{\"Id\":\"d4\",\"State\":\"running\"}]", MediaType.APPLICATION_JSON));

        List<ContainerRef> refs = client.listContainers();

        assertEquals(List.of(new ContainerRef("a1", "lbs-comfyui", "running")), refs);
        assertTrue(refs.get(0).running());
    }

    @Test
    void プロジェクト名が空ならプロジェクトでは絞らない() {
        RestClient.Builder builder = RestClient.builder().baseUrl(URL);
        MockRestServiceServer s = MockRestServiceServer.bindTo(builder).build();
        RestDockerEngineClient unscoped = new RestDockerEngineClient(builder, "  ");
        s.expect(requestTo(URL + "/containers/json?all=true"))
                .andRespond(withSuccess("[" + item("b2", "lbs-comfyui-cpu", "exited", "other") + "]",
                        MediaType.APPLICATION_JSON));

        List<ContainerRef> refs = unscoped.listContainers();

        assertEquals(1, refs.size());
        assertFalse(refs.get(0).running());
    }

    @Test
    void 一覧が空の応答なら空リスト() {
        server.expect(requestTo(URL + "/containers/json?all=true"))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        assertEquals(List.of(), client.listContainers());
    }

    @Test
    void 一覧の取得に失敗したらDockerEngineException() {
        server.expect(requestTo(URL + "/containers/json?all=true")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThrows(DockerEngineException.class, () -> client.listContainers());
    }

    @Test
    void startはPOST_containers_id_startを呼ぶ() {
        server.expect(requestTo(URL + "/containers/a1/start"))
                .andExpect(method(HttpMethod.POST)).andRespond(withNoContent());

        client.startContainer("a1");

        server.verify();
    }

    @Test
    void stopはPOST_containers_id_stopを呼ぶ() {
        server.expect(requestTo(URL + "/containers/a1/stop"))
                .andExpect(method(HttpMethod.POST)).andRespond(withNoContent());

        client.stopContainer("a1");

        server.verify();
    }

    @Test
    void 既に起動済み_停止済みの304は成功として扱う() {
        server.expect(requestTo(URL + "/containers/a1/start")).andRespond(withStatus(HttpStatus.NOT_MODIFIED));
        server.expect(requestTo(URL + "/containers/a1/stop")).andRespond(withStatus(HttpStatus.NOT_MODIFIED));

        client.startContainer("a1");
        client.stopContainer("a1");

        server.verify();
    }

    @Test
    void proxyが403で拒否したらDockerEngineExceptionに403を含める() {
        server.expect(requestTo(URL + "/containers/a1/start")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo(URL + "/containers/a1/stop")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        DockerEngineException start = assertThrows(DockerEngineException.class, () -> client.startContainer("a1"));
        DockerEngineException stop = assertThrows(DockerEngineException.class, () -> client.stopContainer("a1"));

        assertTrue(start.getMessage().contains("403"), start.getMessage());
        assertTrue(stop.getMessage().contains("403"), stop.getMessage());
    }

    @Test
    void 公開コンストラクタは未到達のproxyでも例外をDockerEngineExceptionに包む() {
        RestDockerEngineClient unreachable = new RestDockerEngineClient("http://127.0.0.1:1", "");

        assertThrows(DockerEngineException.class, unreachable::listContainers);
        assertThrows(DockerEngineException.class, () -> unreachable.startContainer("x"));
    }
    @Test
    void 本文の無い応答は空リスト() {
        server.expect(requestTo(URL + "/containers/json?all=true")).andRespond(withNoContent());

        assertEquals(List.of(), client.listContainers());
    }

    @Test
    void 先頭にスラッシュの無い名前もそのまま返す() {
        server.expect(requestTo(URL + "/containers/json?all=true"))
                .andRespond(withSuccess("[{\"Id\":\"z9\",\"Names\":[\"lbs-x\"],\"State\":\"running\","
                        + "\"Labels\":{\"" + LABEL + "\":\"lets_blog_server\"}}]", MediaType.APPLICATION_JSON));

        assertEquals(List.of(new ContainerRef("z9", "lbs-x", "running")), client.listContainers());
    }

    // ---- issue #1585: GET /containers/{id}/json(HostConfig.Runtime と State.Health.Status) ----

    @Test
    void inspectはHostConfigのRuntimeとヘルスチェックの状態を返す() {
        server.expect(requestTo(URL + "/containers/a1/json")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"HostConfig\":{\"Runtime\":\"nvidia\"},"
                        + "\"State\":{\"Health\":{\"Status\":\"healthy\"}}}", MediaType.APPLICATION_JSON));

        DockerEngineClient.ContainerInspection inspection = client.inspectContainer("a1");

        assertEquals("nvidia", inspection.runtime());
        assertTrue(inspection.nvidia());
        assertTrue(inspection.healthy());
        server.verify();
    }

    @Test
    void inspect_ヘルスチェックが無い_Runtimeが空なら_nvidiaでもhealthyでもない() {
        server.expect(requestTo(URL + "/containers/a1/json"))
                .andRespond(withSuccess("{\"HostConfig\":{\"Runtime\":\"\"},\"State\":{}}",
                        MediaType.APPLICATION_JSON));

        DockerEngineClient.ContainerInspection inspection = client.inspectContainer("a1");

        assertEquals("", inspection.runtime());
        assertFalse(inspection.nvidia());
        assertFalse(inspection.healthy());
    }

    @Test
    void inspect_starting中はhealthyではない() {
        server.expect(requestTo(URL + "/containers/a1/json"))
                .andRespond(withSuccess("{\"HostConfig\":{\"Runtime\":\"runc\"},"
                        + "\"State\":{\"Health\":{\"Status\":\"starting\"}}}", MediaType.APPLICATION_JSON));

        assertFalse(client.inspectContainer("a1").healthy());
    }

    @Test
    void inspect_本文が無ければ空の結果() {
        server.expect(requestTo(URL + "/containers/a1/json")).andRespond(withNoContent());

        DockerEngineClient.ContainerInspection inspection = client.inspectContainer("a1");

        assertFalse(inspection.nvidia());
        assertFalse(inspection.healthy());
    }

    @Test
    void inspectの取得に失敗したらDockerEngineException() {
        server.expect(requestTo(URL + "/containers/a1/json")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThrows(DockerEngineException.class, () -> client.inspectContainer("a1"));
    }
}
