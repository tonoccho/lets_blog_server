package com.letsblog.project.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 名前をつけて保存・管理するSSH鍵ペア(Ed25519)。秘密鍵はCredentialCipherで
 * AES-256-GCM暗号化した上でprivateKeyEncryptedへ格納する。
 */
@Entity
@Table(name = "ssh_key_pairs")
@Getter
@Setter
@NoArgsConstructor
public class SshKeyPair {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 255)
    private String comment;

    @Column(name = "public_key_line", nullable = false)
    private String publicKeyLine;

    @Column(name = "private_key_encrypted", nullable = false)
    private byte[] privateKeyEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public SshKeyPair(String name, String comment, String publicKeyLine, byte[] privateKeyEncrypted) {
        this.name = name;
        this.comment = comment;
        this.publicKeyLine = publicKeyLine;
        this.privateKeyEncrypted = privateKeyEncrypted;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
