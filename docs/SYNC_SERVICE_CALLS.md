# サービス間同期呼び出しの規約(C12)

issue #581([C12] サービス間同期呼び出しの規約を定める)。イベント([EVENT_DRIVEN_ARCHITECTURE.md](EVENT_DRIVEN_ARCHITECTURE.md))
で解決できない参照(プレビュー時の図表描画、公開時のサイト認証情報取得など)は同期HTTP呼び出しに
なる。Phase 19の各抽出Issue(#572/#573/#574/#576/#578)は、それぞれ「C12が定まるまでの暫定策」として
個別にRestClientラッパーを実装していた(タイムアウトのみ設定、リトライ・サーキットブレーカー無し)。
本Issueはこれを`lbs-common`の共通実装へ統合し、呼び出し先ごとの方針を一覧化する。

## 共通クライアント: `SyncServiceClient`

`packages/lbs-common/src/main/java/com/letsblog/common/client/`に実装。既存の依存
(`spring-boot-starter-restclient`、`resilience4j-circuitbreaker`/`resilience4j-retry`)のみを使い、
新規のHTTPクライアントライブラリは追加していない。

### タイムアウト(`SyncCallProfile`)

接続タイムアウトは全プロファイル共通で3秒(相手が起動していない/到達不可という状況は処理内容に
よらず早期検知すべきため)。読み取りタイムアウトのみ用途別に4段階。

| プロファイル | 読み取りタイムアウト | 用途の例 |
|---|---|---|
| `SHORT` | 5秒 | 単純なデータ参照(1レコード取得、存在確認)。identity-serviceの`/api/identity/me`等 |
| `STANDARD` | 10秒 | SHORTより複雑だがDB参照の範囲に収まる呼び出し。明示的な理由が無い場合の既定 |
| `RENDER` | 30秒 | 外部プロセス(PlantUMLサーバー、ヘッドレスChromium)依存のレンダリング、大きめのファイル転送 |
| `LLM` | 180秒 | LLMプロバイダーへの実際の問い合わせを伴う呼び出し(ai-serviceの`LLM_REQUEST_TIMEOUT_SECONDS`既定120秒を上回る) |

個別の呼び出しでこれらに当てはまらない場合は`SyncServiceClient.Builder`にカスタム値を足せるが、
まずはこの4段階のいずれかを使うことを推奨する。

### リトライ

冪等な**GETのみ**対象。POST/PATCH/PUT/DELETEはリトライしない(`SyncServiceClient`のAPIレベルで
GET系メソッドのみがリトライ対象になるよう分離しており、呼び出し側で個別に無効化する必要はない)。

- 最大3回試行(初回+リトライ2回)
- 200ms始点・倍率2.0の指数バックオフ(200ms→400ms)
- リトライ対象は接続断・タイムアウト(応答を受け取れなかった場合)のみ。下流が4xx/5xxを明示的に
  返した場合は再試行しても結果が変わらない可能性が高く、下流に無駄な負荷もかけるため対象外

### サーキットブレーカー

下流サービスごとに1つ(呼び出しメソッドが複数あっても共有。同じ下流の不調を1つのブレーカーで検知する)。

- 直近10回の呼び出し(カウントベースのスライディングウィンドウ)のうち5回以上完了して初めて評価する
- 失敗率50%以上でOPENへ遷移
- OPEN後30秒はHALF_OPENへ遷移せず即座に失敗させ、以降HALF_OPENで3回まで試行を許可する
- 「失敗」としてカウントするのは5xx・タイムアウト・通信断のみ。4xx(呼び出し側の入力不備等)は
  下流が健全に応答している証拠なのでカウントしない

OPEN中は実際のHTTP呼び出しを行わず、即座に`SyncServiceCircuitOpenException`を送出する
(下流サービス停止時に呼び出し元がタイムアウトいっぱいまで待たされ続けることを防ぐ)。

### エラー変換

下流の生の例外・ステータスをそのまま呼び出し元へ伝播させない。すべて`SyncServiceException`の
いずれかへ翻訳する。

| 例外 | 意味 |
|---|---|
| `SyncServiceClientErrorException` | 下流が4xxを返した(呼び出し内容・入力値の問題) |
| `SyncServiceServerErrorException` | 下流が5xxを返した |
| `SyncServiceTimeoutException` | 接続/読み取りタイムアウト |
| `SyncServiceUnavailableException` | 接続拒否・DNS失敗等、応答自体を受け取れなかった通信断 |
| `SyncServiceCircuitOpenException` | サーキットブレーカーOPEN中のため呼び出しを行わなかった |

呼び出し元(各サービスの業務ロジック)が、呼び出し内容の性質に応じてフォールバック方針
(機能縮退/プレースホルダ表示、または明確なエラーとして上位へ伝播)を選ぶ。このクライアント自体は
方針を強制しない。

### 認証(`ServiceAuthHeaders`)

2つの付与方式を用意している。

- **`forwardedBearer`**: 呼び出し元(このサービスを呼んだユーザー)のBearerトークンをそのまま
  下流へ転送する。下流側が「そのユーザーの権限」で認可判断をするAPIを呼ぶ場合に使う。
- **`clientCredentials`**: issue #567(B9)の`ServiceTokenClient`でこのサービス自身の
  Client Credentialsトークンを付与する。ユーザーコンテキストが無い呼び出しに使う。

**現状の採用状況と、ADR-0005との関係(既知の乖離、意図的な先送り)**: [ADR-0005](adr/0005-service-to-service-client-credentials.md)は
正式決定として「案B」(サービストークン+`X-On-Behalf-Of-User-Id`相当のヘッダーで元ユーザーを
伝播する)を採用している。しかし本PR時点で実際に移行した全ての呼び出し(下記一覧)は、
Phase 19の各抽出Issueが暫定策として実装していた「呼び出し元ユーザーのBearerトークンをそのまま
転送する」方式(ADR-0005が案A=却下、と呼ぶもの)を**そのまま維持している**。理由:

1. ADR-0005自身が「元ユーザーIDヘッダーの名前・形式の最終確定は、実際にそれを使い始める
   Phase 19の各Issueに委ねる」としており、本Issue時点でも未確定のまま。
2. 案Bへ切り替えるには、下流の各内部ブリッジエンドポイント(`/api/internal/**`)の認可モデルを
   「転送されたユーザーJWTをそのまま信頼する」から「サービストークン+ヘッダーの元ユーザーIDを
   再チェックする」へ変更する必要があり、これは呼び出し元(このIssue)ではなく各下流エンドポイント
   側の変更を伴う、C12の対象(タイムアウト・リトライ・サーキットブレーカー・エラー変換の共通化)
   を超える認可トポロジーの変更である。

このため、`ServiceAuthHeaders.clientCredentials(ServiceTokenClient)`は**利用可能な部品として
用意した**(B9との統合はここで完了)が、実際に案Bへ切り替える作業は、ヘッダー形式が確定し
下流の認可チェックを合わせて変更できるタイミングの別Issueに委ねる。ユーザーコンテキストが
そもそも存在しない呼び出し(定期バッチ等)を新設する場合は、`clientCredentials`を今から使ってよい。

**例外(issue #1083)**: media(`GenerationJobClient`)の`PATCH /api/generation-jobs/{id}`は、
`@Async`な非同期ジョブランナー(ModelInstallJobRunner等)が起動から数分〜数十分後に呼ぶため、
起動時点のユーザーBearerトークンは`forwardedBearer`のままでは失効する(Keycloakの
`accessTokenLifespan`、既定300秒)。ここは「元々ユーザーコンテキストが必要だった」わけではなく
(ai-serviceの`InternalGenerationJobController`は有効なJWTさえあれば認可し、呼び出し元ユーザーの
権限は見ない)、単に暫定策の`forwardedBearer`を踏襲していただけだったため、案Bへの全面切り替えを
待たずに`clientCredentials`へ切り替えた。同じ呼び出し先の`POST /api/generation-jobs`(ジョブ作成、
同期リクエスト内で完結し失効の余地が無い)は引き続き`forwardedBearer`のまま。

## 呼び出し一覧

「移行」列: 本PR(#581)で`SyncServiceClient`へ移行済みは「済」、既存の個別実装のまま(次善策として
方針表のみ整理し、移行は別Issueへ先送り)は「未」。

### identity-service向け

| 呼び出し元 | エンドポイント | プロファイル | リトライ | サーキットブレーカー | フォールバック | 移行 |
|---|---|---|---|---|---|---|
| content/media/ai/analytics/log-writer(共通`IdentityClient`) | `GET /api/identity/me` | SHORT(5秒) | あり(GET) | あり(`identity-service`) | 明確なエラー(fail closed)。認可判定に使う情報のため機能縮退しない | 済 |

5サービスがほぼ同一コードを individually 実装していたものを、`lbs-common`の
`com.letsblog.common.client.IdentityClient`/`ActorProfile`へ統合した(各サービスは
`config/SyncClientConfig`で`@Bean`として構築し、既存の`CurrentActorService`から利用)。

### ai-service向け

| 呼び出し元 | エンドポイント | プロファイル | リトライ | サーキットブレーカー | フォールバック | 移行 |
|---|---|---|---|---|---|---|
| media(`GenerationJobClient`) | `POST /api/generation-jobs` | SHORT(5秒) | なし(POST) | あり(`ai-service`) | 明確なエラー(ジョブID無しでは非同期処理を開始できないため) | 済 |
| media(`GenerationJobClient`) | `PATCH /api/generation-jobs/{id}`(進捗更新"running") | SHORT(5秒) | なし(PATCH) | あり(`ai-service`) | 機能縮退(1回試行してログ警告のみ。認証エラーのWARNは30秒間隔に抑制。issue #1083) | 済 |
| media(`GenerationJobClient`) | `PATCH /api/generation-jobs/{id}`(終端通知"done"/"failed") | SHORT(5秒) | アプリ層で最大3回(issue #1083、下記参照) | あり(`ai-service`) | 再試行を使い切ったらERRORとして記録(例外は投げない。取り残しはai-service側のタイムアウトに委ねる。issue #1083) | 済 |
| legacy-api(`GenerationJobClient`) | `POST /api/generation-jobs`・`PATCH /api/generation-jobs/{id}` | SHORT(5秒) | なし | あり(`ai-service`) | media-service版と同じ(作成=明確なエラー、更新=機能縮退) | 済 |
| content(`AiGenerationClient`) | `POST /api/ai/internal/generate` | LLM(180秒) | なし(POST) | あり(`ai-service`) | 明確なエラー(LLM生成結果はプレースホルダで代替できる性質のものではない) | 済 |
| legacy-api(`AiGenerationClient`) | `POST /api/ai/internal/generate` | LLM(180秒) | なし | あり(`ai-service`) | content-service版と同じ | 済 |
| legacy-api(`AiProjectSettingsClient`) | `GET/PUT/DELETE /api/internal/ai/projects/{id}/brave-search-api-key` | (未移行、既存はSTANDARD相当の10秒) | - | - | (未整理) | 未 |
| log-writer(`GenerationJobClient`) | `GET /api/generation-jobs` | SHORT(5秒) | あり(GET) | あり(`ai-service`) | **機能縮退**(WARNを残しAI_JOBソースのみ除外。操作ログ・監査ログは返す。#825) | 済 |


#### log-writer の AIジョブ取得を機能縮退にしている理由(#825)

統合操作ログ(`GET /api/operation-logs/unified`)は OPERATION / AUDIT / AI_JOB の3ソースを
マージする。前2つは log-writer 自身の `lbs_log` スキーマから取得済みで、AI_JOB だけが
外部への同期呼び出しに依存する。

#825 以前はここを「明確なエラー(502)」にしていたため、**AIジョブ取得の失敗だけで
取得済みの操作ログ・監査ログまで巻き添えで失われていた**。実際、本クライアントが
ai-service へ移設済みのエンドポイントを legacy-api に問い合わせ続けていたため
この失敗が常時発生し、`/operation-logs` 画面は常に空だった。

そのため機能縮退へ変更した。ただし縮退により**失敗が HTTP レスポンスに現れなくなる**ので、
握り潰さず WARN に原因(`SyncServiceException` のメッセージ)とスタックトレースを残す。
向き先・レスポンス形状・認証転送は `GenerationJobClientTest` が固定している。

### legacy-api向け

| 呼び出し元 | エンドポイント | プロファイル | リトライ | サーキットブレーカー | フォールバック | 移行 |
|---|---|---|---|---|---|---|
| content(`LegacyApiBridgeClient`) | `/api/internal/content/**`(プロジェクトメンバー判定・ロール一覧・タグデザイン・site解決等) | (未移行、既存は10秒) | - | - | (未整理、現状は明確なエラー) | 未 |
| ai(`LegacyApiBridgeClient`) | `/api/internal/ai/**`(GitHubアクセス・プロジェクトメンバー判定・LLM設定等) | (未移行、既存は10秒) | - | - | (未整理) | 未 |
| analytics(`LegacyApiBridgeClient`) | `/api/internal/analytics/**`(本番サイト有無・プロジェクトメンバー判定) | (未移行、既存は10秒) | - | - | (未整理) | 未 |
| publishing(`LegacyApiBridgeClient`) | `/api/internal/project/**`(著者マッピング・画像リサイズ設定・プロジェクトメンバー判定) | (未移行、既存は10秒) | - | - | 明確なエラー(fail closed。特にプロジェクトメンバー判定は認可判定に使うため機能縮退させない) | 未(プロジェクトメンバー判定はissue #712でArticlePreviewControllerの移設に伴い追加) |

### publishing-service向け

| 呼び出し元 | エンドポイント | プロファイル | リトライ | サーキットブレーカー | フォールバック | 移行 |
|---|---|---|---|---|---|---|
| media(`CmsBridgeClient`) | `POST /api/internal/publishing/sites/{site}/media`(multipart)・`GET .../media-scan`・`DELETE .../media/{id}` | RENDER(30秒、大きめのメディア転送のため) | GETのみ | あり(`publishing-service`) | 明確なエラー(CMS操作の成否を呼び出し元へ確実に伝える必要があるため) | 済(issue #709でlegacy-apiからpublishing-serviceへ呼び出し先を切り替え、レビュー指摘対応でパスも/api/internal/cms/**から/api/internal/publishing/**へ変更、C12対応は維持) |
| project(`CmsProvisioningBridgeClient`) | `POST /api/internal/project/cms/test-connection`・`install-wp-cli`・`has-author-capability`・`list-active-plugins`・`provision`・`export-database`・`export-media`・`export-themes` | (未移行、既存は固定タイムアウト。接続3秒・読み取り60秒) | - | - | (未整理、既存はconnection-check系のみ機能縮退=失敗結果を返す、それ以外は明確なエラー) | 未(issue #710でlegacy-apiからpublishing-serviceへ呼び出し先を切り替えたが、`SyncServiceClient`への移行は既定プロファイル(最長RENDER30秒)ではSSH/wp-cliのエクスポート処理に対して読み取りタイムアウトが不足する可能性があるため見送り、既存の固定タイムアウトRestClientを維持) |
| ai(`PublishingServiceClient`) | `GET /api/internal/ai/projects/{id}/existing-categories`・`existing-categories-with-parents`・`existing-tags` | (未移行、既存は10秒) | - | - | 機能縮退(取得失敗時は空リストへフォールバック、カテゴリ/タグ提示はメタデータ提案の補助情報のため) | 未(issue #574ではlegacy-apiの`AiBridgeController`が所有していたが、`CmsAdapterFactory`/`cms/*`の所有権がpublishing-serviceへ移った(issue #707)ため、issue #711でこの3エンドポイントのみ`LegacyApiBridgeClient`から分離・切り替え) |

### media-service向け

| 呼び出し元 | エンドポイント | プロファイル | リトライ | サーキットブレーカー | フォールバック | 移行 |
|---|---|---|---|---|---|---|
| content(`MediaRenderClient`) → `PlantUmlEmbedService`(Markdownフェンス埋め込み、プレビュー限定) | `POST /api/render/plantuml` | RENDER(30秒) | なし(POST) | あり(`media-service`) | **プレースホルダ表示**(issue #581の受入基準の例そのもの。副作用の無いプレビュー専用経路のため安全に代替できる) | 済 |
| content(`MediaRenderClient`) → `PlantUmlTagRenderService`/`RechartsTagRenderService`(組み込みタグ`[plantuml]`/`[recharts]`) | `POST /api/render/plantuml`・`POST /api/render/recharts` | RENDER(30秒) | なし | あり(`media-service`、上記と共有) | 明確なエラー(既存の製品判断: タグの記法・データ誤りを利用者に明示する設計を維持。C12では変更しない) | 済(クライアント基盤は移行、フォールバック方針は既存のまま維持) |
| content(`MediaRenderClient`) → `CustomTagGenerationService`(Penpotデザインファイル作成) | `POST /api/render/penpot/design-file` | RENDER(30秒) | なし | あり(`media-service`、上記と共有) | 明確なエラー | 済 |
| legacy-api(`MediaRenderClient`、content版と同一エンドポイントの旧経路) | `POST /api/render/**` | (未移行、既存は20秒) | - | - | (未整理) | 未 |
| legacy-api(`MediaComfyUiClient`) | `/api/comfyui/checkpoints/install`・`/delete` | (未移行) | - | - | (未整理) | 未 |
| legacy-api(`MediaGeneratedImageClient`) | `/api/generated-images`・`/api/generated-images/{id}/file` | (未移行) | - | - | (未整理) | 未 |

### analytics-service向け

| 呼び出し元 | エンドポイント | プロファイル | リトライ | サーキットブレーカー | フォールバック | 移行 |
|---|---|---|---|---|---|---|
| legacy-api(`AnalyticsProjectSettingsClient`) | `GET/PUT/DELETE /api/internal/analytics/projects/{id}/google-analytics`・`/adsense`・`/adsense/client-secret`・`POST .../adsense/oauth-callback` | STANDARD(10秒) | GETのみ | あり(`analytics-service`) | 4xx→`IllegalArgumentException`(409、下流の入力値検証結果をそのまま再送出)、5xx/タイムアウト/サーキットオープン→`AnalyticsServiceException`(502、明確なエラー) | 済 |

### content-service向け

| 呼び出し元 | エンドポイント | プロファイル | リトライ | サーキットブレーカー | フォールバック | 移行 |
|---|---|---|---|---|---|---|
| legacy-api(`ContentServiceClient`) | `/api/internal/content/posts/by-site/{siteId}`・`/projects/{id}/content-settings` | (未移行、既存はメソッドごとに個別timeout) | - | - | (未整理) | 未 |
| publishing(`ContentServiceClient`) | `/api/internal/content/render/**`・`/posts/**` | (未移行、既存はメソッドごとに個別timeout。render系60秒/その他10秒) | - | - | (未整理、現状は明確なエラー) | 未(issue #707でlegacy-apiから移設) |
| publishing(`ContentServiceClient`) | `/api/internal/content/preview-skeleton/fetch-and-splice`・`fetch-real-post`(Playwrightによる実ページナビゲーション) | (未移行、既存は読み取り40秒固定。`RENDER`(30秒)ではナビゲーションのタイムアウト(最大30秒)に足りないため) | - | - | 機能縮退(取得失敗時はavailable=falseを返し、拡張機能側が従来のプレーン表示へフォールバックする) | 未(issue #712でArticlePreviewServiceと共にlegacy-apiから移設) |

### 対象外(外部システム連携。C12のスコープ外)

Keycloak Admin API(identity-service)、GitHub API(ai-service)、ComfyUI/PlantUMLサーバー/Penpot
(media-service)、Google Analytics/AdSense API(analytics-service)、WordPress REST API
(legacy-api)は、いずれも`lets_blog_server`内の別サービスではなく外部システムとの連携であり、
本Issueが対象とする「サービス間(lbs-*間)同期呼び出し」には含めない。

### 対象外(ルーティング層。#573/#574/#578の既知の逸脱を維持)

`gateway`の各サービスへのルーティング(`GatewayRoutingConfig`)は、#573/#574/#578の時点で
「サービス間認証が正式に決まるまでのパススルー」として文書化された既知のギャップであり、本Issueは
「サービス間認証トークンの付与」(`ServiceAuthHeaders.clientCredentials`)を用意するに留め、
gatewayのルーティングトポロジー自体は変更しない(上記「認証」節の理由と同じく、ADR-0005の
案Bへの実際の切り替えが決まるまでの意図的な先送り)。

## 新しい同期呼び出しを追加する手順

1. 呼び出し先の`@Component`クラスに`SyncServiceClient`をフィールドとして持たせ、コンストラクタで
   `SyncServiceClient.builder(restClientBuilder, "<下流サービス名>", baseUrl).profile(<SyncCallProfile>).build()`
   を組み立てる。`<下流サービス名>`は同じJVM内の他クライアントと合わせる(サーキットブレーカーを
   下流サービス単位で共有するため。例: 同じサービスから legacy-api への呼び出しは全クライアントで
   `"legacy-api"`に統一する)。
2. タイムアウトは`SyncCallProfile`の4段階(SHORT/STANDARD/RENDER/LLM)から選ぶ。当てはまらない
   場合のみ個別の値を検討する。
3. GETのみ`client.get(...)`(自動的にリトライ対象)、それ以外は`client.post`/`patch`/`put`/`delete`
   (リトライしない)を使う。
4. 認証は`ServiceAuthHeaders.forwardedBearer(...)`(ユーザーコンテキストがある場合、現状の既定)
   または`ServiceAuthHeaders.clientCredentials(serviceTokenClient)`(無い場合)を、各メソッドの
   `Consumer<HttpHeaders>`引数に渡す。
5. `SyncServiceException`(またはそのサブタイプ)を捕捉し、呼び出し内容の性質に応じたフォールバック
   (プレースホルダ/機能縮退 or 明確なエラー)を選んで呼び出し元固有の例外へ翻訳する。「副作用が無く
   利用者に分かりやすい代替表示ができるか」を基準に選ぶ(判断に迷ったら明確なエラーを既定にする。
   認可判定・書き込み操作は特に機能縮退させない)。
6. この一覧表に行を追加する。

## テスト

`packages/lbs-common/src/test/java/com/letsblog/common/client/SyncServiceClientTest.java`が、実際の
HTTPサーバー(JDK標準の`HttpServer`/`ServerSocket`、追加ライブラリ無し)を使って以下を検証する。

- 正常応答がそのまま返る
- 4xxは`SyncServiceClientErrorException`に分類され、リトライされない
- 5xxの連続でサーキットブレーカーがOPENへ遷移し、以降は下流へ到達せず即座に`SyncServiceCircuitOpenException`
  を送出する(サーキットブレーカーの動作検証)
- 4xxはサーキットブレーカーの失敗としてカウントされない
- 応答が遅い下流に対して、設定した読み取りタイムアウトで打ち切られ、下流の応答を待ち続けて
  ハングしない(受入基準「下流サービス停止時に、呼び出し元がハングせず明確なエラーを返す」の検証)
- 接続不能な下流には即座に`SyncServiceUnavailableException`を送出する
- GETは一時的な通信断からリトライして成功する
- POSTは応答が無くてもリトライせず1回で諦める

## Live-verify(手動確認の記録)

media-service→ai-serviceの実経路(ComfyUIチェックポイントインストールジョブの作成→進捗更新)を
dev環境で通した上で、ai-serviceコンテナを一時停止してmedia-service側の呼び出しが数秒で明確な
エラーとして打ち切られる(ハングしない)ことを確認し、コンテナを再起動して復旧を確認した
(PR説明に記録)。
