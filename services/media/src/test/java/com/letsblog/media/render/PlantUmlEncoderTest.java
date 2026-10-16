package com.letsblog.media.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;

/** {@link PlantUmlEncoder}(issue #1820)。独自アルファベットで符号化した結果を、テスト側の復号で元のソースへ戻せること。 */
class PlantUmlEncoderTest {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_";

    /** PlantUML独自の6ビット文字列をバイト列へ戻し、raw deflateを展開する。 */
    private static String decode(String encoded) throws DataFormatException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int bits = 0;
        int acc = 0;
        for (char c : encoded.toCharArray()) {
            acc = (acc << 6) | ALPHABET.indexOf(c);
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                bytes.write((acc >> bits) & 0xFF);
            }
        }
        Inflater inflater = new Inflater(true);
        inflater.setInput(bytes.toByteArray());
        byte[] out = new byte[1 << 16];
        int n = inflater.inflate(out);
        inflater.end();
        return new String(out, 0, n, StandardCharsets.UTF_8);
    }

    @Test
    void 符号化した結果は元のソースへ戻せる() throws Exception {
        String source = "@startuml\nAlice -> Bob: こんにちは\n@enduml";

        assertThat(decode(PlantUmlEncoder.encode(source))).isEqualTo(source);
    }

    @Test
    void 圧縮後のバイト数が3で割って余り0_1_2のどれになるソースでも元に戻せ_専用アルファベットだけを使う() throws Exception {
        // 短い文字列のraw deflateは1文字増えるごとにほぼ1バイト増えるので、1〜60文字で3つの余りを一巡する。
        StringBuilder source = new StringBuilder();
        for (int length = 1; length <= 60; length++) {
            source.append((char) ('a' + (length * 7) % 26));
            String encoded = PlantUmlEncoder.encode(source.toString());

            assertThat(encoded).hasSizeGreaterThan(0).matches("[0-9A-Za-z_-]+");
            assertThat(encoded.length() % 4).isZero();
            assertThat(decode(encoded)).isEqualTo(source.toString());
        }
    }
}
