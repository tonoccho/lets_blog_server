package com.letsblog.plugins.diagram;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.junit.jupiter.api.Test;

class PlantUmlEncoderTest {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_";

    /** 参照実装の逆変換(PlantUML 独自の base64 → raw inflate)。 */
    static String decode(String encoded) throws DataFormatException {
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
        byte[] out = new byte[65536];
        int n = inflater.inflate(out);
        inflater.end();
        return new String(out, 0, n, StandardCharsets.UTF_8);
    }

    @Test
    void roundTripsForEveryRemainderOfThreeBytes() throws Exception {
        for (String source : new String[] {"a", "ab", "abc", "abcd", "@startuml\nAlice -> Bob: こんにちは\n@enduml"}) {
            String encoded = PlantUmlEncoder.encode(source);
            assertThat(encoded).matches("[0-9A-Za-z_-]+");
            assertThat(decode(encoded)).isEqualTo(source);
        }
    }
}
