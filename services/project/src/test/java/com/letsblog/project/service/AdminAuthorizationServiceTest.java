package com.letsblog.project.service;

import com.letsblog.project.testsupport.ProjectWorld;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AdminAuthorizationServiceの分岐(issue #1817)。CurrentActorService・IdentityBridgeClient は実物で、
 * identity-service(操作者・プロジェクトメンバー判定・所属プロジェクト一覧)はフェイクHTTPサーバーが演じる。
 */
@DisplayName("AdminAuthorizationService: admin権限・プロジェクトメンバーの判定")
class AdminAuthorizationServiceTest {

    private static final String MEMBER = "/api/internal/identity/projects/7/members/5";
    private static final String PROJECT_IDS = "/api/internal/identity/users/5/project-ids";

    private final ProjectWorld world = new ProjectWorld();
    private final AdminAuthorizationService service = world.adminAuthorizationService;

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    @DisplayName("requireAdmin: admin なら通る")
    void requireAdmin() {
        world.actingAsAdmin(1L);
        assertDoesNotThrow(service::requireAdmin);
    }

    @Test
    @DisplayName("requireAdmin: admin でない・操作者なしは Forbidden")
    void requireAdminForbidden() {
        world.actingAsUser(2L);
        assertThrows(ForbiddenException.class, service::requireAdmin);
    }

    @Test
    @DisplayName("requireProjectMemberOrAdmin: admin はメンバー判定を問い合わせず通る")
    void adminSkipsMembership() {
        world.actingAsAdmin(1L);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(7L));
        assertTrue(world.http.requests("GET", ".*/members/.*").isEmpty());
    }

    @Test
    @DisplayName("requireProjectMemberOrAdmin: 操作者なし(ログインなし)は Forbidden")
    void anonymousIsForbidden() {
        ForbiddenException e = assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(7L));

        assertTrue(e.getMessage().contains("ログイン"));
    }

    @Test
    @DisplayName("requireProjectMemberOrAdmin: メンバーなら通る。Bearer は転送する")
    void memberCheck() {
        world.actingAsUser(5L);
        world.http.json("GET", MEMBER, "true");
        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(7L));
        assertEquals("Bearer token-5", world.http.requests("GET", MEMBER).get(0).header("Authorization"));
    }

    @Test
    @DisplayName("requireProjectMemberOrAdmin: メンバーでなければ Forbidden")
    void notMember() {
        world.actingAsUser(5L);
        world.http.json("GET", MEMBER, "false");
        ForbiddenException e = assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(7L));
        assertTrue(e.getMessage().contains("メンバー"));
    }

    @Test
    @DisplayName("accessibleProjectIds: admin は制限なし(空の Optional)")
    void adminHasNoRestriction() {
        world.actingAsAdmin(1L);

        assertTrue(service.accessibleProjectIds().isEmpty());
    }

    @Test
    @DisplayName("accessibleProjectIds: 操作者なしは Forbidden")
    void accessibleProjectIdsAnonymous() {
        assertThrows(ForbiddenException.class, service::accessibleProjectIds);
    }

    @Test
    @DisplayName("accessibleProjectIds: 一般ユーザーは所属プロジェクトのID集合(所属なしなら空集合)")
    void accessibleProjectIdsForMember() {
        world.actingAsUser(5L);
        world.respond("GET", PROJECT_IDS, List.of(1L, 2L));
        assertEquals(Set.of(1L, 2L), service.accessibleProjectIds().orElseThrow());

        world.respond("GET", PROJECT_IDS, List.of());
        assertEquals(Set.of(), service.accessibleProjectIds().orElseThrow());
    }
}
