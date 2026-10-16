package com.letsblog.platform.controller;

import com.letsblog.platform.config.GlobalExceptionHandler;
import com.letsblog.platform.testsupport.StatusFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SystemSettingController(issue #1079のsite-admin-path、Brave Search APIキー)。
 *
 * <p>issue #1825: コントローラ・SystemSettingService・AppSettingService・AdminAuthorizationServiceは実物で、
 * Springの{@link MockMvc}(スタンドアロン。例外ハンドラは本物の{@link GlobalExceptionHandler})を通す。
 * 差し替えるのは外部との通信だけ(identity-serviceのHTTPフェイク、system_settingsのメモリ上のリポジトリ)。
 */
class SystemSettingControllerTest {

    private StatusFixture fixture;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        fixture = new StatusFixture();
        fixture.world.actingAsAdmin(1L);
        mvc = MockMvcBuilders.standaloneSetup(
                        new SystemSettingController(fixture.systemSettingService, fixture.appSettingService))
                .setControllerAdvice(new GlobalExceptionHandler(new org.springframework.boot.servlet.autoconfigure.MultipartProperties()))
                .build();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void getSiteAdminPath_解決済みの値をpathとして返す() throws Exception {
        fixture.setting("site_admin_path", "secret-admin");

        mvc.perform(get("/api/system-settings/site-admin-path"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("secret-admin"));
    }

    @Test
    void getSiteAdminPath_未設定なら環境変数の既定値を返す() throws Exception {
        mvc.perform(get("/api/system-settings/site-admin-path"))
                .andExpect(jsonPath("$.path").value("wp-admin"));
    }

    @Test
    void braveSearchApiKey_設定_状態確認_削除の流れ() throws Exception {
        mvc.perform(get("/api/system-settings/brave-search-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.source").value("NONE"));

        mvc.perform(put("/api/system-settings/brave-search-api-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"k-1\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/system-settings/brave-search-api-key"))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.source").value("DATABASE"));

        mvc.perform(delete("/api/system-settings/brave-search-api-key")).andExpect(status().isNoContent());

        mvc.perform(get("/api/system-settings/brave-search-api-key"))
                .andExpect(jsonPath("$.configured").value(false));
        assertTrue(fixture.store.isEmpty());
    }

    @Test
    void braveSearchApiKey_adminでなければ403() throws Exception {
        fixture.world.actingAsUser(2L);

        mvc.perform(put("/api/system-settings/brave-search-api-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"k-1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/system-settings/brave-search-api-key")).andExpect(status().isForbidden());
        assertEquals(0, fixture.store.size());
    }
}
