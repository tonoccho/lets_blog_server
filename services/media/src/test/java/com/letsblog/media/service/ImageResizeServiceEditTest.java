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
import java.util.List;

import static com.letsblog.media.service.ImageEditOperation.FLIP_HORIZONTAL;
import static com.letsblog.media.service.ImageEditOperation.FLIP_VERTICAL;
import static com.letsblog.media.service.ImageEditOperation.ROTATE_CCW;
import static com.letsblog.media.service.ImageEditOperation.ROTATE_CW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1655: 回転・反転・切り抜き。操作は並べた順に適用し、切り抜きは操作後の画像の座標で指定する。
 * 暗黙の拡縮はせず、メタ情報は残さず、形式は元のまま。
 */
@DisplayName("ImageResizeService#applyEdits(issue #1655)")
class ImageResizeServiceEditTest {

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

    /** 左赤・右青の40x20 PNG。 */
    private static byte[] redBlue() {
        return UploadImageFixtures.png(UploadImageFixtures.leftRedRightBlue(40, 20));
    }

    @Test
    @DisplayName("右90度回転で幅と高さが入れ替わり、左の赤が上に来る")
    void 右回転() throws IOException {
        var result = service.applyEdits(redBlue(), "image/png", List.of(ROTATE_CW), null, null);

        BufferedImage out = decode(result.data());
        assertEquals(20, out.getWidth());
        assertEquals(40, out.getHeight());
        assertEquals(20, result.width());
        assertEquals(40, result.height());
        assertNear(Color.RED, out.getRGB(10, 5));
        assertNear(Color.BLUE, out.getRGB(10, 35));
    }

    @Test
    @DisplayName("左90度回転で左の赤が下に来る")
    void 左回転() throws IOException {
        var result = service.applyEdits(redBlue(), "image/png", List.of(ROTATE_CCW), null, null);

        BufferedImage out = decode(result.data());
        assertEquals(20, out.getWidth());
        assertEquals(40, out.getHeight());
        assertNear(Color.BLUE, out.getRGB(10, 5));
        assertNear(Color.RED, out.getRGB(10, 35));
    }

    @Test
    @DisplayName("左右反転で赤が右に来る。寸法は変わらない")
    void 左右反転() throws IOException {
        var result = service.applyEdits(redBlue(), "image/png", List.of(FLIP_HORIZONTAL), null, null);

        BufferedImage out = decode(result.data());
        assertEquals(40, out.getWidth());
        assertEquals(20, out.getHeight());
        assertNear(Color.BLUE, out.getRGB(5, 10));
        assertNear(Color.RED, out.getRGB(35, 10));
    }

    @Test
    @DisplayName("上下反転で帯の上下が入れ替わる")
    void 上下反転() throws IOException {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.verticalBands(30, 60));

        var result = service.applyEdits(src, "image/png", List.of(FLIP_VERTICAL), null, null);

        BufferedImage out = decode(result.data());
        assertEquals(30, out.getWidth());
        assertEquals(60, out.getHeight());
        assertNear(Color.BLUE, out.getRGB(15, 5));
        assertNear(Color.RED, out.getRGB(15, 55));
    }

    @Test
    @DisplayName("操作は並べた順に適用される(回転してから左右反転)")
    void 操作は順に適用される() throws IOException {
        var result = service.applyEdits(redBlue(), "image/png", List.of(ROTATE_CW, FLIP_HORIZONTAL), null, null);

        BufferedImage out = decode(result.data());
        // 回転後は上が赤・下が青。左右反転しても上下は変わらない。
        assertNear(Color.RED, out.getRGB(10, 5));
        assertNear(Color.BLUE, out.getRGB(10, 35));

        var reversed = service.applyEdits(redBlue(), "image/png", List.of(FLIP_HORIZONTAL, ROTATE_CW), null, null);
        BufferedImage out2 = decode(reversed.data());
        // 先に反転(左青・右赤)してから右回転すると、上が青・下が赤。
        assertNear(Color.BLUE, out2.getRGB(10, 5));
        assertNear(Color.RED, out2.getRGB(10, 35));
    }

    @Test
    @DisplayName("切り抜きは指定範囲の寸法になり、拡縮されない")
    void 切り抜き() throws IOException {
        var result = service.applyEdits(redBlue(), "image/png", List.of(), new ImageCropRegion(15, 2, 10, 8), null);

        BufferedImage out = decode(result.data());
        assertEquals(10, out.getWidth());
        assertEquals(8, out.getHeight());
        assertEquals(10, result.width());
        assertEquals(8, result.height());
        // x=15..24 は 赤(〜19) と 青(20〜)の境をまたぐ
        assertNear(Color.RED, out.getRGB(2, 4));
        assertNear(Color.BLUE, out.getRGB(8, 4));
    }

    @Test
    @DisplayName("切り抜きは操作後の画像の座標で指定する(回転してから切り抜く)")
    void 回転後の座標で切り抜く() throws IOException {
        var result = service.applyEdits(
                redBlue(), "image/png", List.of(ROTATE_CW), new ImageCropRegion(0, 0, 20, 10), null);

        BufferedImage out = decode(result.data());
        assertEquals(20, out.getWidth());
        assertEquals(10, out.getHeight());
        assertNear(Color.RED, out.getRGB(10, 5));
    }

    @Test
    @DisplayName("JPEGはJPEGのまま、メタ情報(GPS/EXIF)は残らない")
    void JPEGは形式を保ちメタ情報を残さない() throws IOException {
        byte[] src = UploadImageFixtures.withExif(
                UploadImageFixtures.jpeg(UploadImageFixtures.solid(80, 40, Color.RED, false)), 1);

        var result = service.applyEdits(src, "image/jpeg", List.of(ROTATE_CW), null, null);

        assertEquals("image/jpeg", result.mimeType());
        BufferedImage out = decode(result.data());
        assertEquals(40, out.getWidth());
        assertEquals(80, out.getHeight());
        String raw = new String(result.data(), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(UploadImageFixtures.GPS_MARKER));
        assertFalse(raw.contains("Exif"));
    }

    @Test
    @DisplayName("透過PNGはPNGのまま透過を保つ")
    void 透過PNGは透過を保つ() throws IOException {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(20, 10, new Color(255, 0, 0, 0), true));

        var result = service.applyEdits(src, "image/png", List.of(FLIP_VERTICAL), null, null);

        assertEquals("image/png", result.mimeType());
        assertTrue(decode(result.data()).getColorModel().hasAlpha());
    }

    @Test
    @DisplayName("操作も切り抜きも無ければ拒否される")
    void 編集内容が無ければ拒否() {
        assertThrows(InvalidImageUploadException.class,
                () -> service.applyEdits(redBlue(), "image/png", List.of(), null, null));
        assertThrows(InvalidImageUploadException.class,
                () -> service.applyEdits(redBlue(), "image/png", null, null, null));
    }

    @Test
    @DisplayName("範囲外・ゼロ以下の切り抜きは拒否される")
    void 不正な切り抜きは拒否() {
        byte[] src = redBlue();
        for (ImageCropRegion bad : List.of(
                new ImageCropRegion(-1, 0, 10, 10),
                new ImageCropRegion(0, -1, 10, 10),
                new ImageCropRegion(0, 0, 0, 10),
                new ImageCropRegion(0, 0, 10, 0),
                new ImageCropRegion(35, 0, 10, 10),
                new ImageCropRegion(0, 15, 10, 10))) {
            assertThrows(InvalidImageUploadException.class,
                    () -> service.applyEdits(src, "image/png", List.of(), bad, null), bad.toString());
        }
    }

    @Test
    @DisplayName("画像全体を指す切り抜きは受け付けられる(境界)")
    void 全体の切り抜きは有効() throws IOException {
        var result = service.applyEdits(redBlue(), "image/png", List.of(), new ImageCropRegion(0, 0, 40, 20), null);

        assertEquals(40, result.width());
        assertEquals(20, result.height());
    }

    @Test
    @DisplayName("画素数が上限を超える画像、読めない画像は拒否される")
    void デコードできない画像は拒否() {
        assertThrows(InvalidImageUploadException.class, () -> service.applyEdits(
                UploadImageFixtures.pngHeaderOnly(10_000, 10_000), "image/png", List.of(ROTATE_CW), null, null));
        assertThrows(InvalidImageUploadException.class, () -> service.applyEdits(
                new byte[] {1, 2, 3}, "image/png", List.of(ROTATE_CW), null, null));
    }
}
