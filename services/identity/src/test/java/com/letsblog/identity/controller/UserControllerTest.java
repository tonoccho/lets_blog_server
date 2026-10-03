package com.letsblog.identity.controller;

import com.letsblog.identity.dto.UserProfileResponse;
import com.letsblog.identity.dto.UserProfileUpdateRequest;
import com.letsblog.identity.dto.UserResponse;
import com.letsblog.identity.service.AdminAuthorizationService;
import com.letsblog.identity.service.ForbiddenException;
import com.letsblog.identity.service.PermissionAuthorizationService;
import com.letsblog.identity.service.RoleService;
import com.letsblog.identity.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #653: GET /api/usersにadmin限定の認可チェックが欠落していた欠陥の回帰テスト。
 *
 * <p>同ファイル内のdeactivate/reactivate等と同様、AdminAuthorizationService.requireAdmin()を
 * コントローラから手続き的に呼ぶ既存パターンに倣う(requireAdmin()自体の単体テストは
 * AdminAuthorizationServiceTestに既にあるため、ここではUserController.list()がそれを
 * 呼び出すこと・呼び出し結果に応じて分岐することのみを検証する)。
 */
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private RoleService roleService;

    @Mock
    private PermissionAuthorizationService permissionAuthorizationService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private UserController controller() {
        return new UserController(userService, roleService, permissionAuthorizationService, adminAuthorizationService);
    }

    @Test
    void list_admin以外は403相当のForbiddenExceptionを送出する() {
        UserController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, controller::list);

        verify(userService, never()).list();
    }

    @Test
    void list_adminは従来通り全ユーザー一覧を取得できる() {
        UserController controller = controller();
        List<UserResponse> users = List.of(
                new UserResponse(1L, "a@example.com", "admin", List.of("admin"),
                        true, true, Instant.now(), Instant.now()),
                new UserResponse(2L, "b@example.com", "user", List.of("user"),
                        true, false, Instant.now(), Instant.now()));
        when(userService.list()).thenReturn(users);

        List<UserResponse> result = controller.list();

        assertEquals(users, result);
        verify(adminAuthorizationService).requireAdmin();
    }

    /**
     * issue #955: RBACのロール割り当て後にKeycloakのrealmロールを{@code users.role}へ
     * 整合させる配線の回帰テスト。整合そのものの挙動はUserServiceTestが検証しているので、
     * ここではコントローラが呼び出すこと・整合の失敗がエンドポイントを壊さないことだけを見る。
     */
    @Test
    void assignRole_割り当て後にKeycloakのrealmロールを整合させる_issue955() {
        UserController controller = controller();
        when(roleService.isPrivilegedRole("ROLE_VIEWER")).thenReturn(false);

        controller.assignRole(1L, "ROLE_VIEWER");

        verify(roleService).assignRoleToUser(1L, "ROLE_VIEWER");
        verify(userService).reconcileKeycloakAdminRole(1L);
    }

    @Test
    void removeRole_解除後にKeycloakのrealmロールを整合させる_issue955() {
        UserController controller = controller();
        when(roleService.isPrivilegedRole("ROLE_VIEWER")).thenReturn(false);

        controller.removeRole(1L, "ROLE_VIEWER");

        verify(roleService).removeRoleFromUser(1L, "ROLE_VIEWER");
        verify(userService).reconcileKeycloakAdminRole(1L);
    }

    @Test
    void assignRole_認可に失敗したらロール割り当ても整合も行わない_issue955() {
        UserController controller = controller();
        when(roleService.isPrivilegedRole("ROLE_ADMIN")).thenReturn(true);
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.assignRole(1L, "ROLE_ADMIN"));

        verify(roleService, never()).assignRoleToUser(any(), any());
        verify(userService, never()).reconcileKeycloakAdminRole(any());
    }

    private UserProfileUpdateRequest profileRequest(String email) {
        return new UserProfileUpdateRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, email);
    }

    @Test
    void updateProfile_メールアドレス変更はadmin限定_issue1192() {
        UserController controller = controller();
        UserProfileUpdateRequest request = profileRequest("new@example.com");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.updateProfile(1L, request));

        verify(userService, never()).updateUserProfile(any(), any());
    }

    @Test
    void updateProfile_adminはメールアドレスを変更できる_issue1192() {
        UserController controller = controller();
        UserProfileUpdateRequest request = profileRequest("new@example.com");
        UserProfileResponse expected = org.mockito.Mockito.mock(UserProfileResponse.class);
        when(userService.updateUserProfile(1L, request)).thenReturn(expected);

        assertEquals(expected, controller.updateProfile(1L, request));

        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void updateProfile_メールアドレス無指定なら本人も従来どおり更新できる_issue1192() {
        UserController controller = controller();
        UserProfileUpdateRequest request = profileRequest(null);
        UserProfileResponse expected = org.mockito.Mockito.mock(UserProfileResponse.class);
        when(userService.updateUserProfile(1L, request)).thenReturn(expected);

        assertEquals(expected, controller.updateProfile(1L, request));

        verify(adminAuthorizationService).requireSelfOrAdmin(1L);
        verify(adminAuthorizationService, never()).requireAdmin();
    }

    @Test
    void updateProfile_空白のメールアドレスは変更なしとして本人も更新できる_issue1192() {
        UserController controller = controller();
        UserProfileUpdateRequest request = profileRequest("  ");
        UserProfileResponse expected = org.mockito.Mockito.mock(UserProfileResponse.class);
        when(userService.updateUserProfile(1L, request)).thenReturn(expected);

        assertEquals(expected, controller.updateProfile(1L, request));

        verify(adminAuthorizationService).requireSelfOrAdmin(1L);
        verify(adminAuthorizationService, never()).requireAdmin();
    }
}
