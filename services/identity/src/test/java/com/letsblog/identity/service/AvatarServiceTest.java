package com.letsblog.identity.service;

import com.letsblog.identity.domain.User;
import com.letsblog.identity.dto.UserProfileResponse;
import com.letsblog.identity.repository.UserRepository;
import com.letsblog.identity.testsupport.ImageFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #1241: アバターアップロードのオーケストレーション(検証 -> 画像処理 -> 保存 -> avatar_url更新)。
 *
 * <p>{@code AvatarImageProcessor}・{@code AvatarStorageService}は実物(画像は実際にデコード・変換され、
 * 一時ディレクトリへ実際に保存される)。差し替えるのはDBのリポジトリだけ。
 */
class AvatarServiceTest {

    @TempDir
    Path storageDir;

    private final UserRepository userRepository = mock(UserRepository.class);
    private AvatarService service;

    @BeforeEach
    void setUp() {
        service = new AvatarService(userRepository, new AvatarImageProcessor(),
                new AvatarStorageService(storageDir.toString()));
    }

    private User user(long id) {
        User user = new User();
        user.setId(id);
        user.setEmail("user" + id + "@example.com");
        user.setRole("user");
        return user;
    }

    private BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void 対応形式のアップロードは処理して保存しavatarUrlを更新する() throws IOException {
        User user = user(1L);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserProfileResponse response = service.uploadAvatar(1L, "image/jpeg", ImageFixtures.jpeg(800, 600));

        assertEquals("/api/users/1/avatar", response.avatarUrl());
        assertEquals("/api/users/1/avatar", user.getAvatarUrl());
        // 実際に512x512のJPEGとして保存されている。
        byte[] stored = Files.readAllBytes(storageDir.resolve("1.jpg"));
        BufferedImage image = decode(stored);
        assertEquals(512, image.getWidth());
        assertEquals(512, image.getHeight());
    }

    @Test
    void PNGとWebPと宣言された入力も処理される() throws IOException {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.uploadAvatar(1L, "image/png", ImageFixtures.png(100, 100));
        service.uploadAvatar(1L, "IMAGE/WEBP", ImageFixtures.png(64, 64));

        assertEquals(512, decode(Files.readAllBytes(storageDir.resolve("1.jpg"))).getWidth());
    }

    @Test
    void GIF等の対応外形式は保存せず例外を送出する() {
        assertThrows(UnsupportedAvatarFormatException.class,
                () -> service.uploadAvatar(1L, "image/gif", "raw".getBytes()));

        assertFalse(Files.exists(storageDir.resolve("1.jpg")));
        verify(userRepository, never()).save(any());
    }

    @Test
    void SVGも対応外形式として拒否する() {
        assertThrows(UnsupportedAvatarFormatException.class,
                () -> service.uploadAvatar(1L, "image/svg+xml", "raw".getBytes()));
    }

    @Test
    void contentTypeがnullの場合も対応外形式として拒否する() {
        assertThrows(UnsupportedAvatarFormatException.class,
                () -> service.uploadAvatar(1L, null, "raw".getBytes()));

        assertFalse(Files.exists(storageDir.resolve("1.jpg")));
    }

    @Test
    void 画像として読めないバイト列は保存せず例外を送出する() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));

        assertThrows(UnsupportedAvatarFormatException.class,
                () -> service.uploadAvatar(1L, "image/jpeg", "not-an-image".getBytes()));

        assertFalse(Files.exists(storageDir.resolve("1.jpg")));
        verify(userRepository, never()).save(any());
    }

    @Test
    void 存在しないユーザーへのアップロードはUserNotFoundExceptionを送出する() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class,
                () -> service.uploadAvatar(99L, "image/png", ImageFixtures.png(10, 10)));
    }

    @Test
    void 保存済みアバターを読み込める() throws IOException {
        when(userRepository.existsById(1L)).thenReturn(true);
        Files.write(storageDir.resolve("1.jpg"), "avatar-bytes".getBytes());

        byte[] result = service.loadAvatar(1L);

        assertEquals("avatar-bytes", new String(result));
    }

    @Test
    void アップロードしたアバターをそのまま読み込める() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.existsById(1L)).thenReturn(true);

        service.uploadAvatar(1L, "image/png", ImageFixtures.png(300, 200));

        assertTrue(service.loadAvatar(1L).length > 0);
        assertNotNull(service.loadAvatar(1L));
    }

    @Test
    void 存在しないユーザーのアバター取得はUserNotFoundExceptionを送出する() {
        when(userRepository.existsById(123L)).thenReturn(false);

        assertThrows(UserNotFoundException.class, () -> service.loadAvatar(123L));
    }

    @Test
    void アバター未設定のユーザーの取得はAvatarNotFoundExceptionを送出する() {
        when(userRepository.existsById(1L)).thenReturn(true);

        assertThrows(AvatarNotFoundException.class, () -> service.loadAvatar(1L));
    }
}
