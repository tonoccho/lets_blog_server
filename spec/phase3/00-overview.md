# Phase 3: CMSアダプタ拡張(microCMS対応)

## 目的

Phase1で実装した `CmsAdapter` インターフェース・`WordPressAdapter` 実装は「WordPressのみ」を前提に設計されている。DBスキーマ・DTO・エンティティ・Web UI のあらゆる層がWordPress固有の命名・型で一直線に実装されている状態(`sites.wp_username`, `CmsCredentials(baseUrl, username, appPassword)`, ID型が`Long`固定 等)。

本Phase では、WordPress以外のCMS(日本製ヘッドレスCMS「**microCMS**」)への対応を段階的に実装し、汎用的なCMS抽象化レイヤーを確立する。これにより「別のCMSを追加する際は新しい `CmsAdapter` 実装クラスを1つ追加するだけで済む」という当初の設計思想を実現する。

## アーキテクチャ(更新)

```
既存(Phase 1):
  PostPublishService
        ↓ (直接注入)
  CmsAdapter (単一Bean@Component: WordPressAdapter のみ)
        ↓
  WordPressAdapter → WordPress REST API

改修(Phase 3):
  PostPublishService / MediaController / TaxonomyController / PlantUmlEmbedService
        ↓ (Factory経由)
  CmsAdapterFactory
        ├ resolveByType(WORDPRESS) → WordPressAdapter
        └ resolveByType(MICROCMS)  → MicroCmsAdapter
                ↓                           ↓
        WordPress REST API      microCMS Content API
```

## 決定済み事項

| 項目 | 決定内容 |
|---|---|
| 対象CMS | microCMS(日本製ヘッドレスCMS、REST API+APIキー認証、画像アップロード専用の管理API別途) |
| 対応範囲 | **本格対応**: DBスキーマ・エンティティ・DTO・Web UI・VSCode拡張まで全レイヤーで汎用化を実施 |
| テスト方針 | **WordPressAdapter の回帰テストを先に整備**(現状ユニットテストが皆無で手動実機検証のみのため。テスト実装後、既存機能を守りながら拡張に着手) |
| ID型の扱い | **Long→String に統一**(microCMSのコンテンツIDが数値ではなく英数字のため、API層・DTO・エンティティで共通のString型を使う) |
| CMS種別判別 | `CmsType` enum (`WORDPRESS`, `MICROCMS`) で管理、DB(`sites.cms_type`)にも永続化 |
| 既存データ互換性 | **段階的移行**: Phase2までのWordPressサイト向けカラム(`wp_username`, `wp_app_password_encrypted`)は残す。新規登録サイトは汎用カラム(`cms_type`, `credentials_encrypted`)を使用。レガシー資格情報からの自動変換(フォールバック)もSiteServiceに実装 |
| CMS別の認証方式 | CmsCredentials を sealed interface に変更、実装クラス(`WordPressCredentials`/`MicroCmsCredentials`)でCMS固有の必須フィールド群を定義 |

## Phase 3 のスコープ

実装項目:
1. ドメインモデルの汎用化(`CmsType`, `CmsCredentials`のsealed化, ID型String化)
2. `WordPressAdapter`の String ID対応 + 回帰テスト整備
3. `CmsAdapterFactory`新設と既存4コンポーネントの注入変更
4. DBスキーマ拡張マイグレーション + エンティティ/DTO/Service更新
5. `MicroCmsAdapter`実装 + テスト
6. Web管理フロントエンドのサイト登録UI(CMS種別選択・動的フォーム)
7. VSCode拡張の型更新(`wpPostId`→`String`)
8. エンドツーエンド実機検証(WordPress既存機能 + microCMS新規機能)

対象外・スコープ外:
- リモート常時稼働化・外部公開時のセキュリティ強化(Phase 2以降バックログに残す)
- その他のCMS(WordPress/microCMS以外)への対応(別途)

## タスク一覧

1. [01-domain-model](01-domain-model.md) — CmsType、sealed CmsCredentials、ID型String化、CmsAdapterシグネチャ変更
2. [02-wordpress-adapter-tests](02-wordpress-adapter-tests.md) — WordPressAdapter修正 + WordPressAdapterTest(回帰テスト)実装
3. [03-cms-adapter-factory](03-cms-adapter-factory.md) — CmsAdapterFactory新設、4箇所の注入変更
4. [04-database-schema](04-database-schema.md) — Flyway V3、エンティティ/Repository/Service/DTO更新
5. [05-microcms-adapter](05-microcms-adapter.md) — MicroCmsAdapter実装、MicroCmsAdapterTest、実機検証手順
6. [06-web-frontend](06-web-frontend.md) — サイト登録UI動的化(CMS選択)、型更新
7. [07-vscode-extension](07-vscode-extension.md) — wpPostId/wp_post_id型のString化

## 未決事項

- microCMS側でのカテゴリ/タグList API の予め用意が必須条件になるため、ユーザードキュメント/Readmeに記載する必要がある (本Phaseでは実装スコープ外、運用段階で別途)
- `CmsCredentials.sealed interface` を Jackson で JSON シリアライズする際の型情報ハンドリング(04-database-schema.md で詳細検討)
- パスワード再設定・監査ログなど、ユーザー管理の拡張機能(Phase3では対象外、Phase4以降で検討)
