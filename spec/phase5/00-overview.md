# Phase 5: ユーザー認証・運用機能の強化

## 目的

Phase 4で実装したユーザー管理拡張機能をベースに、以下の運用・セキュリティ強化機能を追加する:

1. **SMTP メール送信の本番環境対応**: Phase 4のパスワード再設定メール送信を実際のメールサーバー(SMTP)で運用可能にし、本番環境での安定性を確保する。Docker環境でのmailhog/mailpit検証、本番SMTP設定の外部化、メールテンプレートのHTML化対応
2. **監査ログのアーカイブ・削除ポリシー自動化**: 監査ログを1年以上保持しない方針に従い、定期的なアーカイブ・削除を自動化するスケジューラー・削除スクリプトを実装
3. **2FA(二要素認証)の実装**: パスワード認証に加えて時間ベースのワンタイムパスワード(TOTP)による二段階認証を実装し、セキュリティを強化
4. **RBAC(ロールベースアクセス制御)の細粒度化**: 現在のadmin/user の二段階ロールを拡張し、より細粒度な権限管理(例: サイト管理者・投稿編集者など)を導入
5. **真のプロビジョニング機能**: Phase 4で「可視化のみ」としたサイト登録を拡張し、CMS側に実際にカテゴリ・タグ・記事テンプレート等のリソースを自動作成する機能を実装

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| メール配信基盤 | Spring Boot の `JavaMailSender` + 本番SMTP設定(外部化)、開発環境では mailpit/mailhog 利用 |
| メールテンプレート | Thymeleaf + HTML形式で実装、CSSインラインスタイル対応 |
| 監査ログ削除ポリシー | 1年以上のログを毎日夜間(UTC 02:00)に削除。アーカイブは今回は実装対象外 |
| 2FA実装方式 | TOTP(Time-based One-Time Password)、QRコード表示、バックアップコード対応 |
| 2FAライブラリ | Google Authenticator互換の `org.jboss.aerogear:aerogear-otp-java` または `dev.samstevens.totp` |
| RBAC権限体系 | admin(全権限) / editor(投稿・サイト管理可) / viewer(閲覧のみ) の三段階、カスタムロール拡張の余地を保持 |
| プロビジョニング対象 | WordPress: カテゴリ・タグ・著者(ユーザー)の自動作成。microCMS: list型APIの自動初期化(将来的) |
| プロビジョニング時期 | サイト登録直後に同期的に実施、失敗時は登録をロールバック |

## アーキテクチャ(新規機能追加)

```
Phase 4 からの拡張:

1. SMTP メール配信:
   [PasswordResetService / その他メール送信機能]
        ↓ メール送信リクエスト
   [MailTemplate + Thymeleaf レンダリング]
        ↓
   [JavaMailSender(本番SMTP / 開発時 mailpit)]
        ↓
   外部メールサーバー

2. 監査ログ削除ポリシー:
   [AuditLogArchivalScheduler] (毎日 02:00 UTC)
        ↓
   [AuditLogService.deleteLogsOlderThan(1year)]
        ↓
   DB削除実行

3. 2FA認証フロー:
   [ログイン画面] → [パスワード入力]
        ↓
   [UserController.login] (初段階認証)
        ↓
   2FA有効か? ← Yes → [2FA入力画面]
        ↓              ↓
       [JWT発行]    [TOTPコード検証]
        ↓              ↓
   [UserController.verifyTotp]
        ↓
   [JWT発行]

4. RBAC権限管理:
   [ユーザー] → [複数ロール割り当て(admin/editor/viewer)]
        ↓
   [Role エンティティ追加、User と多対多リレーション]
        ↓
   [Spring Security の `@Secured("ROLE_EDITOR")` 等でメソッドレベル制御]

5. 真のプロビジョニング:
   [サイト登録フロー] → [認証情報検証]
        ↓
   [CmsAdapterFactory 経由でCMS別プロビジョニング実行]
        ↓
   [WordPressAdapter/MicroCmsAdapter が リソース作成]
        ↓ (成功時)
   [SiteRepository.save]
```

## Phase 5 のスコープ

実装項目:

### 1. SMTP メール送信の本番環境対応
- [01-smtp-mail-setup](01-smtp-mail-setup.md)
  - `MailTemplate.java` エンティティ新設(テンプレート種別・件名・本文・変数)
  - `MailTemplateService` 新設(テンプレート取得・Thymeleafレンダリング)
  - `MailSenderService` 新設(統一メール送信インターフェース)
  - `application.yml` の SMTP設定項目追加(本番用・開発用の切り替え)
  - Docker Compose に mailpit 追加(開発環境)
  - メールテンプレート実装(HTML形式)
    - パスワード再設定確認メール
    - 2FA登録完了メール
    - その他運用メール
  - メール送信テスト(`MailSenderServiceTest`, `MailTemplateServiceTest`)

### 2. 監査ログのアーカイブ・削除ポリシー自動化
- [02-audit-log-archival](02-audit-log-archival.md)
  - `AuditLogArchivalScheduler` 新設(`@Scheduled(cron = "0 0 2 * * *")` で毎日02:00に実行)
  - `AuditLogService.deleteLogsOlderThan(Duration)` メソッド実装
  - 削除対象ログのアーカイブ(CSV出力、optional)
  - テスト整備(`AuditLogArchivalSchedulerTest`)
  - 削除ログ件数の監視・アラート設定(optional)

### 3. 2FA(二要素認証)の実装
- [03-two-factor-auth](03-two-factor-auth.md)
  - `TwoFactorSecret.java` エンティティ新設(ユーザーID, シークレット, バックアップコード, 有効化フラグ)
  - Flyway マイグレーション(`two_factor_secrets` テーブル)
  - `TwoFactorService` 新設
    - `generateSecret()`: TOTP シークレット生成
    - `generateQrCode()`: QRコード生成(Base64 Data URL)
    - `verifyCode()`: TOTPコード検証
    - `generateBackupCodes()`: バックアップコード生成
  - `UserController` にエンドポイント追加
    - `POST /api/auth/totp/setup` (2FA有効化開始)
    - `POST /api/auth/totp/verify-setup` (QRコード確認後のTOTP検証)
    - `POST /api/auth/totp/verify` (ログイン後の2FA入力画面)
  - Web フロントエンド画面追加(2FA設定・QRコード表示・バックアップコード)
  - テスト整備(`TwoFactorServiceTest`, `TwoFactorSecretRepositoryTest`)

### 4. RBAC(ロールベースアクセス制御)の細粒度化
- [04-rbac-enhancements](04-rbac-enhancements.md)
  - `Role.java` エンティティ新設(ロール名・権限一覧)
  - `Permission.java` エンティティ新設(権限コード・説明)
  - `UserRole` 連結テーブル実装(User ↔ Role の多対多)
  - Flyway マイグレーション(roles・permissions・user_roles テーブル)
  - `RoleService` 新設(ロール・権限管理)
  - 既存の `admin` フィールド削除、`Role` に移行
  - `@Secured` アノテーション基盤の権限チェック実装
  - 管理画面にロール管理ページ追加
  - テスト整備(`RoleServiceTest`, `RoleRepositoryTest`)

### 5. 真のプロビジョニング機能
- [05-true-provisioning](05-true-provisioning.md)
  - `ProvisioningService` 新設(CMS別プロビジョニング統括)
  - `WordPressAdapter` 拡張
    - `provisionCategories()`: デフォルトカテゴリ作成
    - `provisionTags()`: デフォルトタグ作成
    - `provisionAuthor()`: サイト管理者を著者として作成
  - `MicroCmsAdapter` 拡張(同上、microCMS API仕様に合わせて)
  - サイト登録時のプロビジョニング統合(`SiteService.registerSite()` 内で呼び出し)
  - プロビジョニング失敗時のロールバック処理
  - テスト整備(`ProvisioningServiceTest`, `WordPressAdapterProvisioningTest`)

対象外・スコープ外:

- メール配信の非同期化(キューイング・リトライ)は Phase 6 以降の検討課題
- 監査ログのクラウドストレージへの長期アーカイブは Phase 6 以降
- 2FA の U2F/WebAuthn 対応は将来的な拡張
- RBAC の属性ベースアクセス制御(ABAC)化は将来的な拡張
- プロビジョニングの Webhook/イベント駆動化は将来的な拡張

## タスク一覧

1. [01-smtp-mail-setup](01-smtp-mail-setup.md) — SMTP メール送信の本番環境対応・テンプレート実装
2. [02-audit-log-archival](02-audit-log-archival.md) — 監査ログアーカイブ・削除ポリシーの自動化
3. [03-two-factor-auth](03-two-factor-auth.md) — 2FA(TOTP)認証の実装
4. [04-rbac-enhancements](04-rbac-enhancements.md) — ロールベースアクセス制御の細粒度化
5. [05-true-provisioning](05-true-provisioning.md) — 真のプロビジョニング機能の実装

## 実装順序

1. SMTP・メール基盤整備(`MailTemplate`, `MailTemplateService`, `MailSenderService`, Docker mailpit追加)
2. 監査ログ削除スケジューラー実装(`AuditLogArchivalScheduler`, テスト)
3. 2FA機能実装(`TwoFactorSecret`, `TwoFactorService`, TOTP検証, QRコード)
4. 2FA Web フロントエンド画面追加(設定・バックアップコード)
5. RBAC基盤整備(`Role`, `Permission`, `UserRole`, マイグレーション)
6. 既存コード の `admin` フィールド → `Role` への移行
7. RBAC権限チェック実装(`@Secured` アノテーション、管理画面)
8. プロビジョニング基盤実装(`ProvisioningService`, CMS別実装)
9. サイト登録フローへのプロビジョニング統合
10. エンドツーエンド検証

## 未決事項

- メール配信の リトライ戦略(失敗時の再送タイミング、最大試行回数)
- 監査ログアーカイブの出力形式・保管先(ローカルファイル、S3等のクラウドストレージ)
- 2FA設定画面でのバックアップコード表示・ダウンロード UX(一度限りの表示か、再表示可能か)
- RBAC権限体系の細粒度さ加減(管理画面毎のロール vs 操作毎の権限)
- プロビジョニング失敗時の処理(全て失敗でロールバック vs 部分的にスキップ)
- プロビジョニング対象リソースの命名規則(デフォルトカテゴリ名等)
- 既存サイトのプロビジョニング追い実行の必要性・手段
