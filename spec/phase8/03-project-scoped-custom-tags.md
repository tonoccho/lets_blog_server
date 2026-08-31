# Phase 8-3: カスタムタグのプロジェクトスコープ化

## 目的

Phase 7 で実装されたカスタムタグは現在グローバル(全サイト共通)である。Phase 8 では、カスタムタグを「プロジェクトスコープ」に対応させ、各プロジェクトが独自のカスタムタグセットを持つことができるようにする。同時に既存のグローバルタグ運用との後方互換性を維持する。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| プロジェクトスコープの実装 | `custom_tags` テーブルに `project_id`(nullable FK to `projects.id`) 列を追加。`project_id IS NULL` は**グローバルタグ**(プロジェクト非依存、既存レコード含む) |
| tagName の一意性 | `UNIQUE (tagName, project_id)`: 同じプロジェクト内では `tagName` は一意だが、異なるプロジェクト間では同じ名前のタグを持つことができる。グローバルタグとプロジェクトタグ間でも同名許可 |
| タグレンダリングロジック | 投稿をレンダリング際、当該投稿の属するサイトが所属するプロジェクト(02で紐付されたもの)を判定 → 「そのプロジェクトのタグ + グローバルタグ」を対象にレンダリング |
| グローバルタグの継続性 | 既存の `custom_tags` レコード(project_id NULL)は引き続き全サイトで利用可能。プロジェクト所属サイトでも常にグローバルタグは対象 |

## アーキテクチャ・実装詳細

### 1. Database スキーマ拡張(`V14__add_custom_tags_project_scope.sql`)

```sql
ALTER TABLE custom_tags ADD COLUMN project_id BIGINT;
ALTER TABLE custom_tags ADD FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;
ALTER TABLE custom_tags DROP INDEX tagName;
ALTER TABLE custom_tags ADD UNIQUE KEY unique_tag_per_project (tagName, project_id);
```

- `project_id` は nullable(グローバルタグ用)
- 既存レコードには `project_id = NULL` が設定されたまま
- 一意性制約: `(tagName, project_id)` の組み合わせで一意(NULL同士も1レコードのみ許可)

### 2. バックエンド実装

#### `CustomTag` エンティティ拡張
- `api/src/main/java/com/letsblog/api/domain/CustomTag.java`
  - 新フィールド: `projectId` (Long, nullable)
  - 既存フィールド継承: `id`, `tagName`, `htmlTemplate`, `description`, `cssContent`, `createdAt`, `updatedAt`

#### `CustomTagRepository` 拡張
- 既存メソッド(`findByTagName` など)をプロジェクトコンテキスト対応に改修
  - `findByTagNameAndProjectId(tagName, projectId)`: プロジェクトスコープ検索
  - `findByProjectIdAndTagName(projectId, tagName)`: 同上(順序反転)
  - `findByProjectIdOrProjectIdIsNull(projectId)`: プロジェクト所属 + グローバルタグを取得
  - 既存の `findByTagName(tagName)`: グローバルタグのみ取得(互換性維持)

#### `CustomTagService` 改修
- `createCustomTag(tagName, htmlTemplate, description, cssContent, projectId)`
  - `projectId` null 許可(グローバルタグ作成)
  - 一意性チェック: `(tagName, projectId)` のタプルで検証
- `updateCustomTag(tagId, htmlTemplate, description, cssContent)`
  - 既存実装(projectId の変更は許可しない)
- `deleteCustomTag(tagId)`
  - 既存実装(変更なし)
- `listCustomTags(projectId)` : projectId null なら全グローバルタグ、null 以外なら「そのプロジェクトのタグ + グローバル」
  - 戻り値: `List<CustomTag>`

#### `CustomTagRenderService` 改修(重要)
- `renderPost(post, htmlContent)` メソッド改修
  - post の属するサイトが所属するプロジェクト `projectId` を取得
  - `customTagService.listCustomTags(projectId)` で対象タグセット取得
  - 既存の正規表現ベースレンダリング実施(対象タグセット範囲内のみ)

#### `CustomTagController` 拡張
- `GET /api/custom-tags?projectId={id}` — プロジェクトスコープタグ一覧
  - クエリパラム `projectId` (optional)
  - projectId なし → グローバルタグのみ
  - projectId あり → そのプロジェクト + グローバルタグ
  - レスポンス: `List<CustomTagResponse>`
- `POST /api/custom-tags` — カスタムタグ作成
  - リクエスト: `CreateCustomTagRequest` に `projectId` フィールド追加(optional)
  - 認可: 管理者のみ
- `PUT /api/custom-tags/{id}` — タグ編集
  - `projectId` の変更は不可(リクエストに含まれていても無視)
- `DELETE /api/custom-tags/{id}` — タグ削除
  - 認可: 管理者のみ

### 3. フロントエンド実装

#### `/custom-tags` ページの拡張
- `web/src/app/custom-tags/page.tsx`: Server Component
  - 既存のグローバルタグ一覧表示を保持
  - `CustomTagManager.tsx` に `projectId` コンテキスト追加(optional)

#### オプション案: プロジェクト詳細内にタグ管理パネルを配置
- `web/src/app/projects/[id]/page.tsx` に「カスタムタグ」セクション追加
  - そのプロジェクトのみのタグを表示・管理
  - グローバルタグ参照のみ(編集不可)

#### `CustomTagForm.tsx` 改修
- `projectId` 選択フィールド追加(ドロップダウン)
  - グローバル（プロジェクト外）
  - プロジェクト選択肢(管理者が登録したプロジェクト一覧)

#### `actions.ts` 改修
- `createCustomTagAction` に `projectId` パラメータ追加(optional)
- `updateCustomTagAction` はプロジェクト変更不可

## スコープ・実装項目

実装対象:

- [x] `V14__add_custom_tags_project_scope.sql` マイグレーション作成
- [x] `CustomTag` エンティティに `projectId` 追加
- [x] `CustomTagRepository` クエリメソッド拡張
- [x] `CustomTagService` メソッド改修・追加
- [x] `CustomTagRenderService` のレンダリングロジック改修(プロジェクト所属判定)
- [x] `CustomTagController` エンドポイント拡張
- [x] Web管理画面: `/custom-tags` ページをプロジェクトコンテキスト対応に拡張
- [x] テスト整備

対象外・スコープ外:

- プロジェクト間でのタグ共有・複製機能
- グローバルタグのプロジェクトスコープ化への自動移行
- タグの使用箇所追跡機能(削除前の確認など、後続フェーズで検討)

## 実装順序

1. `V14__add_custom_tags_project_scope.sql` 作成・マイグレーション実行
2. `CustomTag` エンティティ・リポジトリ拡張
3. `CustomTagService` 改修
4. `CustomTagRenderService` 改修(投稿レンダリング時のプロジェクト判定)
5. `CustomTagController` エンドポイント拡張
6. Web画面実装
7. テスト整備(既存テストの互換性確認 + 新ケース追加)
8. 実機検証

## テスト整備

- `CustomTagServiceTest` 拡張
  - `createCustomTag_プロジェクトスコープ作成` : projectId指定時の作成確認
  - `listCustomTags_プロジェクトスコープ取得` : 対象プロジェクト + グローバルタグ取得確認
  - `unique_制約_同じプロジェクト内で同名タグ作成失敗` : 同じ projectId での tagName 重複エラー
  - `unique_制約_異なるプロジェクト間では同名タグ許可` : 異なる projectId での同名許可確認
- `CustomTagRenderServiceTest` 拡張
  - `renderPost_プロジェクトスコープタグレンダリング` : 所属プロジェクトのタグが正しくレンダリングされることを確認
  - `renderPost_グローバルタグも対象` : グローバルタグも同時にレンダリングされることを確認
  - `renderPost_異なるプロジェクトタグは対象外` : 異なるプロジェクトのタグはレンダリング対象外確認
- `CustomTagControllerTest` 拡張
  - 各エンドポイントのプロジェクトスコープ対応確認

## 実機検証

- 既存のグローバルタグ(project_id NULL)が全プロジェクトで参照可能なことを確認
- プロジェクトAの新規カスタムタグ作成 → プロジェクトBの投稿でそのタグがレンダリング対象外であることを確認
- 同じ名前のタグを異なるプロジェクトで作成可能なことを確認
- 既存の投稿(プロジェクト非所属)にグローバルタグが適用されることを確認

