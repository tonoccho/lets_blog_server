package com.letsblog.platform.controller;

import com.letsblog.platform.config.GlobalExceptionHandler;
import com.letsblog.platform.service.ExtensionsConnectionImportService;
import com.letsblog.platform.service.ExtensionsPortalUrlPolicy;
import com.letsblog.platform.testsupport.FakeHttpServer;
import com.letsblog.platform.testsupport.StatusFixture;
import java.net.InetAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ExtensionsImportController(issue #1783)。認可はサービス層(requireAdmin)が担い、ここは委譲と入力の解釈を持つ。
 *
 * <p>issue #1825: コントローラ・ExtensionsConnectionImportService・AppSettingService・AdminAuthorizationServiceは実物で、
 * Springの{@link MockMvc}(スタンドアロン)を通す。外部との通信は、ポータルのHTTP(サーバー側のフェイク)、
 * identity-serviceのHTTP(同)、system_settingsのDB(メモリ上のリポジトリ)だけを差し替える。
 */
class ExtensionsImportControllerTest {

    private static final String BASE = "/api/system-settings/app-settings/extensions-import";

    private StatusFixture fixture;
    private FakeHttpServer portal;
    private ExtensionsImportController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        fixture = new StatusFixture();
        portal = new FakeHttpServer();
        portal.json("GET", "/api/connections", "[{\"id\":\"comfyui\",\"publicUrl\":\"https://comfyui.lbx.localhost:8443\","
                + "\"internalUrl\":\"http://lbx-comfyui:8188\"}]");
        ExtensionsPortalUrlPolicy permissive = new ExtensionsPortalUrlPolicy(
                host -> new InetAddress[] {InetAddress.getByName("192.168.1.20")});
        ExtensionsConnectionImportService service = new ExtensionsConnectionImportService(
                fixture.appSettingService, fixture.world.adminAuthorizationService, permissive,
                com.letsblog.common.auth.ToolsTokenProvider.disabled());
        controller = new ExtensionsImportController(service);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(
                        new org.springframework.boot.servlet.autoconfigure.MultipartProperties()))
                .build();
    }

    @AfterEach
    void tearDown() {
        portal.close();
        fixture.close();
    }

    @Test
    void fetchは経路を解釈してポータルの接続先を候補にする() throws Exception {
        String body = "{\"portalUrl\":\"" + portal.baseUrl() + "\",\"route\":\"%s\"}";

        mvc.perform(post(BASE + "/fetch").contentType(MediaType.APPLICATION_JSON).content(body.formatted("SAME_PC")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].key").value("comfyui_base_url"))
                .andExpect(jsonPath("$.candidates[0].candidateValue").value("http://lbx-comfyui:8188"));
        mvc.perform(post(BASE + "/fetch").contentType(MediaType.APPLICATION_JSON).content(body.formatted("OTHER_HOST")))
                .andExpect(jsonPath("$.candidates[0].candidateValue").value("https://comfyui.lbx.localhost:8443"));
    }

    @Test
    void fetchの経路が不明なら例外でポータルへ問い合わせない() {
        for (String route : new String[] {null, "", "ELSEWHERE"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> controller.fetch(new ExtensionsImportController.FetchRequest(portal.baseUrl(), route)));
            assertTrue(e.getMessage().contains("経路"), e.getMessage());
        }
        assertTrue(portal.requests().isEmpty());
    }

    @Test
    void fetchの経路が不明ならHTTPでは409() throws Exception {
        mvc.perform(post(BASE + "/fetch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"portalUrl\":\"" + portal.baseUrl() + "\",\"route\":\"ELSEWHERE\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void applyは値を保存して結果を返す() throws Exception {
        mvc.perform(post(BASE + "/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"portalUrl\":null,\"values\":{\"comfyui_base_url\":\"http://lbx-comfyui:8188\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saved[0]").value("comfyui_base_url"))
                .andExpect(jsonPath("$.rejected").isEmpty());

        assertEquals("http://lbx-comfyui:8188", fixture.appSettingService.getComfyUiBaseUrl());
    }

    @Test
    void 管理者でなければ403() throws Exception {
        fixture.world.actingAsUser(2L);

        mvc.perform(post(BASE + "/apply").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"portalUrl\":null,\"values\":{}}"))
                .andExpect(status().isForbidden());
        assertTrue(portal.requests().isEmpty());
    }
}
