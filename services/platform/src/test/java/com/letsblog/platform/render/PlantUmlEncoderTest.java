package com.letsblog.platform.render;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlantUmlEncoder}。PlantUML独自の64進(0-9A-Za-z-_)で符号化した結果を、テスト側の復号(64進 → raw deflate → UTF-8)で
 * 元の文字列に戻せる。入力の長さを変えて、3バイトずつの符号化の端数(余り0・1・2バイト)をすべて通す。
 */
class PlantUmlEncoderTest {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_";

    private static String decode(String encoded) throws DataFormatException {
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        for (int i = 0; i < encoded.length(); i += 4) {
            int c1 = ALPHABET.indexOf(encoded.charAt(i));
            int c2 = ALPHABET.indexOf(encoded.charAt(i + 1));
            int c3 = ALPHABET.indexOf(encoded.charAt(i + 2));
            int c4 = ALPHABET.indexOf(encoded.charAt(i + 3));
            deflated.write((c1 << 2) | (c2 >> 4));
            deflated.write(((c2 & 0xF) << 4) | (c3 >> 2));
            deflated.write(((c3 & 0x3) << 6) | c4);
        }
        Inflater inflater = new Inflater(true);
        byte[] input = deflated.toByteArray();
        // 端数の分だけ末尾に0が付くが、raw deflateの終端以降は読まれない。
        inflater.setInput(input);
        byte[] out = new byte[4096];
        int n = inflater.inflate(out);
        inflater.end();
        return new String(out, 0, n, StandardCharsets.UTF_8);
    }

    @Test
    void 長さの違う入力をすべて元の文字列に戻せる() throws DataFormatException {
        for (String source : new String[] {"A", "AB", "ABC", "ABCD", "@startuml\nA->B\n@enduml", "日本語の図", "x".repeat(50)}) {
            String encoded = PlantUmlEncoder.encode(source);

            assertTrue(encoded.matches("[0-9A-Za-z_-]+"), encoded);
            assertEquals(0, encoded.length() % 4, encoded);
            assertEquals(source, decode(encoded), source);
        }
    }
}
