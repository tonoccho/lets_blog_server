package com.letsblog.api.crypto;

import com.letsblog.api.crypto.SshKeyGenerationService.SshKeyPair;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ホストの実際の`ssh-keygen`コマンドを呼び出す(モック不可、CIホストにopenssh-clientが必要)。
 */
class SshKeyGenerationServiceTest {

    private final SshKeyGenerationService service = new SshKeyGenerationService();

    @Test
    void generateEd25519_有効なOpenSSH形式の鍵ペアを生成する() {
        SshKeyPair keyPair = service.generateEd25519("test@letsblog");

        assertTrue(keyPair.privateKeyPem().contains("BEGIN OPENSSH PRIVATE KEY"));
        assertTrue(keyPair.privateKeyPem().contains("END OPENSSH PRIVATE KEY"));
        assertTrue(keyPair.publicKeyLine().startsWith("ssh-ed25519 "));
        assertTrue(keyPair.publicKeyLine().endsWith("test@letsblog"));
    }

    @Test
    void generateEd25519_コメントがnullでも鍵ペアを生成できる() {
        SshKeyPair keyPair = service.generateEd25519(null);

        assertTrue(keyPair.privateKeyPem().contains("BEGIN OPENSSH PRIVATE KEY"));
        assertTrue(keyPair.publicKeyLine().startsWith("ssh-ed25519 "));
    }

    @Test
    void generateEd25519_呼び出すたびに異なる鍵ペアを生成する() {
        SshKeyPair first = service.generateEd25519("a");
        SshKeyPair second = service.generateEd25519("b");

        assertEquals(false, first.publicKeyLine().equals(second.publicKeyLine()));
        assertEquals(false, first.privateKeyPem().equals(second.privateKeyPem()));
    }
}
