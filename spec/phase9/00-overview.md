# Phase 9: Phase8フィードバック対応・UI改善・運用堅牢化

## 目的

Phase 8(マルチ環境プロジェクト管理・ユーザー情報拡充・UI改善)の実機運用を踏まえて寄せられたフィードバック9件に対応する。フィードバックの内容は、UX改善(ヘッダー固定表示)、WordPress自動構築の堅牢化(エラーハンドリング・管理者選択・言語選択)、データ可視化(プロジェクト⇔サイト⇔ユーザーの紐付け情報表示)、ユーザープロフィール拡充(SNSリンク・カスタムリンク・WordPress互換項目)に分かれる。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| 対応フィードバック件数 | 9件(全て対応、スコープ外の項目なし) |
| ヘッダー固定方式 | `position: sticky`(`fixed`ではなく、`<main>`側の手動調整不要で将来ズレにくい) |
| WP構築エラー時のクリーンアップ | エージェント側(`index.php`)の自己クリーンアップ + サービス側の保険的呼び出しの両方で冪等性を確保 |
| WP管理者ユーザー決定方法 | サーバー登録ユーザーから選択可能(ただしパスワードは引き続き手入力) |
| WP言語インストール方式 | `wp core download --locale=$locale` で翻訳済みコアを取得(上記言語パックインストール不要) |
| サイト⇔プロジェクト表示 | フロントエンド側で `GET /api/projects` 結果をjoinして表示(バックエンド変更なし) |
| ユーザー⇔プロジェクト表示 | 新規バルクエンドポイント `GET /api/project-users` を追加し、一覧ページでN+1回避 |
| SNSリンク(14種) | 固定14フィールドのJavaレコード(`SocialLinks`)で型安全性を確保、JSON列に格納(Mapのstringly-typed回避) |
| カスタムリンク(任意個数) | `List<CustomLink>` (label+url)をJSON配列として1列に格納(子テーブル化は過剰設計と判断) |
| Display name pluckdown化 | firstName/lastName/nickname/emailから動的に候補生成し選択可能に(フロント側ローカルstate管理) |
| Email表示 | プロフィール編集フォームに表示のみ(編集不可、disabled属性で誤送信防止) |

## アーキテクチャ概要

```
Phase 8で実装されたシステム
  ├─ Web管理画面(Next.js)
  │  ├─ ヘッダー/ナビゲーション(lucide-react icons)
  │  ├─ サイト一覧・管理
  │  ├─ ユーザー一覧・プロフィール編集
  │  ├─ プロジェクト一覧・詳細・環境スロット管理
  │  └─ カスタムタグ・監査ログ・ロール管理
  │
  ├─ API(Spring Boot)
  │  ├─ /api/sites — サイト CRUD・WordPress自動構築
  │  ├─ /api/users/{id} — ユーザープロフィール GET/PUT
  │  ├─ /api/projects — プロジェクト CRUD・環境管理・ユーザー参加管理
  │  ├─ /api/project-users — 全ユーザー⇔プロジェクト紐付(新規、バルク用)
  │  └─ (既存: /api/posts, /ai/*, /render/*, etc.)
  │
  └─ Database(MySQL + Flyway)
      ├─ users(+social_links, +custom_links JSON列追加)
      ├─ sites(+managed_wordpress, +wp_slug, +wp_db_name)
      ├─ projects(+local_site_id, +test_site_id, +production_site_id)
      ├─ project_users
      ├─ posts
      ├─ custom_tags(+project_id)
      └─ (既存のその他テーブル)
```

## Phase 9 のスコープ

実装項目:

### 1. ヘッダー固定表示
- [01-header-fixed.md](01-header-fixed.md)
  - `web/src/app/layout.tsx` の `<header>` に `sticky top-0 z-40` を追加
  - 1行の変更、テストは実機検証のみ

### 2. WordPress新規構築の堅牢化
- [02-wordpress-provisioning-hardening.md](02-wordpress-provisioning-hardening.md)
  - **失敗時の自動クリーンアップ**: エージェント(`index.php`)側・サービス側の両層でクリーンアップ実装、冪等性確保
  - **管理者ユーザーピッカー**: サーバー登録ユーザーから選択可能、メール/ユーザー名は自動入力
  - **言語選択・インストール**: 10言語の選択肢、`wp core download --locale=$locale` で実装

### 3. プロジェクト⇔サイト⇔ユーザーの可視化
- [03-project-site-user-visibility.md](03-project-site-user-visibility.md)
  - **サイト一覧にプロジェクト表示**: フロント側join、バックエンド変更なし
  - **ユーザー一覧に参加プロジェクト表示**: 新規バルクエンドポイント `GET /api/project-users` 追加

### 4. ユーザープロフィール拡充
- [04-user-profile-expansion.md](04-user-profile-expansion.md)
  - **SNSリンク(14種固定)**: 専用のJavaレコード型で型安全性確保、JSON列格納
  - **カスタムリンク(任意個数)**: `List<CustomLink>` をJSON配列で格納
  - **WP互換プロフィール項目**: displayNameをプルダウン化、Emailを表示のみに

対象外・スコープ外:

- WordPress側へのSNSリンク・カスタムメタ同期(現時点では保留、表示のみ)
- SNSアイコン表示(lucide-reactに揃っていない可能性が高い、テキストラベルのみ)
- WordPress言語インストール失敗時の自動フォールバック
- 単数版`GET /api/users/{id}/projects`エンドポイント(将来ユーザー詳細ページで必要な場合に追加)
- WPユーザー削除・無効化ポリシー(Phase 8の方針継続、ユーザーは削除しない)
- アバター画像ファイルアップロード機構

## タスク実装順序

推奨順序: **A(ヘッダー) → C(可視化) → D(プロフィール拡充) → B(WP構築堅牢化)**

理由:
1. **A(ヘッダー固定表示)**: 完全独立、1行の軽微変更。最初に片付けるか他と並行実施もOK
2. **C(プロジェクト⇔サイト⇔ユーザー可視化)**: B とファイル被り(`web/src/app/sites/page.tsx`)あり。C を先に完成させることで調整コスト最小化
3. **D(ユーザープロフィール拡充)**: C と独立(違うファイル`UserProfileForm.tsx`/`users/[id]/edit/`)。A/C/D は並行実施可能。DB migration V15 を追加
4. **B(WP構築堅牢化)**: C の後に着手し、`sites/page.tsx` への変更を調整。DB migration 不要(ユーザーレコード内容変更のみ)

## 相互依存性

```
A(ヘッダー)  ─┐
              ├─→ 全体 (並行実施可)
C(可視化)    ─┴─→ B(WP堅牢化)
D(プロフィール拡充)

※ C→B: web/src/app/sites/page.tsx の共有ファイル調整
※ D: V15 マイグレーション (C/B と無関係、追加/削除の順不定)
※ A: 完全独立
```

## テスト整備

- Unit: `WordPressSiteProvisioningServiceTest`(deprovision 呼び出し検証)、`UserServiceTest`(SNS/カスタムリンク永続化)、`ProjectControllerTest`(新エンドポイント)
- E2E/実機: ヘッダー追従、WP構築失敗時クリーンアップ、管理者ピッカー、言語インストール、サイト/ユーザー一覧の紐付表示、プロフィール編集のSNS/カスタムリンク/displayName等

## 未決事項・将来検討

- 単数版 `GET /api/users/{id}/projects` エンドポイント(ユーザー詳細ページで必要になれば追加)
- SNSリンクのWordPress側カスタムメタ同期(Phase 8 未決事項、保留継続)
- WordPress言語パックのスマートキャッシング・更新戦略
- ユーザー同期失敗時のリトライ・キューイング(Phase 8 未決事項、保留継続)
- プロジェクトメンバーの細粒度アクセス制御(プロジェクト管理者権限など、後続フェーズで検討)

