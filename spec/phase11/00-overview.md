# Phase 11: プロジェクト画面・サイト画面の機能強化

## 目的

以下4件の要望に対応する。

1. プロジェクト詳細画面に「一括管理」機能を追加し、紐付けられた全environment(ローカル/テスト/本番のmanaged WordPress環境)に対してカテゴリ作成・プラグインインストール・テーマインストールを同時実行できるようにする。実行内容は「作業ログ」として保存し、任意の環境へ後から再適用(ロールフォワード)できるようにする
2. プロジェクト環境間の同期機能(Phase10-03で実装済み)にメディアファイル(`wp-content/uploads`)を同期対象として追加する。マスター環境の内容で対象環境を無条件に上書きする(差分チェックはしない)
3. WordPressを新規構築する際、既存のmanaged WordPressサイトを「テンプレート」として選択できるようにし、選択した場合はテンプレートのテーマ・プラグイン・メディア・DB(コンテンツ)を新規サイトへ複製する
4. サイト一覧画面(`/sites`)の視認性を改善する

## 前提・決定事項(全体)

| 項目 | 決定内容 |
|---|---|
| 一括管理・環境同期の対象範囲 | 引き続き`managedWordpress = true`の環境同士に限定する(Phase10-03と同じ制約)。外部登録(REST/SSH)サイトは対象外。理由: 常駐WordPressコンテナ内のローカルコマンド(wp-cli/cp/mysqldump)だけで完結する既存アーキテクチャをそのまま踏襲でき、外部サーバーへの書き込み手段の設計(SSH配布等)を新たに持ち込まずに済むため |
| 実現方式 | Phase10-03と同じく、常駐WordPressコンテナ内の内部限定エージェント`wordpress/provision-agent/index.php`にハンドラを追加する方式を踏襲する(`WordPressProvisioningClient`/`WordPressSyncClient`と同じ内部限定・共有トークン認証パターン) |
| タイムアウト対策 | 重い処理(プラグイン/テーマのダウンロード、DB同期)を伴うエンドポイントは、既存の`location ~ ^/api/projects/[0-9]+/environments/sync$`と同じ`proxy_read_timeout 300s`パターンをnginxに追加する |

## アーキテクチャ概要

```
Web管理画面(Next.js)
  ├─ プロジェクト詳細画面: 一括管理パネル(新規)・作業ログ一覧・ロールフォワード操作
  ├─ プロジェクト詳細画面: 環境同期パネル(既存)にメディア(uploads)を追加
  ├─ サイト登録画面: WordPress新規構築フォームにテンプレートサイト選択を追加
  └─ サイト一覧画面: 視認性改善(検索/フィルタ・グルーピング・バッジ整理)

API(Spring Boot)
  ├─ POST /api/projects/{id}/bulk-management(新規)— カテゴリ/プラグイン/テーマの一括実行(SLUG指定)
  ├─ POST /api/projects/{id}/bulk-management/upload(新規)— プラグイン/テーマのzipアップロードによる一括インストール
  ├─ GET  /api/projects/{id}/bulk-management/logs(新規)— 作業ログ一覧
  ├─ POST /api/projects/{id}/bulk-management/replay(新規)— 指定環境へのロールフォワード(SLUG/ZIP双方)
  ├─ POST /api/projects/{id}/environments/sync(既存)— targetsに"media"を追加
  ├─ POST /api/sites/managed-wordpress(既存)— templateSiteIdを追加(任意)
  └─ (既存: /api/sites, /api/projects 等)

WordPress provisioning agent(index.php, lbs-wordpress内)
  ├─ /bulk-management(新規)— wp term create / wp plugin install / wp theme install をローカル実行(SLUG指定)
  ├─ /bulk-management/upload(新規)— アップロードされたzipから wp plugin install --force / wp theme install --force
  └─ /sync(既存)— targetsに"media"(wp-content/uploads)を追加

Database
  └─ 新規マイグレーション: bulk_operation_logsテーブル(V16、ZIPアップロードの保存先パス等を含む)

Storage
  └─ APIコンテナに新規永続ボリューム(bulk_upload_files)— アップロードされたzipをロールフォワード用に保管
```

## Phase 11 のスコープ

### 1. プロジェクト画面: 一括管理(カテゴリ/プラグイン/テーマ)・作業ログ・ロールフォワード
- [01-bulk-management.md](01-bulk-management.md)

### 2. プロジェクト環境同期: メディアファイル(wp-content/uploads)の同期対応
- [02-environment-media-sync.md](02-environment-media-sync.md)

### 3. サイト画面: WordPress新規構築時のテンプレートサイト選択・クローン
- [03-wordpress-template-clone.md](03-wordpress-template-clone.md)

### 4. サイト画面: サイト一覧の視認性向上
- [04-site-list-visibility.md](04-site-list-visibility.md)

対象外・スコープ外(全体):

- 外部登録(非managed / SSH含む)サイトへの一括管理・環境同期の対応
- プラグイン/テーマの自動有効化(`--activate`)
- 一括管理・同期処理の非同期ジョブ化・進捗表示UI(Phase10-03と同じ方針を踏襲)
- 作業ログの保持期間・自動削除ポリシー

## タスク実装順序

推奨順序: **02(メディア同期) → 03(テンプレートクローン) → 01(一括管理) → 04(サイト一覧視認性)**

理由:

1. **02**: 既存の`/sync`エンドポイント・`WordPressSyncClient`への追加のみで完結し、他タスクへの依存がない。最も小さく着手しやすい
2. **03**: 「新規サイト作成後にテンプレートから複製する」処理は、02で追加する`media`ターゲットを含む`WordPressSyncClient.sync()`をそのまま呼び出すだけで実現できるため、02の完了後に着手すると実装が一度で済む(02より先に着手する場合は`themes`/`plugins`/`db`のみで複製し、後日`media`を追加する2段階実装になる)
3. **01**: 新規のDBテーブル・エージェントエンドポイント・サービス層・フロントエンドパネルを一通り新設する、本フェーズで最もボリュームの大きいタスク。02/03と実装上の依存はないが、後述の通り並行実施も可能
4. **04**: 完全にフロントエンドのみの改善で、他タスクと依存関係がない。最後でも最初でも着手可能

## 相互依存性

```
02(メディア同期) ─→ 03(テンプレートクローン: WordPressSyncClient.syncにmediaターゲットが含まれることを前提とする)
01(一括管理・作業ログ) ─→ 02/03/04とは独立(並行実施可)
04(サイト一覧視認性) ─→ 他タスクとは独立(並行実施可)
```

## テスト整備(全体方針)

- Unit: 各タスクのサービス層・DTOに対するテストをタスクごとに整備する(詳細は各ファイル参照)
- E2E/実機: 一括管理の実行・ロールフォワード、メディア同期の実行、テンプレートからの新規サイト構築、サイト一覧の表示確認をdocker composeスタック上で実施する

## 未決事項・将来検討

- 外部登録(非managed)サイトへの一括管理・環境同期対応の要否
- 作業ログの保持世代・自動削除ポリシー
- プラグイン/テーマの個別バージョン指定・自動更新への対応
- 一括管理・同期処理がnginxの延長タイムアウト(300秒)を超える場合の非同期ジョブ化
