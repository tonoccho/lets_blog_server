package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "frontend_error_logs", indexes = {
        @Index(name = "idx_level", columnList = "level"),
        @Index(name = "idx_created_at", columnList = "created_at"),
        @Index(name = "idx_url", columnList = "url")
})
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
    @Enumerated(EnumType.STRING)
    private ErrorLevel level;

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

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public enum ErrorLevel {
        ERROR, WARN
    }
}
