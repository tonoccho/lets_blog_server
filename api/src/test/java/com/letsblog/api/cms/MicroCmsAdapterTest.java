package com.letsblog.api.cms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * MicroCmsAdapterの回帰テスト。WordPressAdapterTestと同様に
 * RestClient.Builderを単体で生成し、MockRestServiceServerでHTTP通信を検証する。
 */
class MicroCmsAdapterTest {

    private RestClient.Builder restClientBuilder;
    private MicroCmsAdapter adapter;
    private MockRestServiceServer server;

    private final CmsCredentials.MicroCmsCredentials creds = new CmsCredentials.MicroCmsCredentials(
            "myservice", "api-key-123", "mgmt-key-456", "posts", "categories", "tags");

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        adapter = new MicroCmsAdapter(restClientBuilder);
    }

    @Test
    void testSupportedType() {
        assertEquals(CmsType.MICROCMS, adapter.supportedType());
    }

    @Test
    void testCreatePost() {
        server.expect(requestTo("https://myservice.microcms.io/api/v1/posts"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"id\":\"post-abc123\"}",
                        MediaType.APPLICATION_JSON));

        PostContent content = new PostContent("Test", null, "<p>Test</p>", "draft", null, null);

        PostResult result = adapter.createOrUpdatePost(creds, content, null);

        assertEquals("post-abc123", result.id());
        assertTrue(result.link().contains("post-abc123"));
        server.verify();
    }

    @Test
    void testUpdatePost() {
        server.expect(requestTo("https://myservice.microcms.io/api/v1/posts/post-abc123"))
                .andExpect(method(PATCH))
                .andRespond(withSuccess(
                        "{\"id\":\"post-abc123\"}",
                        MediaType.APPLICATION_JSON));

        PostContent content = new PostContent("Updated", null, "<p>Updated</p>", "published", null, null);

        PostResult result = adapter.createOrUpdatePost(creds, content, "post-abc123");

        assertEquals("post-abc123", result.id());
        server.verify();
    }

    @Test
    void testUploadMedia() {
        server.expect(requestTo("https://myservice.microcms-management.io/api/v1/media"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"id\":\"media-xyz789\",\"url\":\"https://images.microcms-assets.io/media-xyz789.png\"}",
                        MediaType.APPLICATION_JSON));

        byte[] imageData = new byte[]{1, 2, 3};

        MediaUploadResult result = adapter.uploadMedia(creds, "image.png", "image/png", imageData);

        assertEquals("media-xyz789", result.id());
        assertTrue(result.url().contains("media-xyz789"));
        server.verify();
    }

    @Test
    void testResolveCategoriesExisting() {
        server.expect(requestTo(containsString("/categories")))
                .andRespond(withSuccess(
                        "{\"contents\":[{\"id\":\"cat-1\",\"name\":\"Tech\"}]}",
                        MediaType.APPLICATION_JSON));

        List<String> ids = adapter.resolveCategories(creds, List.of("Tech"));

        assertEquals(List.of("cat-1"), ids);
        server.verify();
    }

    @Test
    void testResolveCategoriesCreateNew() {
        server.expect(requestTo(containsString("/categories")))
                .andRespond(withSuccess("{\"contents\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://myservice.microcms.io/api/v1/categories"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"id\":\"cat-2\",\"name\":\"NewCategory\"}", MediaType.APPLICATION_JSON));

        List<String> ids = adapter.resolveCategories(creds, List.of("NewCategory"));

        assertEquals(List.of("cat-2"), ids);
        server.verify();
    }

    @Test
    void testResolveTags() {
        server.expect(requestTo(containsString("/tags")))
                .andRespond(withSuccess("{\"contents\":[{\"id\":\"tag-1\",\"name\":\"java\"}]}", MediaType.APPLICATION_JSON));

        List<String> ids = adapter.resolveTags(creds, List.of("java"));

        assertEquals(List.of("tag-1"), ids);
        server.verify();
    }

    @Test
    void testApiError() {
        server.expect(requestTo("https://myservice.microcms.io/api/v1/posts"))
                .andRespond(withServerError());

        PostContent content = new PostContent("Test", null, "<p>Test</p>", "draft", null, null);

        assertThrows(CmsApiException.class,
                () -> adapter.createOrUpdatePost(creds, content, null));
        server.verify();
    }

    @Test
    void testProvisionDefaultCategory_既存カテゴリを返す() {
        server.expect(requestTo(containsString("/categories")))
                .andRespond(withSuccess(
                        "{\"contents\":[{\"id\":\"cat-1\",\"name\":\"Uncategorized\"}]}",
                        MediaType.APPLICATION_JSON));

        String categoryId = adapter.provisionDefaultCategory(creds);

        assertEquals("cat-1", categoryId);
        server.verify();
    }

    @Test
    void testProvisionDefaultTag_存在しなければ作成する() {
        server.expect(requestTo(containsString("/tags")))
                .andRespond(withSuccess("{\"contents\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://myservice.microcms.io/api/v1/tags"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"id\":\"tag-9\",\"name\":\"Let's Blog\"}", MediaType.APPLICATION_JSON));

        String tagId = adapter.provisionDefaultTag(creds);

        assertEquals("tag-9", tagId);
        server.verify();
    }

    @Test
    void testProvisionAuthor_未対応のためnullを返す() {
        assertNull(adapter.provisionAuthor(creds, AuthorProvisioningRequest.of("author@example.com")));
    }
}
