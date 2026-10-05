package com.letsblog.platform.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** ComfyUIの /system_stats が HTTP 200 を返したときだけ健全とみなす(issue #1399 要件5)。 */
class HttpComputeDeviceHealthProbeTest {

    private static final String URL = "http://comfyui.test:8188/system_stats";

    private MockRestServiceServer server;
    private HttpComputeDeviceHealthProbe probe;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        probe = new HttpComputeDeviceHealthProbe(builder);
    }

    @Test
    void 二百なら健全() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertTrue(probe.isHealthy(URL));
    }

    @Test
    void 二百以外は健全ではない() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertFalse(probe.isHealthy(URL));
    }

    @Test
    void 接続できなければ健全ではない() {
        assertFalse(new HttpComputeDeviceHealthProbe().isHealthy("http://127.0.0.1:1/system_stats"));
    }
    @Test
    void 二百以外の成功応答も健全とは見なさない() {
        server.expect(requestTo(URL)).andRespond(withNoContent());

        assertFalse(probe.isHealthy(URL));
    }
}
