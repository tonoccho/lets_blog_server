package com.letsblog.media.service;

import com.letsblog.media.testsupport.UploadImageFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import static com.letsblog.media.service.ImageEditOperation.ROTATE_CW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1656: 明るさ・コントラスト。式はプレビュー(CSS filter: brightness(b) contrast(c))と同じで、
 * b = c = 1 + 値/100(-100..100)。明るさ(値×b、0..255に丸める)を先に、続いてコントラスト
 * ((値-127.5)×c+127.5、0..255に丸める)を適用する。値が0・0なら画素は変わらない。
 */
@DisplayName("ImageResizeService#applyEdits の明るさ・コントラスト(issue #1656)")
class ImageResizeServiceAdjustTest {

    private final ImageResizeService service = new ImageResizeService();

    private static byte[] gray(int level) {
        return UploadImageFixtures.png(UploadImageFixtures.solid(40, 20, new Color(level, level, level), false));
    }

    private static int redOf(byte[] data) throws IOException {
        return new Color(ImageIO.read(new ByteArrayInputStream(data)).getRGB(5, 5)).getRed();
    }

    private int adjustedGray(int level, int brightness, int contrast) throws IOException {
        var result = service.applyEdits(gray(level), "image/png", List.of(), null,
                new ImageAdjustment(brightness, contrast));
        return redOf(result.data());
    }

    private static void assertNearValue(int expected, int actual) {
        assertTrue(Math.abs(expected - actual) <= 1, "期待=" + expected + " 実際=" + actual);
    }

    @Test
    @DisplayName("明るさ: 係数 1+値/100 を掛け、+100で2倍、-100で黒、255で頭打ち")
    void 明るさ() throws IOException {
        assertNearValue(150, adjustedGray(100, 50, 0));
        assertNearValue(50, adjustedGray(100, -50, 0));
        assertEquals(0, adjustedGray(100, -100, 0));
        assertEquals(255, adjustedGray(200, 100, 0));
    }

    @Test
    @DisplayName("コントラスト: 中間(127.5)を軸に係数 1+値/100 で広げ縮め、0..255に収める")
    void コントラスト() throws IOException {
        assertNearValue(86, adjustedGray(100, 0, 50));
        assertNearValue(236, adjustedGray(200, 0, 50));
        assertNearValue(128, adjustedGray(30, 0, -100));
        assertEquals(0, adjustedGray(20, 0, 100));
        assertEquals(255, adjustedGray(230, 0, 100));
    }

    @Test
    @DisplayName("両方を指定すると、明るさのあとにコントラストを適用する(CSSの並びと同じ)")
    void 明るさのあとにコントラスト() throws IOException {
        assertNearValue(161, adjustedGray(100, 50, 50));
    }

    @Test
    @DisplayName("0・0の調整は画素を変えない。回転と組み合わせても同じ")
    void ゼロは変えない() throws IOException {
        var result = service.applyEdits(gray(100), "image/png", List.of(ROTATE_CW), null, new ImageAdjustment(0, 0));

        assertEquals(100, redOf(result.data()));
        assertEquals(20, result.width());
    }

    @Test
    @DisplayName("調整だけの編集も受け付けられ、画素数は変わらない")
    void 調整だけでも有効() throws IOException {
        var result = service.applyEdits(gray(100), "image/png", null, null, new ImageAdjustment(10, 0));

        assertEquals(40, result.width());
        assertEquals(20, result.height());
    }

    @Test
    @DisplayName("0・0の調整だけでは編集内容が無いので拒否される")
    void ゼロだけは拒否() {
        assertThrows(InvalidImageUploadException.class,
                () -> service.applyEdits(gray(100), "image/png", List.of(), null, new ImageAdjustment(0, 0)));
    }

    @Test
    @DisplayName("回転・切り抜きと組み合わせると、すべて適用され、画素数は回転・切り抜きの結果と一致する")
    void 回転と切り抜きと調整() throws IOException {
        var result = service.applyEdits(gray(100), "image/png", List.of(ROTATE_CW),
                new ImageCropRegion(2, 3, 10, 12), new ImageAdjustment(50, 0));

        assertEquals(10, result.width());
        assertEquals(12, result.height());
        assertNearValue(150, redOf(result.data()));
    }

    @Test
    @DisplayName("透過PNGの透明度は変えない")
    void 透過は保つ() throws IOException {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(20, 10, new Color(100, 100, 100, 128), true));

        var result = service.applyEdits(src, "image/png", List.of(), null, new ImageAdjustment(50, 0));

        int argb = ImageIO.read(new ByteArrayInputStream(result.data())).getRGB(5, 5);
        assertEquals(128, (argb >>> 24) & 0xff);
        assertNearValue(150, (argb >> 16) & 0xff);
    }

    @Test
    @DisplayName("JPEGも調整でき、JPEGのまま")
    void JPEGも調整できる() throws IOException {
        byte[] src = UploadImageFixtures.jpeg(UploadImageFixtures.solid(40, 20, new Color(100, 100, 100), false));

        var result = service.applyEdits(src, "image/jpeg", List.of(), null, new ImageAdjustment(50, 0));

        assertEquals("image/jpeg", result.mimeType());
        assertTrue(redOf(result.data()) > 130);
    }

    @Test
    @DisplayName("-100..100の外は拒否される。境界は有効")
    void 範囲外は拒否() throws IOException {
        for (int[] bad : new int[][] {{101, 0}, {-101, 0}, {0, 101}, {0, -101}}) {
            assertThrows(InvalidImageUploadException.class, () -> service.applyEdits(
                    gray(100), "image/png", List.of(ROTATE_CW), null, new ImageAdjustment(bad[0], bad[1])));
        }
        assertEquals(255, adjustedGray(200, 100, 100));
        assertNearValue(128, adjustedGray(100, -100, -100));
    }

    @Test
    @DisplayName("ImageAdjustment#isIdentity は0・0のときだけtrue")
    void 恒等() {
        assertTrue(new ImageAdjustment(0, 0).isIdentity());
        assertEquals(false, new ImageAdjustment(1, 0).isIdentity());
        assertEquals(false, new ImageAdjustment(0, -1).isIdentity());
    }
}
