package com.letsblog.identity.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.identity.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

/**
 * issue #1541: project-service の内部ブリッジ応答の日時が Z 終端 RFC 3339 になっても、
 * 本クライアントの DTO({@code createdAt}/{@code updatedAt} を持たない)が例外なく読めること。
 * 実際の Z 付き JSON を、本番と同じ RestClient + Jackson で読ませて確かめる。
 */
class ProjectServiceClientBridgeDateTimeTest {

    private static final String TOKEN_PATH = "/realms/letsblog/protocol/openid-connect/token";

    private FakeHttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    /** リクエストの無い文脈なので、認証は実物の{@code ServiceTokenClient}がフェイクのトークンエンドポイントから取る。 */
    private ProjectServiceClient client() {
        ServiceTokenClient tokenClient = new ServiceTokenClient(
                RestClient.builder(), server.baseUrl() + TOKEN_PATH, "letsblog-services", "test-secret");
        OutboundAuthHeaders auth = new OutboundAuthHeaders(new MockHttpServletRequest(), tokenClient);
        return new ProjectServiceClient(RestClient.builder(), server.baseUrl(), auth);
    }

    private void start(String path, String body) {
        server = new FakeHttpServer();
        server.json("POST", TOKEN_PATH, "{\"access_token\":\"svc-token\",\"expires_in\":300}");
        server.json("GET", path, body);
    }

    @Test
    void getProject_はZ付きの日時を含む応答を読める() {
        start("/api/internal/project/projects/7", "{\"id\":7,\"name\":\"P\",\"slug\":\"p\",\"masterEnvironment\":\"test\",\"localSiteId\":1,\"testSiteId\":2,\"productionSiteId\":3,\"githubRepository\":null,\"createdAt\":\"2026-09-08T20:03:35Z\",\"updatedAt\":\"2026-09-09T01:02:03Z\"}");
        assertThat(client().getProject(7L).slug()).isEqualTo("p");
    }

    @Test
    void getSite_はZ付きの日時を含む応答を読める() {
        start("/api/internal/project/sites/10", "{\"id\":10,\"siteKey\":\"my-blog\",\"name\":\"My Blog\",\"baseUrl\":\"https://example.com\",\"cmsType\":\"WORDPRESS\",\"managedWordpress\":false,\"wpSlug\":null,\"createdAt\":\"2026-09-08T20:03:35Z\",\"updatedAt\":\"2026-09-09T01:02:03Z\"}");
        assertThat(client().getSite(10L)).get().extracting(ProjectServiceClient.SiteBridge::siteKey).isEqualTo("my-blog");
    }
}
