package com.letsblog.identity.service;

import com.letsblog.identity.domain.Permission;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #815: admin判定の2つの軸(users.roleカラム と RBACのROLE_ADMIN)の関係を固定する。
 *
 * <p>以前は完全に独立していたため、users.role = "admin" でもROLE_ADMINを持たなければ
 * requirePermissionを通れなかった。#798が特権ロールの付与のみをadmin限定にした結果、
 * 「特権ロールは付与できるのに、特権でないロールは付与できない」という非対称が生まれていた。
 */
@ExtendWith(MockitoExtension.class)
class PermissionAuthorizationServiceTest {

    @Mock
    private CurrentActorService currentActorService;

    @Mock
    private UserRepository userRepository;

    private PermissionAuthorizationService service() {
        return new PermissionAuthorizationService(currentActorService, userRepository);
    }

    @Test
    @DisplayName("users.role=adminはRBACロールを持たなくても全Permissionを通る(#815)")
    void adminは全権() {
        when(currentActorService.getCurrentActorId()).thenReturn(1L);
        when(currentActorService.isAdmin()).thenReturn(true);

        assertDoesNotThrow(() -> service().requirePermission(Permission.ROLE_MANAGE));

        // adminならDBのRBACロールを引く必要すら無い(非対称の原因はここを見ていたこと)。
        verify(userRepository, never()).findById(1L);
    }

    @Test
    @DisplayName("非adminはRBACの権限を持っていれば通る")
    void 非adminでも権限があれば通る() {
        User granted = spyWithPermission(true);
        when(currentActorService.getCurrentActorId()).thenReturn(2L);
        when(currentActorService.isAdmin()).thenReturn(false);
        when(userRepository.findById(2L)).thenReturn(Optional.of(granted));

        assertDoesNotThrow(() -> service().requirePermission(Permission.ROLE_MANAGE));
    }

    @Test
    @DisplayName("非adminで権限も無ければ拒否する")
    void 非adminで権限が無ければ拒否() {
        User denied = spyWithPermission(false);
        when(currentActorService.getCurrentActorId()).thenReturn(3L);
        when(currentActorService.isAdmin()).thenReturn(false);
        when(userRepository.findById(3L)).thenReturn(Optional.of(denied));

        assertThrows(ForbiddenException.class, () -> service().requirePermission(Permission.ROLE_MANAGE));
    }

    @Test
    @DisplayName("未認証は拒否する")
    void 未認証は拒否() {
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertThrows(ForbiddenException.class, () -> service().requirePermission(Permission.ROLE_MANAGE));
    }

    /**
     * hasPermission の戻り値だけを差し替えたUserを返す。
     * Roleのpermissionsに公開セッターが無いため、実体を組み立てる代わりにspyで差し替える。
     */
    private User spyWithPermission(boolean granted) {
        User spy = org.mockito.Mockito.spy(new User());
        lenient().doReturn(granted).when(spy).hasPermission(org.mockito.ArgumentMatchers.any());
        return spy;
    }
}
