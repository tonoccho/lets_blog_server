package com.letsblog.media.service;

import com.letsblog.media.testsupport.UploadImageFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1654: アップロード画像を、切り抜き・拡縮せず元の解像度のまま再エンコードする。
 * EXIF Orientationは反映し、メタ情報は残さず、形式(JPEG/PNG)は維持する。
 */
@DisplayName("ImageResizeService#reencodeKeepingResolution(issue #1654)")
class ImageResizeServiceReencodeTest {

    private final ImageResizeService service = new ImageResizeService();

    private static BufferedImage decode(byte[] data) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(data));
    }

    private static void assertNear(Color expected, int rgb) {
        Color actual = new Color(rgb);
        assertTrue(Math.abs(expected.getRed() - actual.getRed()) < 40
                        && Math.abs(expected.getGreen() - actual.getGreen()) < 40
                        && Math.abs(expected.getBlue() - actual.getBlue()) < 40,
                "期待=" + expected + " 実際=" + actual);
    }

    @Test
    @DisplayName("横長画像は元と同じ画素数のまま出力される")
    void 横長は寸法を変えない() throws IOException {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(2400, 1000, Color.RED, false));

        var result = service.reencodeKeepingResolution(src, "image/png");

        BufferedImage out = decode(result.data());
        assertEquals(2400, out.getWidth());
        assertEquals(1000, out.getHeight());
        assertEquals(2400, result.width());
        assertEquals(1000, result.height());
    }

    @Test
    @DisplayName("縦長画像は切り抜かれず、上下の端まで残る")
    void 縦長は切り抜かない() throws IOException {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.verticalBands(700, 1400));

        var result = service.reencodeKeepingResolution(src, "image/png");

        BufferedImage out = decode(result.data());
        assertEquals(700, out.getWidth());
        assertEquals(1400, out.getHeight());
        assertEquals(700, result.width());
        assertEquals(1400, result.height());
        assertNear(Color.RED, out.getRGB(350, 5));
        assertNear(Color.BLUE, out.getRGB(350, 1395));
    }

    @Test
    @DisplayName("小さい画像は拡大されない")
    void 小さい画像は拡大しない() throws IOException {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(320, 180, Color.BLUE, false));

        var result = service.reencodeKeepingResolution(src, "image/png");

        BufferedImage out = decode(result.data());
        assertEquals(320, out.getWidth());
        assertEquals(180, out.getHeight());
    }

    @Test
    @DisplayName("1920x1080より大きい画像も縮小されない")
    void 大きい画像は縮小しない() throws IOException {
        byte[] src = UploadImageFixtures.jpeg(UploadImageFixtures.solid(6000, 3375, Color.GREEN, false));

        var result = service.reencodeKeepingResolution(src, "image/jpeg");

        BufferedImage out = decode(result.data());
        assertEquals(6000, out.getWidth());
        assertEquals(3375, out.getHeight());
        assertNear(Color.GREEN, out.getRGB(3000, 1700));
    }

    @Test
    @DisplayName("EXIF Orientation(6=時計回り90度)を反映した向き・画素数で出力される")
    void Orientationを反映する() throws IOException {
        // 物理は横長(1920x1080)の左=赤・右=青。Orientation 6 で表示すると縦長(1080x1920)の上=赤・下=青。
        byte[] jpeg = UploadImageFixtures.withExif(
                UploadImageFixtures.jpeg(UploadImageFixtures.leftRedRightBlue(1920, 1080)), 6);

        var result = service.reencodeKeepingResolution(jpeg, "image/jpeg");

        BufferedImage out = decode(result.data());
        assertEquals(1080, out.getWidth());
        assertEquals(1920, out.getHeight());
        assertEquals(1080, result.width());
        assertEquals(1920, result.height());
        assertNear(Color.RED, out.getRGB(540, 60));
        assertNear(Color.BLUE, out.getRGB(540, 1860));
    }

    @Test
    @DisplayName("JPEGのGPS/EXIFは出力に残らない")
    void メタ情報は残らない() {
        byte[] jpeg = UploadImageFixtures.withExif(
                UploadImageFixtures.jpeg(UploadImageFixtures.solid(800, 600, Color.RED, false)), 1);
        assertTrue(new String(jpeg, StandardCharsets.ISO_8859_1).contains(UploadImageFixtures.GPS_MARKER));

        var result = service.reencodeKeepingResolution(jpeg, "image/jpeg");

        String text = new String(result.data(), StandardCharsets.ISO_8859_1);
        assertFalse(text.contains(UploadImageFixtures.GPS_MARKER));
        assertFalse(text.contains("Exif"));
    }

    @Test
    @DisplayName("JPEG入力はJPEGで出力される")
    void JPEG入力はJPEG() {
        byte[] src = UploadImageFixtures.jpeg(UploadImageFixtures.solid(800, 600, Color.RED, false));

        var result = service.reencodeKeepingResolution(src, "image/jpeg");

        assertEquals("image/jpeg", result.mimeType());
        assertEquals((byte) 0xFF, result.data()[0]);
        assertEquals((byte) 0xD8, result.data()[1]);
    }

    @Test
    @DisplayName("不透明なPNGもPNGのまま出力される(JPEGへ変換しない)")
    void 不透明PNGはPNGのまま() {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(800, 600, Color.RED, false));

        var result = service.reencodeKeepingResolution(src, "image/png");

        assertEquals("image/png", result.mimeType());
        assertEquals((byte) 0x89, result.data()[0]);
        assertEquals((byte) 'P', result.data()[1]);
    }

    @Test
    @DisplayName("透過のあるPNGはPNGのまま出力され、透過が保たれる")
    void 透過PNGは透過を保つ() throws IOException {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(800, 600, new Color(255, 0, 0, 0), true));

        var result = service.reencodeKeepingResolution(src, "image/png");

        assertEquals("image/png", result.mimeType());
        BufferedImage out = decode(result.data());
        assertTrue(out.getColorModel().hasAlpha());
        assertEquals(0, new Color(out.getRGB(100, 100), true).getAlpha());
    }

    @Test
    @DisplayName("アルファチャンネルを持つ全画素不透明のPNGも、PNGのまま出力される")
    void 全画素不透明のアルファ付きPNGもPNG() {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(800, 600, new Color(10, 200, 10, 255), true));

        assertEquals("image/png", service.reencodeKeepingResolution(src, "image/png").mimeType());
    }

    @Test
    @DisplayName("デコードできないバイト列は拒否する")
    void デコードできないものは拒否() {
        assertThrows(InvalidImageUploadException.class,
                () -> service.reencodeKeepingResolution(new byte[] {1, 2, 3, 4}, "image/png"));
    }

    @Test
    @DisplayName("画素数が上限を超える画像は、デコードせずに拒否する")
    void 画素数が多すぎるものは拒否() {
        byte[] bomb = UploadImageFixtures.pngHeaderOnly(30000, 30000);

        assertThrows(InvalidImageUploadException.class,
                () -> service.reencodeKeepingResolution(bomb, "image/png"));
    }
}
