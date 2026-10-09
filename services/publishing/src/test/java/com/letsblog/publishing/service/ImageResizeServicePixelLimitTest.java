package com.letsblog.publishing.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** issue #1717: 画素数の上限(6,400万画素)を超える画像は、デコードせずに断る。 */
@DisplayName("ImageResizeService の画素数上限(issue #1717)")
class ImageResizeServicePixelLimitTest {

    private final ImageResizeService service = new ImageResizeService();

    @Test
    @DisplayName("上限を超える画像は、元のバイト列に落とさず例外で断る")
    void rejectsOversizedImage() {
        byte[] bomb = OversizedImageFixtures.pngHeaderOnly(30000, 30000);

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> service.resizeToLongEdge(bomb, "image/png", 1300, true));

        assertTrue(e.getMessage().contains("画素数"), e.getMessage());
    }

    @Test
    @DisplayName("stripMetadata 経由でも断る")
    void rejectsOversizedImageViaStripMetadata() {
        byte[] bomb = OversizedImageFixtures.pngHeaderOnly(30000, 30000);

        assertThrows(RuntimeException.class, () -> service.stripMetadata(bomb, "image/png"));
    }

    @Test
    @DisplayName("上限ちょうど(8000x8000)はデコード可否の判定で断らない(IDATが無いので従来どおり元のまま)")
    void atLimitIsNotRejected() {
        byte[] header = OversizedImageFixtures.pngHeaderOnly(8000, 8000);

        assertArrayEquals(header, service.resizeToLongEdge(header, "image/png", 1300));
    }

    @Test
    @DisplayName("読めない形式は従来どおり元のバイト列のまま返す")
    void unreadableFormatPassesThrough() {
        byte[] junk = {1, 2, 3, 4};

        assertArrayEquals(junk, service.resizeToLongEdge(junk, "image/webp", 1300));
    }

    @Test
    @DisplayName("GIFは画素数が大きく見えても素通しする")
    void gifPassesThrough() {
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a'};

        assertEquals(gif, service.resizeToLongEdge(gif, "image/gif", 1300, true).data());
    }
}
