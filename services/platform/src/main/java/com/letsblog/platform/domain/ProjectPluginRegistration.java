package com.letsblog.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * プロジェクト単位のプラグインの登録の上書き(issue #1808、docs/plugins/CONTRACTS.md §4.2)。
 * 項目はシステム全体の{@link PluginRegistration}と同じで、加えて認証情報(CredentialCipherで暗号化した値)を持てる。
 * 認証情報はプロジェクト単位の登録にだけ置ける(ChatGPT / Claudeのキーはプロジェクト単位だけ、という#1568 / #1521の規則)。
 */
@Entity
@Table(name = "project_plugin_registrations")
@Getter
@Setter
@NoArgsConstructor
public class ProjectPluginRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30)
    private PluginKind kind;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "base_url", nullable = false, length = 2048)
    private String baseUrl;

    @Column(name = "in_use", nullable = false)
    private boolean inUse;

    @Column(name = "drawio_url", length = 2048)
    private String drawioUrl;

    @Column(name = "credential_encrypted")
    private byte[] credentialEncrypted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public ProjectPluginRegistration(Long projectId, PluginKind kind, String name, String baseUrl, String drawioUrl) {
        this.projectId = projectId;
        this.kind = kind;
        this.name = name;
        this.baseUrl = baseUrl;
        this.drawioUrl = drawioUrl;
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
