package com.letsblog.project.crypto;

import com.letsblog.project.crypto.SshKeyGenerationService.SshKeyPair;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    // ---- issue #1818: 失敗の分岐 ----

    @Test
    void generateEd25519_コマンドを起動できなければ生成に失敗した例外にする() {
        // コメントにNUL文字を含むとプロセスを起動できない(IOException)。
        SshKeyGenerationService.SshKeyGenerationException e = assertThrows(
                SshKeyGenerationService.SshKeyGenerationException.class, () -> service.generateEd25519("bad\0comment"));

        assertTrue(e.getMessage().startsWith("SSH鍵ペアの生成に失敗しました"));
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void generateEd25519_待っている間に割り込まれたら割り込み状態を戻して失敗した例外にする() {
        Thread.currentThread().interrupt();
        try {
            SshKeyGenerationService.SshKeyGenerationException e = assertThrows(
                    SshKeyGenerationService.SshKeyGenerationException.class, () -> service.generateEd25519("c"));

            assertTrue(e.getMessage().startsWith("SSH鍵ペアの生成に失敗しました"));
            assertTrue(Thread.currentThread().isInterrupted(), "割り込み状態は呼び出し元へ戻る");
        } finally {
            Thread.interrupted();
        }
    }
}
