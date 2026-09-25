package com.letsblog.platform.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Let's Blog 自身の9サービスの稼働状況(issue #589)。
 *
 * <p>gateway の集約ヘルスを展開する方式にしているので、ここでは
 * 「gateway の応答をどう解釈するか」を固定する。
 */
@DisplayName("platform-service: Let's Blog 9サービスの稼働状況(issue #589)")
class LetsBlogServiceStatusServiceTest {

    private static final String GATEWAY_URL = "http://gateway:8080";

    private MockRestServiceServer server;

    private LetsBlogServiceStatusService buildService() {
        RestClient.Builder builder = RestClient.builder().baseUrl(GATEWAY_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        return new LetsBlogServiceStatusService(GATEWAY_URL, builder);
    }

    private static Map<String, LetsBlogServiceStatusService.ServiceHealth> byId(
            List<LetsBlogServiceStatusService.ServiceHealth> all) {
        return all.stream().collect(Collectors.toMap(
                LetsBlogServiceStatusService.ServiceHealth::id, h -> h));
    }

    @Test
    @DisplayName("集約ヘルスのUP/DOWNをサービスごとに展開する")
    void 集約ヘルスを展開する() {
        LetsBlogServiceStatusService service = buildService();
        server.expect(requestTo(GATEWAY_URL + "/actuator/health"))
                .andRespond(withSuccess("""
                        {"status":"DOWN","components":{
                          "identityService":{"status":"UP"},
                          "projectService":{"status":"UP"},
                          "contentService":{"status":"DOWN"},
                          "mediaService":{"status":"UP"},
                          "aiService":{"status":"UP"},
                          "analyticsService":{"status":"UP"},
                          "publishingService":{"status":"UP"},
                          "platformService":{"status":"UP"},
                          "logWriterService":{"status":"UP"}}}
                        """, MediaType.APPLICATION_JSON));

        var result = byId(service.checkAll());

        assertEquals(9, result.size());
        assertTrue(result.get("identityService").up());
        assertFalse(result.get("contentService").up(), "DOWNのサービスはupにしない");
        assertTrue(result.get("contentService").detail().contains("DOWN"));
    }

    @Test
    @DisplayName("停止時に何が使えなくなるかを各サービスが持つ")
    void 影響の説明を持つ() {
        LetsBlogServiceStatusService service = buildService();
        server.expect(requestTo(GATEWAY_URL + "/actuator/health"))
                .andRespond(withSuccess("""
                        {"status":"UP","components":{"contentService":{"status":"UP"}}}
                        """, MediaType.APPLICATION_JSON));

        var result = byId(service.checkAll());

        // サービス名だけでは運用者が影響を判断できない(#589 の受入基準)。
        assertTrue(result.get("contentService").impact().contains("記事本文"));
        assertTrue(result.get("mediaService").impact().contains("画像生成"));
    }

    @Test
    @DisplayName("集約ヘルスが503(下流のどれかがDOWN)でも、本文を読んで個別に判定する")
    void 集約ヘルスが503でも個別に判定する() {
        LetsBlogServiceStatusService service = buildService();
        // 下流が1つでもDOWNなら Actuator は HTTP 503 を返す。ここで例外にすると
        // 「gatewayへ到達できない」と誤判定し、9件すべてが判定不能になってしまう
        // (issue #589 の実機検証で実際に起きた)。
        server.expect(requestTo(GATEWAY_URL + "/actuator/health"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                        {"status":"DOWN","components":{
                          "identityService":{"status":"UP"},
                          "analyticsService":{"status":"DOWN"}}}
                        """));

        var result = byId(service.checkAll());

        assertTrue(result.get("identityService").up(), "他のサービスは正常のままであるべき");
        assertFalse(result.get("analyticsService").up());
    }

    @Test
    @DisplayName("gatewayへ到達できない場合は全サービスを判定不能(ERROR相当)にする")
    void gatewayへ到達できなければ全件エラー() {
        LetsBlogServiceStatusService service = buildService();
        server.expect(requestTo(GATEWAY_URL + "/actuator/health"))
                .andRespond(request -> {
                    throw new java.io.IOException("connection refused");
                });

        var result = service.checkAll();

        assertEquals(9, result.size());
        // 判定できないものを「正常」に見せない。
        assertTrue(result.stream().noneMatch(LetsBlogServiceStatusService.ServiceHealth::up));
        assertTrue(result.get(0).detail().contains("gateway"));
    }

    @Test
    @DisplayName("集約ヘルスに含まれないサービスは『監視対象からの漏れ』として不健全にする")
    void 集約ヘルスに無いサービスは漏れとして扱う() {
        LetsBlogServiceStatusService service = buildService();
        // publishingService だけを欠いた応答。DownstreamHealthConfig への追加漏れを模す。
        server.expect(requestTo(GATEWAY_URL + "/actuator/health"))
                .andRespond(withSuccess("""
                        {"status":"UP","components":{"identityService":{"status":"UP"}}}
                        """, MediaType.APPLICATION_JSON));

        var result = byId(service.checkAll());

        assertFalse(result.get("publishingService").up());
        assertTrue(result.get("publishingService").detail().contains("追加漏れ"));
    }
}
