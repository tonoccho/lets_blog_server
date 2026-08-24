# 認可マトリクス (legacy-api)

issue #568。`services/legacy-api` の全REST APIエンドポイント(`@GetMapping`/`@PostMapping`/
`@PutMapping`/`@DeleteMapping`/`@PatchMapping` の合計179件、30コントローラファイル31クラス
[`HealthController`を含む]。`grep -rhoE '@(Get|Post|Put|Delete|Patch)Mapping' controller/*.java | wc -l`
で確認)について、現行の認可チェックと実際に返るステータスを一覧化する。#573でDiagramController/
GeneratedImageController/RenderController(3ファイル、計12エンドポイント)をmedia-serviceへ移設した
ため、当初の191件・33ファイルから減少している(このマトリクス自体は移設時に更新した)。

対応する統合テストは
`services/legacy-api/src/test/java/com/letsblog/api/integration/AuthorizationMatrixIntegrationTest.java`。

## 現行の認可モデル(2層構造)

legacy-apiはまだ `@PreAuthorize` ベースの宣言的認可へ移行していない
(`SecurityConfig`のjavadoc参照。移行はWeb/VSCode拡張のKeycloakトークン対応(#564/#565)と
カットオーバー手順の確定(#591)より後に行う予定)。現行は以下の2層で認可を行っている。

1. **認証ゲート(→401)**: `com.letsblog.api.config.ApiKeyAuthFilter`(`OncePerRequestFilter`。
   Spring MVCのディスパッチより前に動く)が、`/api/` で始まる全パスに対して `X-API-Key` ヘッダを
   要求する。キーが無い、または`ApiKeyService.resolveUserId(...)`で解決できない不正なキーの場合、
   コントローラメソッドや `@Valid` によるボディ検証に到達する前に即座に401を返す。
   例外として以下は`X-API-Key`なしでも到達できる(`PUBLIC_AUTH_PATHS`):
   - `GET /api/health`
   - `POST /api/auth/login`
   - `POST /api/auth/totp/verify`
   - `POST /api/auth/signup`
   - `POST /api/auth/setup`
   - `GET /api/auth/setup-status`
   - `POST /api/auth/password-reset/request`
   - `POST /api/auth/password-reset/confirm`

   上記以外の全エンドポイントは、リクエストボディやパスパラメータの妥当性に関わらず、
   `X-API-Key` が無い/不正であれば必ず401を返す。

2. **ロール/所有権ゲート(→403)**: コントローラメソッド(または委譲先のサービスメソッド)の
   先頭付近で `AdminAuthorizationService` の以下いずれかを呼ぶ場合がある。
   - `requireAdmin()` — 呼び出し元が `X-Actor-Role: admin`(またはJWT解決結果がadmin)であることを
     要求する。満たさなければ `ForbiddenException` を投げ、`GlobalExceptionHandler` が403へ変換する。
   - `requireProjectMemberOrAdmin(projectId)` — 呼び出し元がadmin、またはそのプロジェクトの
     メンバー(`ProjectUserRepository.findByProjectIdAndUserId`で判定)であることを要求する。
     別プロジェクトのメンバーであっても、対象プロジェクトのメンバーでなければ403。
   - `requireSelfOrAdmin(userId)` — `AdminAuthorizationService`に定義されているが、
     **現時点でどのコントローラからも呼ばれていない(未使用)**。
   - `PermissionAuthorizationService.requirePermission(Permission)` — 同様に定義されているが
     **現時点でどのコントローラからも呼ばれていない(未使用)**。

   上記チェックを直接コントローラで呼ばず、委譲先のサービスメソッド内で呼んでいるケースも
   複数ある(表の「認可チェック」列に「(service層)」と注記)。挙動としては同じくコントローラの
   処理が実行される前に例外が投げられる。

   **いずれのチェックも呼ばないエンドポイント**は、有効な `X-API-Key` さえ持っていれば
   ロール・プロジェクト所属に関わらず到達できる。これは既知のギャップであり、本Issueでは
   修正せず、末尾の「既知のギャップ」節に事実として列挙するに留める。

## 表の見方

- **現行: 未認証**: `X-API-Key` なし/不正の場合に実際に返るステータス。公開エンドポイントは
  「該当なし(公開エンドポイント)」。
- **現行: 権限不足**: ロール/所有権チェックがある場合に、それを満たさない認証済みactorが
  実際に返されるステータス。チェックが無いエンドポイントは「該当なし」。
- **現行: 権限あり**: チェックを満たす場合(またはチェックが無い場合の認証済みactor)に、
  後続のビジネスロジックへ到達すること(「認可OK」)を示す。具体的な2xxコードまでは主張しない。
- **あるべき(#591カットオーバー後)**: 認可チェックがある行は現状維持が期待値
  (未認証→401、権限不足→403、権限あり→通過)。認可チェックが無い行は、断定的な役割を
  書かず「要検討(本Issueの対象外)」とする。

---

## AiController (8エンドポイント、ベースパスなし)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/ai/draft | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | プロジェクト非依存のAI下書き生成。projectIdを取らないためプロジェクト単位の制御ができない |
| POST /api/ai/ask | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 同上 |
| POST /api/ai/tags | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 同上 |
| POST /api/ai/proofread | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 同上 |
| POST /api/ai/image | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 同上 |
| GET /api/ai/image-options | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | projectIdは任意パラメータだが未チェック |
| POST /api/ai/section | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 同上 |
| POST /api/projects/{projectId}/ai/generate-image-prompt | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | 唯一projectIdを取り、正しくチェックしている |

## AppSettingController (2エンドポイント、ベースパス `/api/system-settings/app-settings`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/system-settings/app-settings | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `AppSettingService.getAllSettings()`内でrequireAdmin() |
| PUT /api/system-settings/app-settings | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `AppSettingService.updateSettings()`内でrequireAdmin() |

## ArticlePlanController (15エンドポイント、ベースパス `/api/projects/{projectId}/article-plan`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST .../chat | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../sessions | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../sessions/{sessionId} | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../sessions/by-issue/{issueNumber} | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../issues/{issueNumber}/description | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../issues | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST .../suggest-titles | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST .../accept | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST .../suggest-structure | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST .../issues/{issueNumber}/accept-structure | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST .../suggest-metadata | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../categories | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../categories/hierarchy | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../tags | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST .../issues/{issueNumber}/assign | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |

## ArticlePreviewController (4エンドポイント、ベースパス `/api/projects/{projectId}/preview`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST .../render | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../theme-css | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST .../skeleton | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| DELETE .../preview-post | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |

## AuditLogController (1エンドポイント、ベースパス `/api/audit-logs`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/audit-logs | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 統合テストで代表検証済み(b) |

## AuthController (11エンドポイント、ベースパス `/api/auth`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/auth/login | なし(公開) | 該当なし(公開エンドポイント) | 該当なし | 認可OK | 現状維持(公開エンドポイントとして必要) | PUBLIC_AUTH_PATHS |
| POST /api/auth/password-reset/request | なし(公開) | 該当なし(公開エンドポイント) | 該当なし | 認可OK | 現状維持(公開エンドポイントとして必要) | PUBLIC_AUTH_PATHS |
| POST /api/auth/password-reset/confirm | なし(公開) | 該当なし(公開エンドポイント) | 該当なし | 認可OK | 現状維持(公開エンドポイントとして必要) | PUBLIC_AUTH_PATHS |
| POST /api/auth/signup | なし(公開) | 該当なし(公開エンドポイント) | 該当なし | 認可OK | 現状維持(公開エンドポイントとして必要) | PUBLIC_AUTH_PATHS |
| GET /api/auth/setup-status | なし(公開) | 該当なし(公開エンドポイント) | 該当なし | 認可OK | 現状維持(公開エンドポイントとして必要) | PUBLIC_AUTH_PATHS |
| POST /api/auth/setup | なし(公開) | 該当なし(公開エンドポイント) | 該当なし | 認可OK | 現状維持(公開エンドポイントとして必要) | PUBLIC_AUTH_PATHS。初期管理者セットアップ用 |
| GET /api/auth/totp/status | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | ログイン中actor自身の2FA状態。自己参照のみで他者情報は返さない設計だが、明示的なチェックは無い |
| POST /api/auth/totp/setup | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 同上(自己のみ操作) |
| POST /api/auth/totp/verify-setup | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 同上 |
| POST /api/auth/totp/disable | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 同上 |
| POST /api/auth/totp/verify | なし(公開) | 該当なし(公開エンドポイント) | 該当なし | 認可OK | 現状維持(公開エンドポイントとして必要) | PUBLIC_AUTH_PATHS。ログイン2段階目、まだAPIキーを持たない |

## BackupController (2エンドポイント、ベースパス `/api/backup`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/backup/download | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `BackupService.createBackup()`内でrequireAdmin() |
| POST /api/backup/restore | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `BackupService.restoreBackup()`内でrequireAdmin() |

## ContentCacheController (1エンドポイント、ベースパス `/api/content-cache`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/content-cache | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | `[blogcard]`/`[amazon]`埋め込みタグ用の内部プロキシ/キャッシュ。渡されたURLをスクレイピングするのみ |

## CustomTagController (7エンドポイント、ベースパス `/api/custom-tags`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/custom-tags/generate | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagGenerationService.generate()`内 |
| POST /api/custom-tags/validate | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | ステートレスな検証のみ(DBへの副作用なし) |
| POST /api/custom-tags | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagService.create()`内 |
| GET /api/custom-tags | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | グローバルタグ一覧の参照 |
| GET /api/custom-tags/css-bundle | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | CSSバンドルの参照 |
| PUT /api/custom-tags/{id} | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagService.update()`内 |
| DELETE /api/custom-tags/{id} | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagService.delete()`内 |

## CustomTagTemplateController (9エンドポイント、ベースパス `/api/custom-tag-templates`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/custom-tag-templates | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagTemplateService.create()`内 |
| GET /api/custom-tag-templates/{id} | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 単一テンプレート参照(未公開含む) |
| GET /api/custom-tag-templates | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | `showAll=true`で未公開含む全件も無条件参照可能 |
| GET /api/custom-tag-templates/my-templates | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 呼び出し元自身のテンプレートに限定される想定だが、明示チェックは無い |
| PUT /api/custom-tag-templates/{id} | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagTemplateService.update()`内 |
| POST /api/custom-tag-templates/{id}/publish | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagTemplateService.publish()`内 |
| POST /api/custom-tag-templates/{id}/unpublish | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagTemplateService.unpublish()`内 |
| POST /api/custom-tag-templates/{id}/clone | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagTemplateService.clone()`内 |
| DELETE /api/custom-tag-templates/{id} | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagTemplateService.delete()`内 |

## DashboardController (5エンドポイント、ベースパス `/api/dashboard`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/dashboard/service-status | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 接続先サービスの稼働状況(URL等は含まない概要) |
| GET /api/dashboard/service-status/stream | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 上記のSSE配信版 |
| GET /api/dashboard/service-status/detail | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 応答時間・エラー内容等の詳細診断情報(admin限定、コード上のコメントでも明記) |
| GET /api/dashboard/container-status | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | Dockerコンテナ稼働状況 |
| GET /api/dashboard/container-status/stream | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 上記のSSE配信版 |

## FrontendErrorLogController (2エンドポイント、ベースパス `/api/logs`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/logs/errors | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | Web BFFからのフロントエンドエラー記録。書き込みのみ |
| GET /api/logs/errors | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |

## GenerationJobController (2エンドポイント、ベースパス `/api/generation-jobs`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/generation-jobs | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 全プロジェクト横断のジョブ履歴一覧 |
| GET /api/generation-jobs/{id} | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | |

## HealthController (1エンドポイント、ベースパスなし)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/health | なし(公開) | 該当なし(公開エンドポイント) | 該当なし | 認可OK | 現状維持(公開エンドポイントとして必要) | ApiKeyAuthFilterの`shouldNotFilter`で明示的に除外 |

## MediaController (1エンドポイント、ベースパスなし)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/media/upload | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | site識別子を渡せば任意のサイトへメディアをアップロード可能 |

## MetadataController (2エンドポイント、ベースパス `/api/metadata`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/metadata/post-statuses | なし(意図的) | 401 | 該当なし | 認可OK | 現状維持(設計として認証済み全員に公開) | クラスjavadocに「特定のPermissionは要求せず、認証済みactorであれば参照できる」と明記。ギャップではなく設計 |
| GET /api/metadata/roles | なし(意図的) | 401 | 該当なし | 認可OK | 現状維持(設計として認証済み全員に公開) | 同上 |

## OperationLogController (4エンドポイント、ベースパス `/api/operation-logs`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/operation-logs | なし(自己スコープ) | 401 | 該当なし | 認可OK | 現状維持(設計として自己ログのみ) | ログイン中actor自身のログとして記録。`currentActorService`が`null`ならコントローラ内で自前401 |
| GET /api/operation-logs | なし(自己スコープ) | 401 | 該当なし | 認可OK | 現状維持(設計として自己ログのみ) | `requireActorId()`で自分のログのみ参照(admin/project権限チェックではなく自己判定) |
| GET /api/operation-logs/{operationId} | なし(自己スコープ) | 401 | 該当なし | 認可OK | 現状維持(設計として自己ログのみ) | 同上 |
| GET /api/operation-logs/unified | なし(自己スコープ) | 401 | 該当なし | 認可OK | 現状維持(設計として自己ログのみ) | `isAdmin()`はフィルタ条件緩和のためだけに使い、拒否には使わない |

## PostController (4エンドポイント、ベースパス `/api/posts`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/posts | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 全プロジェクト横断の投稿履歴一覧 |
| POST /api/posts/publish | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | WordPressへの新規投稿/更新。site識別子のみで対象を選べる |
| GET /api/posts/{site}/by-slug/{slug} | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | |
| DELETE /api/posts/{site}/{wpPostId} | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 投稿削除(WordPress上はゴミ箱移動)がプロジェクト所属確認なしに可能 |

## ProjectAiModelController (10エンドポイント、ベースパス `/api/projects/{id}/ai-models`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET .../llm/models | requireAdmin | 401 | 403 | 認可OK | 現状維持 | プロジェクト単位のパスだが、チェックはrequireProjectMemberOrAdminではなくrequireAdmin(=projectId未使用) |
| PUT .../llm/models/selection | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| GET .../llm/provider | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| PUT .../llm/provider/selection | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| GET .../image/provider | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| PUT .../image/provider/selection | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| GET .../comfyui/checkpoints | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| PUT .../comfyui/checkpoints/selection | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| POST .../comfyui/checkpoints/install | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| DELETE .../comfyui/checkpoints/{fileName} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |

## ProjectApiKeyController (14エンドポイント、ベースパス `/api/projects/{projectId}/api-keys`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET .../github-token | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `ProjectApiKeyService`内で各メソッドがrequireProjectMemberOrAdmin() |
| PUT .../github-token | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| DELETE .../github-token | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| GET .../brave-search-api-key | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| PUT .../brave-search-api-key | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| DELETE .../brave-search-api-key | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| GET .../google-analytics | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| PUT .../google-analytics | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| DELETE .../google-analytics | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| GET .../adsense | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| PUT .../adsense | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| PUT .../adsense/client-secret | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| DELETE .../adsense | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| POST .../adsense/oauth-callback | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | Next.js側OAuthコールバックからのサーバー間呼び出し。ブラウザ直叩き想定ではないが、チェック自体はある |

## ProjectController (42エンドポイント、ベースパス `/api/projects`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/projects | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET /api/projects | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 全プロジェクト一覧。所属確認なしで全件参照可能 |
| GET /api/projects/{id} | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 単一プロジェクト参照。所属確認なし |
| PUT /api/projects/{id} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| DELETE /api/projects/{id} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/environments | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| DELETE /api/projects/{id}/environments/{environment} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/projects/{id}/master-environment | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/projects/{id}/github-repository | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/projects/{id}/css-selector-prefix | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/projects/{id}/image-generation-prompt-defaults | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/projects/{id}/image-generation-size-defaults | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/projects/{id}/article-image-resize-default | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/projects/{id}/image-content-filter-settings | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/environments/sync | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/apply | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/apply-all | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/upload | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/asset-images/{generatedImageId}/upload | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET /api/projects/{id}/bulk-management/categories/comparison | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET /api/projects/{id}/bulk-management/tags/comparison | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/categories/sync | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/categories/delete-all | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/categories/edit-sync | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/categories/sync-all | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/tags/sync | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/tags/delete-all | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/tags/edit-sync | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/tags/sync-all | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET /api/projects/{id}/bulk-management/plugins/comparison | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET /api/projects/{id}/bulk-management/themes/comparison | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/plugins/reconcile | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/themes/reconcile | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/plugins/delete-all | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/themes/delete-all | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET /api/projects/{id}/bulk-management/posts/comparison | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/posts/delete-all | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/posts/status-update | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET /api/projects/{id}/users | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/users | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/projects/{id}/users/{userId} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| DELETE /api/projects/{id}/users/{userId} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |

## ProjectCustomTagController (3エンドポイント、ベースパス `/api/projects/{projectId}/custom-tags`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/projects/{projectId}/custom-tags | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | 統合テスト(c)のプロジェクト間分離検証で使用 |
| GET /api/projects/{projectId}/custom-tags/css-bundle | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{projectId}/custom-tags/preview | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |

## ProjectDashboardController (2エンドポイント、ベースパス `/api/projects/{projectId}/dashboard`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET .../google-analytics | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `GoogleAnalyticsReportService.getReport()`内 |
| GET .../adsense | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `AdSenseReportService.getReport()`内 |

## ProjectMediaGarbageCollectionController (2エンドポイント、ベースパス `/api/projects/{id}/media-garbage-collection`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET .../scan | requireAdmin | 401 | 403 | 認可OK | 現状維持 | プロジェクト単位のパスだが、requireProjectMemberOrAdminではなくrequireAdmin |
| POST .../delete | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |

## ProjectUserController (1エンドポイント、ベースパス `/api/project-users`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/project-users | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 全プロジェクト横断のproject-userペア一覧。統合テストで代表検証済み(b) |

## SiteController (11エンドポイント、ベースパス `/api/sites`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/sites | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | Swagger `@ApiResponse`は401のみ列挙(403は無い)。実装と整合 |
| POST /api/sites/managed-wordpress | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | Swagger `@ApiResponse`は401のみ列挙。実装と整合するが、マネージドサイトの新規作成という重い操作にadmin/所属チェックが無い |
| POST /api/sites/managed-wordpress/adopt | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 同上 |
| GET /api/sites | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 全サイト一覧 |
| GET /api/sites/{id} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | Swagger `@ApiResponse`が403を明記しており実装と整合 |
| POST /api/sites/ssh-keypair | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/sites/{id} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/sites/{id}/test-connection | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | Swagger `@ApiResponse`は401のみ列挙(403は無い)。実装と整合。テスト(`testConnection_admin権限不問で呼べる`)でも意図的と明記 |
| DELETE /api/sites/{id} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/sites/{id}/install-wp-cli | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/sites/{id}/reprovision | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |

## SiteStaticContentController (2エンドポイント、ベースパス `/api/sites/{siteId}/static-content`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/sites/{siteId}/static-content | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 統合テストで代表検証済み(b) |
| POST /api/sites/{siteId}/static-content/generate | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |

## SshKeyPairController (3エンドポイント、ベースパス `/api/ssh-key-pairs`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/ssh-key-pairs | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `SshKeyPairService.list()`内 |
| POST /api/ssh-key-pairs | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `SshKeyPairService.generate()`内 |
| DELETE /api/ssh-key-pairs/{id} | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `SshKeyPairService.delete()`内 |

## SystemSettingController (3エンドポイント、ベースパス `/api/system-settings`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/system-settings/brave-search-api-key | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | `SystemSettingService.getBraveSearchApiKeyStatus()`にrequireAdmin()が無い(設定値そのものは返さず、設定済みか否か/設定元のみ) |
| PUT /api/system-settings/brave-search-api-key | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `SystemSettingService.setBraveSearchApiKey()`内 |
| DELETE /api/system-settings/brave-search-api-key | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `SystemSettingService.clearBraveSearchApiKey()`内 |

## TagDesignSettingController (3エンドポイント、ベースパス `/api/projects/{projectId}/tag-design-settings`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/projects/{projectId}/tag-design-settings | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/projects/{projectId}/tag-design-settings/{tagType} | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{projectId}/tag-design-settings/{tagType}/generate | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |

## TaxonomyController (1エンドポイント、ベースパスなし)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/taxonomy/resolve | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | site識別子を渡せば任意サイトのカテゴリ/タグ解決が可能 |

## VscodeExtensionController (1エンドポイント、ベースパス `/api/system/vscode-extension`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/system/vscode-extension | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | VSCode拡張機能(.vsix)のビルド・ダウンロード。認証済みなら誰でも取得可能 |

---

## 既知のギャップ(認可チェックが無いエンドポイント、本Issueでは修正せず事実列挙のみ)

以下は有効な `X-API-Key` さえあれば、ロール・プロジェクト所属に関わらず到達できる
(`requireAdmin`/`requireProjectMemberOrAdmin`のいずれも呼ばれない)エンドポイント。
修正は本Issueのスコープ外であり、別Issueでの対応を推奨する。

- `AiController`: `POST /api/ai/draft`, `POST /api/ai/ask`, `POST /api/ai/tags`,
  `POST /api/ai/proofread`, `POST /api/ai/image`, `GET /api/ai/image-options`,
  `POST /api/ai/section`(`generateImagePrompt`のみプロジェクト単位チェックあり)
- `ContentCacheController`: `GET /api/content-cache`
- `CustomTagController`: `POST /api/custom-tags/validate`, `GET /api/custom-tags`,
  `GET /api/custom-tags/css-bundle`
- `CustomTagTemplateController`: `GET /api/custom-tag-templates/{id}`,
  `GET /api/custom-tag-templates`, `GET /api/custom-tag-templates/my-templates`
- `DashboardController`: `GET /api/dashboard/service-status`,
  `GET /api/dashboard/service-status/stream`, `GET /api/dashboard/container-status`,
  `GET /api/dashboard/container-status/stream`
- `FrontendErrorLogController`: `POST /api/logs/errors`
- `GenerationJobController`: `GET /api/generation-jobs`, `GET /api/generation-jobs/{id}`
- `MediaController`: `POST /api/media/upload`(任意のsiteへアップロード可能)
- `OperationLogController`: 全4エンドポイント(ただし自己スコープ設計。備考参照)
- `PostController`: 全4エンドポイント。WordPressへの投稿公開・削除を含む、影響の大きい操作
- `ProjectController`: `GET /api/projects`(一覧), `GET /api/projects/{id}`(詳細)
- `SiteController`: `POST /api/sites`, `POST /api/sites/managed-wordpress`,
  `POST /api/sites/managed-wordpress/adopt`, `GET /api/sites`,
  `POST /api/sites/{id}/test-connection`
- `SystemSettingController`: `GET /api/system-settings/brave-search-api-key`
- `TaxonomyController`: `POST /api/taxonomy/resolve`
- `VscodeExtensionController`: `GET /api/system/vscode-extension`

`AuthController`の`GET/POST /api/auth/totp/*`(自身の2FA操作)は自己参照のみを扱う設計であり、
上記とは性質が異なる(他ユーザーの情報には触れない)ため、別掲として本節末尾に注記するに留める。

### 未使用の認可プリミティブ

- `AdminAuthorizationService.requireSelfOrAdmin(Long userId)` — 定義されているが、
  現時点でどのコントローラからも呼ばれていない(未使用)。
- `PermissionAuthorizationService.requirePermission(Permission)` — 同様に未使用。
