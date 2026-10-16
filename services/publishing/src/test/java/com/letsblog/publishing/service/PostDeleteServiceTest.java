package com.letsblog.publishing.service;

import com.letsblog.publishing.cms.agent.PostNotFoundException;
import com.letsblog.publishing.testsupport.PublishingWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * PostDeleteServiceの回帰テスト。
 *
 * <p>issue #1815で書き直し: 自前のクラス(ProjectServiceClient・ContentServiceClient・CmsAdapterFactory・
 * WordPressAdapter・DomainEventPublisher・AdminAuthorizationService)は実物を使い、外部との通信だけを差し替える。
 * HTTPは{@code FakeHttpServer}、WordPressへのSSHは{@code FakeWordPress}、MQは{@code RabbitTemplate}のMockito。
 */
class PostDeleteServiceTest {

    private static final String ADMIN = "Bearer admin-token";

    private PublishingWorld world;
    private PostDeleteService service;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld().withMainSite();
        world.stubActor(ADMIN, 1L, "admin", "admin@example.com").asUser(ADMIN);
        service = new PostDeleteService(world.projectServiceClient, world.cmsAdapterFactory,
                world.contentServiceClient, world.domainEventPublisher, world.adminAuthorizationService);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private boolean eventPublished() {
        try {
            verify(world.rabbitTemplate).convertAndSend(eq("letsblog.events"), eq("post.deleted"), any(Object.class));
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    @Test
    void delete_CmsAdapterで削除しcontent_serviceへtrash反映を依頼する() {
        world.ssh.addPost("99", "my-article", "publish");

        service.delete("main", "99");

        assertEquals("trash", world.ssh.posts.get("99").status, "WordPressでは投稿がゴミ箱へ移る");
        var request = world.content.requests("POST", "/api/internal/content/posts/mark-trashed");
        assertEquals(1, request.size());
        assertTrue(request.get(0).bodyText().contains("\"wpPostId\":\"99\""), request.get(0).bodyText());
        assertTrue(request.get(0).bodyText().contains("\"siteId\":1"), request.get(0).bodyText());
        assertTrue(eventPublished());
    }

    @Test
    void delete_CmsAdapterが例外を投げたらcontent_serviceへの反映もイベント発行も行われない() {
        // 投稿99はWordPressに存在しない。
        assertThrows(PostNotFoundException.class, () -> service.delete("main", "99"));

        assertTrue(world.content.requests("POST", "/api/internal/content/posts/mark-trashed").isEmpty());
        verify(world.rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void delete_プロジェクトメンバーでもadminでもなければCMSへ触れずに拒否する() {
        // issue #830: 投稿削除は「認証済みなら誰でも」ではなく、サイトが属するプロジェクトの
        // メンバー(またはadmin)に限定する。CMSへの副作用が始まる前に弾かれることを確かめる。
        world.ssh.addPost("99", "my-article", "publish");
        world.bindProject(1L, 7L, null, null, 1L);
        world.stubActor("Bearer member-token", 10L, "user", "m@example.com").stubProjectMember(7L, 10L, false);
        world.asUser("Bearer member-token");

        assertThrows(ForbiddenException.class, () -> service.delete("main", "99"));

        assertEquals("publish", world.ssh.posts.get("99").status, "投稿には触れない");
        assertFalse(world.ssh.ran("post delete"));
        assertTrue(world.content.requests("POST", "/api/internal/content/posts/mark-trashed").isEmpty());
        verify(world.rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }

    @Test
    void delete_サイトが属するプロジェクトのIDで認可判定を行う() {
        world.ssh.addPost("99", "my-article", "publish");
        world.bindProject(1L, 7L, null, null, 1L);
        world.stubActor("Bearer member-token", 10L, "user", "m@example.com").stubProjectMember(7L, 10L, true);
        world.asUser("Bearer member-token");

        service.delete("main", "99");

        assertEquals(1, world.identity.requests("GET", "/api/internal/identity/projects/7/members/10").size(),
                "プロジェクト7のメンバー判定が行われる");
        assertEquals("trash", world.ssh.posts.get("99").status);
    }
}
