package com.letsblog.api.buffer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * BufferClientの回帰テスト。GithubClientTest/WordPressAdapterTestと同様、
 * MockRestServiceServerでHTTP通信(form-urlencoded)を検証する。
 */
class BufferClientTest {

    private static final String BASE_URL = "https://buffer.test";

    private BufferClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BufferClient(builder);
    }

    @Test
    void createUpdate_成功時に作成されたupdate一覧を返す() {
        server.expect(requestTo(BASE_URL + "/updates/create.json"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("access_token=test-access-token")))
                .andExpect(content().string(containsString("profile_ids%5B%5D=profile-1")))
                .andExpect(content().string(containsString("profile_ids%5B%5D=profile-2")))
                .andExpect(content().string(containsString("text=Hello")))
                .andExpect(content().string(containsString("scheduled_at=1700000000")))
                .andRespond(withSuccess(
                        "{\"success\":true,\"updates\":["
                                + "{\"id\":\"upd-1\",\"profile_id\":\"profile-1\"},"
                                + "{\"id\":\"upd-2\",\"profile_id\":\"profile-2\"}"
                                + "]}",
                        MediaType.APPLICATION_JSON));

        List<BufferUpdate> updates = client.createUpdate(
                List.of("profile-1", "profile-2"), "Hello", Instant.ofEpochSecond(1700000000L), "test-access-token");

        assertEquals(2, updates.size());
        assertEquals("upd-1", updates.get(0).id());
        assertEquals("profile-1", updates.get(0).profileId());
        assertEquals("upd-2", updates.get(1).id());
        assertEquals("profile-2", updates.get(1).profileId());
        server.verify();
    }

    @Test
    void createUpdate_successがfalseの場合は例外を投げる() {
        server.expect(requestTo(BASE_URL + "/updates/create.json"))
                .andRespond(withSuccess(
                        "{\"success\":false,\"message\":\"Invalid access token\"}", MediaType.APPLICATION_JSON));

        BufferApiException e = assertThrows(BufferApiException.class,
                () -> client.createUpdate(List.of("profile-1"), "Hello", Instant.now(), "test-access-token"));

        assertEquals("Bufferへの投稿予約に失敗しました: Invalid access token", e.getMessage());
    }

    @Test
    void createUpdate_5xx応答の場合は例外を投げる() {
        server.expect(requestTo(BASE_URL + "/updates/create.json")).andRespond(withServerError());

        assertThrows(BufferApiException.class,
                () -> client.createUpdate(List.of("profile-1"), "Hello", Instant.now(), "test-access-token"));
    }

    @Test
    void getUpdateStatistics_statisticsの各項目を取得する() {
        server.expect(requestTo(BASE_URL + "/updates/upd-1.json?access_token=test-access-token"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"statistics\":{\"clicks\":5,\"favorites\":10,\"comments\":2,\"shares\":3}}",
                        MediaType.APPLICATION_JSON));

        BufferUpdateStatistics stats = client.getUpdateStatistics("upd-1", "test-access-token");

        assertEquals(5, stats.clicks());
        assertEquals(10, stats.favorites());
        assertEquals(2, stats.comments());
        assertEquals(3, stats.shares());
    }

    @Test
    void getUpdateStatistics_comments_sharesが無ければmentions_retweetsにフォールバックする() {
        server.expect(requestTo(BASE_URL + "/updates/upd-1.json?access_token=test-access-token"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"statistics\":{\"clicks\":5,\"favorites\":10,\"mentions\":4,\"retweets\":6}}",
                        MediaType.APPLICATION_JSON));

        BufferUpdateStatistics stats = client.getUpdateStatistics("upd-1", "test-access-token");

        assertEquals(4, stats.comments());
        assertEquals(6, stats.shares());
    }

    @Test
    void getUpdateStatistics_statisticsが無ければ全て0() {
        server.expect(requestTo(BASE_URL + "/updates/upd-1.json?access_token=test-access-token"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        BufferUpdateStatistics stats = client.getUpdateStatistics("upd-1", "test-access-token");

        assertEquals(0, stats.clicks());
        assertEquals(0, stats.favorites());
        assertEquals(0, stats.comments());
        assertEquals(0, stats.shares());
    }

    @Test
    void getUpdateStatistics_5xx応答の場合は例外を投げる() {
        server.expect(requestTo(BASE_URL + "/updates/upd-1.json?access_token=test-access-token"))
                .andRespond(withServerError());

        assertThrows(BufferApiException.class, () -> client.getUpdateStatistics("upd-1", "test-access-token"));
    }
}
