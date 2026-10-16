package com.letsblog.content.service;

import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * content-serviceのAdminAuthorizationService(legacy-apiから複製されたrequireAdmin/
 * requireProjectMemberOrAdmin、issue #576)の単体テスト(issue #644)。legacy-apiの
 * {@code com.letsblog.api.service.AdminAuthorizationServiceTest}と同じ観点(admin許可/非admin拒否/
 * プロジェクトメンバー許可/非メンバー拒否)を、本サービスの依存関係(CurrentActorService経由の
 * identity-service委譲、IdentityBridgeClient経由のプロジェクトメンバー判定)に合わせて検証する。
 *
 * <p>issue #1821: CurrentActorService・IdentityBridgeClient・ProjectBridgeClientは実物で、identity-service・
 * project-serviceの代わりに{@link ContentWorld}のHTTPフェイクを向ける。
 */
class AdminAuthorizationServiceTest {

    private ContentWorld world;
    private AdminAuthorizationService service;

    @BeforeEach
    void setUp() {
        world = new ContentWorld();
        service = world.adminAuthorizationService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void requireAdmin_adminなら例外を投げない() {
        world.actingAsAdmin(1L);

        assertDoesNotThrow(() -> service.requireAdmin());
    }

    @Test
    void requireAdmin_admin以外はForbidden() {
        world.actingAsUser(1L);

        assertThrows(ForbiddenException.class, () -> service.requireAdmin());
    }

    @Test
    void requireProjectMemberOrAdmin_adminなら無条件で許可() {
        world.actingAsAdmin(1L);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(1L));
        assertTrue(world.http.requests("GET", "/api/internal/identity/projects/.*").isEmpty());
    }

    @Test
    void requireProjectMemberOrAdmin_プロジェクトメンバーなら許可() {
        world.actingAsUser(10L).projectMember(1L, 10L, true);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(1L));
        // 呼び出し元のBearerトークンをそのまま転送する
        assertEquals("Bearer token-10",
                world.http.requests("GET", "/api/internal/identity/projects/1/members/10").get(0)
                        .header("Authorization"));
    }

    @Test
    void requireProjectMemberOrAdmin_メンバーでなければ例外() {
        world.actingAsUser(10L).projectMember(1L, 10L, false);

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
    }

    @Test
    void requireProjectMemberOrAdmin_操作者を解決できなければ例外() {
        // Authorizationヘッダーが無い(未ログイン)ので、操作者を解決できない
        world.inRequest();

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
    }

    @Test
    void accessibleSiteIds_adminは絞り込まない() {
        world.actingAsAdmin(1L);

        assertEquals(Optional.empty(), service.accessibleSiteIds());
    }

    @Test
    void accessibleSiteIds_admin以外はproject_serviceが返したサイトのIDの集合() {
        world.actingAsUser(10L).accessibleSites(10L, 3L, 4L, 3L);

        assertEquals(Optional.of(Set.of(3L, 4L)), service.accessibleSiteIds());
        assertEquals("Bearer token-10",
                world.http.requests("GET", "/api/internal/project/users/10/site-ids").get(0)
                        .header("Authorization"));
    }

    @Test
    void accessibleSiteIds_操作者を解決できなければ例外() {
        world.inRequest();

        assertThrows(ForbiddenException.class, () -> service.accessibleSiteIds());
    }
}
