package com.letsblog.media.controller;

import com.letsblog.media.client.CmsBridgeClient;
import com.letsblog.media.client.MediaUploadResult;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.ForbiddenException;
import com.letsblog.media.service.ImageResizeService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * legacy-apiから移設(issue #573 stage3)。/api/media/upload がアップロード前にEXIF等のメタ情報を
 * 削除すること(issue #432、ImageResizeServiceのロジック自体は変更していない)と、
 * Bearerトークンをlegacy-apiのCMSブリッジへ転送することを検証する。
 */
@ExtendWith(MockitoExtension.class)
class MediaControllerTest {

    @Mock
    private CmsBridgeClient cmsBridgeClient;
    @Mock
    private HttpServletRequest request;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private final ImageResizeService imageResizeService = new ImageResizeService();

    private MediaController controller() {
        return new MediaController(cmsBridgeClient, imageResizeService, request, adminAuthorizationService);
    }

    @Test
    void upload_JPEGのExifメタ情報を削除してからアップロードしBearerトークンを転送する() throws Exception {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer token-abc");
        when(cmsBridgeClient.uploadMedia(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new MediaUploadResult("1", "https://example.com/media/1.jpg"));

        byte[] withExif = insertExifApp1(renderJpeg(100, 80));
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", withExif);

        controller().upload("my-site", file);

        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(cmsBridgeClient).uploadMedia(
                org.mockito.ArgumentMatchers.eq("my-site"), anyString(), anyString(), bytesCaptor.capture(),
                org.mockito.ArgumentMatchers.eq("Bearer token-abc"));
        assertFalse(containsApp1Marker(bytesCaptor.getValue()), "CMSへ送信されるバイト列からAPP1(Exif)セグメントが削除されていること");
    }

    private boolean containsApp1Marker(byte[] jpeg) {
        for (int i = 0; i + 1 < jpeg.length; i++) {
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
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer token-abc");
        when(cmsBridgeClient.resolveProjectIdBySiteKey("main", "Bearer token-abc")).thenReturn(7L);
        when(cmsBridgeClient.uploadMedia(any(), anyString(), anyString(), any(), any()))
                .thenReturn(new MediaUploadResult("1", "https://example.com/media/1.jpg"));

        controller().upload("main", new MockMultipartFile(
                "file", "a.jpg", "image/jpeg", renderJpeg(10, 10)));

        verify(adminAuthorizationService).requireProjectMemberOrAdminForResource(7L);
    }

    @Test
    void upload_プロジェクトメンバーでなければCMSへ書き込まない() {
        // CMSのメディアライブラリへ直接書き込むので、誰でも通してはいけない(issue #830)。
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer token-abc");
        when(cmsBridgeClient.resolveProjectIdBySiteKey("main", "Bearer token-abc")).thenReturn(7L);
        doThrow(new ForbiddenException("メンバーではありません"))
                .when(adminAuthorizationService).requireProjectMemberOrAdminForResource(7L);

        assertThrows(ForbiddenException.class, () -> controller().upload("main", new MockMultipartFile(
                "file", "a.jpg", "image/jpeg", new byte[] {1, 2, 3})));

        verify(cmsBridgeClient, never()).uploadMedia(any(), anyString(), anyString(), any(), any());
    }

    // ---- issue #1717 ----

    @Test
    void upload_画素数が上限を超える画像は断り_CMSへアップロードしない() {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer token-abc");

        assertThrows(com.letsblog.media.service.InvalidImageUploadException.class,
                () -> controller().upload("main", new MockMultipartFile(
                        "file", "bomb.png", "image/png",
                        com.letsblog.media.testsupport.UploadImageFixtures.pngHeaderOnly(30000, 30000))));

        verify(cmsBridgeClient, never()).uploadMedia(any(), anyString(), anyString(), any(), any());
    }
}
