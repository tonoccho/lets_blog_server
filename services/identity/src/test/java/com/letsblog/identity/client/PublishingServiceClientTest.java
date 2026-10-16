package com.letsblog.identity.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.identity.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link PublishingServiceClient}。HTTPはサーバー側フェイク({@link FakeHttpServer})で、クライアントは実物。 */
class PublishingServiceClientTest {

    private static final String TOKEN_PATH = "/realms/letsblog/protocol/openid-connect/token";
    private static final String AUTHORS_PATH = "/api/internal/publishing/sites/site-1/authors";

    private FakeHttpServer server;
    private PublishingServiceClient client;

    @BeforeEach
    void setUp() {
        server = new FakeHttpServer();
        server.json("POST", TOKEN_PATH, "{\"access_token\":\"svc-token\",\"expires_in\":300}");
        client = clientFor(server.baseUrl());
    }

    private PublishingServiceClient clientFor(String baseUrl) {
        ServiceTokenClient tokenClient = new ServiceTokenClient(
                RestClient.builder(), server.baseUrl() + TOKEN_PATH, "letsblog-services", "test-secret");
        OutboundAuthHeaders auth = new OutboundAuthHeaders(new MockHttpServletRequest(), tokenClient);
        return new PublishingServiceClient(RestClient.builder(), baseUrl, auth);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private PublishingServiceClient.AuthorProvisioningRequest request() {
        return new PublishingServiceClient.AuthorProvisioningRequest(
                "a@example.com", "author", "A", "B", "AB", "https://example.com", "bio", "ja");
    }

    @Test
    void provisionAuthor_リクエスト本文を送りcmsAuthorIdを返す() {
        server.json("POST", AUTHORS_PATH, "{\"cmsAuthorId\":\"77\"}");

        PublishingServiceClient.AuthorProvisioningResponse response = client.provisionAuthor("site-1", request());

        assertEquals("77", response.cmsAuthorId());
        String body = server.requests("POST", AUTHORS_PATH).get(0).bodyText();
        assertTrue(body.contains("\"email\":\"a@example.com\"") && body.contains("\"wpRole\":\"author\""), body);
    }

    @Test
    void provisionAuthor_成功でも本文が空ならPublishingServiceException() {
        server.on("POST", AUTHORS_PATH, FakeHttpServer.Response.empty(200));

        PublishingServiceException e =
                assertThrows(PublishingServiceException.class, () -> client.provisionAuthor("site-1", request()));

        assertTrue(e.getMessage().contains("空の応答"), e.getMessage());
    }

    @Test
    void provisionAuthor_エラー応答はPublishingServiceExceptionに変換される() {
        server.status("POST", AUTHORS_PATH, 500, "cms-down");

        PublishingServiceException e =
                assertThrows(PublishingServiceException.class, () -> client.provisionAuthor("site-1", request()));

        assertTrue(e.getMessage().contains("cms-down"), e.getMessage());
    }

    @Test
    void provisionAuthor_接続できなければPublishingServiceException() {
        FakeHttpServer gone = new FakeHttpServer();
        String closedUrl = gone.baseUrl();
        gone.close();

        assertThrows(PublishingServiceException.class,
                () -> clientFor(closedUrl).provisionAuthor("site-1", request()));
    }
}
