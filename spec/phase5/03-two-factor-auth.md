# 03. 2FA(二要素認証)の実装

## 目的

パスワード認証に加えて時間ベースのワンタイムパスワード(TOTP)を用いた二段階認証を実装する。これにより、パスワード流出時の不正アクセスリスクを軽減し、セキュリティを大幅に強化する。Google Authenticator等の標準TOTP互換アプリで認証が可能な設計とし、バックアップコードも提供して緊急時のアカウント復旧を支援する。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| 2FA方式 | TOTP(Time-based One-Time Password)、RFC 6238準拠 |
| ライブラリ | `dev.samstevens.totp:totp` (またはGoogle Authenticator互換ライブラリ) |
| QRコード | Base64 Data URL で返却、フロントエンドで `<img>` タグで表示 |
| トークン有効期限 | 30秒(標準的なTOTP設定) |
| バックアップコード | 10個生成、1回限りの使用(ユーザー紛失時の代替認証) |
| 2FA設定場所 | ユーザー設定画面(`/settings/security`) |
| 2FA有効化フロー | ユーザーが「2FAを有効化」→ QRコード表示 → TOTPコード入力で確認 → 有効化完了 |
| 既存ユーザーへの強制 | 任意(admin 権限は強制化を検討) |
| 2FAによるログイン後のセッション | JWT に 2FA確認済みフラグを含める |

## コンポーネント構成

### `TwoFactorSecret.java` (エンティティ)

```java
package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "two_factor_secrets")
@Getter
@Setter
@NoArgsConstructor
public class TwoFactorSecret {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    @Column(name = "secret", nullable = false, length = 32)
    private String secret; // Base32 encoded TOTP シークレット

    @Column(name = "backup_codes", columnDefinition = "TEXT")
    private String backupCodes; // JSON形式: ["code1", "code2", ...]

    @Column(name = "is_enabled", nullable = false)
    private Boolean isEnabled = false;

    @Column(name = "enabled_at")
    private LocalDateTime enabledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
```

### `TwoFactorSecretRepository.java`

```java
package com.letsblog.api.repository;

import com.letsblog.api.domain.TwoFactorSecret;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TwoFactorSecretRepository extends JpaRepository<TwoFactorSecret, Long> {
    Optional<TwoFactorSecret> findByUserId(Long userId);
    Optional<TwoFactorSecret> findByUserIdAndIsEnabledTrue(Long userId);
}
```

### `TwoFactorService.java`

```java
package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.domain.TwoFactorSecret;
import com.letsblog.api.repository.TwoFactorSecretRepository;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Slf4j
public class TwoFactorService {

    private final TwoFactorSecretRepository twoFactorSecretRepository;
    private final CodeGenerator codeGenerator;
    private final QrGenerator qrGenerator;
    private final ObjectMapper objectMapper;

    public TwoFactorService(TwoFactorSecretRepository twoFactorSecretRepository) {
        this.twoFactorSecretRepository = twoFactorSecretRepository;
        this.codeGenerator = new DefaultCodeGenerator();
        this.qrGenerator = new ZxingPngQrGenerator();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * TOTPシークレットを生成し、QRコード(Base64 Data URL)とバックアップコードを返す。
     */
    @Transactional
    public TwoFactorSetupResponse generateTwoFactorSecret(Long userId, String userEmail) {
        // 既存の秘密が未有効化の状態であれば上書き
        twoFactorSecretRepository.findByUserId(userId)
                .ifPresent(existing -> {
                    if (!existing.getIsEnabled()) {
                        twoFactorSecretRepository.delete(existing);
                    } else {
                        throw new IllegalStateException("ユーザーは既に2FAが有効化されています");
                    }
                });

        // 新しいシークレットを生成
        String secret = new DefaultSecretGenerator().generate();
        List<String> backupCodes = generateBackupCodes();

        TwoFactorSecret twoFactorSecret = new TwoFactorSecret();
        twoFactorSecret.setUserId(userId);
        twoFactorSecret.setSecret(secret);
        twoFactorSecret.setIsEnabled(false);
        try {
            twoFactorSecret.setBackupCodes(objectMapper.writeValueAsString(backupCodes));
        } catch (Exception e) {
            throw new RuntimeException("バックアップコードのシリアライズに失敗しました", e);
        }

        twoFactorSecretRepository.save(twoFactorSecret);

        // QRコード生成
        String qrCodeDataUrl = generateQrCodeDataUrl(secret, userEmail);

        return new TwoFactorSetupResponse(qrCodeDataUrl, backupCodes);
    }

    /**
     * QRコードを Base64 Data URL形式で生成する。
     */
    private String generateQrCodeDataUrl(String secret, String userEmail) {
        try {
            QrData data = new QrData.Builder()
                    .label(userEmail)
                    .secret(secret)
                    .issuer("Let's Blog")
                    .build();

            byte[] imageData = qrGenerator.generate(data);
            String base64 = Base64.getEncoder().encodeToString(imageData);
            return "data:image/png;base64," + base64;
        } catch (QrGenerationException e) {
            log.error("Failed to generate QR code: {}", e.getMessage());
            throw new QrCodeGenerationException("QRコード生成に失敗しました", e);
        }
    }

    /**
     * ユーザー入力のTOTPコードを検証し、2FAを有効化する。
     */
    @Transactional
    public void verifyAndEnableTwoFactor(Long userId, String code) {
        TwoFactorSecret twoFactorSecret = twoFactorSecretRepository.findByUserId(userId)
                .orElseThrow(() -> new TwoFactorSecretNotFoundException("2FAシークレットが見つかりません"));

        if (twoFactorSecret.getIsEnabled()) {
            throw new IllegalStateException("2FAは既に有効化されています");
        }

        // TOTPコード検証(30秒の時間差を許容)
        if (!verifyCode(twoFactorSecret.getSecret(), code)) {
            throw new InvalidTotpCodeException("無効なTOTPコードです。正しいコードを入力してください。");
        }

        twoFactorSecret.setIsEnabled(true);
        twoFactorSecret.setEnabledAt(LocalDateTime.now());
        twoFactorSecretRepository.save(twoFactorSecret);

        log.info("2FA enabled for user: {}", userId);
    }

    /**
     * ログイン時に入力されたTOTPコードを検証する。
     */
    public boolean verifyTotpCode(Long userId, String code) {
        TwoFactorSecret twoFactorSecret = twoFactorSecretRepository.findByUserIdAndIsEnabledTrue(userId)
                .orElse(null);

        if (twoFactorSecret == null) {
            return false; // 2FAが有効でなければスキップ
        }

        // バックアップコードの確認を先に行う
        if (isValidBackupCode(twoFactorSecret, code)) {
            consumeBackupCode(twoFactorSecret, code);
            return true;
        }

        // 通常のTOTPコード検証
        return verifyCode(twoFactorSecret.getSecret(), code);
    }

    /**
     * TOTPコードを検証(30秒の時間差を許容)
     */
    private boolean verifyCode(String secret, String code) {
        try {
            TimeProvider timeProvider = new SystemTimeProvider();
            int expectedCode = codeGenerator.generate(secret, timeProvider.getTime());
            return code.equals(String.format("%06d", expectedCode));
        } catch (Exception e) {
            log.error("Failed to verify TOTP code: {}", e.getMessage());
            return false;
        }
    }

    /**
     * バックアップコードの有効性を確認
     */
    private boolean isValidBackupCode(TwoFactorSecret twoFactorSecret, String code) {
        try {
            List<String> backupCodes = objectMapper.readValue(
                    twoFactorSecret.getBackupCodes(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            return backupCodes.contains(code);
        } catch (Exception e) {
            log.error("Failed to parse backup codes: {}", e.getMessage());
            return false;
        }
    }

    /**
     * バックアップコードを消費(削除)
     */
    @Transactional
    private void consumeBackupCode(TwoFactorSecret twoFactorSecret, String code) {
        try {
            List<String> backupCodes = objectMapper.readValue(
                    twoFactorSecret.getBackupCodes(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            backupCodes.remove(code);
            twoFactorSecret.setBackupCodes(objectMapper.writeValueAsString(backupCodes));
            twoFactorSecretRepository.save(twoFactorSecret);
            log.info("Backup code consumed for user: {}", twoFactorSecret.getUserId());
        } catch (Exception e) {
            log.error("Failed to consume backup code: {}", e.getMessage());
            throw new RuntimeException("バックアップコードの消費に失敗しました", e);
        }
    }

    /**
     * 2FAを無効化(管理画面用)
     */
    @Transactional
    public void disableTwoFactor(Long userId) {
        twoFactorSecretRepository.findByUserId(userId)
                .ifPresent(twoFactorSecret -> {
                    twoFactorSecretRepository.delete(twoFactorSecret);
                    log.info("2FA disabled for user: {}", userId);
                });
    }

    /**
     * バックアップコード10個を生成
     */
    private List<String> generateBackupCodes() {
        List<String> codes = new ArrayList<>();
        Random random = new Random();
        for (int i = 0; i < 10; i++) {
            String code = String.format("%08d", random.nextInt(100000000));
            codes.add(code);
        }
        return codes;
    }

    // DTOs
    public record TwoFactorSetupResponse(String qrCodeDataUrl, List<String> backupCodes) {}
}
```

### `UserController` への新エンドポイント

```java
// UserController.java に以下を追加

@PostMapping("/auth/totp/setup")
public ResponseEntity<TwoFactorService.TwoFactorSetupResponse> setupTwoFactor(
        @AuthenticationPrincipal User user) {
    var response = twoFactorService.generateTwoFactorSecret(user.getId(), user.getEmail());
    return ResponseEntity.ok(response);
}

@PostMapping("/auth/totp/verify-setup")
public ResponseEntity<String> verifyTwoFactorSetup(
        @AuthenticationPrincipal User user,
        @Valid @RequestBody VerifyTotpRequest request) {
    twoFactorService.verifyAndEnableTwoFactor(user.getId(), request.code());
    return ResponseEntity.ok("2FAが有効化されました。");
}

@PostMapping("/auth/totp/verify")
public ResponseEntity<String> verifyTotpLogin(
        @Valid @RequestBody VerifyTotpRequest request) {
    // ログイン時の2FA検証。既に認証されているユーザーからのリクエストを想定。
    var principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    if (principal instanceof User user) {
        boolean isValid = twoFactorService.verifyTotpCode(user.getId(), request.code());
        if (isValid) {
            return ResponseEntity.ok("2FA認証に成功しました。");
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("TOTPコードが無効です。");
        }
    }
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
}

public record VerifyTotpRequest(
    @NotBlank(message = "TOTPコードは必須です")
    String code
) {}
```

### 例外クラス

```java
package com.letsblog.api.exception;

public class TwoFactorSecretNotFoundException extends RuntimeException {
    public TwoFactorSecretNotFoundException(String message) {
        super(message);
    }
}

public class InvalidTotpCodeException extends RuntimeException {
    public InvalidTotpCodeException(String message) {
        super(message);
    }
}

public class QrCodeGenerationException extends RuntimeException {
    public QrCodeGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

### Flyway マイグレーション

```sql
-- V6__add_two_factor_secrets.sql
CREATE TABLE two_factor_secrets (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL UNIQUE,
    secret VARCHAR(32) NOT NULL,
    backup_codes TEXT NOT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    enabled_at DATETIME,
    created_at DATETIME NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_id_enabled (user_id, is_enabled)
);
```

## タスクチェックリスト

- [ ] TOTP ライブラリ (`dev.samstevens.totp` 等) を build.gradle に追加
- [ ] `TwoFactorSecret.java` エンティティ実装
- [ ] `TwoFactorSecretRepository.java` 実装
- [ ] `TwoFactorService.java` 実装(秘密生成・QRコード・コード検証・バックアップコード)
- [ ] 例外クラス実装(`TwoFactorSecretNotFoundException`, `InvalidTotpCodeException`, `QrCodeGenerationException`)
- [ ] Flyway マイグレーション `V6__add_two_factor_secrets.sql` 作成
- [ ] `UserController` に TOTP検証エンドポイント追加
- [ ] `TwoFactorServiceTest` 実装(秘密生成・コード検証・バックアップコード消費)
- [ ] `TwoFactorSecretRepositoryTest` 実装
- [ ] Web フロントエンド: 2FA設定画面(`/settings/security`)実装
  - [ ] 「2FAを有効化」ボタン
  - [ ] QRコード表示
  - [ ] TOTP入力フォーム
  - [ ] バックアップコード表示・ダウンロード
- [ ] Google Authenticator等の実アプリでQRコードをスキャンして動作確認
- [ ] バックアップコード利用時の動作確認
- [ ] ログイン後のTOTP入力フロー実装(Web画面)
- [ ] `./gradlew test` でテスト PASS 確認

## 未決事項

- 2FA設定後のバックアップコード表示画面UX(ダウンロード vs 画面内表示のみ)
- バックアップコード消費後の再生成ポリシー(ユーザーが再生成可能か)
- admin 権限への2FA強制化の必要性
- 2FAデバイス紛失時のリカバリー手段(セキュリティ質問 vs サポート対応)
- 2FAが有効な状態での ロジン画面UI(2段階の入力フォーム vs 1フォーム)
- TOTP コード入力時の時間ズレ許容範囲(±30秒の設定カスタマイズ可能性)
