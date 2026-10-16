package com.letsblog.identity.messaging;

import com.letsblog.common.messaging.ProjectEnvironmentBoundEvent;
import com.letsblog.identity.domain.ProjectUser;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.domain.UserSiteAuthor;
import com.letsblog.identity.testsupport.FakeHttpServer;
import com.letsblog.identity.testsupport.IdentityWorld;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #1324: project.environment-boundを受けて、既存メンバーの補填を依頼する。
 * {@code ProjectUserSyncService}は実物で、project-service / publishing-serviceへの内部ブリッジは
 * サーバー側のフェイクHTTPで受ける。
 */
class EventMessageListenerTest {

    private IdentityWorld world;
    private EventMessageListener listener;

    @BeforeEach
    void setUp() {
        world = new IdentityWorld();
        listener = new EventMessageListener(world.projectUserSyncService);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void onProjectEnvironmentBound_イベントのprojectIdとsiteIdで補填を依頼する() {
        world.bridges.json("GET", "/api/internal/project/sites/30",
                "{\"id\":30,\"siteKey\":\"site-30\",\"name\":\"本番\",\"baseUrl\":\"https://example.com\"}");
        world.bridges.json("POST", "/api/internal/publishing/sites/site-30/authors", "{\"cmsAuthorId\":\"wp-501\"}");
        User member = new User();
        member.setId(11L);
        member.setEmail("member@example.com");
        when(world.projectUserRepository.findByProjectId(7L)).thenReturn(List.of(new ProjectUser(7L, 11L, "editor")));
        when(world.userSiteAuthorRepository.findByUserIdAndSiteId(11L, 30L)).thenReturn(Optional.empty());
        when(world.userRepository.findById(11L)).thenReturn(Optional.of(member));

        listener.onProjectEnvironmentBound(new ProjectEnvironmentBoundEvent("evt-1", Instant.now(), 7L, 30L));

        // 内部ブリッジの実際のHTTP: サイトの照会 -> 著者のプロビジョニング。
        assertEquals(1, world.bridges.requests("GET", "/api/internal/project/sites/30").size());
        FakeHttpServer.Request provision =
                world.bridges.requests("POST", "/api/internal/publishing/sites/site-30/authors").get(0);
        org.junit.jupiter.api.Assertions.assertTrue(provision.bodyText().contains("member@example.com"));
        ArgumentCaptor<UserSiteAuthor> saved = ArgumentCaptor.forClass(UserSiteAuthor.class);
        verify(world.userSiteAuthorRepository).save(saved.capture());
        assertEquals("wp-501", saved.getValue().getCmsAuthorId());
        assertEquals(30L, saved.getValue().getSiteId());
    }
}
