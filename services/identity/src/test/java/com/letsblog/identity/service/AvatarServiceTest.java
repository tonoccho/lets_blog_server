package com.letsblog.identity.service;

import com.letsblog.identity.domain.User;
import com.letsblog.identity.dto.UserProfileResponse;
import com.letsblog.identity.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #1241: アバターアップロードのオーケストレーション(検証 -> 画像処理 -> 保存 -> avatar_url更新)。
 */
@ExtendWith(MockitoExtension.class)
class AvatarServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AvatarImageProcessor avatarImageProcessor;

    @Mock
    private AvatarStorageService avatarStorageService;

    private AvatarService service() {
        return new AvatarService(userRepository, avatarImageProcessor, avatarStorageService);
    }

    private User user(long id) {
        User user = new User();
        user.setId(id);
        user.setEmail("user" + id + "@example.com");
        user.setRole("user");
        return user;
    }

    @Test
    void 対応形式のアップロードは処理して保存しavatarUrlを更新する() {
        User user = user(1L);
        byte[] raw = "raw-bytes".getBytes();
        byte[] processed = "processed-bytes".getBytes();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(avatarImageProcessor.process(raw, "image/jpeg")).thenReturn(processed);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfileResponse response = service().uploadAvatar(1L, "image/jpeg", raw);

        verify(avatarStorageService).store(1L, processed);
        assertEquals("/api/users/1/avatar", response.avatarUrl());
    }

    @Test
    void GIF等の対応外形式は保存せず例外を送出する() {
        assertThrows(UnsupportedAvatarFormatException.class,
                () -> service().uploadAvatar(1L, "image/gif", "raw".getBytes()));

        verify(avatarStorageService, never()).store(anyLong(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void SVGも対応外形式として拒否する() {
        assertThrows(UnsupportedAvatarFormatException.class,
                () -> service().uploadAvatar(1L, "image/svg+xml", "raw".getBytes()));
    }

    @Test
    void contentTypeがnullの場合も対応外形式として拒否する() {
        assertThrows(UnsupportedAvatarFormatException.class,
                () -> service().uploadAvatar(1L, null, "raw".getBytes()));

        verify(avatarStorageService, never()).store(anyLong(), any());
    }

    @Test
    void 存在しないユーザーへのアップロードはUserNotFoundExceptionを送出する() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class,
                () -> service().uploadAvatar(99L, "image/png", "raw".getBytes()));
    }

    @Test
    void 保存済みアバターを読み込める() {
        when(userRepository.existsById(1L)).thenReturn(true);
        when(avatarStorageService.load(1L)).thenReturn(Optional.of("avatar-bytes".getBytes()));

        byte[] result = service().loadAvatar(1L);

        assertEquals("avatar-bytes", new String(result));
    }

    @Test
    void 存在しないユーザーのアバター取得はUserNotFoundExceptionを送出する() {
        when(userRepository.existsById(123L)).thenReturn(false);

        assertThrows(UserNotFoundException.class, () -> service().loadAvatar(123L));
    }

    @Test
    void アバター未設定のユーザーの取得はAvatarNotFoundExceptionを送出する() {
        when(userRepository.existsById(1L)).thenReturn(true);
        when(avatarStorageService.load(1L)).thenReturn(Optional.empty());

        assertThrows(AvatarNotFoundException.class, () -> service().loadAvatar(1L));
    }

    @Test
    void アップロード時に渡したcontentTypeがそのまま画像処理へ渡る() {
        User user = user(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<String> contentTypeCaptor = ArgumentCaptor.forClass(String.class);
        when(avatarImageProcessor.process(any(), contentTypeCaptor.capture())).thenReturn("x".getBytes());

        service().uploadAvatar(1L, "image/webp", "raw".getBytes());

        assertEquals("image/webp", contentTypeCaptor.getValue());
    }
}
