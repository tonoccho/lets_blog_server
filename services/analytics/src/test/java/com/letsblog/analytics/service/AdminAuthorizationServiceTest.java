package com.letsblog.analytics.service;

import com.letsblog.analytics.testsupport.AnalyticsWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * analytics-serviceのAdminAuthorizationService(legacy-apiから複製されたrequireAdmin/
 * requireProjectMemberOrAdmin、issue #578)の単体テスト(issue #644)。legacy-apiの
 * {@code com.letsblog.api.service.AdminAuthorizationServiceTest}と同じ観点(admin許可/非admin拒否/
 * プロジェクトメンバー許可/非メンバー拒否)を検証する。
 *
 * <p>issue #1827: {@code CurrentActorService}と{@code IdentityBridgeClient}は実物。外部との通信である
 * identity-service(操作者の解決とメンバー判定)だけを、サーバー側フェイクのHTTPで差し替える。
 */
class AdminAuthorizationServiceTest {

    private AnalyticsWorld world;
    private AdminAuthorizationService service;

    @BeforeEach
    void setUp() {
        world = new AnalyticsWorld();
        service = world.authorization;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void requireAdmin_adminなら例外を投げない() {
        world.actor("admin-token", 1L, "admin");
        world.asBearer("admin-token");

        assertDoesNotThrow(() -> service.requireAdmin());
    }

    @Test
    void requireAdmin_admin以外はForbidden() {
        world.actor("user-token", 10L, "user");
        world.asBearer("user-token");

        ForbiddenException e = assertThrows(ForbiddenException.class, () -> service.requireAdmin());
        assertEquals("この操作にはadmin権限が必要です", e.getMessage());
    }

    @Test
    void requireAdmin_Authorizationヘッダーが無ければForbiddenでidentity_serviceへ問い合わせない() {
        world.asBearer(null);

        assertThrows(ForbiddenException.class, () -> service.requireAdmin());
        assertTrue(world.http.requests("GET", "/api/identity/me").isEmpty());
    }

    @Test
    void requireProjectMemberOrAdmin_adminなら無条件で許可しメンバー判定へ問い合わせない() {
        world.actor("admin-token", 1L, "admin");
        world.asBearer("admin-token");

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(1L));
        assertTrue(world.http.requests("GET", "/api/internal/identity/projects/.*").isEmpty());
    }

    @Test
    void requireProjectMemberOrAdmin_プロジェクトメンバーなら許可し呼び出し元のトークンを転送する() {
        world.actor("member-token", 10L, "user").member(1L, 10L);
        world.asBearer("member-token");

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(1L));
        var memberCheck = world.http.requests("GET", "/api/internal/identity/projects/1/members/10");
        assertEquals(1, memberCheck.size());
        assertEquals("Bearer member-token", memberCheck.get(0).header("Authorization"));
    }

    @Test
    void requireProjectMemberOrAdmin_メンバーでなければ例外() {
        world.actor("user-token", 10L, "user");
        world.asBearer("user-token");

        ForbiddenException e =
                assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
        assertEquals("この操作にはプロジェクトメンバーまたはadmin権限が必要です", e.getMessage());
    }

    @Test
    void requireProjectMemberOrAdmin_別のプロジェクトのメンバーでは許可されない() {
        world.actor("user-token", 10L, "user").member(2L, 10L);
        world.asBearer("user-token");

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
    }

    @Test
    void requireProjectMemberOrAdmin_操作者を解決できなければ例外() {
        // identity-serviceが401を返す(無効化ユーザー等)=操作者なし。502ではなく403にする(issue #829)。
        world.asBearer("unknown-token");

        ForbiddenException e =
                assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
        assertEquals("この操作にはログインが必要です", e.getMessage());
        assertTrue(world.http.requests("GET", "/api/internal/identity/projects/.*").isEmpty());
    }

    @Test
    void requireProjectMemberOrAdmin_identity_serviceの障害は403にせず伝える() {
        world.http.status("GET", "/api/identity/me", 500, "{}");
        world.asBearer("any-token");

        assertThrows(IdentityServiceUnavailableException.class, () -> service.requireProjectMemberOrAdmin(1L));
    }
}
