# 認可マトリクス (legacy-api)

issue #568。`services/legacy-api` の全REST APIエンドポイント(`@GetMapping`/`@PostMapping`/
`@PutMapping`/`@DeleteMapping`/`@PatchMapping` の合計174件、28コントローラファイル
[`HealthController`を含む]。`grep -rhoE '@(Get|Post|Put|Delete|Patch)Mapping' controller/*.java | wc -l`
で確認)について、現行の認可チェックと実際に返るステータスを一覧化する。#573でDiagramController/
GeneratedImageController/RenderControllerをmedia-serviceへ移設し、当初の191件・33ファイルから
減少している(このマトリクス自体は各stageの移設時に更新した)。stage3でMediaController/
ProjectMediaGarbageCollectionControllerをmedia-serviceへ移設した一方、GenerationJobControllerに
`POST /api/generation-jobs`を追加し、新設の内部ブリッジ`CmsMediaBridgeController`
(`POST /api/internal/cms/sites/{site}/media`、`GET .../projects/{projectId}/media-scan`、
`DELETE .../projects/{projectId}/media/{mediaId}`、計3エンドポイント)を追加した。当時の
`CmsMediaBridgeController`はmedia-service専用の内部呼び出しであり、gatewayを経由した
既存フロントエンドから直接到達可能な経路ではないため、下表の一覧からは省略しつつ
`SecurityConfig`の対象からは除外していなかった(未認証では401になる。統合テストの
Authorizationヘッダーなし401チェックの対象にも含めていた)。issue #566でAuthControllerの
ログイン・2FA・パスワードリセット系8エンドポイントを撤去したため、その時点の総数は上記174件から
8件減った166件(公開パスの`signup`/`setup`/`setup-status`3件を含む)だった。

その後issue #709で`CmsMediaBridgeController`はpublishing-serviceへ移設され、legacy-apiには
存在しない(パスも`/api/internal/cms/**`から、publishing-service内の他の内部ブリッジと同じ
`/api/internal/{owning-service}/**`命名規則に合わせて`/api/internal/publishing/**`配下へ変更
されている)。legacy-apiの統合テスト(`AuthorizationMatrixIntegrationTest`)の401チェック対象
からもこの3エンドポイントは除外済み。

対応する統合テストは
`services/legacy-api/src/test/java/com/letsblog/api/integration/AuthorizationMatrixIntegrationTest.java`。

## 現行の認可モデル(2層構造)

legacy-apiはまだ `@PreAuthorize` ベースの宣言的認可へ移行していない
(`SecurityConfig`のjavadoc参照。移行は認可マトリクス整備(#568、B10)のスコープであり、
本Issue(#566)では実施しない)。現行は以下の2層で認可を行っている。

1. **認証ゲート(→401)**: `com.letsblog.api.config.SecurityConfig`が、公開パスを除く
   全`/api/**`パスに対してKeycloak発行の有効なJWT(`Authorization: Bearer`ヘッダー)を要求する
   (issue #566で`ApiKeyAuthFilter`によるヘッダーベースのAPIキー認証を撤去し、Resource Server
   のJWT検証へ全面移行した)。JWTが無い、または不正・期限切れ・署名不正の場合、コントローラ
   メソッドや `@Valid` によるボディ検証に到達する前に即座に401を返す。
   例外として以下はJWTなしでも到達できる(`SecurityConfig.PUBLIC_PATHS`):
   - `GET /api/health`
   - `POST /api/auth/setup`
   - `GET /api/auth/setup-status`
   - Actuator (`/actuator/**`)・APIドキュメント (`/v3/api-docs/**`、`/swagger-ui/**`)

   上記以外の全エンドポイントは、リクエストボディやパスパラメータの妥当性に関わらず、
   有効なJWTが無ければ必ず401を返す。

   **platform-serviceへ移設されたエンドポイントについて(issue #705)**:
   `AppSettingController`・`BackupController`・`DashboardController`・`SystemSettingController`・
   `VscodeExtensionController`は#693/#694/#695/#696で`services/platform`へ移設された。移設先の
   `com.letsblog.platform.config.SecurityConfig`は当初、他の抽出サービスのテンプレート通り
   全経路`permitAll()`だったため、この認証ゲートが一時的に失われ、`GET /api/system/vscode-extension`等が
   Authorizationヘッダーなしでも200を返す後退が発生していた(gateway側も`anyExchange().permitAll()`で
   あり、どちらの層でも認証必須化が行われていなかった)。issue #705でplatform-serviceの`SecurityConfig`を
   legacy-apiと同じ形(公開パスを除き`anyRequest().authenticated()`)へ変更し、下表の「未認証: 401」を
   実態として復元した。platform-serviceの公開パスは Actuator (`/actuator/**`)・APIドキュメント
   (`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html`)・サービス間内部ブリッジ
   (`/api/internal/platform/**`、gatewayのルート表に無く外部から到達できない。同SecurityConfigの
   Javadoc参照)のみ。対応する統合テストは
   `services/platform/src/test/java/com/letsblog/platform/integration/AuthorizationMatrixIntegrationTest.java`。

2. **ロール/所有権ゲート(→403)**: コントローラメソッド(または委譲先のサービスメソッド)の
   先頭付近で `AdminAuthorizationService` の以下いずれかを呼ぶ場合がある。
   - `requireAdmin()` — 呼び出し元のactorがadminロール(`CurrentActorService`がJWTのsub
     クレームから解決したローカルUserのrole)であることを要求する。満たさなければ
     `ForbiddenException` を投げ、`GlobalExceptionHandler` が403へ変換する。
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

   **いずれのチェックも呼ばないエンドポイント**は、有効なJWTさえ持っていればロール・
   プロジェクト所属に関わらず到達できる。これは既知のギャップであり、本Issueでは
   修正せず、末尾の「既知のギャップ」節に事実として列挙するに留める。

## 表の見方

- **現行: 未認証**: 有効なJWT(Authorization: Bearer)なし/不正の場合に実際に返るステータス。
  公開エンドポイントは「該当なし(公開エンドポイント)」。
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

platform-service所有(issue #693)。未認証401はplatform-serviceの`SecurityConfig`が担う(#705)。

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

認可チェックの内容は変わらないが、実装の所有サービスは分割済み。`POST .../render` はcontent-service
(issue #576)、残る3つはpublishing-service(issue #712、Epic #551 C6-6)が持つ。いずれも
requireProjectMemberOrAdmin をコントローラ側で呼ぶ点は同じ(プロジェクトメンバー判定は、
`project_user` を所有するlegacy-apiへの内部ブリッジ経由)。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST .../render | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | content-service所有(#576) |
| GET .../theme-css | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | publishing-service所有(#712) |
| POST .../skeleton | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | publishing-service所有(#712) |
| DELETE .../preview-post | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | publishing-service所有(#712) |

## AuditLogController (1エンドポイント、ベースパス `/api/audit-logs`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/audit-logs | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 統合テストで代表検証済み(b) |

## AuthController (2エンドポイント、ベースパス `/api/auth`)

issue #566でログイン(`POST /api/auth/login`)・2FA(`GET/POST /api/auth/totp/*`)・
パスワードリセット(`POST /api/auth/password-reset/*`)の計8エンドポイントはKeycloakへ
全面移行し撤去した。さらにissue #688で、ログインのKeycloak一本化(#564)以降ローカルDBにしか
アカウントを作らずログイン不能なユーザーを生むだけになっていたセルフサインアップ
(`AuthController.signup`。Web/拡張/SDK/OpenAPIのいずれからも呼び出し元は無かった)も撤去した。
残る2エンドポイントは、Keycloak上にまだアカウントが1つも存在しない状態からのWeb管理画面
初回セットアップ専用で、いずれも`SecurityConfig.PUBLIC_PATHS`により公開されている。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/auth/setup-status | なし(公開) | 該当なし(公開エンドポイント) | 該当なし | 認可OK | 現状維持(公開エンドポイントとして必要) | PUBLIC_PATHS |
| POST /api/auth/setup | なし(公開) | 該当なし(公開エンドポイント) | 該当なし | 認可OK | 現状維持(公開エンドポイントとして必要) | PUBLIC_PATHS。初期管理者セットアップ用(ローカルDB直書きのみでKeycloak側にはアカウントを作らない) |

## BackupController (2エンドポイント、ベースパス `/api/backup`)

platform-service所有(issue #694)。未認証401はplatform-serviceの`SecurityConfig`が担う(#705)。

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

platform-service所有(issue #695)。未認証401はplatform-serviceの`SecurityConfig`が担う(#705)。
SSE配信の2エンドポイントもブラウザから直接ではなくWeb BFF(`web/src/app/api/dashboard/*/stream/route.ts`)が
Authorizationヘッダーを付けて中継するため、認証必須化の影響を受けない。

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

## GenerationJobController (3エンドポイント、ベースパス `/api/generation-jobs`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/generation-jobs | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 全プロジェクト横断のジョブ履歴一覧 |
| GET /api/generation-jobs/{id} | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | |
| PATCH /api/generation-jobs/{id} | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | #573 stage2で追加。media-service側の非同期ジョブランナーがBearerトークンを転送して呼ぶ内部向け更新API |

## HealthController (1エンドポイント、ベースパスなし)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/health | なし(公開) | 該当なし(公開エンドポイント) | 該当なし | 認可OK | 現状維持(公開エンドポイントとして必要) | `SecurityConfig.PUBLIC_PATHS`で明示的に除外 |

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

platform-service所有(issue #693)。未認証401はplatform-serviceの`SecurityConfig`が担う(#705)。

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

platform-service所有(issue #696)。未認証401はplatform-serviceの`SecurityConfig`が担う(#705)。
移設直後は同SecurityConfigが全経路permitAllだったためAuthorizationヘッダーなしでも200で.vsixが
取得できていた(#705の後退)。ロールチェックが無く「ログイン済みなら誰でも取得可能」である点は
issue #705でも変更しておらず、下記「既知のギャップ」に残る。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/system/vscode-extension | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | VSCode拡張機能(.vsix)のビルド・ダウンロード。認証済みなら誰でも取得可能 |

---

## 既知のギャップ(認可チェックが無いエンドポイント、本Issueでは修正せず事実列挙のみ)

以下は有効なJWTさえあれば、ロール・プロジェクト所属に関わらず到達できる
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
- `GenerationJobController`: `GET /api/generation-jobs`, `GET /api/generation-jobs/{id}`,
  `PATCH /api/generation-jobs/{id}`(#573 stage2で追加), `POST /api/generation-jobs`(#573 stage3で追加)
- `OperationLogController`: 全4エンドポイント(ただし自己スコープ設計。備考参照)
- `PostController`: 全4エンドポイント。WordPressへの投稿公開・削除を含む、影響の大きい操作
- `ProjectController`: `GET /api/projects`(一覧), `GET /api/projects/{id}`(詳細)
- `SiteController`: `POST /api/sites`, `POST /api/sites/managed-wordpress`,
  `POST /api/sites/managed-wordpress/adopt`, `GET /api/sites`,
  `POST /api/sites/{id}/test-connection`
- `SystemSettingController`: `GET /api/system-settings/brave-search-api-key`
- `TaxonomyController`: `POST /api/taxonomy/resolve`
- `VscodeExtensionController`: `GET /api/system/vscode-extension`

### 未使用の認可プリミティブ

- `AdminAuthorizationService.requireSelfOrAdmin(Long userId)` — 定義されているが、
  現時点でどのコントローラからも呼ばれていない(未使用)。
- `PermissionAuthorizationService.requirePermission(Permission)` — 同様に未使用。
