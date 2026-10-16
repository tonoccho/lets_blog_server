package com.letsblog.platform.service;

import com.letsblog.platform.testsupport.PlatformWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * platform-serviceの{@link AdminAuthorizationService}(issue #1808のrequireProjectMemberOrAdmin、issue #1825で全分岐)。
 * identity-serviceはHTTPのサーバー側フェイクで模し、CurrentActorService・IdentityBridgeClientは実物を通す。
 */
class AdminAuthorizationServiceProjectMemberTest {

    private PlatformWorld world;
    private AdminAuthorizationService service;

    @BeforeEach
    void setUp() {
        world = new PlatformWorld();
        service = world.adminAuthorizationService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void 管理者はメンバー確認なしで通る() {
        world.actingAsAdmin(1L);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(10L));
        assertThat(world.http.requests("GET", "/api/internal/identity/projects/.*")).isEmpty();
    }

    @Test
    void 操作者を解決できなければ拒否する() {
        world.anonymous();

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(10L));
        assertThat(world.http.requests("GET", "/api/internal/identity/projects/.*")).isEmpty();
    }

    @Test
    void メンバーは通り_メンバーでなければ拒否する() {
        world.actingAsUser(5L).projectMember(10L, 5L, true).projectMember(11L, 5L, false);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(10L));
        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(11L));
        assertThat(world.http.requests("GET", "/api/internal/identity/projects/10/members/5").get(0)
                .header("Authorization")).isEqualTo("Bearer token-5");
    }

    @Test
    void requireAdminはadminだけを通す() {
        world.actingAsAdmin(1L);
        assertDoesNotThrow(service::requireAdmin);

        world.actingAsUser(2L);
        assertThrows(ForbiddenException.class, service::requireAdmin);
    }

    @Test
    void requireAuthenticatedはログイン済みだけを通す() {
        world.actingAsUser(2L);
        assertDoesNotThrow(service::requireAuthenticated);

        world.anonymous();
        assertThrows(ForbiddenException.class, service::requireAuthenticated);
    }
}
