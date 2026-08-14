package com.letsblog.api.service;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ImageResizeServiceの回帰テスト(issue #291)。長編基準のリサイズ・拡大の抑止・
 * GIF/デコード不能データのフォールバックを検証する。
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
    void resizeToLongEdge_長編が閾値以下の場合は元のバイト列をそのまま返す() throws Exception {
        byte[] original = renderPng(400, 300);

        byte[] result = service.resizeToLongEdge(original, "image/png", 1300);

        assertArrayEquals(original, result);
    }

    @Test
    void resizeToLongEdge_拡大はしない() throws Exception {
        byte[] original = renderPng(100, 50);

        byte[] result = service.resizeToLongEdge(original, "image/png", 1300);

        assertArrayEquals(original, result);
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

    private byte[] renderPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private byte[] renderJpeg(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
