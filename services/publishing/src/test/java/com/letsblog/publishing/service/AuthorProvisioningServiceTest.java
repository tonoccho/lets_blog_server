package com.letsblog.publishing.service;

import com.letsblog.publishing.cms.AuthorProvisioningRequest;
import com.letsblog.publishing.cms.CmsApiException;
import com.letsblog.publishing.testsupport.PublishingWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AuthorProvisioningServiceの回帰テスト(issue #707、#575設計判断4の書き込み側)。
 *
 * <p>issue #1815で書き直し: ProjectServiceClient・CmsAdapterFactory・WordPressAdapterは実物を使い、
 * 外部との通信だけを差し替える。project-serviceへのHTTPは{@code FakeHttpServer}、SSHはFakeSshCommandExecutor。
 */
class AuthorProvisioningServiceTest {

    private PublishingWorld world;
    private AuthorProvisioningService service;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        service = new AuthorProvisioningService(world.projectServiceClient, world.cmsAdapterFactory);
        world.stubCredentials("main", PublishingWorld.sshCredentialsMap());
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void provisionAuthor_権限があればCmsAdapterへ委譲する() {
        world.ssh.whenOk("echo ok", "ok").whenOk("core version", "6.4.2")
                .whenOk("user list", "[]").whenOk("user create", "9").whenOk("user update", "");
        AuthorProvisioningRequest request = new AuthorProvisioningRequest(
                "member@example.com", "editor", null, null, null, null, null, null);

        String cmsAuthorId = service.provisionAuthor("main", request);

        assertEquals("9", cmsAuthorId);
        assertTrue(world.ssh.ran("user create"), "著者の作成コマンドがSSHで実行されること: " + world.ssh.commands);
        assertTrue(world.ssh.ran("user update '9'"), "作成した著者のプロフィールが更新されること");
    }

    @Test
    void provisionAuthor_権限がなければCmsApiExceptionを投げる() {
        world.ssh.whenFail("echo ok", "Permission denied");
        AuthorProvisioningRequest request = new AuthorProvisioningRequest(
                "member@example.com", "editor", null, null, null, null, null, null);

        CmsApiException e = assertThrows(CmsApiException.class, () -> service.provisionAuthor("main", request));

        assertTrue(e.getMessage().contains("サイト 'main'"));
        assertFalse(world.ssh.ran("user create"), "権限が無ければ著者は作成されない");
    }
}
