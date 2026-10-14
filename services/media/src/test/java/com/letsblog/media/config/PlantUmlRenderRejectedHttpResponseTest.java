package com.letsblog.media.config;

import com.letsblog.media.controller.RenderController;
import com.letsblog.media.render.PlantUmlClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PlantUMLサーバーが図を拒否(エラー応答)したとき、POST /api/render/plantuml は502ではなく
 * 400と「PlantUML が図を描けませんでした: …」を返す(issue #1776)。
 */
@DisplayName("media-service: PlantUML拒否のHTTP応答(issue #1776)")
class PlantUmlRenderRejectedHttpResponseTest {

    @Test
    void 拒否は400で詳細を含み接続できない旨を含まない() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getResponseHeaders().add("X-PlantUML-Diagram-Error", "Syntax Error?");
            ex.getResponseHeaders().add("X-PlantUML-Diagram-Error-Line", "2");
            ex.sendResponseHeaders(400, -1);
            ex.close();
        });
        server.start();
        try {
            RenderController controller = new RenderController(
                    new PlantUmlClient("http://127.0.0.1:" + server.getAddress().getPort()), null);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                    .setControllerAdvice(new GlobalExceptionHandler()).build();

            mvc.perform(post("/api/render/plantuml")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"source\":\"@startuml\\nA->B\\n@enduml\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", startsWith("PlantUML が図を描けませんでした: ")))
                    .andExpect(jsonPath("$.error", containsString("Syntax Error? (2 行目)")))
                    .andExpect(jsonPath("$.error", not(containsString("接続できません"))));
        } finally {
            server.stop(0);
        }
    }
}
