package com.letsblog.platform.controller;

import com.letsblog.platform.config.GlobalExceptionHandler;
import com.letsblog.platform.service.PluginFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PluginRegistrationController(issue #1807)。HTTP入出力と、サービス層の認可・検査・件数規則を通して確かめる。
 *
 * <p>issue #1825: コントローラ・PluginRegistrationService・AdminAuthorizationServiceは実物で、Springの
 * {@link MockMvc}(スタンドアロン)を通す。差し替えるのは外部との通信(identity-serviceのHTTPフェイク、
 * 登録のDB=メモリ上のリポジトリ、接続先の名前解決)だけ。
 */
class PluginRegistrationControllerTest {

    private static final String BASE = "/api/system-settings/plugin-registrations";
    private static final String BODY = "{\"kind\":\"LLM\",\"name\":\"社内\",\"url\":\"http://lbx-ollama:11434/v1\"}";

    private PluginFixture fixture;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        fixture = new PluginFixture();
        mvc = MockMvcBuilders.standaloneSetup(new PluginRegistrationController(fixture.systemService))
                .setControllerAdvice(new GlobalExceptionHandler(
                        new org.springframework.boot.servlet.autoconfigure.MultipartProperties()))
                .build();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void 一覧_追加_編集_削除_使用中の切り替えを行える() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.kind").value("LLM"))
                .andExpect(jsonPath("$.name").value("社内"));

        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("社内"))
                .andExpect(jsonPath("$[0].inUse").value(false));

        mvc.perform(put(BASE + "/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"社内2\",\"url\":\"http://lbx-ollama:11434/v1\"}"))
                .andExpect(jsonPath("$.name").value("社内2"));

        mvc.perform(put(BASE + "/1/in-use")).andExpect(jsonPath("$.inUse").value(true));

        mvc.perform(delete(BASE + "/1")).andExpect(status().isNoContent());
        assertEquals(0, fixture.systemStore.size());
    }

    @Test
    void 管理者でなければ403() throws Exception {
        fixture.world.actingAsUser(2L);

        mvc.perform(get(BASE)).andExpect(status().isForbidden());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        assertEquals(0, fixture.systemStore.size());
    }

    @Test
    void 不正な入力は409で理由を返す() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"BOGUS\",\"name\":\"a\",\"url\":\"http://lbx-ollama:1\"}"))
                .andExpect(status().isConflict());
    }
}
