package com.letsblog.identity.controller;

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

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                        true, true, LocalDateTime.now(), LocalDateTime.now()),
                new UserResponse(2L, "b@example.com", "user", List.of("user"),
                        true, false, LocalDateTime.now(), LocalDateTime.now()));
        when(userService.list()).thenReturn(users);

        List<UserResponse> result = controller.list();

        assertEquals(users, result);
        verify(adminAuthorizationService).requireAdmin();
    }
}
