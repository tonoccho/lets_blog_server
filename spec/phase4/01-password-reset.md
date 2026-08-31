# 01. パスワード再設定機能

## 目的

ユーザーが自身のパスワードを忘れた場合、メール認証を通じてセキュアにパスワードをリセットできる機能を実装する。

現状ではadminが直接パスワード変更できるのみで、ユーザー自身は忘れたパスワードを回復できない。本機能により、管理者の手を借りずにセルフサービスでのリセットを実現する。

## 前提・決定事項

- トークン有効期限: **24時間**
- トークンの一意性: UUID v4(ランダムで重複リスク無視できる)
- パスワード再設定完了後は使用済みトークンを無効化し、同じトークンの再利用を防止
- パスワード要件: 8文字以上(既存の`UserCreateRequest`と同じ)
- 複数パスワード再設定リクエストがあった場合: 新しいトークンが前のトークンを置き換える(前回のトークルはその時点で無効化)

## コンポーネント構成

### `PasswordResetToken.java` (新規エンティティ)

```java
package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "password_reset_tokens")
@Getter
@Setter
@NoArgsConstructor
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token", nullable = false, unique = true, length = 255)
    private String token;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used", nullable = false)
    private Boolean used = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
```

### `PasswordResetTokenRepository.java` (新規リポジトリ)

```java
package com.letsblog.api.repository;

import com.letsblog.api.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByToken(String token);
    Optional<PasswordResetToken> findByUserIdAndUsedFalse(Long userId);
    void deleteByUserIdAndUsedTrue(Long userId);
}
```

### `PasswordResetService.java` (新規サービス)

```java
package com.letsblog.api.service;

import com.letsblog.api.domain.PasswordResetToken;
import com.letsblog.api.domain.User;
import com.letsblog.api.dto.PasswordResetRequest;
import com.letsblog.api.dto.PasswordResetConfirmRequest;
import com.letsblog.api.repository.PasswordResetTokenRepository;
import com.letsblog.api.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@Slf4j
public class PasswordResetService {

    private static final int TOKEN_EXPIRY_HOURS = 24;

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;

    @Value("${app.mail.from:noreply@letsblog.example.com}")
    private String fromEmail;

    @Value("${app.web.base-url:http://localhost:3000}")
    private String baseUrl;

    public PasswordResetService(
            PasswordResetTokenRepository tokenRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JavaMailSender mailSender) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
    }

    /**
     * パスワード再設定リクエストを処理し、再設定用メールを送信する。
     * 前回のトークンが未使用状態にあればそれを無効化し、新しいトークンを生成する。
     */
    @Transactional
    public void requestPasswordReset(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("メールアドレス '" + email + "' は登録されていません"));

        // 既存の未使用トークンがあれば無効化
        tokenRepository.findByUserIdAndUsedFalse(user.getId())
                .ifPresent(existingToken -> {
                    existingToken.setUsed(true);
                    tokenRepository.save(existingToken);
                });

        // 新しいトークンを生成
        String token = UUID.randomUUID().toString();
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.setToken(token);
        resetToken.setUserId(user.getId());
        resetToken.setExpiresAt(LocalDateTime.now().plusHours(TOKEN_EXPIRY_HOURS));
        resetToken.setUsed(false);

        tokenRepository.save(resetToken);

        // メール送信
        sendResetEmail(user.getEmail(), token);
        log.info("Password reset token generated for user: {}", user.getId());
    }

    /**
     * トークンを検証して、新しいパスワードを設定する。
     */
    @Transactional
    public void confirmPasswordReset(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("パスワードは8文字以上である必要があります");
        }

        PasswordResetToken resetToken = tokenRepository.findByToken(token)
                .orElseThrow(() -> new InvalidTokenException("無効な再設定トークンです"));

        if (resetToken.getUsed()) {
            throw new InvalidTokenException("このトークンは既に使用済みです");
        }

        if (LocalDateTime.now().isAfter(resetToken.getExpiresAt())) {
            throw new InvalidTokenException("再設定トークンの有効期限が切れています");
        }

        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new UserNotFoundException("ユーザーが見つかりません"));

        // パスワード更新
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // トークンを使用済みに
        resetToken.setUsed(true);
        tokenRepository.save(resetToken);

        log.info("Password reset confirmed for user: {}", user.getId());
    }

    private void sendResetEmail(String email, String token) {
        String resetLink = baseUrl + "/auth/password-reset?token=" + token;
        String subject = "Let's Blog - パスワード再設定";
        String body = "パスワードをリセットするには、以下のリンクをクリックしてください:\n\n"
                + resetLink + "\n\n"
                + "このリンクは24時間有効です。\n\n"
                + "覚えのない場合はこのメールを無視してください。";

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(email);
        message.setSubject(subject);
        message.setText(body);

        try {
            mailSender.send(message);
            log.info("Reset email sent to: {}", email);
        } catch (Exception e) {
            log.error("Failed to send reset email to {}: {}", email, e.getMessage());
            throw new EmailSendException("パスワード再設定メールの送信に失敗しました", e);
        }
    }
}
```

### `PasswordResetRequest.java`/`PasswordResetConfirmRequest.java` (DTO)

```java
// PasswordResetRequest.java
package com.letsblog.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequest(
    @NotBlank(message = "メールアドレスは必須です")
    @Email(message = "有効なメールアドレスを入力してください")
    String email
) {}

// PasswordResetConfirmRequest.java
package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmRequest(
    @NotBlank(message = "トークンは必須です")
    String token,

    @NotBlank(message = "パスワードは必須です")
    @Size(min = 8, message = "パスワードは8文字以上である必要があります")
    String newPassword
) {}
```

### `UserController` への新エンドポイント追加

```java
// UserController.java の既存メソッド内に以下を追加

@PostMapping("/password-reset/request")
public ResponseEntity<String> requestPasswordReset(
        @Valid @RequestBody PasswordResetRequest request) {
    passwordResetService.requestPasswordReset(request.email());
    // セキュリティ上、メールアドレスが存在するか否かを応答に含めない
    return ResponseEntity.ok("再設定用メールを送信しました。メールボックスをご確認ください。");
}

@PostMapping("/password-reset/confirm")
public ResponseEntity<String> confirmPasswordReset(
        @Valid @RequestBody PasswordResetConfirmRequest request) {
    passwordResetService.confirmPasswordReset(request.token(), request.newPassword());
    return ResponseEntity.ok("パスワードをリセットしました。新しいパスワードでログインしてください。");
}

// 例外ハンドリング
@ExceptionHandler(InvalidTokenException.class)
public ResponseEntity<Map<String, String>> handleInvalidToken(InvalidTokenException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", e.getMessage()));
}

@ExceptionHandler(EmailSendException.class)
public ResponseEntity<Map<String, String>> handleEmailSendError(EmailSendException e) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", e.getMessage()));
}
```

### Flyway マイグレーション

```sql
-- V4__add_password_reset_tokens.sql
CREATE TABLE password_reset_tokens (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    token VARCHAR(255) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    expires_at DATETIME NOT NULL,
    `used` BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_token (token),
    INDEX idx_user_id_used (user_id, `used`),
    INDEX idx_expires_at (expires_at)
);
```

## タスクチェックリスト

- [ ] `PasswordResetToken.java` エンティティ実装
- [ ] `PasswordResetTokenRepository.java` 実装
- [ ] Flyway マイグレーション `V4__add_password_reset_tokens.sql` 作成
- [ ] `PasswordResetService.java` 実装(トークン生成・メール送信・トークン検証・パスワード更新)
- [ ] `PasswordResetRequest.java`/`PasswordResetConfirmRequest.java` DTO実装
- [ ] `UserController` に `/api/auth/password-reset/request`, `/api/auth/password-reset/confirm` エンドポイント追加
- [ ] 例外クラス実装(`InvalidTokenException`, `EmailSendException`)
- [ ] `PasswordResetServiceTest` 実装(トークン生成・有効期限検証・パスワード更新・メール送信エラー時の処理)
- [ ] `PasswordResetTokenRepositoryTest` 実装
- [ ] Web フロントエンド: パスワード忘却画面(`/auth/forgot-password`)、パスワード再設定画面(`/auth/password-reset?token=...`)を追加
- [ ] メール送信実装(SMTP 設定、または MailHog 等のテスト用メールサーバー連携)
- [ ] 開発環境での動作確認(メール受信・トークン有効期限切れ・既使用トークル再利用防止等)
- [ ] `./gradlew test` でテスト PASS 確認

## 未決事項

- メール送信の本番環境設定(SMTP サーバー選定、認証情報の外部化)
- パスワード再設定メールのHTML テンプレート化(スタイル、ロゴ等の実装詳細)
- トークンの複数同時発行時の挙動(前回のトークルを自動無効化 vs 複数同時有効)
- パスワード要件の強化(大文字・特殊文字の必須化など)
- Rate limiting(ブルートフォース攻撃対策)の実装タイミング
