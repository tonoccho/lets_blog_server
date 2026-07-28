# 01. SMTP メール送信の本番環境対応

## 目的

Phase 4で実装したパスワード再設定メール送信を、本番環境での実際のメールサーバー(SMTP)を利用して安定稼働させる。開発環境では mailpit/mailhog を用いてメール送信の検証を行い、本番環境では信頼できるSMTPサーバー設定(SendGrid, AWS SES等)を外部化する。あわせてメールテンプレートを HTML形式に改善し、複数のメール用途(パスワード再設定・2FA登録完了等)に対応可能な基盤を整備する。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| メール送信ライブラリ | Spring Boot の `JavaMailSender` (JavaMail API + Jakarta Mail) |
| 開発環境メールサーバー | mailpit (SMTP + Web UI で受信メール確認可) |
| メールテンプレートエンジン | Thymeleaf + HTML形式、CSS インラインスタイル対応 |
| SMTP設定外部化 | `application.yml` に本番・開発両環境の設定を記載、環境変数で切り替え |
| メール送信失敗時の処理 | 例外ログ記録、ユーザーへの通知(optional) |
| 送信者メールアドレス | `app.mail.from` プロパティで設定可能 |

## コンポーネント構成

### `MailTemplate.java` (エンティティ)

```java
package com.letsblog.api.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "mail_templates")
@Getter
@Setter
@NoArgsConstructor
public class MailTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_key", nullable = false, unique = true, length = 100)
    private String templateKey; // e.g., "password-reset", "totp-setup"

    @Column(name = "subject", nullable = false, length = 255)
    private String subject; // メール件名

    @Column(name = "body_html", nullable = false, columnDefinition = "TEXT")
    private String bodyHtml; // HTML形式のメール本文

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;
}
```

### `MailTemplateService.java`

```java
package com.letsblog.api.service;

import com.letsblog.api.domain.MailTemplate;
import com.letsblog.api.repository.MailTemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

@Service
@Slf4j
public class MailTemplateService {

    private final MailTemplateRepository mailTemplateRepository;
    private final TemplateEngine templateEngine;

    public MailTemplateService(
            MailTemplateRepository mailTemplateRepository,
            TemplateEngine templateEngine) {
        this.mailTemplateRepository = mailTemplateRepository;
        this.templateEngine = templateEngine;
    }

    /**
     * テンプレートキーからメールテンプレートを取得し、変数を埋め込む。
     */
    public MailContent renderTemplate(String templateKey, Map<String, Object> variables) {
        MailTemplate template = mailTemplateRepository.findByTemplateKeyAndIsActiveTrue(templateKey)
                .orElseThrow(() -> new MailTemplateNotFoundException("テンプレート '" + templateKey + "' が見つかりません"));

        // Thymeleafコンテキストに変数を設定
        Context context = new Context();
        context.setVariables(variables);

        // 件名をレンダリング
        String renderedSubject = templateEngine.process(template.getSubject(), context);

        // HTML本文をレンダリング
        String renderedBody = templateEngine.process(template.getBodyHtml(), context);

        return new MailContent(renderedSubject, renderedBody);
    }

    public record MailContent(String subject, String bodyHtml) {}
}
```

### `MailSenderService.java`

```java
package com.letsblog.api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;

@Service
@Slf4j
public class MailSenderService {

    private final JavaMailSender mailSender;
    private final MailTemplateService mailTemplateService;

    @Value("${app.mail.from:noreply@letsblog.example.com}")
    private String fromEmail;

    public MailSenderService(
            JavaMailSender mailSender,
            MailTemplateService mailTemplateService) {
        this.mailSender = mailSender;
        this.mailTemplateService = mailTemplateService;
    }

    /**
     * テンプレートを使用してメールを送信する。
     */
    public void sendMail(String to, String templateKey, Map<String, Object> variables) {
        try {
            MailTemplateService.MailContent content = mailTemplateService.renderTemplate(templateKey, variables);
            sendHtmlMail(to, content.subject(), content.bodyHtml());
        } catch (Exception e) {
            log.error("Failed to send mail using template '{}' to {}: {}", templateKey, to, e.getMessage());
            throw new MailSendException("メール送信に失敗しました", e);
        }
    }

    /**
     * HTML形式のメールを送信する。
     */
    public void sendHtmlMail(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true); // true で HTML形式を指定

            mailSender.send(message);
            log.info("HTML mail sent to: {} (subject: {})", to, subject);
        } catch (MessagingException e) {
            log.error("Failed to send HTML mail to {}: {}", to, e.getMessage());
            throw new MailSendException("メール送信に失敗しました", e);
        }
    }
}
```

### `MailTemplateRepository.java`

```java
package com.letsblog.api.repository;

import com.letsblog.api.domain.MailTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MailTemplateRepository extends JpaRepository<MailTemplate, Long> {
    Optional<MailTemplate> findByTemplateKeyAndIsActiveTrue(String templateKey);
}
```

### `MailTemplateNotFoundException.java` / `MailSendException.java`

```java
// MailTemplateNotFoundException.java
package com.letsblog.api.exception;

public class MailTemplateNotFoundException extends RuntimeException {
    public MailTemplateNotFoundException(String message) {
        super(message);
    }
}

// MailSendException.java
package com.letsblog.api.exception;

public class MailSendException extends RuntimeException {
    public MailSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

### `application.yml` の設定例

```yaml
spring:
  mail:
    # 開発環境: mailpit
    host: mailpit
    port: 1025
    username: ""
    password: ""
    properties:
      mail:
        smtp:
          auth: false
          starttls:
            enable: false

app:
  mail:
    from: noreply@letsblog.example.com
```

### `application-prod.yml` の設定例(本番環境)

```yaml
spring:
  mail:
    # 本番環境: SendGrid/AWS SES等の実SMTP
    host: ${MAIL_HOST}
    port: ${MAIL_PORT:587}
    username: ${MAIL_USERNAME}
    password: ${MAIL_PASSWORD}
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
            required: true

app:
  mail:
    from: ${MAIL_FROM_ADDRESS}
```

### `docker-compose.yml` の mailpit 追加

```yaml
services:
  mailpit:
    image: axllent/mailpit:latest
    ports:
      - "1025:1025"  # SMTP
      - "8025:8025"  # Web UI
    environment:
      MP_MAX_MESSAGES: 500
    networks:
      - letsblog-network
```

### メールテンプレート HTML の例

```html
<!-- password-reset template -->
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <style>
        body { font-family: Arial, sans-serif; background-color: #f5f5f5; }
        .container { max-width: 600px; margin: 20px auto; background: white; padding: 20px; border-radius: 5px; }
        .header { background-color: #2c3e50; color: white; padding: 20px; text-align: center; }
        .content { padding: 20px; line-height: 1.6; }
        .cta { text-align: center; margin: 20px 0; }
        .button { display: inline-block; padding: 10px 20px; background-color: #3498db; color: white; text-decoration: none; border-radius: 5px; }
        .footer { background-color: #ecf0f1; padding: 10px; text-align: center; font-size: 12px; color: #7f8c8d; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>Let's Blog</h1>
        </div>
        <div class="content">
            <p>パスワードをリセットするリクエストを受け取りました。</p>
            <p>以下のボタンをクリックして、パスワードをリセットしてください。このリンクは24時間有効です。</p>
            <div class="cta">
                <a th:href="|${baseUrl}/auth/password-reset?token=${token}|" class="button">パスワードをリセット</a>
            </div>
            <p>ご自身でリクエストしていない場合は、このメールを無視してください。</p>
        </div>
        <div class="footer">
            <p>&copy; 2024 Let's Blog. All rights reserved.</p>
        </div>
    </div>
</body>
</html>
```

## タスクチェックリスト

- [ ] `MailTemplate.java` エンティティ実装
- [ ] `MailTemplateRepository.java` 実装
- [ ] `MailTemplateService.java` 実装(Thymeleafレンダリング)
- [ ] `MailSenderService.java` 実装(JavaMailSender統合)
- [ ] 例外クラス実装(`MailTemplateNotFoundException`, `MailSendException`)
- [ ] Flyway マイグレーション `V5__add_mail_templates.sql` 作成
- [ ] メールテンプレート HTML 実装(password-reset など)
- [ ] `docker-compose.yml` に mailpit サービス追加
- [ ] `application.yml` / `application-prod.yml` SMTP設定追加
- [ ] `PasswordResetService` を `MailSenderService` 経由に変更
- [ ] `MailSenderServiceTest` 実装(テンプレートレンダリング・メール送信)
- [ ] `MailTemplateServiceTest` 実装(Thymeleaf変数埋め込み)
- [ ] 開発環境での動作確認(mailpit Web UIでメール確認)
- [ ] `./gradlew test` でテスト PASS 確認

## 未決事項

- メール送信失敗時の リトライ戦略(何回まで、何秒間隔か)
- HTMLメールが表示できないクライアント向けのプレーンテキスト版の必要性
- メール送信ログのDB記録(送受信履歴の監査、optional)
- 本番環境での SMTP設定の安全な管理(`.env` vs Secret Manager)
- メール配信の非同期化(現在は同期)への移行タイミング
