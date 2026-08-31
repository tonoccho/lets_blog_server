# API リファレンス(Let's Blog VSCode拡張)

拡張が呼び出す仲介APIサーバーのエンドポイント一覧です。
実装はすべて [`src/apiClient.ts`](src/apiClient.ts) にあり、レスポンスの検証スキーマは
[`src/schemas.ts`](src/schemas.ts) にあります。

## 共通仕様

### ベースURL

設定 `letsBlog.serverUrl`(既定: `https://localhost`)。末尾のスラッシュは除去されます。

### 認証ヘッダ

| ヘッダ | 内容 | 付与される呼び出し |
| --- | --- | --- |
| `X-API-Key` | SecretStorageに保管されたAPIキー | ログイン系以外のすべて |
| `X-Actor-Id` | 操作の実行者のユーザーID | actorを渡した呼び出し |
| `X-Actor-Role` | 実行者のロール | actorを渡した呼び出し |

`/api/auth/login` と `/api/auth/totp/verify` は、APIキーを取得する前に呼ぶため
`X-API-Key` なしでの呼び出しがサーバー側で許可されています。

### リトライ

一時的な失敗(ネットワーク断・タイムアウト・5xx・429)のみ、指数バックオフで最大3回再試行します。
**再試行は冪等な呼び出しに限定**しており、下表の「リトライ」列が `-` のものは再試行しません
(タイムアウト後にサーバー側で処理が完了していた場合、重複投稿や二重割り当てになるため)。

### キャッシュ

「キャッシュ」列に記載のあるものは5分間キャッシュされます。
`assignIssue` / `acceptArticleStructure` / `deleteGeneratedImage` の直後は該当プロジェクトのキャッシュを、
ログイン・APIキー変更時は全キャッシュを破棄します。

---

## 認証

| メソッド | パス | 関数 | リトライ | 説明 |
| --- | --- | --- | --- | --- |
| POST | `/api/auth/login` | `login` | - | メールアドレス/パスワードでログイン。2FA未設定ならこの時点でAPIキーが発行される。 |
| POST | `/api/auth/totp/verify` | `verifyTotpLogin` | - | `login` が `twoFactorRequired=true` を返した場合にTOTPコードを検証し、APIキーを取得する。 |

**レスポンス** (`LoginResult`): `user` / `twoFactorRequired` / `apiKey`

---

## 投稿

| メソッド | パス | 関数 | リトライ | 説明 |
| --- | --- | --- | --- | --- |
| POST | `/api/posts/publish` | `publishPost` | - | Markdown記事を投稿/更新する(`multipart/form-data`)。 |
| DELETE | `/api/posts/{site}/{wpPostId}` | `deletePost` | - | 投稿を削除する(WordPressでは既定でゴミ箱へ移動)。 |

### `publishPost` のパート

| パート名 | 内容 |
| --- | --- |
| `site` | 投稿先のサイトキー |
| `title` / `slug` / `status` | front matter由来 |
| `categories` / `tags` | 各値を個別のパートとして繰り返し送る |
| `wpPostId` | 既存投稿の更新時のみ |
| `markdown` | 本文 |
| `images` | ローカル画像のバイナリ |
| `imageReferences` | `images` と同じ順序の、本文中の参照文字列 |
| `featuredImageFilename` | アイキャッチの参照文字列 |
| `publishScheduledAt` | 公開予定日時(ISO 8601、本番サイトのみ有効) |

> `imageReferences` を別途送るのは、マルチパートの `filename` ヘッダではパス区切りを含む
> 参照文字列が往復しない(サーバー/コンテナ側でベース名へ変換されうる)ためです。

**レスポンス** (`PublishResult`): `wpPostId` / `wpPostUrl` / `status`

---

## サイト・プロジェクト・ユーザー

| メソッド | パス | 関数 | リトライ | キャッシュ | 説明 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/sites` | `listSites` | ○ | ○ | 登録済みサイト一覧 |
| GET | `/api/projects` | `listProjects` | ○ | ○ | プロジェクト一覧 |
| GET | `/api/projects/{projectId}` | `getProject` | ○ | ○ | プロジェクト詳細(ローカル/テスト/本番のサイト紐付けを含む) |
| GET | `/api/users` | `listUsers` | ○ | - | ユーザー一覧 |

---

## AI 生成

| メソッド | パス | 関数 | リトライ | 説明 |
| --- | --- | --- | --- | --- |
| POST | `/api/ai/draft` | `askAi` | ○ | 下書き生成/校正/要約 |
| POST | `/api/ai/section` | `generateSection` | ○ | セクション本文/リード文の生成(中断可) |
| POST | `/api/ai/tags` | `suggestTags` | ○ | カテゴリ/タグの提案 |
| POST | `/api/ai/image` | `generateImage` | - | 画像生成(結果がサーバーに保存されるため再試行しない、中断可) |
| GET | `/api/ai/image-options` | `getImageGenerationOptions` | ○ | 利用可能なモデル/サンプラー/LoRA一覧(キャッシュあり) |

生成系のレスポンスは `result` / `sources` / `searchNote` を含みます。
`searchNote` は出典が無い理由を示すもので、出典があるかのように装わないために使います。

---

## 記事プランニング(GitHub連携)

| メソッド | パス | 関数 | リトライ | キャッシュ | 説明 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/projects/{id}/article-plan/issues?state=` | `listUnassignedIssues` | ○ | ○ | Issue一覧(拡張側で未割り当てのみに絞り込む) |
| GET | `/api/projects/{id}/article-plan/issues/{n}/description` | `getIssueDescription` | ○ | - | Issue本文 |
| GET | `/api/projects/{id}/article-plan/categories` | `listExistingCategories` | ○ | ○ | マスター環境サイトの既存カテゴリ |
| POST | `/api/projects/{id}/article-plan/chat` | `postPlanChat` | - | - | 壁打ちチャット(セッションが記録されるため再試行しない、中断可) |
| POST | `/api/projects/{id}/article-plan/suggest-structure` | `suggestArticleStructure` | ○ | - | 記事構成の提案(中断可) |
| POST | `/api/projects/{id}/article-plan/suggest-metadata` | `suggestMetadata` | ○ | - | タイトル/スラッグ/カテゴリ/タグの提案(中断可) |
| POST | `/api/projects/{id}/article-plan/issues/{n}/accept-structure` | `acceptArticleStructure` | - | - | 構成をIssue本文へ書き込む |
| POST | `/api/projects/{id}/article-plan/issues/{n}/assign` | `assignIssue` | - | - | Issueを自分に割り当てる |

---

## プレビュー

| メソッド | パス | 関数 | リトライ | キャッシュ | 説明 |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/projects/{id}/preview/render` | `renderPreviewHtml` | ○ | - | Markdown→HTML変換(カスタムタグ展開を含む) |
| GET | `/api/projects/{id}/preview/theme-css?siteId=` | `getThemeCss` | ○ | ○ | テーマCSSの取得。`siteId` 省略時はマスター環境サイト。 |

`ThemeCssResult` は `available=false` のとき `reason` に取得できなかった理由が入ります
(サイト未紐付け・WordPress以外・接続失敗など)。

---

## 生成画像ギャラリー

| メソッド | パス | 関数 | リトライ | キャッシュ | 説明 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/generated-images?projectId=` | `listGeneratedImages` | ○ | ○ | 保存済み生成画像の一覧(メタデータのみ) |
| GET | `/api/generated-images/{id}/file` | `downloadGeneratedImage` | ○ | - | 画像バイナリ(PNG) |
| DELETE | `/api/generated-images/{id}` | `deleteGeneratedImage` | - | - | サーバーから削除 |

---

## エラーレスポンスの扱い

| ステータス | 拡張側の扱い |
| --- | --- |
| 2xx | Zodスキーマで検証。不一致なら `ResponseValidationError` |
| 400 / 403 / 404 / 409 / 413 | `ApiError`。リトライせず、ステータス別の対応策を添えて通知 |
| 401 | `ApiError`。再ログイン/APIキー再設定を促す |
| 429 / 5xx | `ApiError`。冪等な呼び出しのみリトライ |
| 接続不可 | `NetworkError`。serverUrl・サーバー状態・証明書設定の確認を促す |
| タイムアウト | `TimeoutError`。`letsBlog.requestTimeoutMs` の調整を促す |
| 利用者による中断 | `CancelledError`。失敗として扱わない |
