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
 * apiサーバーのFrontendErrorLogエンティティと同じ`frontend_error_logs`テーブルを共有する
 * (スキーマ自体の所有権・マイグレーションはapiサーバー側にある。issue #466)。
 * levelはapi側ではenumだが、ここでは受信したメッセージの文字列をそのまま保存するだけのため
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
