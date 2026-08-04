# Phase 15-05: VSCode拡張の記事プレビュー(マスター環境CSS適用)

## スコープ

VSCode拡張から現在編集中の記事をプレビューできるようにする。プロジェクトのマスター環境(test/production)サイトがWordPressであれば、そのサイトのCSSを適用した見た目で確認できる。

## 決定事項

| 項目 | 決定内容 |
|---|---|
| 表示方法 | VSCode Webviewパネル内にHTML表示(外部ブラウザは使わない) |
| Markdown→HTML変換 | `PostPublishService`と同じ`CustomTagRenderService`/`MarkdownRenderer`を再利用。ただしPlantUML埋め込み・画像アップロードは行わない(副作用のある外部呼び出しをプレビューで避けるため) |
| ローカル画像 | VSCode拡張側で`extractLocalImageReferences`を使ってbase64データURIへ変換してからAPIへ送信する(サーバー側にアップロードしない) |
| マスター環境CSS取得 | `Project#masterEnvironment`(test/production)に紐づくサイトが`WORDPRESS`の場合のみ、トップページの`<link rel="stylesheet">`を収集し中身を連結して返す。非WordPress・サイト未紐付け・接続失敗時は`available=false`+理由を返し、拡張側はCSSなしで警告表示 |
| 認可 | `ArticlePlanController`と同じ`requireProjectMemberOrAdmin` |

## 実装

### バックエンド

- `ArticlePreviewService`(新規): `renderHtml(projectId, markdown)` / `fetchMasterThemeCss(projectId)`
- `ArticlePreviewController`(新規、`/api/projects/{projectId}/preview`): `POST /render`, `GET /theme-css`
- DTO: `RenderPreviewRequest/Response`, `ThemeCssResponse`

### VSCode拡張

- `apiClient.ts`: `renderPreviewHtml`, `getMasterThemeCss`
- `previewPanel.ts`(新規): シングルトンWebviewパネル(view-only、`enableScripts: false`)。CSSを`<style>`として埋め込み、CSS取得に失敗した場合は警告バナーを表示
- `letsBlog.previewArticle`コマンド: front matterの`project_id`(なければ`selectProject`で選択中のプロジェクト)を使用

## テスト

- `ArticlePreviewServiceTest`: `MockRestServiceServer`でstylesheetリンク抽出・相対URL解決・非WordPress/未紐付け/接続失敗時のフォールバックを検証
- `ArticlePreviewControllerTest`: 認可デリゲートを検証
- VSCode拡張は`npm run compile`で型チェックのみ確認。Extension Development Hostでの実機プレビュー確認は本セッションでは未実施

## 既知の制限

- PlantUML図・記事内で新規に貼り付けたリモート未アップロード画像はプレビューに反映されない
- CSS取得は`<link rel="stylesheet">`のみを対象とし、JS注入スタイルやインラインの`<style>`ブロックは収集しない
