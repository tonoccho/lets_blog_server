# Phase 15-06: VSCode拡張からtest環境サイトへのワンクリック投稿

## スコープ

VSCode拡張から、選択中プロジェクトの「test環境」サイトへワンクリックで記事を投稿できるようにする。

## 決定事項

バックエンド変更は不要。既存`GET /api/projects/{id}`(認可なし、API keyのみで呼び出し可)が`testSite.siteKey`を含むレスポンスを返すため、これをそのまま利用する。

## 実装

### VSCode拡張

- `apiClient.ts`: `getProject(serverUrl, apiKey, actor, projectId)`を追加(`GET /api/projects/{id}`)
- `extension.ts`:
  - 既存`commandPublish`から画像収集・`publishPost`呼び出し・front matter書き戻し・完了通知を`publishToSite(context, editor, siteKey)`ヘルパーへ切り出し(`letsBlog.publish`と共有するための最小限のリファクタ)
  - `letsBlog.publishToTestEnvironment`(新規): front matterの`project_id`(なければ選択中のプロジェクト)から`getProject`でtest環境サイトを解決し、`publishToSite`を呼ぶ。test環境サイトが未紐付けの場合はエラーメッセージを表示

## テスト

- `npm run compile`で型チェックのみ確認。バックエンド変更なしのため既存のバックエンドテストに影響なし。Extension Development Hostでの実機投稿確認は本セッションでは未実施
