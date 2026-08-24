package com.letsblog.media.render;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;

/**
 * PlantUMLサーバーのURL(/png/&lt;encoded&gt;, /svg/&lt;encoded&gt;)で使われる
 * 独自エンコード(raw deflate + 64文字の専用アルファベット)を実装する。
 * PlantUML公式・各種サードパーティ実装(plantuml.com のJSエンコーダ等)と同一のアルゴリズム。
 */
public final class PlantUmlEncoder {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_".toCharArray();

    private PlantUmlEncoder() {
    }

    public static String encode(String source) {
        byte[] deflated = deflate(source.getBytes(StandardCharsets.UTF_8));
        return encode64(deflated);
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        deflater.setInput(data);
        deflater.finish();

        ByteArrayOutputStream output = new ByteArrayOutputStream(data.length);
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            output.write(buffer, 0, count);
        }
        deflater.end();
        return output.toByteArray();
    }

    private static String encode64(byte[] data) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < data.length; i += 3) {
            if (i + 2 < data.length) {
                append3bytes(result, data[i] & 0xFF, data[i + 1] & 0xFF, data[i + 2] & 0xFF);
            } else if (i + 1 < data.length) {
                append3bytes(result, data[i] & 0xFF, data[i + 1] & 0xFF, 0);
            } else {
                append3bytes(result, data[i] & 0xFF, 0, 0);
            }
        }
        return result.toString();
    }

    private static void append3bytes(StringBuilder out, int b1, int b2, int b3) {
        int c1 = b1 >> 2;
        int c2 = ((b1 & 0x3) << 4) | (b2 >> 4);
        int c3 = ((b2 & 0xF) << 2) | (b3 >> 6);
        int c4 = b3 & 0x3F;
        out.append(ALPHABET[c1 & 0x3F]);
        out.append(ALPHABET[c2 & 0x3F]);
        out.append(ALPHABET[c3 & 0x3F]);
        out.append(ALPHABET[c4 & 0x3F]);
    }
}
