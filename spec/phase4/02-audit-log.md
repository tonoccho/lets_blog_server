# 02. 監査ログ機能

## 目的

主要な操作(ログイン・ユーザー管理・投稿・サイト登録等)をシステム全体で追跡可能なログとして記録し、「いつ、誰が、何をしたのか」をコンプライアンス・セキュリティ・トラブルシューティング目的で後から確認できる機能を実装する。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| ログ記録対象 | 認証(ログイン・ログアウト), ユーザー管理(作成/更新/削除), 投稿(作成/更新/削除/公開), サイト登録 |
| 記録方法 | AOP Aspect で `@AuditLog` アノテーション付きメソッドを自動横取り |
| 記録内容 | ユーザーID, 操作種別, リソース種別/ID, 変更内容(JSON), タイムスタンプ, リモートIP(optional) |
| 保有期間 | 1年間(超過分の自動削除は Phase 5 以降) |
| アクセス制御 | admin のみが閲覧可能 |
| パフォーマンス | 非同期記録(Spring の `@Async` で IO ブロックを避ける)は Phase 5 での検討課題 |

## コンポーネント構成

### `AuditLogAction.java` (操作種別 enum)

```java
package com.letsblog.api.domain;

public enum AuditLogAction {
    LOGIN("ログイン"),
    LOGOUT("ログアウト"),
    USER_CREATED("ユーザー作成"),
    USER_UPDATED("ユーザー更新"),
    USER_DELETED("ユーザー削除"),
    USER_ROLE_CHANGED("ユーザーロール変更"),
    POST_CREATED("投稿作成"),
    POST_UPDATED("投稿更新"),
    POST_DELETED("投稿削除"),
    POST_PUBLISHED("投稿公開"),
    SITE_REGISTERED("サイト登録"),
    PASSWORD_RESET_REQUESTED("パスワード再設定リクエスト"),
    PASSWORD_RESET_CONFIRMED("パスワード再設定完了");

    private final String displayName;

    AuditLogAction(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
```

### `AuditLog.java` (エンティティ)

```java
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

    @Column(name = "action", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private AuditLogAction action;

    @Column(name = "resource_type", length = 50)
    private String resourceType; // e.g. "USER", "POST", "SITE"

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "changes", columnDefinition = "TEXT")
    private String changes; // JSON形式: {"field": "oldValue", "newValue": "newValue"}

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
```

### `AuditLogRepository.java`

```java
package com.letsblog.api.repository;

import com.letsblog.api.domain.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    Page<AuditLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<AuditLog> findByActionOrderByCreatedAtDesc(AuditLogAction action, Pageable pageable);

    Page<AuditLog> findByResourceTypeAndResourceIdOrderByCreatedAtDesc(String resourceType, Long resourceId, Pageable pageable);

    Page<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime start, LocalDateTime end, Pageable pageable);

    List<AuditLog> findByCreatedAtBefore(LocalDateTime threshold);
}
```

### `AuditLogService.java`

```java
package com.letsblog.api.service;

import com.letsblog.api.domain.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.repository.AuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 監査ログを記録する。
     */
    @Transactional
    public void log(Long userId, AuditLogAction action, String resourceType,
                    Long resourceId, String changes, String remoteIp, String userAgent) {
        AuditLog log = new AuditLog();
        log.setUserId(userId);
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setChanges(changes);
        log.setRemoteIp(remoteIp);
        log.setUserAgent(userAgent);

        auditLogRepository.save(log);
        logInternal("Audit log recorded: action={}, userId={}, resource={}/{}", action, userId, resourceType, resourceId);
    }

    /**
     * 特定ユーザーの監査ログを取得(ページネーション)。
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> findByUserId(Long userId, Pageable pageable) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    /**
     * 特定の操作種別のログを取得。
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> findByAction(AuditLogAction action, Pageable pageable) {
        return auditLogRepository.findByActionOrderByCreatedAtDesc(action, pageable);
    }

    /**
     * 特定のリソースに関連するログを取得。
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> findByResource(String resourceType, Long resourceId, Pageable pageable) {
        return auditLogRepository.findByResourceTypeAndResourceIdOrderByCreatedAtDesc(resourceType, resourceId, pageable);
    }

    /**
     * 期間内のログを取得。
     */
    @Transactional(readOnly = true)
    public Page<AuditLog> findByDateRange(LocalDateTime start, LocalDateTime end, Pageable pageable) {
        return auditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable);
    }

    /**
     * 1年以上前のログを削除(スケジューラーから定期実行)。
     */
    @Transactional
    public void deleteOldLogs() {
        LocalDateTime threshold = LocalDateTime.now().minus(365, ChronoUnit.DAYS);
        var oldLogs = auditLogRepository.findByCreatedAtBefore(threshold);
        if (!oldLogs.isEmpty()) {
            auditLogRepository.deleteAll(oldLogs);
            logInternal("Deleted {} old audit logs before {}", oldLogs.size(), threshold);
        }
    }

    private void logInternal(String message, Object... args) {
        log.info(message, args);
    }
}
```

### `AuditLog.java` アノテーション

```java
package com.letsblog.api.aop;

import com.letsblog.api.domain.AuditLogAction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {
    AuditLogAction action();

    String resourceType() default "";

    String resourceIdParamName() default "id"; // メソッドパラメータから resourceId を取り出すための名前
}
```

### `AuditLogAspect.java` (AOP Aspect)

```java
package com.letsblog.api.aop;

import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.service.AuditLogService;
import com.letsblog.api.service.CurrentUserService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Arrays;

@Aspect
@Component
@Slf4j
public class AuditLogAspect {

    private final AuditLogService auditLogService;
    private final CurrentUserService currentUserService; // ログイン中のユーザーID取得用

    public AuditLogAspect(AuditLogService auditLogService, CurrentUserService currentUserService) {
        this.auditLogService = auditLogService;
        this.currentUserService = currentUserService;
    }

    @Around("@annotation(com.letsblog.api.aop.AuditLog)")
    public Object auditLogAdvice(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = getMethod(joinPoint);
        AuditLog annotation = method.getAnnotation(AuditLog.class);

        Object result = joinPoint.proceed();

        try {
            Long userId = currentUserService.getCurrentUserId();
            if (userId == null) {
                return result; // ユーザー非ログイン状態(パスワード再設定等)では記録しない、またはnullを記録
            }

            AuditLogAction action = annotation.action();
            String resourceType = annotation.resourceType();
            Long resourceId = extractResourceId(joinPoint, annotation.resourceIdParamName());

            String remoteIp = getRemoteIp();
            String userAgent = getUserAgent();

            String changes = buildChanges(result);

            auditLogService.log(userId, action, resourceType, resourceId, changes, remoteIp, userAgent);
        } catch (Exception e) {
            log.warn("Failed to record audit log", e);
        }

        return result;
    }

    private Method getMethod(ProceedingJoinPoint joinPoint) throws NoSuchMethodException {
        String methodName = joinPoint.getSignature().getName();
        Class<?>[] argTypes = Arrays.stream(joinPoint.getArgs())
                .map(Object::getClass)
                .toArray(Class<?>[]::new);
        return joinPoint.getTarget().getClass().getMethod(methodName, argTypes);
    }

    private Long extractResourceId(ProceedingJoinPoint joinPoint, String paramName) {
        String[] paramNames = ((org.aspectj.lang.reflect.MethodSignature) joinPoint.getSignature()).getParameterNames();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < paramNames.length; i++) {
            if (paramNames[i].equals(paramName)) {
                Object arg = args[i];
                if (arg instanceof Long) {
                    return (Long) arg;
                } else if (arg instanceof Integer) {
                    return ((Integer) arg).longValue();
                }
            }
        }
        return null;
    }

    private String buildChanges(Object result) {
        if (result == null) {
            return null;
        }
        // JSON シリアライズ(ObjectMapper 使用)
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
        } catch (Exception e) {
            return result.toString();
        }
    }

    private String getRemoteIp() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        String clientIp = request.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isEmpty()) {
            clientIp = request.getRemoteAddr();
        }
        return clientIp;
    }

    private String getUserAgent() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        return attributes.getRequest().getHeader("User-Agent");
    }
}
```

### `AuditLogController.java` (管理画面用API)

```java
package com.letsblog.api.controller;

import com.letsblog.api.domain.AuditLog;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.service.AuditLogService;
import com.letsblog.api.service.AdminAuthorizationService; // admin チェック
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/audit-logs")
@Slf4j
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final AdminAuthorizationService adminAuthorizationService;

    public AuditLogController(AuditLogService auditLogService, AdminAuthorizationService adminAuthorizationService) {
        this.auditLogService = auditLogService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /**
     * 監査ログ一覧(ページネーション付き)。
     * admin のみアクセス可能。
     */
    @GetMapping
    public ResponseEntity<Page<AuditLog>> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) AuditLogAction action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            Pageable pageable) {

        adminAuthorizationService.checkAdmin(); // admin チェック

        Page<AuditLog> logs;

        if (userId != null) {
            logs = auditLogService.findByUserId(userId, pageable);
        } else if (action != null) {
            logs = auditLogService.findByAction(action, pageable);
        } else if (resourceType != null && resourceId != null) {
            logs = auditLogService.findByResource(resourceType, resourceId, pageable);
        } else if (startDate != null && endDate != null) {
            logs = auditLogService.findByDateRange(startDate, endDate, pageable);
        } else {
            // デフォルト: 全ログを時系列逆順
            logs = auditLogService.findByDateRange(
                    LocalDateTime.now().minusMonths(1),
                    LocalDateTime.now().plusDays(1),
                    pageable);
        }

        return ResponseEntity.ok(logs);
    }
}
```

### Flyway マイグレーション

```sql
-- V5__add_audit_logs.sql
CREATE TABLE audit_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT,
    action VARCHAR(50) NOT NULL,
    resource_type VARCHAR(50),
    resource_id BIGINT,
    changes TEXT,
    remote_ip VARCHAR(45),
    user_agent TEXT,
    created_at DATETIME NOT NULL,
    INDEX idx_user_id (user_id),
    INDEX idx_action (action),
    INDEX idx_created_at (created_at),
    INDEX idx_resource_type_id (resource_type, resource_id)
);
```

## タスクチェックリスト

- [ ] `AuditLogAction.java` enum 実装
- [ ] `AuditLog.java` エンティティ実装
- [ ] `AuditLogRepository.java` 実装
- [ ] Flyway マイグレーション `V5__add_audit_logs.sql` 作成
- [ ] `AuditLogService.java` 実装(ログ記録・検索・削除)
- [ ] `@AuditLog` アノテーション実装
- [ ] `AuditLogAspect.java` AOP Aspect 実装
- [ ] `AuditLogController.java` 実装(admin のみアクセス可能なAPI)
- [ ] `AdminAuthorizationService` 実装(admin ロールチェック)
- [ ] `CurrentUserService` 実装(ログイン中のユーザーID取得)
- [ ] 既存の主要メソッドに `@AuditLog` アノテーション追加
  - [ ] `UserService.login()`
  - [ ] `UserService.create()`
  - [ ] `UserService.update()`
  - [ ] `UserService.delete()`
  - [ ] `PostPublishService.publishPost()`
  - [ ] `SiteController.registerSite()`
  - [ ] `PasswordResetService.requestPasswordReset()`
  - [ ] `PasswordResetService.confirmPasswordReset()`
- [ ] `AuditLogServiceTest` 実装(ログ記録・検索・削除)
- [ ] `AuditLogAspectTest` 実装
- [ ] Web 管理画面に監査ログ閲覧ページ追加
  - [ ] フィルタ(ユーザー/操作種別/期間)
  - [ ] ページネーション
  - [ ] CSV 出力(optional, Phase 5 以降)
- [ ] スケジューラー実装: 1年以上前のログを定期削除(cron job)
- [ ] `./gradlew test` でテスト PASS 確認

## 未決事項

- ユーザー非ログイン状態(パスワード再設定メールアドレス入力等)での監査ログ記録の要件
- `changes` フィールドの詳細仕様(差分のみ記録 vs 更新後の全体JSON)
- 監査ログ削除後の復旧要件(外部ストレージへのバックアップが必要か)
- 大量監査ログによるDB パフォーマンス影響の想定と対策(インデックス設計・アーカイブ戦略)
- 監査ログの暗号化要件(特に変更内容に個人情報が含まれる場合)
- 監査ログの非同期記録化(Spring の `@Async`による IO ノンブロッキング)
