package com.letsblog.common.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void 暗号化した値を復号すると元の平文に戻る() {
        CredentialCipher cipher = new CredentialCipher(KEY);

        byte[] encrypted = cipher.encrypt("hunter2");

        assertEquals("hunter2", cipher.decrypt(encrypted));
    }

    @Test
    void 暗号化するたびに異なるIVで異なる暗号文になる() {
        CredentialCipher cipher = new CredentialCipher(KEY);

        byte[] first = cipher.encrypt("hunter2");
        byte[] second = cipher.encrypt("hunter2");

        assertNotEquals(Base64.getEncoder().encodeToString(first), Base64.getEncoder().encodeToString(second));
    }

    @Test
    void 鍵が32バイトでない場合は例外を投げる() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThrows(IllegalStateException.class, () -> new CredentialCipher(shortKey));
    }

    @Test
    void 鍵が未設定の場合は例外を投げる() {
        assertThrows(IllegalStateException.class, () -> new CredentialCipher(""));
    }
}
