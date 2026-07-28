# Phase 4: ユーザー管理拡張機能

## 目的

Phase 2で実装したユーザー認証・管理機能(ユーザー作成/削除/ロール変更)に対して、以下の拡張機能を実装し、運用環境での利便性・可視性・安全性を向上させる:

1. **パスワード再設定機能**: 忘れたパスワードをメール経由でリセットできる機能。現状は admin のみがユーザーのパスワードを直接変更でき、ユーザー自身は変更方法がない
2. **監査ログ機能**: 主要な操作(ログイン・ユーザー作成/更新/削除・投稿作成/更新・サイト登録等)を記録し、いつ誰が何をしたかを事後的に追跡できる
3. **UIコントラスト改善**: 管理画面の文字が薄く判読しづらい箇所を修正する(利用者フィードバックにより追加)
4. **ユーザーセルフサインアップ + 初期管理者アカウントの安全な払い出し**: 新規ユーザー登録機能を追加し、あわせて `.env` に固定のデフォルト管理者認証情報を平文で置く現行方式を廃止する(利用者フィードバックにより追加)
5. **サイト登録時のプロビジョニング可視化**: サイト登録が実際に何を行っているか(既存CMS認証情報の保存のみか、サーバー側で何か作成するのか)を利用者に明示する(利用者フィードバックにより追加)

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| パスワード再設定方式 | メールアドレス入力 → 再設定用トークン付きメール送信 → トークン検証 → パスワード再設定の3ステップ流 |
| トークン有効期限 | 24時間(ユースケースに応じて調整可能) |
| メール送信 | Spring Boot の `JavaMailSender`(SMTP設定が必要) または 外部メールサービスAPIを試験実装 |
| 監査ログ記録対象 | 認証関連(ログイン・ログアウト), ユーザー管理(作成/更新/削除/ロール変更), 投稿操作(作成/更新/削除/公開), サイト登録, 権限操作等 |
| ログ記録手法 | AOP または Interceptor で主要メソッド前後を自動記録(手動で Service 層に insert 文を散らさない) |
| ログ保持方針 | 1年間は DB に保持、それ以降は アーカイブ/削除の検討(Phase4では未実装) |
| 監査ログの秘匿性 | admin のみアクセス可能(一般ユーザーからのアクセスは403で拒否) |

## アーキテクチャ(新規機能追加)

```
既存(Phase 2 以後):
  [Webクライアント] → [UserController] → [UserService]
                                              ↓
                                        [UserRepository]
                                              ↓
                                        DB (users テーブル)

拡張(Phase 4):
  1. パスワード再設定フロー:
     [Webクライアント]
          ↓ POST /api/auth/password-reset/request (メールアドレス)
     [UserController]
          ↓
     [PasswordResetService]
          ├→ 一時トークン生成・保存([password_reset_tokens] テーブル)
          └→ メール送信
     
     ユーザーがメール内のリンク → [Webクライアント]
          ↓ POST /api/auth/password-reset/confirm (トークン + 新パスワード)
     [UserController]
          ↓
     [PasswordResetService]
          ├→ トークン検証(有効期限・存在確認)
          └→ パスワード変更

  2. 監査ログ記録:
     主要な操作(ログイン・投稿作成・ユーザー削除等)
          ↓ [AOP 監査ログ Aspect]
          ├→ [AuditLogService]
          └→ [AuditLog Repository]
                ↓
          DB (audit_logs テーブル)

  3. 監査ログ閲覧:
     [Webクライアント]
          ↓ GET /api/audit-logs (管理画面から)
     [AuditLogController] (admin のみ)
          ↓
     [AuditLogService]
          ↓
     DB (audit_logs テーブル)
```

## Phase 4 のスコープ

実装項目:

### 1. パスワード再設定機能
- [01-password-reset](01-password-reset.md)
  - `PasswordResetToken` エンティティ新設(トークン・有効期限・ユーザーID・使用済みフラグ)
  - Flyway マイグレーション(password_reset_tokens テーブル)
  - `PasswordResetService` 新設
    - `requestReset(email)`: トークン生成・保存・メール送信
    - `confirmReset(token, newPassword)`: トークン検証・パスワード更新・トークン無効化
  - `UserController` に新エンドポイント追加
    - `POST /api/auth/password-reset/request`
    - `POST /api/auth/password-reset/confirm`
  - メール送信実装(HTML テンプレート)
  - Web フロントエンド画面追加(パスワード忘却→再設定フロー)
  - テスト整備(`PasswordResetServiceTest`, `PasswordResetTokenRepositoryTest`)

### 2. 監査ログ機能
- [02-audit-log](02-audit-log.md)
  - `AuditLog` エンティティ新設(ユーザーID, 操作種別, リソース種別, リソースID, 変更内容(JSON), タイムスタンプ, IPアドレス(optional))
  - Flyway マイグレーション(audit_logs テーブル)
  - `AuditLogService` 新設
    - 監査ログ記録メソッド(`logLogin`, `logUserCreated` 等)
    - 操作種別 enum 定義(`LOGIN`, `LOGOUT`, `USER_CREATED`, `USER_DELETED`, `POST_CREATED` 等)
  - AOP Aspect 実装(`@AuditLog` アノテーション → 自動記録)
  - `AuditLogController` 新設(admin のみ)
    - `GET /api/audit-logs` (フィルタ・ページネーション)
  - Web 管理画面に監査ログ閲覧ページ追加
  - テスト整備(`AuditLogServiceTest`, `AuditLogRepositoryTest`)

### 3. UIコントラスト改善

- [03-ui-contrast-fix](03-ui-contrast-fix.md)
  - ログイン画面・各フォーム(サイト登録/ユーザー管理)の低コントラスト箇所の洗い出しと修正
  - ダークモード時の文字色不整合の解消

### 4. ユーザーセルフサインアップ + 初期管理者アカウントの安全な払い出し

- [04-user-self-registration](04-user-self-registration.md)
  - `POST /api/auth/signup` 相当のセルフサインアップエンドポイント新設
  - `.env` の `INITIAL_ADMIN_EMAIL`/`INITIAL_ADMIN_PASSWORD` 固定値方式の廃止・代替方式への移行

### 5. サイト登録時のプロビジョニング可視化

- [05-site-provisioning-visibility](05-site-provisioning-visibility.md)
  - サイト登録時に接続テスト(疎通確認)を行い成否を画面に表示
  - 「プロビジョニングは行わず、既存サイトの認証情報を登録するだけ」である旨をUI上に明記

対象外・スコープ外:
- パスワード再設定メール送信の実際の SMTP 設定運用(Docker Compose に mailhog 等を追加する検討は別途)
- 監査ログのアーカイブ・削除ポリシーの自動化(1年以上のデータ削除スクリプト等は Phase 5 以降)
- 2FA(Two-Factor Authentication)の実装
- ロールベースアクセス制御(RBAC)の細粒度化
- サイト登録時に実際にCMS側へリソースを新規作成する「真のプロビジョニング」機能の実装(今回は可視化・明示のみ。実プロビジョニングはPhase5以降で検討)

## タスク一覧

1. [01-password-reset](01-password-reset.md) — パスワード再設定機能の実装・テスト
2. [02-audit-log](02-audit-log.md) — 監査ログ機能の実装・テスト・管理画面統合
3. [03-ui-contrast-fix](03-ui-contrast-fix.md) — 管理画面の低コントラスト箇所の修正
4. [04-user-self-registration](04-user-self-registration.md) — セルフサインアップ実装・初期管理者払い出し方式の変更
5. [05-site-provisioning-visibility](05-site-provisioning-visibility.md) — サイト登録の疎通確認・プロビジョニング有無の明示

## 実装順序

1. ドメインモデル・DBスキーマ設計(`PasswordResetToken`/`AuditLog` エンティティ, Flyway マイグレーション)
2. `PasswordResetService` 実装 + テスト
3. `UserController` にパスワード再設定エンドポイント追加
4. メール送信実装(テンプレート・SMTP設定)
5. `AuditLogService`/AOP Aspect 実装 + テスト
6. `AuditLogController` 実装(admin のみフィルタ)
7. Web フロントエンド画面追加(パスワード再設定フォーム・監査ログ閲覧表)
8. UIコントラスト改善(低コストなため早期に着手可能)
9. ユーザーセルフサインアップ実装 + 初期管理者払い出し方式の変更
10. サイト登録の疎通確認・プロビジョニング有無の明示
11. エンドツーエンド検証

## 未決事項

- メール送信先の設定方法(SMTP vs 外部API vs ダミー実装など。開発時と本番時で異なる可能性)
- 監査ログの粒度(すべてのデータベース操作を記録するか、ユーザー感知できる「ビジネス操作」のみか)
- 監査ログの変更内容フィールドの仕様(更新前後の full object JSON vs カラム差分のみ等)
- パスワード再設定トークンが複数発行された場合の扱い(前回のトークン自動無効化 vs 複数同時有効)
- 監査ログの自動削除ポリシー(DB容量・性能影響の予測が必要)
- UIコントラスト改善の対応範囲(ダークモード対応まで含めるか、まずはライトモード固定で修正するか)
- セルフサインアップ時のデフォルトロールと有効化フロー(登録直後から一般ユーザーとして使えるか、admin承認制にするか)
- 初期管理者アカウントの新しい払い出し方式(初回起動時にランダムパスワードを生成しログのみに出力する案 vs 初回アクセス時にセットアップ画面で管理者を作成させる案)
- サイト登録時の疎通確認(ping)を同期的に行うか、失敗時も登録自体は許可するか
