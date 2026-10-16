package com.letsblog.project.provisioning;

import com.letsblog.project.testsupport.FakeHttpServer;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** provision-agentの/plugins(有効なプラグインの一覧)の読み取り(issue #1818)。クライアントは実物、エージェントはフェイクHTTPサーバー。 */
class WordPressAgentPluginsClientTest {

    private final FakeHttpServer agent = new FakeHttpServer();
    private final WordPressAgentPluginsClient client = new WordPressAgentPluginsClient(agent.baseUrl(), "agent-token");

    @AfterEach
    void close() {
        agent.close();
    }

    @Test
    void 有効なプラグインの名前だけを返し_トークンとslugを送る() {
        agent.json("POST", "/plugins", "{\"plugins\":[{\"name\":\"Akismet\",\"status\":\"active\"},"
                + "{\"name\":\"休止中\",\"status\":\"inactive\"},\"not-an-object\"]}");

        assertEquals(List.of("Akismet"), client.listActivePluginNames("my-slug"));
        FakeHttpServer.Request sent = agent.requests("POST", "/plugins").get(0);
        assertEquals("agent-token", sent.header("X-Provision-Token"));
        assertEquals("{\"slug\":\"my-slug\"}", sent.bodyText());
    }

    @Test
    void 本文が空_pluginsが一覧でない_通信失敗のときは空の一覧を返す() {
        agent.on("POST", "/plugins", FakeHttpServer.Response.empty(200));
        assertEquals(List.of(), client.listActivePluginNames("s"));

        agent.json("POST", "/plugins", "{\"plugins\":\"oops\"}");
        assertEquals(List.of(), client.listActivePluginNames("s"));

        agent.status("POST", "/plugins", 500, "{\"error\":\"x\"}");
        assertEquals(List.of(), client.listActivePluginNames("s"));
    }
}
