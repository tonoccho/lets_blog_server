# Phase 12: プロジェクト詳細画面の改善(環境同期の制限・一括管理の比較テーブル化・マスター環境設定)

## 目的

以下3件の要望に対応する。

1. 環境同期(Phase10-03で実装済み、Phase11-02でメディアを追加済み)について、**ローカル環境を同期元に指定できない**制限を追加する
2. 一括管理(Phase11-01で実装済み・Phase11拡張でカテゴリ編集/削除・プラグイン/テーマ有効化/無効化/削除に対応済み)を、**カテゴリ/プラグイン/テーマ/タグの4タブ**構成に再編し、各タブを「3環境(ローカル/テスト/本番)の値を横並びで比較し、マスター環境と異なる値は赤字表示し、項目単位で編集・削除・マスターへの同期ができる」比較テーブルUIへ置き換える。カテゴリ・タグは20件ごとにページネーションする
3. プロジェクトごとに、テスト環境・本番環境のどちらを**マスター環境**にするか設定できるようにする(ローカルはマスターにできない)。マスター環境は2の比較テーブルの基準として使われる

## 前提・決定事項(全体)

| 項目 | 決定内容 |
|---|---|
| 対象範囲 | 引き続き`managedWordpress = true`の環境同士に限定する(Phase10-03/Phase11-01と同じ制約)。外部登録(REST/SSH)サイトが紐付いている環境スロットは比較テーブル上に表示はするが、値取得不可のため常に「対象外」表示とし、diff判定・編集・削除・同期の対象から除外する |
| マスター環境とテスト/本番間の同期(要望2/3)と、環境まるごと同期(要望1、Phase10-03)の関係 | **完全に独立した別機能**として扱う。要望1はディレクトリ・DBまるごとを`from`→`to`でコピーする(wp-cliの`db export`/`db import`相当、同期元/先を都度選択)。要望2/3はカテゴリ・タグ・プラグイン・テーマを**項目単位**でマスター環境の値に合わせるワンクリック操作で、`from`/`to`の指定は不要(常にマスター→非マスターの向き)。既存の[EnvironmentSyncPanel.tsx](../../web/src/app/projects/%5Bid%5D/EnvironmentSyncPanel.tsx)とは別のUI・APIとして実装する |
| 一括管理UIの位置づけ | Phase11で実装した「対象→操作」の二階層タブ+フォーム入力方式(作成/編集/削除フォームを都度送信し、全managed環境へ同一値を適用する`execute()`)は**比較テーブルUIに置き換えて廃止**する。作業ログ一覧・ロールフォワード機能(`BulkOperationLog`・`replay()`)はそのまま維持し、比較テーブル経由の操作も引き続き`bulk_operation_logs`に記録する |
| 既存コードの再利用方針 | `BulkOperationType`(`CATEGORY_CREATE`等のenum)・`BulkOperationLog`のカラム構成(`category_slug`/`category_parent_slug`/`category_target_slug`/`category_description`)・provision-agentの`wp term create/update/delete`ハンドラは、名称はそのままにタグ(`post_tag`)にも共用する形で拡張し、マイグレーション(スキーマ変更)は発生させない |
| 実現方式 | 引き続き常駐WordPressコンテナ内の内部限定エージェント`wordpress/provision-agent/index.php`にハンドラを追加・拡張する方式を踏襲する(`WordPressBulkManagementClient`と同じ内部限定・共有トークン認証パターン) |

## アーキテクチャ概要

```
Web管理画面(Next.js)
  ├─ プロジェクト詳細画面: 環境同期パネル(既存) — 同期元にローカルを選択不可にする制限を追加
  ├─ プロジェクト詳細画面: マスター環境設定(新規) — テスト/本番のいずれかを選択
  └─ プロジェクト詳細画面: 一括管理パネル(再編) — カテゴリ/プラグイン/テーマ/タグの4タブ、比較テーブル+作業ログ/ロールフォワード

API(Spring Boot)
  ├─ POST /api/projects/{id}/environments/sync(既存)— fromがlocalの場合400を返す制限を追加
  ├─ PUT  /api/projects/{id}/master-environment(新規)— test/productionを設定
  ├─ GET  /api/projects/{id}/bulk-management/categories/comparison(新規)
  ├─ GET  /api/projects/{id}/bulk-management/tags/comparison(新規)
  ├─ POST /api/projects/{id}/bulk-management/categories/sync・/tags/sync(新規)
  ├─ POST /api/projects/{id}/bulk-management/categories/delete-all・/tags/delete-all(新規)
  ├─ POST /api/projects/{id}/bulk-management/apply(新規、単一環境向け。旧`POST /bulk-management`を置き換え)
  ├─ GET  /api/projects/{id}/bulk-management/plugins/comparison・/themes/comparison(新規)
  ├─ POST /api/projects/{id}/bulk-management/plugins/delete-all・/themes/delete-all(新規)
  ├─ POST /api/projects/{id}/bulk-management/upload(既存、変更なし)— zipアップロードは引き続き全managed環境へ一括インストール
  ├─ POST /api/projects/{id}/bulk-management/replay・GET /bulk-management/logs(既存、変更なし)
  └─ (廃止) POST /api/projects/{id}/bulk-management(JSON、全managed環境へ同一値適用) ・ GET /bulk-management/categories(参照環境1つのみ) — 比較テーブルUIからは呼ばれなくなるため削除する

WordPress provisioning agent(index.php, lbs-wordpress内)
  ├─ /bulk-management: category_*ハンドラをtaxonomy引数化して共用し、tag_create/tag_edit/tag_delete(taxonomy=post_tag)を追加
  ├─ /categories(既存)に加え /tags(新規、同じ構造でtaxonomy=post_tag)
  └─ /plugins・/themes(新規)— 1環境分の一覧(name+status)を返す。比較テーブルの初期表示に使用
```

## Phase 12 のスコープ

### 1. 環境同期: ローカル環境を同期元に指定不可にする制限
- [01-environment-sync-source-restriction.md](01-environment-sync-source-restriction.md)

### 2. プロジェクトのマスター環境設定(テスト/本番のいずれか)
- [02-master-environment-setting.md](02-master-environment-setting.md)

### 3. 一括管理: カテゴリ・タグの比較テーブル化
- [03-bulk-management-category-tag.md](03-bulk-management-category-tag.md)

### 4. 一括管理: プラグイン・テーマの比較テーブル化
- [04-bulk-management-plugin-theme.md](04-bulk-management-plugin-theme.md)

対象外・スコープ外(全体):

- 外部登録(非managed / SSH含む)サイトへの一括管理・環境同期・マスター同期の対応
- カテゴリ/タグの階層全体を一括で同期する自動再帰処理(親→子の順で手動同期する運用とする)
- プラグイン/テーマのバージョン指定・自動更新・プレミアムライセンス投入
- 比較テーブル・同期処理の非同期ジョブ化・進捗表示UI(Phase10-03/Phase11-01と同じ方針を踏襲)

## タスク実装順序

推奨順序: **01(同期元制限) → 02(マスター環境設定) → 03(カテゴリ・タグ比較) → 04(プラグイン・テーマ比較)**

理由:

1. **01**: 既存の`ProjectEnvironmentSyncService`へのバリデーション追加のみで完結し、他タスクへの依存がない。最も小さく着手しやすい
2. **02**: `Project`エンティティへのフィールド追加が03/04の比較テーブル(マスター環境の解決)の前提になるため、先に完了させる
3. **03**: `BulkManagementService`に新設する`applyToEnvironment()`(単一環境への適用、旧`execute()`を置き換える)を04がそのまま再利用するため、03を先に実装する
4. **04**: 03で新設した`applyToEnvironment()`・比較テーブルのUIパターンを流用するため03の後に着手する

## 相互依存性

```
02(マスター環境設定) ─→ 03(カテゴリ・タグ比較: マスター環境の解決が前提)
02(マスター環境設定) ─→ 04(プラグイン・テーマ比較: 同上)
03(applyToEnvironment新設) ─→ 04(同メソッドを再利用)
01(同期元制限) ─→ 02/03/04とは独立(並行実施可)
```

## テスト整備(全体方針)

- Unit: 各タスクのサービス層・DTOに対するテストをタスクごとに整備する(詳細は各ファイル参照)
- E2E/実機: 同期元制限の動作確認、マスター環境設定の切り替え、カテゴリ/タグ/プラグイン/テーマ比較テーブルでの編集・削除・同期をdocker composeスタック上で実施する

## 未決事項・将来検討

- 外部登録(非managed)サイトへの一括管理・環境同期・マスター同期対応の要否
- カテゴリ/タグの階層全体を1クリックで同期する自動再帰処理の要否
- プラグイン/テーマの世代管理(バージョン指定インストール・ロールバック)
- 比較テーブルの行数が多い場合の検索・絞り込みUI(現状はページネーションのみ)
