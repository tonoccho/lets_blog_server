package com.letsblog.ai.client;

import com.letsblog.ai.dto.CategoryOption;
import com.letsblog.ai.testsupport.FakeHttpServer;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** PublishingServiceClient(issue #1824): 既存カテゴリ・タグの取得で、認可ヘッダー・空の応答・取得失敗を実HTTPで通す。 */
class PublishingServiceClientTest {

    private static final String CATEGORIES = "/api/internal/ai/projects/7/existing-categories";
    private static final String WITH_PARENTS = "/api/internal/ai/projects/7/existing-categories-with-parents";
    private static final String TAGS = "/api/internal/ai/projects/7/existing-tags";

    private FakeHttpServer server;
    private PublishingServiceClient client;

    @BeforeEach
    void setUp() {
        server = new FakeHttpServer();
        client = new PublishingServiceClient(RestClient.builder(), server.baseUrl());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void 既存カテゴリとタグと親つきカテゴリを返しBearerトークンを転送する() {
        server.json("GET", CATEGORIES, "[\"お知らせ\",\"技術\"]");
        server.json("GET", TAGS, "[\"Java\"]");
        server.json("GET", WITH_PARENTS, "[{\"name\":\"子\",\"parentName\":\"親\"}]");

        assertEquals(List.of("お知らせ", "技術"), client.listExistingCategories(7L, "Bearer t"));
        assertEquals(List.of("Java"), client.listExistingTags(7L, "Bearer t"));
        assertEquals(List.of(new CategoryOption("子", "親")), client.listExistingCategoriesWithParents(7L, "Bearer t"));
        assertEquals("Bearer t", server.last().header("Authorization"));
    }

    @Test
    void 応答が空なら空リスト() {
        server.on("GET", CATEGORIES, FakeHttpServer.Response.empty(200));

        assertEquals(List.of(), client.listExistingCategories(7L, "Bearer t"));
    }

    @Test
    void 取得に失敗したら空リスト() {
        server.status("GET", TAGS, 500, "boom");

        assertEquals(List.of(), client.listExistingTags(7L, "Bearer t"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void トークンがnullや空白ならAuthorizationヘッダーを付けない(String token) {
        server.json("GET", TAGS, "[\"Java\"]");

        assertEquals(List.of("Java"), client.listExistingTags(7L, token));
        assertNull(server.last().header("Authorization"));
    }
}
