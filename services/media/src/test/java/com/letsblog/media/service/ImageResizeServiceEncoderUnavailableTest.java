package com.letsblog.media.service;

import com.letsblog.media.testsupport.ImageWriters;
import com.letsblog.media.testsupport.UploadImageFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JPEG・PNGの書き出し器が実行環境に無いときの{@code ImageResizeService}(issue #1819で分岐を通すために追加)。
 *
 * <p>JDKの画像書き出し器をテストの間だけ外し({@link ImageWriters})、書き出せないときは
 * アップロードの変換なら利用者向けのエラー、メタ情報の除去・縮小なら元のバイト列のまま扱うことを確かめる。
 * 書き出し器は処理の後で必ず元に戻すので、他のテストには影響しない。
 */
@DisplayName("ImageResizeService: 書き出し器が使えない環境")
class ImageResizeServiceEncoderUnavailableTest {

    private final ImageResizeService service = new ImageResizeService();
    private final byte[] jpeg = UploadImageFixtures.jpeg(UploadImageFixtures.solid(80, 40, Color.RED, false));
    private final byte[] png = UploadImageFixtures.png(UploadImageFixtures.solid(80, 40, Color.RED, false));

    @Test
    @DisplayName("JPEGの書き出し器が無ければ、JPEGの縮小は元のバイト列を返す")
    void JPEGの縮小は元のまま() {
        ImageWriters.without("jpeg", () -> {
            ImageResizeService.ResizeResult result = service.resizeToLongEdge(jpeg, "image/jpeg", 40, false);

            assertArrayEquals(jpeg, result.data());
            assertEquals("image/jpeg", result.mimeType());
        });
    }

    @Test
    @DisplayName("JPEGの書き出し器が無ければ、JPEG化は元のバイト列とMIMEタイプを返す")
    void JPEG化は元のまま() {
        ImageWriters.without("jpeg", () -> {
            ImageResizeService.ResizeResult result = service.resizeToJpeg(png, "image/png", 40);

            assertArrayEquals(png, result.data());
            assertEquals("image/png", result.mimeType());
        });
    }

    @Test
    @DisplayName("JPEGの書き出し器が無ければ、JPEGのアップロードの変換は利用者向けのエラーにする")
    void JPEGのアップロードはエラー() {
        ImageWriters.without("jpeg", () -> {
            InvalidImageUploadException e = assertThrows(InvalidImageUploadException.class,
                    () -> service.reencodeKeepingResolution(jpeg, "image/jpeg"));

            assertEquals("画像を変換できませんでした。", e.getMessage());
        });
    }

    @Test
    @DisplayName("PNGの書き出し器が無ければ、PNGの縮小は元のバイト列を返し、アップロードの変換はエラーにする")
    void PNGの書き出し器が無い() {
        ImageWriters.without("png", () -> {
            ImageResizeService.ResizeResult result = service.resizeToLongEdge(png, "image/png", 40, false);
            assertArrayEquals(png, result.data());
            assertEquals("image/png", result.mimeType());

            assertThrows(InvalidImageUploadException.class,
                    () -> service.reencodeKeepingResolution(png, "image/png"));
        });
    }

    @Test
    @DisplayName("書き出し器は処理の後で元に戻り、通常どおり書き出せる")
    void 書き出し器は元に戻る() {
        ImageWriters.without("jpeg", () -> { });
        ImageWriters.without("png", () -> { });

        assertNotNull(service.reencodeKeepingResolution(jpeg, "image/jpeg").data());
        assertEquals("image/png", service.reencodeKeepingResolution(png, "image/png").mimeType());
    }
}
