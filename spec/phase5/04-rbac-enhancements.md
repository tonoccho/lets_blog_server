# 04. RBAC(ロールベースアクセス制御)の細粒度化

## 目的

現在のユーザー管理では admin フラグの二値で権限を管理しているが、これを拡張して、より細粒度なロールベースアクセス制御(RBAC)を導入する。複数のロール(admin・editor・viewer等)と権限(permission)を定義し、ユーザーに複数のロールを割り当て可能にすることで、将来的な権限管理の柔軟性と拡張性を確保する。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| ロール体系 | **admin**(全権限) / **editor**(投稿・サイト管理) / **viewer**(閲覧のみ) の3段階 |
| ユーザー - ロール関係 | 多対多(1ユーザーが複数ロールを保有可能) |
| 権限チェック方法 | Spring Security の `@Secured` / `@PreAuthorize` アノテーション |
| 既存 `admin` フィールド | `User.admin` を削除し、ロール移行(ROLE_ADMIN を持つユーザー = 管理者) |
| デフォルトロール | 新規ユーザー登録時は `ROLE_VIEWER`、セルフサインアップ時も同じ |
| ロール変更の監査 | 監査ログに「ユーザーロール変更」を記録 |
| 権限定義 | enum 形式で管理(USER_CREATE, USER_DELETE, POST_CREATE, POST_PUBLISH等) |

## コンポーネント構成

### `Role.java` (エンティティ)

```java
package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_name", nullable = false, unique = true, length = 100)
    private String roleName; // e.g., "ROLE_ADMIN", "ROLE_EDITOR", "ROLE_VIEWER"

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName; // e.g., "管理者", "編集者", "閲覧者"

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @ElementCollection(targetClass = Permission.class)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission")
    private Set<Permission> permissions = new HashSet<>();

    @ManyToMany(mappedBy = "roles")
    private Set<User> users = new HashSet<>();

    public Role(String roleName, String displayName) {
        this.roleName = roleName;
        this.displayName = displayName;
    }
}
```

### `Permission.java` (enum)

```java
package com.letsblog.api.domain;

public enum Permission {
    // ユーザー管理
    USER_CREATE("ユーザー作成"),
    USER_READ("ユーザー閲覧"),
    USER_UPDATE("ユーザー更新"),
    USER_DELETE("ユーザー削除"),
    USER_ROLE_MANAGE("ユーザーロール変更"),

    // 投稿管理
    POST_CREATE("投稿作成"),
    POST_READ("投稿閲覧"),
    POST_UPDATE("投稿更新"),
    POST_DELETE("投稿削除"),
    POST_PUBLISH("投稿公開"),

    // サイト管理
    SITE_CREATE("サイト登録"),
    SITE_READ("サイト閲覧"),
    SITE_UPDATE("サイト設定変更"),
    SITE_DELETE("サイト削除"),

    // 監査ログ
    AUDIT_LOG_VIEW("監査ログ閲覧"),
    AUDIT_LOG_DELETE("監査ログ削除"),

    // システム
    SYSTEM_CONFIG("システム設定変更"),
    ROLE_MANAGE("ロール・権限管理");

    private final String description;

    Permission(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
```

### `User.java` (既存エンティティの修正)

```java
// User.java の既存コードを修正

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {
    // ... 既存フィールド ...

    @Column(name = "admin") // 廃止予定フィールド(マイグレーション期間のみ)
    private Boolean admin = false; // 移行期間のため残置

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
        name = "user_roles",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();

    /**
     * 指定された権限を持つかを判定
     */
    public boolean hasPermission(Permission permission) {
        return roles.stream()
                .flatMap(role -> role.getPermissions().stream())
                .anyMatch(p -> p == permission);
    }

    /**
     * 指定されたロール名を持つかを判定
     */
    public boolean hasRole(String roleName) {
        return roles.stream()
                .anyMatch(role -> role.getRoleName().equals(roleName));
    }

    /**
     * ロールの文字列表現(Spring Security 用)
     */
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority(role.getRoleName()))
                .collect(Collectors.toList());
    }
}
```

### `RoleRepository.java`

```java
package com.letsblog.api.repository;

import com.letsblog.api.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByRoleName(String roleName);
}
```

### `UserRole` 連結テーブル(Flyway で自動生成)

```sql
-- Flyway マイグレーション内で定義
CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
);
```

### `RoleService.java`

```java
package com.letsblog.api.service;

import com.letsblog.api.domain.Role;
import com.letsblog.api.domain.User;
import com.letsblog.api.repository.RoleRepository;
import com.letsblog.api.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class RoleService {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public RoleService(
            RoleRepository roleRepository,
            UserRepository userRepository,
            AuditLogService auditLogService) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * ユーザーにロールを割り当てる
     */
    @Transactional
    public void assignRoleToUser(Long userId, String roleName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("ユーザーが見つかりません"));

        Role role = roleRepository.findByRoleName(roleName)
                .orElseThrow(() -> new RoleNotFoundException("ロール '" + roleName + "' が見つかりません"));

        user.getRoles().add(role);
        userRepository.save(user);

        // 監査ログ記録
        auditLogService.logUserRoleChanged(userId, "assign", roleName);
        log.info("Role {} assigned to user {}", roleName, userId);
    }

    /**
     * ユーザーからロールを削除
     */
    @Transactional
    public void removeRoleFromUser(Long userId, String roleName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("ユーザーが見つかりません"));

        Role role = roleRepository.findByRoleName(roleName)
                .orElseThrow(() -> new RoleNotFoundException("ロール '" + roleName + "' が見つかりません"));

        user.getRoles().remove(role);
        userRepository.save(user);

        // 監査ログ記録
        auditLogService.logUserRoleChanged(userId, "remove", roleName);
        log.info("Role {} removed from user {}", roleName, userId);
    }

    /**
     * ロール一覧を取得
     */
    public List<Role> getAllRoles() {
        return roleRepository.findAll();
    }

    /**
     * ロールを作成(システム初期化時のみ)
     */
    @Transactional
    public Role createRole(String roleName, String displayName, Set<String> permissions) {
        Role role = new Role(roleName, displayName);
        roleRepository.save(role);
        return role;
    }
}
```

### `SecurityConfiguration.java` (Spring Security設定)

```java
package com.letsblog.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration
@EnableMethodSecurity(securedEnabled = true, prePostEnabled = true)
public class SecurityConfiguration {

    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
        return new DefaultMethodSecurityExpressionHandler();
    }
}
```

### 権限チェックの例(コントローラー)

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<List<UserResponse>> listUsers() {
        // 管理者のみアクセス可能
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @PostMapping
    @Secured("ROLE_ADMIN")
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody UserCreateRequest request) {
        // 管理者のみアクセス可能
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(userService.createUser(request));
    }

    @PostMapping("/{userId}/roles/{roleName}")
    @PreAuthorize("hasPermission('ROLE_MANAGE')")
    public ResponseEntity<String> assignRole(
            @PathVariable Long userId,
            @PathVariable String roleName) {
        roleService.assignRoleToUser(userId, roleName);
        return ResponseEntity.ok("ロールが割り当てられました。");
    }
}
```

### Flyway マイグレーション

```sql
-- V7__add_rbac_tables.sql
CREATE TABLE roles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_name VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    description TEXT,
    INDEX idx_role_name (role_name)
);

CREATE TABLE role_permissions (
    role_id BIGINT NOT NULL,
    permission VARCHAR(50) NOT NULL,
    PRIMARY KEY (role_id, permission),
    FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
);

-- デフォルトロールの作成
INSERT INTO roles (role_name, display_name, description) VALUES
('ROLE_ADMIN', '管理者', 'システムの全機能にアクセス可能'),
('ROLE_EDITOR', '編集者', '投稿・サイト管理が可能'),
('ROLE_VIEWER', '閲覧者', 'コンテンツ閲覧のみ可能');

-- デフォルト権限の割り当て
INSERT INTO role_permissions (role_id, permission) SELECT id, 'USER_CREATE' FROM roles WHERE role_name = 'ROLE_ADMIN';
INSERT INTO role_permissions (role_id, permission) SELECT id, 'USER_DELETE' FROM roles WHERE role_name = 'ROLE_ADMIN';
-- ... (admin向けの全権限を列挙)

INSERT INTO role_permissions (role_id, permission) SELECT id, 'POST_CREATE' FROM roles WHERE role_name = 'ROLE_EDITOR';
INSERT INTO role_permissions (role_id, permission) SELECT id, 'POST_PUBLISH' FROM roles WHERE role_name = 'ROLE_EDITOR';

INSERT INTO role_permissions (role_id, permission) SELECT id, 'POST_READ' FROM roles WHERE role_name = 'ROLE_VIEWER';
```

## タスクチェックリスト

- [ ] `Role.java` エンティティ実装
- [ ] `Permission.java` enum 定義
- [ ] `RoleRepository.java` 実装
- [ ] `User.java` に `roles` フィールド追加(多対多リレーション)
- [ ] `User.java` に `hasPermission()`, `hasRole()`, `getAuthorities()` メソッド追加
- [ ] `RoleService.java` 実装(ロール割り当て・削除・一覧取得)
- [ ] `SecurityConfiguration.java` で `@EnableMethodSecurity` を設定
- [ ] 例外クラス実装(`RoleNotFoundException`)
- [ ] Flyway マイグレーション `V7__add_rbac_tables.sql` 作成(デフォルトロール・権限の初期化含む)
- [ ] 既存コードの権限チェック実装(`@PreAuthorize`, `@Secured` アノテーション)
- [ ] 既存ユーザーの admin フラグを ROLE_ADMIN/ROLE_VIEWER に移行(マイグレーションスクリプト)
- [ ] `User.admin` フィールド削除(マイグレーション完了後)
- [ ] `RoleServiceTest` 実装
- [ ] `RoleRepositoryTest` 実装
- [ ] Web フロントエンド: ロール管理画面実装(`/admin/roles`)
  - [ ] ロール一覧表示
  - [ ] ユーザーへのロール割り当て/削除
  - [ ] ロール権限一覧表示
- [ ] 既存権限チェックコード(admin フラグ確認)を ロール・権限ベースに置き換え
- [ ] `./gradlew test` でテスト PASS 確認

## 未決事項

- 権限定義の粒度(現在約15種類、将来の追加方針)
- 動的ロール作成の必要性(現在は固定の3段階)
- 権限とロール の マッピング戦略(データベース vs コード内の enum)
- 既存 admin フィールドの レガシーコード対応期間
- ロール・権限の Web フロントエンド表示言語(日本語 vs 英字ロール名)
- 権限チェック失敗時の UI メッセージ(権限不足の表示内容)
