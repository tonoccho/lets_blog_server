package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "operation_logs", indexes = {
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_operation_id", columnList = "operation_id"),
        @Index(name = "idx_created_at", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class OperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operation_id", nullable = false, length = 36)
    private String operationId;

    @Column(name = "user_id")
    private Long userId;

    /**
     * JWTのsubクレーム(issue #569)。ローカルUser解決(userId)とは独立に保持し、
     * User未同期・削除済みでも監査証跡の追跡性を保つ。X-Actor-Idヘッダー経由の操作、および
     * V66マイグレーション以前の既存行はnull。
     */
    @Column(name = "actor_keycloak_sub", length = 255)
    private String actorKeycloakSub;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    @Column(name = "path", nullable = false, length = 500)
    private String path;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "duration_ms", nullable = false)
    private Long durationMs;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
