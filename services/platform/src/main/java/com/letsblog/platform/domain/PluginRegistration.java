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
 * システム全体のプラグインの登録(issue #1807、docs/plugins/CONTRACTS.md §4.2)。
 * 認証情報は持たない(システム全体の登録は認証情報を持たない。ADR-0012)。
 */
@Entity
@Table(name = "plugin_registrations")
@Getter
@Setter
@NoArgsConstructor
public class PluginRegistration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public PluginRegistration(PluginKind kind, String name, String baseUrl, String drawioUrl) {
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
