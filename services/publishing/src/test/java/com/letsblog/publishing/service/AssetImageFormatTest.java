package com.letsblog.publishing.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** issue #1599: アセット画像のMIME・拡張子は、実際のバイト列(マジックナンバー)から決める。 */
@DisplayName("AssetImageFormat(issue #1599)")
class AssetImageFormatTest {

    @Test
    @DisplayName("JPEGの先頭バイトはimage/jpegと拡張子jpg")
    void jpeg() {
        AssetImageFormat format = AssetImageFormat.detect(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});

        assertEquals("image/jpeg", format.mimeType());
        assertEquals("jpg", format.extension());
    }

    @Test
    @DisplayName("PNGの先頭バイトはimage/pngと拡張子png")
    void png() {
        AssetImageFormat format = AssetImageFormat.detect(
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0});

        assertEquals("image/png", format.mimeType());
        assertEquals("png", format.extension());
    }

    @Test
    @DisplayName("判別できないバイト列・短いバイト列・nullは従来どおりimage/png")
    void 判別できなければpng() {
        assertEquals("image/png", AssetImageFormat.detect(new byte[] {1, 2, 3}).mimeType());
        assertEquals("image/png", AssetImageFormat.detect(new byte[0]).mimeType());
        assertEquals("image/png", AssetImageFormat.detect(null).mimeType());
        assertEquals("png", AssetImageFormat.detect(new byte[] {(byte) 0x89, 'P', 'N', 'X', 0, 0, 0, 0}).extension());
    }

    static Stream<byte[]> JPEGではないバイト列() {
        return Stream.of(
                new byte[] {(byte) 0xFF},
                new byte[] {(byte) 0xFF, (byte) 0xD8},
                new byte[] {(byte) 0xFF, (byte) 0xD8, 0x00},
                new byte[] {(byte) 0xFF, 0x00, (byte) 0xFF},
                new byte[] {0x00, (byte) 0xD8, (byte) 0xFF});
    }

    @ParameterizedTest
    @MethodSource("JPEGではないバイト列")
    @DisplayName("JPEGの先頭3バイトに一致しなければ、従来どおりimage/png")
    void JPEGの先頭に一致しなければpng(byte[] data) {
        assertEquals("image/png", AssetImageFormat.detect(data).mimeType());
    }
}
