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
 * issue #1599: 固定解像度(1920x1080)への中央切り抜き(cover)変換。
 * 16:9に中央で切り抜いてから拡縮し、EXIF Orientationを反映し、メタ情報を残さない。
 */
@DisplayName("ImageResizeService#coverTo(issue #1599)")
class ImageResizeServiceCoverTest {

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
    @DisplayName("横長画像は1920x1080になる")
    void 横長は1920x1080() throws IOException {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(3840, 1000, Color.RED, false));

        var result = service.coverTo(src, "image/png", 1920, 1080);

        BufferedImage out = decode(result.data());
        assertEquals(1920, out.getWidth());
        assertEquals(1080, out.getHeight());
    }

    @Test
    @DisplayName("縦長画像は上下が切れ、中央部分が残る")
    void 縦長は上下が切れる() throws IOException {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.verticalBands(1080, 1920));

        var result = service.coverTo(src, "image/png", 1920, 1080);

        BufferedImage out = decode(result.data());
        assertEquals(1920, out.getWidth());
        assertEquals(1080, out.getHeight());
        // 縦長の中央の16:9は全面が中央の緑の帯の内側に収まる。上端・下端にも赤や青は残らない。
        assertNear(Color.GREEN, out.getRGB(960, 10));
        assertNear(Color.GREEN, out.getRGB(960, 540));
        assertNear(Color.GREEN, out.getRGB(960, 1070));
    }

    @Test
    @DisplayName("1920x1080より小さい画像は拡大される")
    void 小さい画像は拡大される() throws IOException {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(320, 180, Color.BLUE, false));

        var result = service.coverTo(src, "image/png", 1920, 1080);

        BufferedImage out = decode(result.data());
        assertEquals(1920, out.getWidth());
        assertEquals(1080, out.getHeight());
        assertNear(Color.BLUE, out.getRGB(10, 10));
    }

    @Test
    @DisplayName("EXIF Orientation(6=時計回り90度)を反映してから切り抜く")
    void Orientationを反映してから切り抜く() throws IOException {
        // 物理は横長の左=赤・右=青。Orientation 6 で表示すると縦長の上=赤・下=青になる。
        byte[] jpeg = UploadImageFixtures.withExif(
                UploadImageFixtures.jpeg(UploadImageFixtures.leftRedRightBlue(1920, 1080)), 6);

        var result = service.coverTo(jpeg, "image/jpeg", 1920, 1080);

        BufferedImage out = decode(result.data());
        assertEquals(1920, out.getWidth());
        assertEquals(1080, out.getHeight());
        assertNear(Color.RED, out.getRGB(960, 60));
        assertNear(Color.BLUE, out.getRGB(960, 1020));
    }

    @Test
    @DisplayName("JPEGのGPS/EXIFは出力に残らない")
    void メタ情報は残らない() {
        byte[] jpeg = UploadImageFixtures.withExif(
                UploadImageFixtures.jpeg(UploadImageFixtures.solid(800, 600, Color.RED, false)), 1);
        assertTrue(new String(jpeg, StandardCharsets.ISO_8859_1).contains(UploadImageFixtures.GPS_MARKER));

        var result = service.coverTo(jpeg, "image/jpeg", 1920, 1080);

        String text = new String(result.data(), StandardCharsets.ISO_8859_1);
        assertFalse(text.contains(UploadImageFixtures.GPS_MARKER));
        assertFalse(text.contains("Exif"));
    }

    @Test
    @DisplayName("不透明な画像はJPEGで保存される(PNG入力でも)")
    void 不透明はJPEG() {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(800, 600, Color.RED, false));

        var result = service.coverTo(src, "image/png", 1920, 1080);

        assertEquals("image/jpeg", result.mimeType());
        assertEquals((byte) 0xFF, result.data()[0]);
        assertEquals((byte) 0xD8, result.data()[1]);
    }

    @Test
    @DisplayName("JPEG入力はJPEGで保存される")
    void JPEG入力はJPEG() {
        byte[] src = UploadImageFixtures.jpeg(UploadImageFixtures.solid(800, 600, Color.RED, false));

        assertEquals("image/jpeg", service.coverTo(src, "image/jpeg", 1920, 1080).mimeType());
    }

    @Test
    @DisplayName("透過のある画像はPNGのまま保存され、透過が保たれる")
    void 透過はPNG() throws IOException {
        BufferedImage translucent = UploadImageFixtures.solid(800, 600, new Color(255, 0, 0, 0), true);
        byte[] src = UploadImageFixtures.png(translucent);

        var result = service.coverTo(src, "image/png", 1920, 1080);

        assertEquals("image/png", result.mimeType());
        BufferedImage out = decode(result.data());
        assertTrue(out.getColorModel().hasAlpha());
        assertEquals(0, new Color(out.getRGB(100, 100), true).getAlpha());
    }

    @Test
    @DisplayName("デコードできないバイト列は拒否する")
    void デコードできないものは拒否() {
        assertThrows(InvalidImageUploadException.class,
                () -> service.coverTo(new byte[] {1, 2, 3, 4}, "image/png", 1920, 1080));
    }

    @Test
    @DisplayName("画素数が上限を超える画像は、デコードせずに拒否する")
    void 画素数が多すぎるものは拒否() {
        byte[] bomb = UploadImageFixtures.pngHeaderOnly(30000, 30000);

        assertThrows(InvalidImageUploadException.class, () -> service.coverTo(bomb, "image/png", 1920, 1080));
    }

    @Test
    @DisplayName("アルファチャンネルを持つが全画素が不透明なPNGは、不透明として扱いJPEGで保存する")
    void 全画素が不透明なアルファ付きPNGはJPEG() {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(800, 600, new Color(10, 200, 10, 255), true));

        assertEquals("image/jpeg", service.coverTo(src, "image/png", 1920, 1080).mimeType());
    }

    @Test
    @DisplayName("一部だけ透過した画像(最初の行は不透明)でもPNGのまま保存する")
    void 一部だけ透過ならPNG() throws IOException {
        BufferedImage image = UploadImageFixtures.solid(800, 450, new Color(0, 0, 255, 255), true);
        for (int x = 0; x < 800; x++) {
            image.setRGB(x, 449, 0x00000000);
        }

        var result = service.coverTo(UploadImageFixtures.png(image), "image/png", 1920, 1080);

        assertEquals("image/png", result.mimeType());
        assertEquals(0, new Color(decode(result.data()).getRGB(100, 1079), true).getAlpha());
    }

    @Test
    @DisplayName("目標の2倍を超える大きな画像も、1920x1080へ縮小される")
    void 大きな画像は縮小される() throws IOException {
        byte[] src = UploadImageFixtures.jpeg(UploadImageFixtures.solid(6000, 3375, Color.GREEN, false));

        var result = service.coverTo(src, "image/jpeg", 1920, 1080);

        BufferedImage out = decode(result.data());
        assertEquals(1920, out.getWidth());
        assertEquals(1080, out.getHeight());
        assertNear(Color.GREEN, out.getRGB(960, 540));
    }

    @Test
    @DisplayName("ちょうど16:9の画像は切り抜かずにそのまま1920x1080になる")
    void ちょうど16対9() throws IOException {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.verticalBands(1920, 1080));

        BufferedImage out = decode(service.coverTo(src, "image/png", 1920, 1080).data());

        assertNear(Color.RED, out.getRGB(10, 10));
        assertNear(Color.BLUE, out.getRGB(10, 1070));
    }
}
