package com.letsblog.media.service;

import com.letsblog.media.testsupport.UploadImageFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** issue #1717: resizeToLongEdge / resizeToJpeg も画素数の上限を超える画像をデコード前に断る。 */
@DisplayName("ImageResizeService の画素数上限(issue #1717)")
class ImageResizeServicePixelLimitTest {

    private final ImageResizeService service = new ImageResizeService();

    @Test
    @DisplayName("resizeToLongEdge は上限超過を元のバイト列に落とさず断る")
    void resizeToLongEdgeRejects() {
        byte[] bomb = UploadImageFixtures.pngHeaderOnly(30000, 30000);

        assertThrows(InvalidImageUploadException.class, () -> service.resizeToLongEdge(bomb, "image/png", 1300, true));
    }

    @Test
    @DisplayName("stripMetadata 経由でも断る")
    void stripMetadataRejects() {
        byte[] bomb = UploadImageFixtures.pngHeaderOnly(30000, 30000);

        assertThrows(InvalidImageUploadException.class, () -> service.stripMetadata(bomb, "image/png"));
    }

    @Test
    @DisplayName("resizeToJpeg は上限超過を断る")
    void resizeToJpegRejects() {
        byte[] bomb = UploadImageFixtures.pngHeaderOnly(30000, 30000);

        assertThrows(InvalidImageUploadException.class, () -> service.resizeToJpeg(bomb, "image/png", 1300));
    }

    @Test
    @DisplayName("上限以下だがデコードできない画像は従来どおり元のバイト列のまま返す")
    void withinLimitButUndecodablePassesThrough() {
        byte[] header = UploadImageFixtures.pngHeaderOnly(8000, 8000);

        assertArrayEquals(header, service.resizeToLongEdge(header, "image/png", 1300, false).data());
        assertArrayEquals(header, service.resizeToJpeg(header, "image/png", 1300).data());
    }

    @Test
    @DisplayName("ImageIO が読めない形式は従来どおり元のバイト列のまま返す")
    void unreadableFormatPassesThrough() {
        byte[] junk = {1, 2, 3, 4};

        assertArrayEquals(junk, service.resizeToLongEdge(junk, "image/webp", 1300, false).data());
        assertEquals("image/webp", service.resizeToJpeg(junk, "image/webp", 1300).mimeType());
    }
}
