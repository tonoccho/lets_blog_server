# Phase 7: WordPress自動プロビジョニング・カスタムタグレンダリング機能

## 目的

Phase 6までで運用基盤(リバースプロキシ・セットアップマニュアル)が整った。Phase 7では、以下2つの新機能を追加する:

1. **WordPress自動プロビジョニング**: これまでの「外部の既存WordPressサイトを登録する」フローに加え、Let's Blog Server自身が保持する常駐WordPressコンテナ上に、管理画面の操作だけで新規WordPressインスタンスをその場で構築できるようにする。
2. **カスタムショートコードタグ**: Markdown→HTML変換パイプラインに、管理画面でDB駆動に定義できる独自のショートコード的タグ機構を追加する。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| WordPress設置方式 | 常駐`wordpress`コンテナ1つに対し、サイトごとにサブディレクトリ(`/var/www/html/sites/{slug}/`)で独立したWordPressコアを設置する「サブディレクトリ設置型」。WP Multisiteは使わない |
| DB方式 | 共有MySQLに、サイトごとの専用データベース(`wp_{slug}`)を作成(スキーマ分離)。MySQLユーザーはサイトごとに分離せず、既存の共通`lbs_app`ユーザーへ都度`GRANT`する |
| プロビジョニング実行経路 | WordPressコンテナ内に**内部限定のプロビジョニングエージェント**(共有シークレットで保護、nginxには公開しない)を追加。Spring Boot APIからHTTP経由で呼び出し、エージェント側でWP-CLIを実行する。Docker socketをAPIコンテナにマウントする方式(`docker exec`)は採用しない |
| ルーティング | nginxに`/sites/`プレフィックスのlocationを追加。phpMyAdminと異なりrewriteは行わず、Apache DocumentRoot構造とURLパスをそのまま一致させる |
| 既存プロビジョニング機能との関係 | WP自動構築完了後、生成したURL・Application Passwordを使い既存の`SiteService.register()`(Phase4/5実装済み、カテゴリ/タグ/著者の自動作成を含む)をそのまま呼び出す。インフラ構築とリソース初期化が1回のサイト作成操作で完結する |
| サイト削除時の挙動 | WordPress自動構築サイトを削除する場合、WPインスタンス(サブディレクトリ)と専用DBも連動削除する。外部登録サイト(既存フロー)の削除では、このインフラ削除処理は行わない |
| カスタムタグのスコープ | 全サイト共通のグローバル機能(PlantUML埋め込みと同様、サイト非依存の変換ステップ) |
| カスタムタグ記法 | フェンス形式 `:::tagname key="value"\n本文\n:::`。PlantUMLの ` ```plantuml ` (バッククォート3連)とは別の記法体系(コロン3連)にすることで、名前の衝突判定ロジックを実装せず構造的に衝突を回避する |
| カスタムタグテンプレートの信頼境界 | admin権限者のみ編集可能。テンプレートに任意HTMLの記述を許可し、サニタイズ処理は追加しない(投稿者側の信頼境界と同列に扱う) |

## アーキテクチャ

```
1. WordPress自動プロビジョニング:

管理画面「サイト新規作成」(WordPressをこのサーバーに構築)
    ↓
[SiteController] → [WordPressProvisioningService]
    ↓ HTTP (内部ネットワークのみ、共有シークレット)
[wordpress コンテナ内 provisioning agent]
    ├─ mkdir /var/www/html/sites/{slug}
    ├─ CREATE DATABASE wp_{slug}; GRANT ... TO lbs_app (MySQL rootで実行)
    ├─ wp core download / wp config create / wp core install
    └─ wp user application-password create (admin向け発行)
    ↓ (生成されたURL・Application Passwordを返却)
[SiteService.register()] (Phase4/5実装済み・カテゴリ/タグ/著者プロビジョニングを含む)
    ↓
sites テーブルへ登録(自動構築であることを示すフラグ・slugを追加保存)

サイト削除時:
[SiteController.delete] → [WordPressProvisioningService.deprovision]
    ↓ HTTP
[provisioning agent] → サブディレクトリ削除 + DROP DATABASE wp_{slug}
    ↓
sites テーブルから削除

2. カスタムショートコードタグ:

[PostPublishService.publish()]
    ↓ Markdown本文
[CustomTagRenderService] (PlantUmlEmbedServiceと同型の正規表現前処理)
    ├─ custom_tags テーブルから定義取得
    └─ :::tagname ... ::: ブロックをHTMLテンプレートへ置換
    ↓
[PlantUmlEmbedService] → [MarkdownRenderer] → HTML
```

## Phase 7 のスコープ

実装項目:

### 1. WordPress自動プロビジョニング
- [01-wordpress-provisioning](01-wordpress-provisioning.md)
  - `docker-compose.yml` に `wordpress` サービス追加(カスタムDockerfile、WP-CLI同梱)
  - WordPressコンテナ内の内部限定プロビジョニングエージェント実装
  - nginx `/sites/` ルーティング追加
  - `sites` テーブル拡張(自動構築フラグ・slug列)
  - `WordPressProvisioningService` / `WordPressProvisioningClient` 実装
  - `SiteController` の新規作成・削除エンドポイント拡張
  - Web管理画面: サイト作成フォームへの選択肢追加、削除UI追加
  - 実機検証(サイト作成→WP自動構築→投稿→削除まで一気通貫)

### 2. カスタムショートコードタグ
- [02-custom-tags](02-custom-tags.md)
  - Flyway `V9__add_custom_tags.sql` (`custom_tags` テーブル)
  - `CustomTag` エンティティ・リポジトリ・DTO
  - `CustomTagService`(CRUD、admin権限限定)・`CustomTagController`(`/api/custom-tags`)
  - `CustomTagRenderService`(正規表現によるMarkdown前処理)
  - `PostPublishService` への組み込み
  - Web管理画面 `/custom-tags` ページ(CRUD UI)
  - テスト整備(`CustomTagRenderServiceTest`, `CustomTagServiceTest`)

対象外・スコープ外:

- WordPress Multisiteネットワーク機能の利用
- 独自ドメイン紐付け(現状は `https://localhost/sites/{slug}` 固定)
- サイトごとのMySQLユーザー分離
- カスタムタグテンプレートのサニタイズ・許可タグ制限
- flexmarkの拡張(Extension/NodeParser)機構を用いたAST的統合(正規表現前処理方式を採用)
- microCMSサイトへのWordPress自動プロビジョニング相当機能(WordPress専用)

## タスク一覧

1. [01-wordpress-provisioning](01-wordpress-provisioning.md) — WordPress自動プロビジョニング機能の実装
2. [02-custom-tags](02-custom-tags.md) — カスタムショートコードタグ機能の実装

## 実装順序

1. カスタムショートコードタグ機能(既存インフラの変更を伴わず、独立して実装・検証可能なため先行)
2. WordPress自動プロビジョニング基盤(Docker/nginx/エージェント)
3. Spring Boot側のプロビジョニング連携・既存プロビジョニングとの統合
4. Web管理画面の両機能UI追加
5. エンドツーエンド実機検証

## 未決事項

- WordPress自動構築サイトの数が増えた場合の、共有WordPressコンテナのリソース(PHPプロセス数・メモリ)上限の検討
- プロビジョニングエージェントの認証方式(現状は共有シークレットヘッダのみを想定、将来的な強化の要否)
- カスタムタグテンプレートのプレースホルダ仕様の拡張性(属性の型・繰り返し対応等)
