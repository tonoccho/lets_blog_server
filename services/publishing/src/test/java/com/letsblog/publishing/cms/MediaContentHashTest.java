package com.letsblog.publishing.cms;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaContentHashTest {

    @Test
    void sha256Hex_は小文字16進64桁を返す() {
        // sha256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                MediaContentHash.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void isValid_は小文字16進64桁だけを受け入れる() {
        assertTrue(MediaContentHash.isValid("a".repeat(64)));
        assertFalse(MediaContentHash.isValid(null));
        assertFalse(MediaContentHash.isValid("a".repeat(63)));
        assertFalse(MediaContentHash.isValid("A".repeat(64)));
        assertFalse(MediaContentHash.isValid("g".repeat(64)));
        assertFalse(MediaContentHash.isValid("a".repeat(63) + "'"));
    }
}
