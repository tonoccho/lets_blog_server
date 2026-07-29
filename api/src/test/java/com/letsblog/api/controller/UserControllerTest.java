package com.letsblog.api.controller;

import com.letsblog.api.dto.UserProfileResponse;
import com.letsblog.api.dto.UserProfileUpdateRequest;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ForbiddenException;
import com.letsblog.api.service.PermissionAuthorizationService;
import com.letsblog.api.service.RoleService;
import com.letsblog.api.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private UserProfileResponse buildProfile() {
        return new UserProfileResponse(
                1L, "user@example.com", "user", List.of(),
                "太郎", "山田", "山田太郎", "taro",
                "https://example.com", "自己紹介", "ja_JP",
                "https://gravatar.com/avatar/xxx", "開発部", "エンジニア",
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void getProfile_本人またはadminは取得できる() {
        UserController controller = controller();
        when(userService.findUserWithProfile(1L)).thenReturn(buildProfile());

        UserProfileResponse response = controller.getProfile(1L);

        assertEquals("山田太郎", response.displayName());
        verify(adminAuthorizationService).requireSelfOrAdmin(1L);
    }

    @Test
    void getProfile_本人でもadminでもなければForbidden() {
        UserController controller = controller();
        doThrow(new ForbiddenException("この操作には本人またはadmin権限が必要です"))
                .when(adminAuthorizationService).requireSelfOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller.getProfile(1L));
    }

    @Test
    void updateProfile_本人またはadminは更新できる() {
        UserController controller = controller();
        UserProfileUpdateRequest request = new UserProfileUpdateRequest(
                "太郎", "山田", "山田太郎", "taro",
                "https://example.com", "自己紹介", "ja_JP",
                "https://gravatar.com/avatar/xxx", "開発部", "エンジニア");
        when(userService.updateUserProfile(1L, request)).thenReturn(buildProfile());

        UserProfileResponse response = controller.updateProfile(1L, request);

        assertEquals("エンジニア", response.position());
        verify(adminAuthorizationService).requireSelfOrAdmin(1L);
    }

    @Test
    void updateProfile_権限がなければForbidden() {
        UserController controller = controller();
        UserProfileUpdateRequest request = new UserProfileUpdateRequest(
                null, null, null, null, null, null, null, null, null, null);
        doThrow(new ForbiddenException("この操作には本人またはadmin権限が必要です"))
                .when(adminAuthorizationService).requireSelfOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller.updateProfile(1L, request));
    }
}
