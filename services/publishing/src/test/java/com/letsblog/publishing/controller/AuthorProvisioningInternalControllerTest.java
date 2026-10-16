package com.letsblog.publishing.controller;

import com.letsblog.publishing.dto.AuthorProvisioningBridgeRequest;
import com.letsblog.publishing.dto.AuthorProvisioningBridgeResponse;
import com.letsblog.publishing.service.AuthorProvisioningService;
import com.letsblog.publishing.testsupport.PublishingWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1816で書き直し: AuthorProvisioningServiceは実物を使い、project-serviceへのHTTP
 * ({@code FakeHttpServer})とWordPressへのSSH({@code FakeWordPress})だけを差し替える。
 */
class AuthorProvisioningInternalControllerTest {

    private PublishingWorld world;
    private AuthorProvisioningInternalController controller;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        world.stubCredentials("main", PublishingWorld.sshCredentialsMap());
        controller = new AuthorProvisioningInternalController(
                new AuthorProvisioningService(world.projectServiceClient, world.cmsAdapterFactory));
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void provisionAuthor_サービスへ委譲し結果を返す() {
        world.ssh.whenOk("echo ok", "ok").whenOk("core version", "6.4.2")
                .whenOk("user list", "[]").whenOk("user create", "9").whenOk("user update", "");
        AuthorProvisioningBridgeRequest request = new AuthorProvisioningBridgeRequest(
                "member@example.com", "editor", "太郎", "山田", "山田太郎", null, null, null);

        AuthorProvisioningBridgeResponse response = controller.provisionAuthor("main", request);

        assertEquals("9", response.cmsAuthorId());
        assertTrue(world.ssh.ran("user create"), world.ssh.commands.toString());
        assertTrue(world.ssh.ran("editor"), "wpRoleがWordPressへ渡る: " + world.ssh.commands);
        assertTrue(world.ssh.ran("member@example.com"), "emailがWordPressへ渡る: " + world.ssh.commands);
    }
}
