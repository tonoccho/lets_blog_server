package com.letsblog.logwriter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * `operation_logs`テーブルのエンティティ。スキーマ自体の所有権・マイグレーション(lbs_logスキーマ、
 * ADR-0004)は本サービスが持つ(issue #466で導入、#572でlegacy-apiから完全移管)。
 */
@Entity
@Table(name = "operation_logs")
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
     * User未同期・削除済みでも監査証跡の追跡性を保つ。apiサーバー側マイグレーション(V66)で
     * 追加された列で、この側は{@code ddl-auto: none}のためJPAマッピングのみ追随させる。
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
}
