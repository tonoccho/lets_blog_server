package com.letsblog.media.controller;

import com.letsblog.media.dto.GeneratedImageDetailResponse;
import com.letsblog.media.service.ForbiddenException;
import com.letsblog.media.service.InvalidImageUploadException;
import com.letsblog.media.testsupport.MediaWorld;
import com.letsblog.media.testsupport.UploadImageFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1599: POST /api/generated-images/upload。
 *
 * <p>issue #1820: アップロードサービス・認可・保存は実物で、DB(リポジトリ)・MQ・identity-service・ai-serviceだけを
 * 差し替える。
 */
@DisplayName("GeneratedImageUploadController(issue #1599)")
class GeneratedImageUploadControllerTest {

    private final MediaWorld world = new MediaWorld();

    private GeneratedImageUploadController controller;

    @BeforeEach
    void setUp() {
        world.actingAsAdmin(1L);
        controller = new GeneratedImageUploadController(world.adminAuthorizationService, world.uploadService);
    }

    @AfterEach
    void close() {
        world.close();
    }

    private static byte[] png(int width, int height) {
        return UploadImageFixtures.png(UploadImageFixtures.solid(width, height, Color.BLUE, false));
    }

    @Test
    @DisplayName("メンバー/adminならアップロードでき、UPLOADの詳細を返す")
    void アップロードできる() {
        GeneratedImageDetailResponse response = controller.upload(
                7L, new MockMultipartFile("file", "a.png", "image/png", png(64, 32)));

        assertEquals("UPLOAD", response.provider());
        assertEquals(7L, response.projectId());
        assertNull(response.prompt());
        assertEquals(64, response.width());
        assertEquals(32, response.height());
        assertEquals(1, world.storedFileCount());
        // 登録後のAIタグ付けでも同じ行を保存し直すため、保存は1回とは限らない。
        assertEquals(response.id(), world.savedImages.get(0).getId());
    }

    @Test
    @DisplayName("プロジェクトのメンバーはadminでなくてもアップロードできる")
    void メンバーはアップロードできる() {
        world.actingAsUser(10L);
        world.http.json("GET", "/api/internal/identity/projects/7/members/10", "true");

        GeneratedImageDetailResponse response = controller.upload(
                7L, new MockMultipartFile("file", "a.png", "image/png", png(8, 8)));

        assertEquals("UPLOAD", response.provider());
    }

    @Test
    @DisplayName("非メンバーはForbiddenで、ファイルは処理されない")
    void 非メンバーは403() {
        world.actingAsUser(10L);
        world.http.json("GET", "/api/internal/identity/projects/7/members/10", "false");

        assertThrows(ForbiddenException.class, () -> controller.upload(
                7L, new MockMultipartFile("file", "a.png", "image/png", png(8, 8))));
        assertEquals(0, world.storedFileCount());
        assertTrue(world.savedImages.isEmpty());
    }

    @Test
    @DisplayName("ファイルが空なら拒否される")
    void 空ファイルは拒否() {
        assertThrows(InvalidImageUploadException.class, () -> controller.upload(
                7L, new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[0])));
        assertEquals(0, world.storedFileCount());
    }

    @Test
    @DisplayName("サービスの拒否はそのまま伝わる")
    void サービスの拒否は伝わる() {
        assertThrows(InvalidImageUploadException.class, () -> controller.upload(
                7L, new MockMultipartFile("file", "a.gif", "image/gif", UploadImageFixtures.gif(4, 4))));
        assertEquals(0, world.storedFileCount());
    }
}
