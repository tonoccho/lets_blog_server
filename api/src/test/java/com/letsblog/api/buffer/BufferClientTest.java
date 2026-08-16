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
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * BufferClientの回帰テスト。GraphQL API(単一エンドポイントへのPOST、JSON body)への移行(issue #411)後の
 * 挙動をMockRestServiceServerで検証する。
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
        server.expect(requestTo(BASE_URL))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-access-token"))
                .andExpect(content().string(containsString("channelId: \\\"profile-1\\\"")))
                .andExpect(content().string(containsString("text: \\\"Hello\\\"")))
                .andExpect(content().string(containsString("dueAt: \\\"2023-11-14T22:13:20Z\\\"")))
                .andRespond(withSuccess(
                        "{\"data\":{\"createPost\":{\"post\":{\"id\":\"upd-1\"}}}}", MediaType.APPLICATION_JSON));

        List<BufferUpdate> updates = client.createUpdate(
                List.of("profile-1"), "Hello", Instant.ofEpochSecond(1700000000L), "test-access-token");

        assertEquals(1, updates.size());
        assertEquals("upd-1", updates.get(0).id());
        assertEquals("profile-1", updates.get(0).profileId());
        server.verify();
    }

    @Test
    void createUpdate_複数profileIdの場合はchannelIdごとにmutationを呼び出す() {
        server.expect(requestTo(BASE_URL))
                .andExpect(content().string(containsString("channelId: \\\"profile-1\\\"")))
                .andRespond(withSuccess(
                        "{\"data\":{\"createPost\":{\"post\":{\"id\":\"upd-1\"}}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL))
                .andExpect(content().string(containsString("channelId: \\\"profile-2\\\"")))
                .andRespond(withSuccess(
                        "{\"data\":{\"createPost\":{\"post\":{\"id\":\"upd-2\"}}}}", MediaType.APPLICATION_JSON));

        List<BufferUpdate> updates = client.createUpdate(
                List.of("profile-1", "profile-2"), "Hello", Instant.now(), "test-access-token");

        assertEquals(2, updates.size());
        assertEquals("upd-1", updates.get(0).id());
        assertEquals("profile-1", updates.get(0).profileId());
        assertEquals("upd-2", updates.get(1).id());
        assertEquals("profile-2", updates.get(1).profileId());
        server.verify();
    }

    @Test
    void createUpdate_MutationErrorの場合は例外を投げる() {
        server.expect(requestTo(BASE_URL))
                .andRespond(withSuccess(
                        "{\"data\":{\"createPost\":{\"message\":\"Invalid channel\"}}}", MediaType.APPLICATION_JSON));

        BufferApiException e = assertThrows(BufferApiException.class,
                () -> client.createUpdate(List.of("profile-1"), "Hello", Instant.now(), "test-access-token"));

        assertEquals("Bufferへの投稿予約に失敗しました: Invalid channel", e.getMessage());
    }

    @Test
    void createUpdate_トップレベルerrorsがある場合は例外を投げる() {
        server.expect(requestTo(BASE_URL))
                .andRespond(withSuccess(
                        "{\"errors\":[{\"message\":\"Unauthorized\"}]}", MediaType.APPLICATION_JSON));

        BufferApiException e = assertThrows(BufferApiException.class,
                () -> client.createUpdate(List.of("profile-1"), "Hello", Instant.now(), "test-access-token"));

        assertEquals("Bufferへの投稿予約に失敗しました: Unauthorized", e.getMessage());
    }

    @Test
    void createUpdate_5xx応答の場合は例外を投げる() {
        server.expect(requestTo(BASE_URL)).andRespond(withServerError());

        assertThrows(BufferApiException.class,
                () -> client.createUpdate(List.of("profile-1"), "Hello", Instant.now(), "test-access-token"));
    }

    @Test
    void getUpdateStatistics_metrics配列の各項目を取得する() {
        server.expect(requestTo(BASE_URL))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-access-token"))
                .andExpect(content().string(containsString("id: \\\"upd-1\\\"")))
                .andRespond(withSuccess(
                        "{\"data\":{\"post\":{\"metrics\":["
                                + "{\"type\":\"clicks\",\"value\":5},"
                                + "{\"type\":\"reactions\",\"value\":10},"
                                + "{\"type\":\"comments\",\"value\":2},"
                                + "{\"type\":\"reposts\",\"value\":3}"
                                + "]}}}",
                        MediaType.APPLICATION_JSON));

        BufferUpdateStatistics stats = client.getUpdateStatistics("upd-1", "test-access-token");

        assertEquals(5, stats.clicks());
        assertEquals(10, stats.favorites());
        assertEquals(2, stats.comments());
        assertEquals(3, stats.shares());
    }

    @Test
    void getUpdateStatistics_reactions_repostsが無ければ代替の名称にフォールバックする() {
        server.expect(requestTo(BASE_URL))
                .andRespond(withSuccess(
                        "{\"data\":{\"post\":{\"metrics\":["
                                + "{\"type\":\"likes\",\"value\":10},"
                                + "{\"type\":\"shares\",\"value\":3}"
                                + "]}}}",
                        MediaType.APPLICATION_JSON));

        BufferUpdateStatistics stats = client.getUpdateStatistics("upd-1", "test-access-token");

        assertEquals(10, stats.favorites());
        assertEquals(3, stats.shares());
    }

    @Test
    void getUpdateStatistics_postが無ければ全て0() {
        server.expect(requestTo(BASE_URL))
                .andRespond(withSuccess("{\"data\":{\"post\":null}}", MediaType.APPLICATION_JSON));

        BufferUpdateStatistics stats = client.getUpdateStatistics("upd-1", "test-access-token");

        assertEquals(0, stats.clicks());
        assertEquals(0, stats.favorites());
        assertEquals(0, stats.comments());
        assertEquals(0, stats.shares());
    }

    @Test
    void getUpdateStatistics_5xx応答の場合は例外を投げる() {
        server.expect(requestTo(BASE_URL)).andRespond(withServerError());

        assertThrows(BufferApiException.class, () -> client.getUpdateStatistics("upd-1", "test-access-token"));
    }
}
