# Phase 10: サイト管理強化・WordPress著者作成エラー修正・環境間同期

## 目的

以下3件の要望・不具合報告に対応する。

1. サイト管理画面に「疎通確認(再チェック)」機能と「サイト編集」機能を追加する
2. プロジェクトへのユーザー追加時に発生する `APIエラー (502): {"error":"WordPress著者の作成に失敗しました: 403 FORBIDDEN` の原因究明・修正
3. プロジェクトの環境(ローカル/テスト/本番)間で、WordPressのテーマ・プラグイン・DBを同期できるようにする

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| 疎通確認 | 既存の `CmsAdapter.testConnection()` をそのまま再利用。新規 `POST /api/sites/{id}/test-connection`。結果は `Site` に永続化しない(既存の「登録時のみ計算・非保存」という設計を踏襲) |
| サイト編集 | 新規 `PUT /api/sites/{id}`。編集可能なのは `name` は常時、`credentials`(認証情報一式)は非managedサイト(外部登録サイト)のみ。`siteKey`・`cmsType` は不変(変更不可) |
| 認証情報の編集方式 | 既存の暗号化済みcredentials mapを復号→リクエストで指定されたフィールドのみ上書き→再度JSON化して暗号化・保存(部分パッチ方式。全置換は要求しない) |
| レガシー認証情報の統合 | `wpUsername`/`wpAppPasswordEncrypted`(Phase2以前の旧WordPress専用列)を使っている既存サイトを編集した場合、この機会に汎用の `credentialsEncrypted` 列へ統合する |
| 403エラーの根本原因 | サイト登録時、WordPress認証情報(Application Password)が実際に`create_users`権限(Administrator権限)を持つアカウントかどうかの検証が一切行われていなかったため。Editor等の非管理者アカウントでも登録・疎通確認自体は成功してしまい、実際にプロジェクトへユーザーを追加する段階で初めて403が発覚していた |
| 403対応方針 | (1) 疎通確認処理を拡張し、WordPressサイトについては`create_users`権限(Administrator相当)の有無も判定して警告表示する (2) `provisionAuthor`失敗時のエラーメッセージを、原因が分かりやすい内容に改善する (3) 既存の壊れたサイトは、01で追加する「サイト編集」機能を使って管理者アカウントの認証情報に更新することで復旧する(データ移行は不要、運用手順で解決) |
| 環境同期の対象 | `managedWordpress = true`(自動構築)の環境同士のみ。外部登録サイトが紐付いている環境スロットは同期対象外(UI上で無効化) |
| 環境同期の実現方式 | 常駐WordPressコンテナ(`lbs-wordpress`)内で完結するローカルコマンドのみで実現する(`cp -r`によるファイルコピー、`mysqldump`\|`mysql`によるDBコピー、`wp search-replace`によるURL書き換え)。全ての管理対象環境は同一コンテナ・同一MySQLインスタンス上にあるため、SSHや外部転送の仕組みは導入しない |
| DB同期の対象範囲 | `wp_users`・`wp_usermeta` は同期対象から除外する。各環境の管理者・プロジェクトメンバーのWordPressアカウントは既存の`ProjectUserSyncService`が環境ごとに個別管理しているため、DB同期で上書きしない |
| DB同期後のURL書き換え | `wp search-replace <コピー元URL> <コピー先URL> --all-tables --allow-root` を同期直後に必ず実行する(シリアライズ化データにも安全に対応するため、生SQLでの文字列置換は行わない) |
| 同期前のバックアップ | 同期先(上書きされる側)の既存DB・wp-content(themes/plugins)を上書き前に自動でバックアップする。保持世代は直近1世代のみ(古い世代の自動削除・世代管理は行わない) |
| 同期方向 | 制限なし。ローカル⇔テスト⇔本番、bindされている任意の2環境間で実行可能(一方向の強制はしない) |
| 同期処理のタイムアウト対策 | nginxに同期専用locationを新設し `proxy_read_timeout 300s` を設定(既存の`/ollama/`と同じパターン)。非同期ジョブ化・進捗ポーリングの仕組みは本フェーズでは導入しない(将来、実データで300秒を超える場合に再検討) |
| 同期の確認UX | 既存の「サイト削除」ボタンと同じ `window.confirm` による確認のみ。環境名の入力を求める等の強い確認UIは導入しない |

## アーキテクチャ概要

```
Web管理画面(Next.js)
  ├─ サイト一覧: 疎通確認ボタン(再チェック)・編集ボタンを追加
  ├─ サイト編集フォーム(新規): name / credentials(非managedのみ)
  ├─ プロジェクト詳細画面: 環境同期パネル(新規、テーマ/プラグイン/DB選択+確認)
  └─ (既存: サイト登録・プロジェクト管理・ユーザー管理等)

API(Spring Boot)
  ├─ POST /api/sites/{id}/test-connection(新規)— 疎通確認・管理者権限チェック
  ├─ PUT  /api/sites/{id}(新規)— サイト編集
  ├─ WordPressAdapter.provisionAuthor — 403時のエラーメッセージ改善
  ├─ POST /api/projects/{id}/environments/sync(新規)— 環境間同期トリガー
  └─ (既存: /api/sites, /api/projects, /api/project-users 等)

WordPress provisioning agent(index.php, lbs-wordpress内)
  └─ /sync(新規)— cp -r(テーマ/プラグイン)・mysqldump|mysql+search-replace(DB)をローカル実行

Database
  └─ スキーマ変更なし(Phase10はマイグレーション不要)
```

## Phase 10 のスコープ

### 1. サイト管理: 疎通確認・編集機能
- [01-site-management-visibility-edit.md](01-site-management-visibility-edit.md)

### 2. WordPress著者作成403エラーの原因究明・修正
- [02-wordpress-author-permission-fix.md](02-wordpress-author-permission-fix.md)

### 3. プロジェクト環境間のテーマ・プラグイン・DB同期
- [03-environment-sync.md](03-environment-sync.md)

### 4. WordPress SSH(wp-cli)トランスポートの追加
- [04-wordpress-ssh-transport.md](04-wordpress-ssh-transport.md) — Cloudflare等でREST APIが遮断される外部サイト向けに、SSH+wp-cli経由の代替操作経路を追加(設計中、01〜03完了後に着手する追加スコープ)

対象外・スコープ外(全体):

- 外部登録(非managed)サイトへの環境同期対応
- 同期処理の非同期ジョブ化・進捗表示UI
- バックアップの世代管理・自動削除ポリシー
- サイト編集での `siteKey`/`cmsType` 変更
- WordPress管理者権限チェックを登録時にブロッキング化すること(警告表示のみに留める)

## タスク実装順序

推奨順序: **01(サイト編集・疎通確認) → 02(403修正) → 03(環境同期)**

理由:
1. **01**: 02で発覚した「既存の壊れたサイト」を復旧する手段(認証情報の編集)として02より先に必要
2. **02**: 01のサイト編集APIを土台に、疎通確認の拡張(管理者権限チェック)とエラーメッセージ改善を行う
3. **03**: 01/02とファイル・ロジックの重複がなく独立して着手可能(並行実施も可)

## 相互依存性

```
01(サイト編集・疎通確認) ─→ 02(403修正: 疎通確認の拡張が01のAPIに依存)
03(環境同期) ─→ 01/02とは独立(並行実施可)
```

## テスト整備

- Unit: `SiteServiceTest`(update/checkConnection/管理者権限判定)、`SiteControllerTest`(新規エンドポイント)、`WordPressAdapterTest`(403エラーメッセージ)、`ProjectEnvironmentSyncServiceTest`(新規)
- E2E/実機: サイト編集・再疎通確認、実際に非管理者アカウントで登録→403再現→編集で復旧、環境同期の実行(テーマ/プラグイン/DBそれぞれ)

## 未決事項・将来検討

- 大規模DBで同期処理がnginxの延長タイムアウト(300秒)を超える場合の非同期ジョブ化
- バックアップの保持世代数・自動削除ポリシー
- 外部登録(非managed)サイトへの同期対応の要否・実現方式(SSH鍵配布、リモートエージェント設置等)
- テーマ/プラグインの個別選択同期(現状は`wp-content/themes`・`wp-content/plugins`ディレクトリ全体の一括同期)
- DB同期時に`wp_users`/`wp_usermeta`以外にも除外すべきテーブルがないかの継続精査(プラグイン依存データ等)
- サイト編集機能での`cmsType`・`siteKey`変更の要否
- WordPress管理者権限チェック(`create_users`)を登録時にブロッキングにするか、警告のみに留めるかの継続検討
- プロジェクト詳細画面に各環境サイトの管理者権限状態を表示するかどうか
