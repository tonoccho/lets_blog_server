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
 * apiサーバーのAuditLogエンティティと同じ`audit_logs`テーブルを共有する
 * (スキーマ自体の所有権・マイグレーションはapiサーバー側にある。issue #466)。
 * actionはapi側ではAuditLogAction enumだが、ここでは受信したメッセージの文字列を
 * そのまま保存するだけのため単純なStringとして扱う。
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "action", nullable = false, length = 50)
    private String action;

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
}
