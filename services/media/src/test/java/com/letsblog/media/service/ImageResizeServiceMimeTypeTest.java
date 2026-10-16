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

/**
 * 申告されたMIMEタイプ(null・{@code image/jpg}・大文字小文字)ごとの扱い(issue #1819で分岐を通すために追加)。
 */
@DisplayName("ImageResizeService: MIMEタイプの扱い")
class ImageResizeServiceMimeTypeTest {

    private final ImageResizeService service = new ImageResizeService();

    @Test
    @DisplayName("MIMEタイプが分からない(null)画像は、PNGとして縮小して書き出す")
    void MIMEタイプがnull() throws IOException {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(400, 200, Color.RED, false));

        ImageResizeService.ResizeResult result = service.resizeToLongEdge(src, null, 100, false);

        assertEquals("image/png", result.mimeType());
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(result.data()));
        assertEquals(100, out.getWidth());
        assertEquals(50, out.getHeight());
    }

    @Test
    @DisplayName("MIMEタイプがimage/gifなら、デコードせず元のバイト列を返す")
    void GIFはそのまま() {
        byte[] gif = UploadImageFixtures.gif(40, 20);

        ImageResizeService.ResizeResult result = service.resizeToLongEdge(gif, "IMAGE/GIF", 10, false);

        assertArrayEquals(gif, result.data());
        assertEquals("IMAGE/GIF", result.mimeType());
    }

    @Test
    @DisplayName("image/jpg(非標準の綴り)もJPEGとして扱い、JPEGで書き出す")
    void image_jpgもJPEG() {
        byte[] src = UploadImageFixtures.jpeg(UploadImageFixtures.solid(80, 40, Color.RED, false));

        ImageResizeService.ReencodedImage result = service.reencodeKeepingResolution(src, "image/jpg");

        assertEquals("image/jpeg", result.mimeType());
        assertEquals(80, result.width());
    }

    @Test
    @DisplayName("大文字のIMAGE/JPEGもJPEGとして扱う")
    void 大文字のJPEG() {
        byte[] src = UploadImageFixtures.jpeg(UploadImageFixtures.solid(80, 40, Color.RED, false));

        assertEquals("image/jpeg", service.reencodeKeepingResolution(src, "IMAGE/JPEG").mimeType());
    }

    @Test
    @DisplayName("MIMEタイプがnullのまま再エンコードすると、PNGで書き出す")
    void 再エンコードのMIMEタイプがnull() {
        byte[] src = UploadImageFixtures.png(UploadImageFixtures.solid(80, 40, Color.RED, false));

        ImageResizeService.ReencodedImage result = service.reencodeKeepingResolution(src, null);

        assertEquals("image/png", result.mimeType());
    }
}
