package com.letsblog.platform.controller;

import com.letsblog.platform.config.GlobalExceptionHandler;
import com.letsblog.platform.service.PluginFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部の解決API(issue #1807 / #1808)。種類を指定して使用中の登録(または未接続)を返す。
 *
 * <p>issue #1825: コントローラと、システム全体・プロジェクト単位の登録サービスは実物で、登録は(メモリ上の)リポジトリに
 * 保存して解決する。Springの{@link MockMvc}(スタンドアロン)を通す。
 */
class InternalPluginRegistrationControllerTest {

    private static final String RESOLVE = "/api/internal/platform/plugin-registrations/resolve";

    private PluginFixture fixture;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        fixture = new PluginFixture();
        mvc = MockMvcBuilders.standaloneSetup(new InternalPluginRegistrationController(fixture.systemService))
                .setControllerAdvice(new GlobalExceptionHandler(
                        new org.springframework.boot.servlet.autoconfigure.MultipartProperties()))
                .build();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void 登録が無ければ未接続を返す() throws Exception {
        mvc.perform(get(RESOLVE).param("kind", "LLM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    void 種類を指定して使用中の登録を解決する() throws Exception {
        var created = fixture.systemService.create("LLM", "社内", "http://lbx-ollama:11434/v1", null);
        fixture.systemService.activate(created.id());

        mvc.perform(get(RESOLVE).param("kind", "LLM"))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.name").value("社内"))
                .andExpect(jsonPath("$.scope").value("SYSTEM"));
    }

    @Test
    void プロジェクトIDを任意で受け取り_上書きがあればそれを解決する() throws Exception {
        var override = fixture.projectService.create(10L, "LLM", "本命", "http://gpu-pc.example:1", null, "sk");
        fixture.projectService.activate(10L, override.id());

        mvc.perform(get(RESOLVE).param("kind", "LLM").param("projectId", "10"))
                .andExpect(jsonPath("$.name").value("本命"))
                .andExpect(jsonPath("$.scope").value("PROJECT"))
                .andExpect(jsonPath("$.credential").value("sk"));
    }

    @Test
    void 不明な種類は409() throws Exception {
        mvc.perform(get(RESOLVE).param("kind", "BOGUS")).andExpect(status().isConflict());
    }
}
