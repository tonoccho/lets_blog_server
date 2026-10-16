package com.letsblog.identity.service;

import com.letsblog.identity.domain.Permission;
import com.letsblog.identity.domain.Role;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.testsupport.IdentityWorld;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #815: admin判定の2つの軸(users.roleカラム と RBACのROLE_ADMIN)の関係を固定する。
 *
 * <p>以前は完全に独立していたため、users.role = "admin" でもROLE_ADMINを持たなければ
 * requirePermissionを通れなかった。#798が特権ロールの付与のみをadmin限定にした結果、
 * 「特権ロールは付与できるのに、特権でないロールは付与できない」という非対称が生まれていた。
 *
 * <p>操作者は実際のJWT・実物の{@code CurrentActorService}で作り、RBACのロールは実物の
 * {@link Role}/{@link User}を組み立てる(DBのリポジトリだけがMockito)。
 */
class PermissionAuthorizationServiceTest {

    private IdentityWorld world;
    private PermissionAuthorizationService service;

    @BeforeEach
    void setUp() {
        world = new IdentityWorld();
        service = world.permissionAuthorizationService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    @DisplayName("users.role=adminはRBACロールを持たなくても全Permissionを通る(#815)")
    void adminは全権() {
        world.actAs(1L, "admin");

        assertDoesNotThrow(() -> service.requirePermission(Permission.ROLE_MANAGE));

        // adminならDBのRBACロールを引く必要すら無い(非対称の原因はここを見ていたこと)。
        verify(world.userRepository, never()).findById(1L);
    }

    @Test
    @DisplayName("非adminはRBACの権限を持っていれば通る")
    void 非adminでも権限があれば通る() {
        User actor = world.actAs(2L, "user");
        Role granting = new Role("ROLE_MANAGER", "管理");
        granting.getPermissions().add(Permission.ROLE_MANAGE);
        actor.getRoles().add(granting);
        when(world.userRepository.findById(2L)).thenReturn(Optional.of(actor));

        assertDoesNotThrow(() -> service.requirePermission(Permission.ROLE_MANAGE));
    }

    @Test
    @DisplayName("非adminで権限も無ければ拒否する")
    void 非adminで権限が無ければ拒否() {
        User actor = world.actAs(3L, "user");
        Role other = new Role("ROLE_VIEWER", "閲覧者");
        other.getPermissions().add(Permission.POST_READ);
        actor.getRoles().add(other);
        when(world.userRepository.findById(3L)).thenReturn(Optional.of(actor));

        ForbiddenException thrown =
                assertThrows(ForbiddenException.class, () -> service.requirePermission(Permission.ROLE_MANAGE));
        org.junit.jupiter.api.Assertions.assertTrue(thrown.getMessage().contains("ROLE_MANAGE"));
    }

    @Test
    @DisplayName("操作者に対応するユーザー行がDBから消えていれば拒否する")
    void ユーザー行が無ければ拒否() {
        world.actAs(4L, "user");
        when(world.userRepository.findById(4L)).thenReturn(Optional.empty());

        ForbiddenException thrown =
                assertThrows(ForbiddenException.class, () -> service.requirePermission(Permission.ROLE_MANAGE));
        org.junit.jupiter.api.Assertions.assertEquals("この操作には認証が必要です", thrown.getMessage());
    }

    @Test
    @DisplayName("未認証は拒否する")
    void 未認証は拒否() {
        assertThrows(ForbiddenException.class, () -> service.requirePermission(Permission.ROLE_MANAGE));
    }
}
