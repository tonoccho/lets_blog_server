package com.letsblog.media.service;

import com.letsblog.media.testsupport.UploadImageFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** issue #1657: タグ付け用コピーをバイト数の上限に収めるため、透過PNGでもJPEGへ縮小して書き出す。 */
@DisplayName("ImageResizeService#resizeToJpeg(issue #1657)")
class ImageResizeServiceJpegTest {

    private final ImageResizeService service = new ImageResizeService();

    @Test
    @DisplayName("長辺が上限を超える透過PNGは、縮小して白背景のJPEGになる")
    void 縮小してJPEGにする() throws IOException {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(400, 200, new Color(0, 0, 0, 0), true));

        ImageResizeService.ResizeResult result = service.resizeToJpeg(src, "image/png", 100);

        assertEquals("image/jpeg", result.mimeType());
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(result.data()));
        assertEquals(100, out.getWidth());
        assertEquals(50, out.getHeight());
        Color pixel = new Color(out.getRGB(50, 25));
        assertTrue(pixel.getRed() > 240 && pixel.getGreen() > 240 && pixel.getBlue() > 240, "transparent -> white");
    }

    @Test
    @DisplayName("長辺が上限以下なら拡大せず、寸法はそのままJPEGにする")
    void 小さい画像は寸法を変えない() throws IOException {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(80, 40, Color.RED, false));

        ImageResizeService.ResizeResult result = service.resizeToJpeg(src, "image/png", 100);

        assertEquals("image/jpeg", result.mimeType());
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(result.data()));
        assertEquals(80, out.getWidth());
        assertEquals(40, out.getHeight());
    }

    @Test
    @DisplayName("デコードできないバイト列は、そのまま返す")
    void デコードできなければ元のまま() {
        byte[] garbage = {1, 2, 3, 4};

        ImageResizeService.ResizeResult result = service.resizeToJpeg(garbage, "image/png", 100);

        assertArrayEquals(garbage, result.data());
        assertEquals("image/png", result.mimeType());
    }

    @Test
    @DisplayName("nullなど処理中に例外になる入力も、元のものをそのまま返す")
    void 例外は元のまま() {
        ImageResizeService.ResizeResult result = service.resizeToJpeg(null, "image/png", 100);

        assertEquals(null, result.data());
        assertEquals("image/png", result.mimeType());
    }
}
