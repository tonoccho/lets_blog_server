package com.letsblog.media.service;

import com.letsblog.media.testsupport.FakeHttpServer;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * media-serviceのAdminAuthorizationService(legacy-apiから複製されたrequireAdminのみ、issue #573)の
 * 単体テスト(issue #644)。log-writerの{@code AdminAuthorizationServiceTest}と同じ構造(admin許可/
 * 非admin拒否)。
 *
 * <p>issue #830 で requireProjectMemberOrAdmin を追加した。それまでは requireAdmin のみで、
 * 「media-serviceの認可対象エンドポイントはadmin限定なのでプロジェクトメンバー判定は要らない」と
 * 記録されていたが、実際にはダイアグラム・生成画像の12エンドポイントが<b>認可チェックを一切
 * 持っていなかった</b>(admin限定だったのはProjectMediaGarbageCollectionControllerだけ)。
 *
 * <p>issue #1819: {@code CurrentActorService}・{@code IdentityBridgeClient}は実物で、identity-serviceの
 * {@code /api/identity/me}と{@code /api/internal/identity/projects/{id}/members/{userId}}を
 * フェイクHTTPサーバー({@link MediaWorld})が演じる。
 */
class AdminAuthorizationServiceTest {

    private static final String MEMBER_PATH = "/api/internal/identity/projects/1/members/10";

    private final MediaWorld world = new MediaWorld();
    private final AdminAuthorizationService service = world.adminAuthorizationService;

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    void requireAdmin_adminなら例外を投げない() {
        world.actingAsAdmin(1L);

        assertDoesNotThrow(() -> service.requireAdmin());
    }

    @Test
    void requireAdmin_admin以外はForbidden() {
        world.actingAsUser(10L);

        assertThrows(ForbiddenException.class, () -> service.requireAdmin());
    }

    // ---- issue #830 で追加したプロジェクトメンバー判定 ----

    @Test
    void requireProjectMemberOrAdmin_adminなら無条件で許可() {
        world.actingAsAdmin(1L);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(1L));
        // adminは所属を問わずバイパスするため、identity-serviceへのメンバー問い合わせ自体を行わない。
        assertTrue(world.http.requests("GET", "/api/internal/identity/.*").isEmpty());
    }

    @Test
    void requireProjectMemberOrAdmin_プロジェクトメンバーなら許可() {
        world.actingAsUser(10L);
        world.http.json("GET", MEMBER_PATH, "true");

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdmin(1L));
        assertEquals("Bearer token-10", world.http.requests("GET", MEMBER_PATH).get(0).header("Authorization"),
                "呼び出し元のBearerトークンをそのまま転送する");
    }

    @Test
    void requireProjectMemberOrAdmin_メンバーでなければ例外() {
        world.actingAsUser(10L);
        world.http.json("GET", MEMBER_PATH, "false");

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
    }

    @Test
    void requireProjectMemberOrAdmin_操作者を解決できなければ例外() {
        // Authorizationヘッダーが無い=操作者なし。メンバー問い合わせまで進まない。
        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdmin(1L));
        assertTrue(world.http.requests("GET", "/api/internal/identity/.*").isEmpty());
    }

    @Test
    void requireProjectMemberOrAdminForResource_プロジェクト未紐付けのリソースはadminのみ許可() {
        // projectIdがnullのリソース(プロジェクトに紐付けずに作られたダイアグラム・生成画像)は
        // 判定に使えるメンバーシップが無いため、admin だけを通す。
        world.actingAsAdmin(1L);

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdminForResource(null));
        assertTrue(world.http.requests("GET", "/api/internal/identity/.*").isEmpty());
    }

    @Test
    void requireProjectMemberOrAdminForResource_未紐付けリソースへの非adminはForbidden() {
        world.actingAsUser(10L);

        assertThrows(ForbiddenException.class, () -> service.requireProjectMemberOrAdminForResource(null));
        assertTrue(world.http.requests("GET", "/api/internal/identity/.*").isEmpty());
    }

    @Test
    void requireProjectMemberOrAdminForResource_プロジェクト紐付けのリソースはメンバー判定に回す() {
        world.actingAsUser(10L);
        world.http.on("GET", MEMBER_PATH, FakeHttpServer.Response.ok("true"));

        assertDoesNotThrow(() -> service.requireProjectMemberOrAdminForResource(1L));
    }

    // ---- issue #1493: 認証済みなら誰でも(フォルダ一覧の閲覧) ----

    @Test
    void requireAuthenticated_認証済みなら例外を投げない() {
        world.actingAsUser(10L);

        assertDoesNotThrow(() -> service.requireAuthenticated());
    }

    @Test
    void requireAuthenticated_操作者を解決できなければForbidden() {
        assertThrows(ForbiddenException.class, () -> service.requireAuthenticated());
    }
}
