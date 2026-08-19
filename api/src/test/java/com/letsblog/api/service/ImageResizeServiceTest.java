package com.letsblog.api.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ImageResizeServiceの回帰テスト。長編基準のリサイズ・拡大の抑止・GIF/デコード不能データの
 * フォールバック(issue #291)に加え、EXIF等のメタ情報削除とOrientation正規化(issue #432)を検証する。
 */
class ImageResizeServiceTest {

    private final ImageResizeService service = new ImageResizeService();

    @Test
    void resizeToLongEdge_長編が閾値を超える場合は縦横比を保ってリサイズする() throws Exception {
        byte[] original = renderPng(2000, 1000);

        byte[] resized = service.resizeToLongEdge(original, "image/png", 1000);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(resized));
        assertEquals(1000, image.getWidth());
        assertEquals(500, image.getHeight());
    }

    @Test
    void resizeToLongEdge_長編が閾値以下の場合はリサイズせず同じ寸法のまま返す() throws Exception {
        byte[] original = renderPng(400, 300);

        byte[] result = service.resizeToLongEdge(original, "image/png", 1300);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(400, image.getWidth());
        assertEquals(300, image.getHeight());
    }

    @Test
    void resizeToLongEdge_拡大はしない() throws Exception {
        byte[] original = renderPng(100, 50);

        byte[] result = service.resizeToLongEdge(original, "image/png", 1300);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(100, image.getWidth());
        assertEquals(50, image.getHeight());
    }

    @Test
    void resizeToLongEdge_GIFはアニメーションを壊しうるため対象外にする() {
        byte[] original = {1, 2, 3};

        byte[] result = service.resizeToLongEdge(original, "image/gif", 10);

        assertArrayEquals(original, result);
    }

    @Test
    void resizeToLongEdge_デコードできないデータは元のバイト列をそのまま返す() {
        byte[] notAnImage = {1, 2, 3, 4, 5};

        byte[] result = service.resizeToLongEdge(notAnImage, "image/png", 100);

        assertArrayEquals(notAnImage, result);
    }

    @Test
    void resizeToLongEdge_jpegはリサイズ後もjpegとしてデコードできる() throws Exception {
        byte[] original = renderJpeg(2000, 1000);

        byte[] resized = service.resizeToLongEdge(original, "image/jpeg", 500);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(resized));
        assertNotNull(image);
        assertEquals(500, image.getWidth());
        assertEquals(250, image.getHeight());
    }

    @Test
    void resizeToLongEdge_JPEGのExif_APP1セグメントを削除する() throws Exception {
        byte[] withExif = insertExifApp1(renderJpeg(400, 300), 1);
        assertTrue(containsApp1Marker(withExif), "テストデータにAPP1セグメントが含まれていること");

        byte[] result = service.resizeToLongEdge(withExif, "image/jpeg", 1300);

        assertFalse(containsApp1Marker(result), "アップロード用画像からAPP1(Exif)セグメントが削除されていること");
    }

    @Test
    void resizeToLongEdge_convertOpaquePngToJpeg指定時は透過なしPNGをJPEGへ変換する() throws Exception {
        byte[] original = renderPng(400, 200);

        ImageResizeService.ResizeResult result = service.resizeToLongEdge(original, "image/png", 1300, true);

        assertEquals("image/jpeg", result.mimeType());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.data()));
        assertEquals(400, image.getWidth());
        assertEquals(200, image.getHeight());
        assertTrue(isJpeg(result.data()), "エンコード結果がJPEGのマジックバイトで始まること");
    }

    @Test
    void resizeToLongEdge_convertOpaquePngToJpeg指定時も透過ありPNGはPNGのまま維持する() throws Exception {
        byte[] original = renderTransparentPng(400, 200);

        ImageResizeService.ResizeResult result = service.resizeToLongEdge(original, "image/png", 1300, true);

        assertEquals("image/png", result.mimeType());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result.data()));
        assertTrue(image.getColorModel().hasAlpha());
    }

    @Test
    void resizeToLongEdge_convertOpaquePngToJpeg未指定時はPNGのまま維持する() throws Exception {
        byte[] original = renderPng(400, 200);

        ImageResizeService.ResizeResult result = service.resizeToLongEdge(original, "image/png", 1300, false);

        assertEquals("image/png", result.mimeType());
    }

    private boolean isJpeg(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
    }

    private byte[] renderTransparentPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void stripMetadata_リサイズせずExifのみ削除する() throws Exception {
        byte[] withExif = insertExifApp1(renderJpeg(400, 300), 1);

        byte[] result = service.stripMetadata(withExif, "image/jpeg");

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(400, image.getWidth());
        assertEquals(300, image.getHeight());
        assertFalse(containsApp1Marker(result));
    }

    // 32x16の非対称画像。左半分(x<16)は赤、右半分(x>=16)は青。JPEG化に伴う多少の圧縮
    // ノイズがあっても判定できるよう、境界から離れた点の色の支配的なチャンネルで判定する。

    @Test
    void resizeToLongEdge_Orientation6は90度時計回りに正規化してからExifを削除する() throws Exception {
        BufferedImage marker = buildLeftRightMarker();
        byte[] withOrientation = insertExifApp1(encodeJpeg(marker), 6);

        byte[] result = service.resizeToLongEdge(withOrientation, "image/jpeg", 1300);

        BufferedImage output = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(16, output.getWidth());
        assertEquals(32, output.getHeight());
        assertDominantChannel(output, 8, 8, RED);
        assertDominantChannel(output, 8, 24, BLUE);
        assertFalse(containsApp1Marker(result));
    }

    @Test
    void resizeToLongEdge_Orientation8は90度反時計回りに正規化する() throws Exception {
        BufferedImage marker = buildLeftRightMarker();
        byte[] withOrientation = insertExifApp1(encodeJpeg(marker), 8);

        byte[] result = service.resizeToLongEdge(withOrientation, "image/jpeg", 1300);

        BufferedImage output = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(16, output.getWidth());
        assertEquals(32, output.getHeight());
        assertDominantChannel(output, 8, 8, BLUE);
        assertDominantChannel(output, 8, 24, RED);
    }

    @Test
    void resizeToLongEdge_Orientation3は180度回転して正規化する() throws Exception {
        BufferedImage marker = buildLeftRightMarker();
        byte[] withOrientation = insertExifApp1(encodeJpeg(marker), 3);

        byte[] result = service.resizeToLongEdge(withOrientation, "image/jpeg", 1300);

        BufferedImage output = ImageIO.read(new ByteArrayInputStream(result));
        assertEquals(32, output.getWidth());
        assertEquals(16, output.getHeight());
        assertDominantChannel(output, 8, 8, BLUE);
        assertDominantChannel(output, 24, 8, RED);
    }

    private static final int RED = 0;
    private static final int BLUE = 1;

    private BufferedImage buildLeftRightMarker() {
        BufferedImage image = new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, x < 16 ? 0xFF0000 : 0x0000FF);
            }
        }
        return image;
    }

    private void assertDominantChannel(BufferedImage image, int x, int y, int expectedChannel) {
        int rgb = image.getRGB(x, y);
        int r = (rgb >> 16) & 0xFF;
        int b = rgb & 0xFF;
        if (expectedChannel == RED) {
            assertTrue(r > b, "赤が支配的であること: r=" + r + ", b=" + b + " at (" + x + "," + y + ")");
        } else {
            assertTrue(b > r, "青が支配的であること: r=" + r + ", b=" + b + " at (" + x + "," + y + ")");
        }
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

    private byte[] insertExifApp1(byte[] jpegBytes, int orientation) {
        byte[] app1 = buildExifApp1Segment(orientation);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpegBytes, 0, 2);
        out.write(app1, 0, app1.length);
        out.write(jpegBytes, 2, jpegBytes.length - 2);
        return out.toByteArray();
    }

    private byte[] buildExifApp1Segment(int orientation) {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        tiff.write('M');
        tiff.write('M');
        writeInt16BE(tiff, 42);
        writeInt32BE(tiff, 8);
        writeInt16BE(tiff, 1);
        writeInt16BE(tiff, 0x0112);
        writeInt16BE(tiff, 3);
        writeInt32BE(tiff, 1);
        writeInt16BE(tiff, orientation);
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

    private byte[] renderPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private byte[] renderJpeg(int width, int height) throws Exception {
        return encodeJpeg(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB));
    }

    private byte[] encodeJpeg(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
