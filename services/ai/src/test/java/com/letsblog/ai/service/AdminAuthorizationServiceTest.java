package com.letsblog.ai.service;

import com.letsblog.ai.testsupport.AiWorld;
import com.letsblog.ai.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ai-serviceのAdminAuthorizationService(legacy-apiから複製されたrequireAdmin/
 * requireProjectMemberOrAdmin、issue #574)の単体テスト(issue #644)。legacy-apiの
 * {@code com.letsblog.api.service.AdminAuthorizationServiceTest}と同じ観点(admin許可/非admin拒否/
 * プロジェクトメンバー許可/非メンバー拒否)を、本サービスの依存関係(CurrentActorService経由の
 * identity-service委譲、IdentityBridgeClient経由のプロジェクトメンバー判定)に合わせて検証する。
 */

/**
 * 操作者の解決はCurrentActorService(実物)がidentity-serviceのHTTPフェイクへ問い合わせ、プロジェクトメンバー判定は
 * IdentityBridgeClient(実物)がHTTPフェイクへ問い合わせる(issue #1823。自前のクラスはモックしない)。
 */
class AdminAuthorizationServiceTest {

    private AiWorld world;
    private AdminAuthorizationService service;

    @BeforeEach
    void setUp() {
        world = new AiWorld();
        service = world.adminAuthorizationService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private void membership(long projectId, long userId, String body) {
        world.http.on("GET", "/api/internal/identity/projects/" + projectId + "/members/" + userId,
                FakeHttpServer.Response.ok(body));
    }

    @Test
    void requireAdmin_adminなら例外を投げない() {
        world.admin();

        assertDoesNotThrow(() -> service.requireAdmin());
    }

    @Test
    void requireAdmin_admin以外はForbidden() {
        world.member(10L);

        assertThrows(ForbiddenException.class, () -> service.requireAdmin());
    }

    @Test
    void requireAdmin_Authorizationヘッダーが無ければ操作者なしでForbidden() {
        assertThrows(ForbiddenException.class, () -> service.requireAdmin());
        assertEquals(0, world.http.requests().size());
    }

    @Test
    void requireProjectMemberOrAdmin_adminなら無条件で許可() {
        world.admin();

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(1L));
        assertEquals(0, world.http.requests("GET", "/api/internal/identity/.*").size());
    }

    @Test
    void requireProjectMemberOrAdmin_プロジェクトメンバーなら許可し呼び出し元のトークンを転送する() {
        world.member(10L);
        membership(1L, 10L, "true");

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(1L));

        assertEquals(AiWorld.BEARER, world.http.last().header("Authorization"));
    }

    @Test
    void requireProjectMemberOrAdmin_メンバーでなければ例外() {
        world.member(10L);
        membership(1L, 10L, "false");

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
    }

    @Test
    void requireProjectMemberOrAdmin_操作者を解決できなければ例外() {
        // identity-serviceが401を返す(無効化ユーザーなど)と操作者なし。メンバー判定は呼ばれない。
        world.request.addHeader("Authorization", AiWorld.BEARER);
        world.http.on("GET", "/api/identity/me", FakeHttpServer.Response.json(401, "{}"));

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
        assertEquals(0, world.http.requests("GET", "/api/internal/identity/.*").size());
    }
}
