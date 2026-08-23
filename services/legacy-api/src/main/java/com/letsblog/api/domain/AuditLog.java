package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_action", columnList = "action"),
        @Index(name = "idx_created_at", columnList = "created_at"),
        @Index(name = "idx_resource_type_id", columnList = "resource_type, resource_id")
})
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    /**
     * JWTのsubクレーム(issue #569)。ローカルUser解決(userId)とは独立に保持し、
     * User未同期・削除済みでも監査証跡の追跡性を保つ。X-Actor-Idヘッダー経由の操作、および
     * V66マイグレーション以前の既存行はnull。
     */
    @Column(name = "actor_keycloak_sub", length = 255)
    private String actorKeycloakSub;

    @Column(name = "action", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private AuditLogAction action;

    @Column(name = "resource_type", length = 50)
    private String resourceType;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "changes", columnDefinition = "TEXT")
    private String changes;

    @Column(name = "remote_ip", length = 45)
    private String remoteIp;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
