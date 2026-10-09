package com.letsblog.publishing.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/** IHDRだけが宣言する寸法の、デコードされない(IDATを持たない)PNG。画素数上限の検査用(issue #1717)。 */
final class OversizedImageFixtures {

    private OversizedImageFixtures() {
    }

    static byte[] pngHeaderOnly(int width, int height) {
        byte[] ihdr = {
                (byte) (width >> 24), (byte) (width >> 16), (byte) (width >> 8), (byte) width,
                (byte) (height >> 24), (byte) (height >> 16), (byte) (height >> 8), (byte) height,
                8, 2, 0, 0, 0};
        byte[] type = "IHDR".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        out.writeBytes(new byte[] {0, 0, 0, 13});
        out.writeBytes(type);
        out.writeBytes(ihdr);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(ihdr);
        long value = crc.getValue();
        out.writeBytes(new byte[] {(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value});
        return out.toByteArray();
    }
}
