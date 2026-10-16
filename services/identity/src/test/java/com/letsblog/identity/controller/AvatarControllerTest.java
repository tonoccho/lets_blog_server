package com.letsblog.identity.controller;

import com.letsblog.identity.domain.User;
import com.letsblog.identity.service.AvatarImageProcessor;
import com.letsblog.identity.service.AvatarService;
import com.letsblog.identity.service.AvatarStorageService;
import com.letsblog.identity.testsupport.IdentityWorld;
import com.letsblog.identity.testsupport.ImageFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1241 AC8: アバターのアップロード/取得の認可が、既存のPUT /api/users/{id}と同じ
 * requireSelfOrAdminに揃っていることの検証。実物のコントローラ・{@code AvatarService}(画像変換・保存を含む)を
 * MockMvcに載せ、DBのリポジトリだけをMockitoで差し替える。
 */
class AvatarControllerTest {

    @TempDir
    Path storageDir;

    private IdentityWorld world;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        world = new IdentityWorld();
        AvatarService avatarService = new AvatarService(world.userRepository, new AvatarImageProcessor(),
                new AvatarStorageService(storageDir.toString()));
        mvc = world.mvc(new AvatarController(avatarService, world.adminAuthorizationService));
        when(world.userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private User user(long id) {
        User user = new User();
        user.setId(id);
        user.setEmail("user" + id + "@example.com");
        user.setRole("user");
        return user;
    }

    private MockMultipartFile avatarFile() {
        return new MockMultipartFile("file", "avatar.jpg", MediaType.IMAGE_JPEG_VALUE, ImageFixtures.jpeg(640, 480));
    }

    @Test
    void アップロードは本人なら許可され変換した画像が保存される() throws Exception {
        world.actAs(1L, "user");
        when(world.userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));

        mvc.perform(multipart("/api/users/1/avatar").file(avatarFile()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value("/api/users/1/avatar"));

        assertTrue(Files.exists(storageDir.resolve("1.jpg")));
    }

    @Test
    void アップロードはadminなら他人にも許可される() throws Exception {
        world.actAs(9L, "admin");
        when(world.userRepository.findById(1L)).thenReturn(Optional.of(user(1L)));

        mvc.perform(multipart("/api/users/1/avatar").file(avatarFile())).andExpect(status().isOk());
    }

    @Test
    void アップロードは本人でもadminでもなければ拒否される() throws Exception {
        world.actAs(2L, "user");

        mvc.perform(multipart("/api/users/1/avatar").file(avatarFile())).andExpect(status().isForbidden());

        assertFalse(Files.exists(storageDir.resolve("1.jpg")));
        verify(world.userRepository, never()).save(any());
    }

    @Test
    void 対応外の形式は400相当で拒否される() throws Exception {
        world.actAs(1L, "user");
        MockMultipartFile gif = new MockMultipartFile("file", "a.gif", "image/gif", "GIF89a".getBytes());

        mvc.perform(multipart("/api/users/1/avatar").file(gif)).andExpect(status().is4xxClientError());

        assertFalse(Files.exists(storageDir.resolve("1.jpg")));
    }

    @Test
    void 取得は本人なら許可されJPEGをキャッシュ無効で返す() throws Exception {
        world.actAs(1L, "user");
        when(world.userRepository.existsById(1L)).thenReturn(true);
        byte[] bytes = ImageFixtures.jpeg(512, 512);
        Files.write(storageDir.resolve("1.jpg"), bytes);

        mvc.perform(get("/api/users/1/avatar"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(bytes));
    }

    @Test
    void 取得は本人でもadminでもなければ拒否される() throws Exception {
        world.actAs(2L, "user");

        mvc.perform(get("/api/users/1/avatar")).andExpect(status().isForbidden());

        verify(world.userRepository, never()).existsById(any());
    }

    @Test
    void 取得はアバター未設定なら404() throws Exception {
        world.actAs(1L, "user");
        when(world.userRepository.existsById(1L)).thenReturn(true);

        mvc.perform(get("/api/users/1/avatar")).andExpect(status().isNotFound());
    }
}
