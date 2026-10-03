package com.letsblog.identity.controller;

import com.letsblog.identity.dto.UserProfileResponse;
import com.letsblog.identity.service.AdminAuthorizationService;
import com.letsblog.identity.service.AvatarService;
import com.letsblog.identity.service.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #1241 AC8: アバターのアップロード/取得の認可が、既存のPUT /api/users/{id}と同じ
 * requireSelfOrAdminに揃っていることの検証。
 */
@ExtendWith(MockitoExtension.class)
class AvatarControllerTest {

    @Mock
    private AvatarService avatarService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private AvatarController controller() {
        return new AvatarController(avatarService, adminAuthorizationService);
    }

    private UserProfileResponse profile() {
        return new UserProfileResponse(
                1L, "a@example.com", "user", java.util.List.of("user"),
                null, null, null, null, null, null, null, null,
                "/api/users/1/avatar", null, null, null, null, false,
                Instant.now(), Instant.now());
    }

    @Test
    void アップロードは本人またはadminのみ許可する() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "avatar.jpg", MediaType.IMAGE_JPEG_VALUE, "bytes".getBytes());
        when(avatarService.uploadAvatar(1L, MediaType.IMAGE_JPEG_VALUE, file.getBytes())).thenReturn(profile());

        UserProfileResponse response = controller().uploadAvatar(1L, file);

        verify(adminAuthorizationService).requireSelfOrAdmin(1L);
        assertEquals("/api/users/1/avatar", response.avatarUrl());
    }

    @Test
    void アップロードは本人でもadminでもなければ拒否される() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "avatar.jpg", MediaType.IMAGE_JPEG_VALUE, "bytes".getBytes());
        doThrow(new ForbiddenException("この操作には本人またはadmin権限が必要です"))
                .when(adminAuthorizationService).requireSelfOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller().uploadAvatar(1L, file));

        verify(avatarService, never()).uploadAvatar(anyLong(), anyString(), any());
    }

    @Test
    void 取得は本人またはadminのみ許可する() {
        byte[] bytes = "avatar-bytes".getBytes();
        when(avatarService.loadAvatar(1L)).thenReturn(bytes);

        ResponseEntity<byte[]> response = controller().getAvatar(1L);

        verify(adminAuthorizationService).requireSelfOrAdmin(1L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(bytes, response.getBody());
        assertEquals(MediaType.IMAGE_JPEG, response.getHeaders().getContentType());
    }

    @Test
    void 取得は本人でもadminでもなければ拒否される() {
        doThrow(new ForbiddenException("この操作には本人またはadmin権限が必要です"))
                .when(adminAuthorizationService).requireSelfOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller().getAvatar(1L));

        verify(avatarService, never()).loadAvatar(anyLong());
    }
}
