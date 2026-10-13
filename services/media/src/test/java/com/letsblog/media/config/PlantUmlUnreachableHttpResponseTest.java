package com.letsblog.media.config;

import com.letsblog.media.controller.RenderController;
import com.letsblog.media.render.PlantUmlClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PlantUMLサーバーへ到達できないとき、POST /api/render/plantuml が素の500ではなく
 * 接続できない旨を含む502になる(issue #1759)。実際の{@link PlantUmlClient}(存在しない宛先)と
 * {@link GlobalExceptionHandler}をMockMvcで結ぶ。
 */
@DisplayName("media-service: PlantUML不到達のHTTP応答(issue #1759)")
class PlantUmlUnreachableHttpResponseTest {

    @Test
    void 不到達は502で接続できない旨を本文に含める() throws Exception {
        RenderController controller = new RenderController(new PlantUmlClient("http://127.0.0.1:1"), null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/api/render/plantuml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"@startuml\\nA->B\\n@enduml\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error", containsString("PlantUMLサーバーに接続できません")));
    }
}
