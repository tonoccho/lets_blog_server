package com.letsblog.publishing.controller;

import com.letsblog.publishing.dto.PostPublishResponse;
import com.letsblog.publishing.service.PostDeleteService;
import com.letsblog.publishing.testsupport.PublishingWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * publishing-serviceのPostController(legacy-apiから移設。issue #707)の回帰テスト。
 *
 * <p>issue #1816で書き直し: PostPublishService・PostDeleteServiceは実物を使い、外部との通信
 * (HTTPは{@code FakeHttpServer}、SSHは{@code FakeWordPress}、MQは{@code RabbitTemplate})だけを差し替える。
 */
class PostControllerTest {

    private static final String ADMIN = "Bearer admin-token";

    private PublishingWorld world;
    private PostController controller;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld().withMainSite();
        world.stubActor(ADMIN, 1L, "admin", "admin@example.com").asUser(ADMIN);
        PostDeleteService deleteService = new PostDeleteService(world.projectServiceClient, world.cmsAdapterFactory,
                world.contentServiceClient, world.domainEventPublisher, world.adminAuthorizationService);
        controller = new PostController(world.postPublishService, deleteService);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void publish_PostPublishServiceへ委譲する() {
        PostPublishResponse result = controller.publish(
                "main", "title", "slug", "draft", null, null, null, "markdown", null, null, null, null);

        assertNotNull(result.wpPostId());
        assertEquals("draft", result.status());
        assertEquals("slug", world.ssh.posts.get(result.wpPostId()).slug, "WordPressへ投稿が作られる");
        assertEquals(1, world.content.requests("PUT", "/api/internal/content/posts").size());
    }

    @Test
    void delete_PostDeleteServiceへ委譲する() {
        world.ssh.addPost("99", "my-article", "publish");

        controller.delete("main", "99");

        assertEquals("trash", world.ssh.posts.get("99").status);
        assertTrue(world.content.requests("POST", "/api/internal/content/posts/mark-trashed").get(0)
                .bodyText().contains("\"wpPostId\":\"99\""));
    }
}
