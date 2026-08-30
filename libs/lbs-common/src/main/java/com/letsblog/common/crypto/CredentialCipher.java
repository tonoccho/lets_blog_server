package com.letsblog.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * WordPressアプリケーションパスワード等の秘匿情報をAES-256-GCMで暗号化/復号する。
 * 鍵は APP_ENCRYPTION_KEY(Base64エンコードされた32バイト値)から取得する。
 * 全サービスが同じ鍵で復号できるよう、サービス間で共有する。
 */
@Component
public class CredentialCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int IV_LENGTH_BYTES = 12;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(@Value("${app.encryption-key}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("app.encryption-key (APP_ENCRYPTION_KEY) が設定されていません");
        }
        byte[] decoded = Base64.getDecoder().decode(base64Key);
        if (decoded.length != 32) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY は32バイト(Base64エンコード)である必要があります");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public byte[] encrypt(String plainText) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes());

            return ByteBuffer.allocate(IV_LENGTH_BYTES + cipherText.length)
                    .put(iv)
                    .put(cipherText)
                    .array();
        } catch (Exception e) {
            throw new IllegalStateException("暗号化に失敗しました", e);
        }
    }

    public String decrypt(byte[] stored) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(stored);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            buffer.get(iv);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText));
        } catch (Exception e) {
            throw new IllegalStateException("復号に失敗しました", e);
        }
    }
}
