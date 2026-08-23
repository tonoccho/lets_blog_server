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
 * `frontend_error_logs`テーブルのエンティティ。スキーマ自体の所有権・マイグレーション
 * (lbs_logスキーマ、ADR-0004)は本サービスが持つ(issue #466で導入、#572でlegacy-apiから完全移管)。
 * levelはlegacy-api側ではenumだが、ここでは受信したメッセージの文字列をそのまま保存するだけのため
 * 単純なStringとして扱う。
 */
@Entity
@Table(name = "frontend_error_logs")
@Getter
@Setter
@NoArgsConstructor
public class FrontendErrorLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "stack", columnDefinition = "TEXT")
    private String stack;

    @Column(name = "component_stack", columnDefinition = "TEXT")
    private String componentStack;

    /**
     * JWTから解決したローカルUser id(issue #569)。フロントエンドエラーログは従来actor概念を
     * 持たなかったが、監査ログ・操作ログと同様に追加する。apiサーバー側マイグレーション(V66)で
     * 追加された列で、この側は{@code ddl-auto: none}のためJPAマッピングのみ追随させる。
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * JWTのsubクレーム(issue #569)。ローカルUser解決(userId)とは独立に保持し、
     * User未同期・削除済みでも監査証跡の追跡性を保つ。
     */
    @Column(name = "actor_keycloak_sub", length = 255)
    private String actorKeycloakSub;

    @Column(name = "level", nullable = false, length = 20)
    private String level;

    @Column(name = "context", columnDefinition = "TEXT")
    private String context;

    @Column(name = "url", columnDefinition = "TEXT")
    private String url;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
