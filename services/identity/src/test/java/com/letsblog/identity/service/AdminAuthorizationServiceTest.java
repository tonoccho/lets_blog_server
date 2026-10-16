package com.letsblog.identity.service;

import com.letsblog.identity.domain.User;
import com.letsblog.identity.testsupport.IdentityWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 認可。{@code CurrentActorService}は実物で、操作者は実際のJWTをSecurityContextへ置いて作る
 * (DBのリポジトリだけが外部との通信としてMockitoで差し替わる)。
 */
class AdminAuthorizationServiceTest {

    private IdentityWorld world;
    private AdminAuthorizationService service;

    @BeforeEach
    void setUp() {
        world = new IdentityWorld();
        service = world.adminAuthorizationService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void requireAdmin_admin操作者は許可() {
        world.actAs(1L, "admin");

        assertDoesNotThrow(() -> service.requireAdmin());
    }

    @Test
    void requireAdmin_操作者なしは拒否() {
        ForbiddenException thrown = assertThrows(ForbiddenException.class, () -> service.requireAdmin());

        assertEquals("この操作にはadmin権限が必要です", thrown.getMessage());
    }

    @Test
    void requireSelfOrAdmin_admin操作者は誰でも許可() {
        world.actAs(1L, "admin");

        assertDoesNotThrow(() -> service.requireSelfOrAdmin(99L));
    }

    @Test
    void requireSelfOrAdmin_本人は許可() {
        world.actAs(1L, "user");

        assertDoesNotThrow(() -> service.requireSelfOrAdmin(1L));
    }

    @Test
    void requireSelfOrAdmin_他人は拒否() {
        world.actAs(1L, "user");

        assertThrows(ForbiddenException.class, () -> service.requireSelfOrAdmin(2L));
    }

    @Test
    void requireSelfOrAdmin_操作者を解決できなければ拒否() {
        assertThrows(ForbiddenException.class, () -> service.requireSelfOrAdmin(1L));
    }

    // ---------------------------------------------- requireAdminAndNotSelf(#796で追加、#798で拡張)

    @Test
    void requireAdminAndNotSelf_adminが他人を対象にする場合は許可() {
        world.actAs(1L, "admin");

        assertDoesNotThrow(() -> service.requireAdminAndNotSelf(2L, "自分自身は対象にできません"));
    }

    @Test
    void requireAdminAndNotSelf_adminでも自分自身は拒否() {
        world.actAs(1L, "admin");

        ForbiddenException thrown = assertThrows(
                ForbiddenException.class,
                () -> service.requireAdminAndNotSelf(1L, "自分自身のアカウントは無効化できません"));
        // 呼び出し元が渡したメッセージがそのまま使われる(削除と無効化で文言を変えるため)。
        assertEquals("自分自身のアカウントは無効化できません", thrown.getMessage());
    }

    @Test
    void requireAdminAndNotSelf_非adminはadmin判定で先に拒否() {
        world.actAs(1L, "user");

        ForbiddenException thrown = assertThrows(
                ForbiddenException.class,
                () -> service.requireAdminAndNotSelf(2L, "自分自身のアカウントは削除できません"));
        assertEquals("この操作にはadmin権限が必要です", thrown.getMessage());
    }

    /**
     * 操作者のロールはadminだが{@code users}行にIDが無い(解決結果のIDがnull)場合は、自己判定をスキップする。
     * 将来admin判定の実装が変わったときに、nullが「自分自身ではない」と扱われる
     * (=fail-openにならない形で固定される)ことを、実物の{@code CurrentActorService}で確かめる。
     */
    @Test
    void requireAdminAndNotSelf_操作者IDが解決できない場合は自己判定をスキップ() {
        User idless = new User();
        idless.setEmail("idless@example.com");
        idless.setRole("admin");
        idless.setEnabled(true);
        world.actAsUser(idless);

        assertDoesNotThrow(() -> service.requireAdminAndNotSelf(1L, "自分自身は対象にできません"));
    }

    // ---------------------------------------------- requireNotSelfDemotion(#798)

    @Test
    void requireNotSelfDemotion_role未指定は何もしない() {
        world.actAs(1L, "admin");

        assertDoesNotThrow(() -> service.requireNotSelfDemotion(1L, null));
    }

    @Test
    void requireNotSelfDemotion_adminのままなら自分自身でも許可() {
        world.actAs(1L, "admin");

        assertDoesNotThrow(() -> service.requireNotSelfDemotion(1L, "admin"));
    }

    @Test
    void requireNotSelfDemotion_自分自身をadmin以外へ変えるのは拒否() {
        world.actAs(1L, "admin");

        ForbiddenException thrown =
                assertThrows(ForbiddenException.class, () -> service.requireNotSelfDemotion(1L, "user"));
        assertEquals("自分自身のadmin権限は外せません", thrown.getMessage());
    }

    @Test
    void requireNotSelfDemotion_他人のroleを変えるのは許可() {
        User actor = world.actAs(1L, "admin");

        assertDoesNotThrow(() -> service.requireNotSelfDemotion(actor.getId() + 1, "user"));
    }

    @Test
    void requireNotSelfDemotion_操作者を解決できない場合は拒否しない() {
        // 認可そのもの(requireAdmin)は呼び出し元が別に行う。ここは「自分自身か」だけを見る。
        assertDoesNotThrow(() -> service.requireNotSelfDemotion(1L, "user"));
    }
}
