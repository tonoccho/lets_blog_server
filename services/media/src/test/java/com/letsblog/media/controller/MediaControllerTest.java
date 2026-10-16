package com.letsblog.media.controller;

import com.letsblog.media.service.ForbiddenException;
import com.letsblog.media.service.ImageResizeService;
import com.letsblog.media.testsupport.FakeHttpServer;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * legacy-apiから移設(issue #573 stage3)。/api/media/upload がアップロード前にEXIF等のメタ情報を
 * 削除すること(issue #432、ImageResizeServiceのロジック自体は変更していない)と、
 * Bearerトークンをlegacy-apiのCMSブリッジへ転送することを検証する。
 */
class MediaControllerTest {

    private static final String SITE_PROJECT_PATH = "/api/internal/publishing/sites/[^/]+/project-id";
    private static final String UPLOAD_PATH = "/api/internal/publishing/sites/[^/]+/media";

    /**
     * {@code CmsBridgeClient}・{@code AdminAuthorizationService}・{@code CurrentActorService}は実物で、
     * publishing-service・identity-serviceの応答をフェイクHTTPサーバーが演じる(issue #1820)。
     */
    private final MediaWorld world = new MediaWorld();

    private final ImageResizeService imageResizeService = new ImageResizeService();

    @BeforeEach
    void setUp() {
        world.http.json("GET", SITE_PROJECT_PATH, "{\"projectId\":7}");
        world.http.json("POST", UPLOAD_PATH, "{\"id\":\"1\",\"url\":\"https://example.com/media/1.jpg\"}");
    }

    @AfterEach
    void close() {
        world.close();
    }

    private MediaController controller() {
        return new MediaController(
                world.cmsBridgeClient, imageResizeService, world.request, world.adminAuthorizationService);
    }

    @Test
    void upload_JPEGのExifメタ情報を削除してからアップロードしBearerトークンを転送する() throws Exception {
        world.actingAsAdmin(1L);

        byte[] withExif = insertExifApp1(renderJpeg(100, 80));
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", withExif);

        controller().upload("my-site", file);

        FakeHttpServer.Request uploaded = world.http.requests("POST", UPLOAD_PATH).get(0);
        assertEquals("/api/internal/publishing/sites/my-site/media", uploaded.path());
        assertEquals("Bearer token-1", uploaded.header("Authorization"));
        assertTrue(containsJpeg(uploaded.body()), "CMSへ送信される本文にJPEGが含まれること");
        assertFalse(containsApp1Marker(uploaded.body()), "CMSへ送信されるバイト列からAPP1(Exif)セグメントが削除されていること");
    }

    private boolean containsJpeg(byte[] body) {
        return jpegStart(body) >= 0;
    }

    private int jpegStart(byte[] body) {
        for (int i = 0; i + 1 < body.length; i++) {
            if ((body[i] & 0xFF) == 0xFF && (body[i + 1] & 0xFF) == 0xD8) {
                return i;
            }
        }
        return -1;
    }

    private boolean containsApp1Marker(byte[] body) {
        byte[] jpeg = body;
        for (int i = Math.max(jpegStart(body), 0); i + 1 < jpeg.length; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xE1) {
                return true;
            }
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xDA) {
                break;
            }
        }
        return false;
    }

    private byte[] insertExifApp1(byte[] jpegBytes) {
        byte[] app1 = buildExifApp1Segment();
        byte[] out = new byte[2 + app1.length + (jpegBytes.length - 2)];
        System.arraycopy(jpegBytes, 0, out, 0, 2);
        System.arraycopy(app1, 0, out, 2, app1.length);
        System.arraycopy(jpegBytes, 2, out, 2 + app1.length, jpegBytes.length - 2);
        return out;
    }

    private byte[] buildExifApp1Segment() {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.write('M');
        tiff.write('M');
        writeInt16BE(tiff, 42);
        writeInt32BE(tiff, 8);
        writeInt16BE(tiff, 1);
        writeInt16BE(tiff, 0x0112);
        writeInt16BE(tiff, 3);
        writeInt32BE(tiff, 1);
        writeInt16BE(tiff, 1);
        writeInt16BE(tiff, 0);
        writeInt32BE(tiff, 0);
        byte[] tiffBytes = tiff.toByteArray();

        byte[] exifHeader = {'E', 'x', 'i', 'f', 0, 0};
        int length = 2 + exifHeader.length + tiffBytes.length;

        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        seg.write(0xFF);
        seg.write(0xE1);
        writeInt16BE(seg, length);
        seg.write(exifHeader, 0, exifHeader.length);
        seg.write(tiffBytes, 0, tiffBytes.length);
        return seg.toByteArray();
    }

    private void writeInt16BE(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private void writeInt32BE(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private byte[] renderJpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    // ---- issue #830 ----

    @Test
    void upload_サイトが属するプロジェクトのIDで認可判定を行う() throws Exception {
        world.actingAsUser(10L);
        world.http.json("GET", "/api/internal/identity/projects/7/members/10", "true");

        controller().upload("main", new MockMultipartFile(
                "file", "a.jpg", "image/jpeg", renderJpeg(10, 10)));

        assertEquals(1, world.http.requests("GET", "/api/internal/identity/projects/7/members/10").size());
        assertEquals(1, world.http.requests("POST", UPLOAD_PATH).size());
    }

    @Test
    void upload_プロジェクトメンバーでなければCMSへ書き込まない() {
        // CMSのメディアライブラリへ直接書き込むので、誰でも通してはいけない(issue #830)。
        world.actingAsUser(10L);
        world.http.json("GET", "/api/internal/identity/projects/7/members/10", "false");

        assertThrows(ForbiddenException.class, () -> controller().upload("main", new MockMultipartFile(
                "file", "a.jpg", "image/jpeg", new byte[] {1, 2, 3})));

        assertTrue(world.http.requests("POST", UPLOAD_PATH).isEmpty());
    }

    // ---- issue #1717 ----

    @Test
    void upload_画素数が上限を超える画像は断り_CMSへアップロードしない() {
        world.actingAsAdmin(1L);

        assertThrows(com.letsblog.media.service.InvalidImageUploadException.class,
                () -> controller().upload("main", new MockMultipartFile(
                        "file", "bomb.png", "image/png",
                        com.letsblog.media.testsupport.UploadImageFixtures.pngHeaderOnly(30000, 30000))));

        assertTrue(world.http.requests("POST", UPLOAD_PATH).isEmpty());
    }
}
