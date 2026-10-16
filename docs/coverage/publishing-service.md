# services/publishing `com.letsblog.publishing.service` の分岐カバレッジ(#1815)

Epic #1711 の子 Issue #1815 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`services/publishing` の
`com.letsblog.publishing.service` パッケージ(45 ファイル)に適用した結果。プロダクションコードは変更していない
(この Issue の差分は `services/publishing/src/test/**` と本ファイルだけ)。

このファイルは次の 3 節で構成する。

1. 計測値(着手時・完了時)
2. モックの境界
3. テストから到達できない分岐

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(CLAUDE.md → Coverage のとおり、`&&` / `||` もバイトコード上は別の分岐なので、
C1 と C2 を兼ねる)。値は `jacocoTestReport.xml` の `<package name="com/letsblog/publishing/service">` 直下の
`<counter type="BRANCH">` から取る。`services/publishing` のテスト全体を 1 回走らせた結果なので、`service` のクラスを
間接的に通す他パッケージ(`controller/` など)のテストも数に入る。`service` に下位パッケージは無い。

計測コマンド(着手時・完了時とも同じ):

```bash
./gradlew :services:publishing:cleanTest :services:publishing:test :services:publishing:jacocoTestReport
```

計測条件:

- MySQL: ホストの `127.0.0.1:3306`(ADR-0006、#1769)が起動した状態で実行した。
- `HikariDeadConnectionRecoveryIntegrationTest`(`config` パッケージ。#1095)は、既定の `-Dlbs.dockerAvailable=false` の
  ため**実行されない**(2 件とも skipped)条件で測った。着手時も完了時も同じ条件で、このテストは `service` のクラスを
  通さないので、`service` の数には影響しない。skipped はこの 2 件だけ(完了時の全体: 1419 件、失敗 0、skipped 2)。

### 着手時

| 項目 | 値 |
| --- | --- |
| コミット | `9a839433`(develop 起点、ブランチ `test/1815-publishing-service-coverage` の作成直後。変更なし) |
| コマンド | 上のとおり |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/publishing/service` | 768 | 117 | 86.8% |

着手時の未カバーのクラス別内訳(missed の数。合計 117):

| クラス | missed |
| --- | ---: |
| `ImageResizeService` | 47 |
| `SiteService` | 16 |
| `CurrentActorService` | 16 |
| `BulkManagementService` | 15 |
| `PostPublishService` | 11 |
| `PostComparisonService` | 3 |
| `PlantUmlTagRenderService` | 3 |
| `TermComparisonService` | 2 |
| `PluginThemeComparisonService` / `PlantUmlEmbedService` / `ArticlePreviewService` / `ArticleFrontMatterParser` | 各 1 |

着手時のテスト: `service/` 配下 33 ファイル(テスト以外の補助 `OverlapProbe`・`OversizedImageFixtures` を含む)、`@Test` / `@ParameterizedTest` 426 件。うち 24 ファイルが、下の「モックの境界」
の外の型(自前のクラス)をモックしていた(後述の「着手時にモックの境界の外だった型」)。

### 完了時

| 項目 | 値 |
| --- | --- |
| コミット | `b4c1d7c3`(テストの最終コミット。この後のコミットは本ファイルの追加だけで、テストとプロダクションコードは変わらない) |
| コマンド | 上のとおり |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/publishing/service` | 860 | 25 | 97.2% |

分母(covered + missed)は着手時も完了時も 885 で変わらない(プロダクションコードを変えていない)。残る 25 は、すべて下の
「テストから到達できない分岐」に載せた(クラス別: `ImageResizeService` 8、`PostPublishService` 6、`TermComparisonService` 3、
`PostComparisonService` 3、`SiteService` / `PullRequestArticleService` / `PluginThemeComparisonService` /
`BulkManagementService` / `BulkInstallJobRunner` 各 1)。

完了時の `service/` 配下は 36 ファイル(`CurrentActorServiceTest`・`SiteServiceTest`・`ImageResizeServiceBranchTest` を追加)、
`@Test` / `@ParameterizedTest` 516 件。

### 突き合わせの手順(受け入れ基準 3 の再現)

`jacocoTestReport.xml` から、`service` に残る未カバー分岐を行ごとに取り出す。

```bash
python3 -I - <<'EOF'
import xml.etree.ElementTree as ET
root = ET.parse('services/publishing/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
for pkg in root.findall('package'):
    if pkg.get('name') != 'com/letsblog/publishing/service':
        continue
    for sf in pkg.findall('sourcefile'):
        for line in sf.findall('line'):
            if int(line.get('mb')) > 0:
                print(sf.get('name'), line.get('nr'), 'missed', line.get('mb'), 'covered', line.get('cb'))
EOF
```

完了時(`b4c1d7c3`)の出力は次の 23 行で、`missed` の合計 25 が下の表の行数(25 行)と 1 対 1 に対応する
(`PostPublishService.java 468` と `ImageResizeService.java 294` は `missed 2` なので表では 2 行ずつ)。

```text
TermComparisonService.java 355 missed 1 covered 3
TermComparisonService.java 441 missed 1 covered 1
TermComparisonService.java 480 missed 1 covered 3
PluginThemeComparisonService.java 368 missed 1 covered 3
BulkManagementService.java 553 missed 1 covered 3
PullRequestArticleService.java 65 missed 1 covered 3
BulkInstallJobRunner.java 118 missed 1 covered 1
PostComparisonService.java 117 missed 1 covered 3
PostComparisonService.java 143 missed 1 covered 3
PostComparisonService.java 233 missed 1 covered 3
SiteService.java 59 missed 1 covered 1
PostPublishService.java 217 missed 1 covered 3
PostPublishService.java 230 missed 1 covered 3
PostPublishService.java 401 missed 1 covered 1
PostPublishService.java 468 missed 2 covered 2
PostPublishService.java 556 missed 1 covered 3
ImageResizeService.java 294 missed 2 covered 4
ImageResizeService.java 298 missed 1 covered 1
ImageResizeService.java 303 missed 1 covered 7
ImageResizeService.java 307 missed 1 covered 3
ImageResizeService.java 312 missed 1 covered 3
ImageResizeService.java 327 missed 1 covered 3
ImageResizeService.java 335 missed 1 covered 1
```

(行の並びは XML の順で、毎回同じとは限らない。突き合わせるのは「ファイル名・行」の集合。)

注意: JaCoCo は、ブロックの最後まで実行が進んだときにだけそのブロックをカバー済みにする。例外を投げて終わる呼び出しだけを
通したテストでは、その呼び出しの後ろの分岐が「未通過」と出る。分岐を埋めるときは、例外になる経路と、例外にならず戻る経路の
両方を通す。

## モックの境界

`com.letsblog.publishing.service` のテスト(`services/publishing/src/test/java/com/letsblog/publishing/service/` 配下と、
それらが共有する `.../testsupport/` 配下)で、Mockito のモック(`@Mock` / `@MockitoBean` / `@MockitoSpyBean` / `mock()` /
`spy()`)にしている型は次の **2 つだけ**。確認コマンドと完了時(`b4c1d7c3`)の出力:

```bash
grep -rnE "@Mock|@MockitoBean|@MockitoSpyBean|@Spy|[^a-zA-Z.]mock\(|[^a-zA-Z]spy\(|Mockito\.(mock|spy)\(" \
  services/publishing/src/test/java/com/letsblog/publishing/service \
  services/publishing/src/test/java/com/letsblog/publishing/testsupport | grep -v "import "
```

```text
.../service/ArticleReviewApprovalServiceTest.java:63:        repository = mock(ArticleReviewRepository.class);
.../service/ArticleReviewFeedbackServiceTest.java:48:        repository = mock(ArticleReviewRepository.class);
.../service/ArticleSubmissionServiceTest.java:47:        repository = mock(ArticleReviewRepository.class);
.../service/ArticleReviewPublishServiceTest.java:60:        repository = mock(ArticleReviewRepository.class);
.../testsupport/PublishingWorld.java:104:    public final RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
```

| 型 | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `ArticleReviewRepository`(`com.letsblog.publishing.repository`) | Mockito `mock()` | **DB アクセス**(Spring Data のリポジトリ。自前のコードを持たないインタフェースで、`article_reviews` テーブルの読み書きそのもの) | `ArticleSubmissionServiceTest`・`ArticleReviewFeedbackServiceTest`・`ArticleReviewApprovalServiceTest`・`ArticleReviewPublishServiceTest` |
| `RabbitTemplate`(`org.springframework.amqp.rabbit.core`) | Mockito `mock()`(`PublishingWorld` が持ち、実物の `DomainEventPublisher` へ渡す) | **MQ との送受信**(`letsblog.events` exchange への `convertAndSend`) | `PostPublishServiceTest`・`PostDeleteServiceTest` |

Mockito 以外で外部との通信(または Spring のフレームワークの入口)を差し替えているものも、同じ表に載せる。いずれも
Mockito のモックではなく、自前のクラスの代わりでもない。

| 型・仕掛け | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `FakeHttpServer`(`testsupport`。JDK 標準 `com.sun.net.httpserver.HttpServer` をループバックで起動) | サーバー側フェイク。メソッド+パスごとに応答を登録し、受けた要求を記録する。`GithubStubs`・`AgentListings` が応答の組み立てを補う | **HTTP 通信**: project-service・identity-service・content-service・media-service・ai-service の内部ブリッジ、GitHub REST API、provision-agent(`/bulk-management`・`/categories` など)、サービス間トークンのエンドポイント。自前の `*Client`(`ProjectServiceClient`・`ContentServiceClient`・`GithubPullRequestClient`・`GenerationJobClient`・`WordPressBulkManagementClient` など)は実物をこのサーバーに向ける | `service/` のほぼ全て |
| `FakeWordPress`(`testsupport`)・`FakeSshCommandExecutor`(`cms` のテストにある既存の手書きフェイク) | `SshCommandExecutor` の手書きフェイク。`FakeWordPress` は wp-cli のコマンドを解釈して、(ホスト, wpPath) ごとの投稿・メディア・ターム・ユーザー・プラグイン・テーマの状態を持つ | **SSH(wp-cli)/SFTP**。自前の `WordPressAdapter`・`WordPressSshOperations`・`CmsAdapterFactory` は実物 | 投稿・一括管理・環境間比較・プレビュー・ジョブのテスト |
| `MockHttpServletRequest`・`TestingAuthenticationToken`・`JwtAuthenticationToken`(Spring Test / Spring Security) | Mockito ではないテスト用の実装 | **HTTP リクエストの入口**(`Authorization`・`X-Forwarded-For` ヘッダー、JWT の `sub`)。`RequestContextHolder` に束縛して使う | `CurrentActorServiceTest`、HTTP の `Authorization` を転送する全テスト |
| `AsyncProxies`(`testsupport`)+ 本番と同じ `AsyncJobConfig` の Executor | Spring の実際の AOP プロキシ(`AsyncAnnotationAdvisor`)で、自前のランナーの `@Async` を効かせる | フレームワークの入口(`@Async`)。実行枠と待ち行列が満杯のときの `TaskRejectedException` も、実際の `ThreadPoolTaskExecutor` を満杯にして起こす | `BulkInstallJobStarterTest`・`AssetImageUploadJobStarterTest` |
| H2 のメモリ DB + HikariCP + `DataSourceTransactionManager` | 実物の Spring トランザクションプロキシ | **DB 接続**(リモート I/O 中に DB 接続を掴まないことの検証) | `RemoteIoOutsideTransactionTest` |

次の 2 つは、外部との通信ではないが、モックでもない(実物のオブジェクトに、失敗や環境の欠落を作る入力を与える)。

| 仕掛け | 内容 | 使っているテスト |
| --- | --- | --- |
| 実物の `ObjectMapper` に、必ず失敗するシリアライザ(`SimpleModule`)を登録したもの | 「JSON 化に失敗しても空の JSON で続ける」分岐を通す。`ObjectMapper` を Mockito で模してはいない | `AssetImageUploadJobRunnerTest`・`AssetImageUploadJobStarterTest`・`BulkInstallJobStarterTest` |
| `javax.imageio.spi.IIORegistry` から JPEG / PNG の `ImageWriterSpi` を一時的に外して(テストの最後に戻す)、画像を書き出せない JVM を作る | 画像の書き出しに失敗したら元のバイト列を返す分岐を通す | `ImageResizeServiceBranchTest` |

### 着手時にモックの境界の外だった型

着手時(`9a839433`)の `service/` 配下のテストは、24 ファイルで次の型をモックしていた(()内はモックしていたファイル数)。
これらはすべて実物へ書き直した。完了時にこれらをモックしているテストは 0 件。

- 自前の `CmsAdapterFactory`(12)・`CmsAdapter`(11)・`SiteService`(7)・`BulkManagementService`(6)・`BulkUploadStorageService`(6)・
  `MediaSettingsBridgeClient`(6)・`ProjectService`(6)・`ProjectServiceClient`(6)・`WordPressBulkManagementClient`(6)・
  `WordPressSshOperations`(6)・`ContentServiceClient`(4)・`CurrentActorService`(4)・`GithubPullRequestClient`(4)・
  `ImageResizeService`(4)・`AdminAuthorizationService`(2)・`DomainEventPublisher`(2)・`IdentityBridgeClient`(2)・
  `MediaRenderClient`(2)・`PostPublishService`(2)・`PullRequestArticleService`(2)・`AssetImageUploadJobRunner`(1)・
  `BulkInstallJobRunner`(1)・`MediaGeneratedImageClient`(1)・`PlantUmlEmbedService`(1)・`PlantUmlTagRenderService`(1)
- 自前のクラスを包む通信ラッパー: `GenerationJobClient`(6。`packages/lbs-common`)・`ServiceTokenClient`(2。同)
- `ObjectMapper`(2)
- 境界の中(DB): `ArticleReviewRepository`(4。これは完了時も `mock()` のまま)

## テストから到達できない分岐

完了時に残る 25 分岐。プロダクションコードを変えずには、その分岐に入る入力や状態を作れない理由を具体的に書く。
「実測」は、実際に `ImageIO` に読ませて確かめたことを指す。

| # | クラス | メソッド | 行 | 未カバーの分岐 | 到達できない理由 |
| ---: | --- | --- | ---: | --- | --- |
| 1 | `ImageResizeService` | `readJpegOrientation` | 294 | `bytes.length < 4` が真 | 呼び出しは `decodeCheckingPixelLimit` で画像として読めたバイト列だけに限られる。4 バイト未満で `ImageIO` が画像として読める形式が無い |
| 2 | `ImageResizeService` | `readJpegOrientation` | 294 | `(bytes[1] & 0xFF) != 0xD8` が真(先頭は `0xFF`) | 先頭が `0xFF` で始まり `ImageIO` が読める形式が無い(JPEG は `FFD8`、PNG は `0x89`、GIF は `G`、BMP は `B`、WBMP は `0x00`、TIFF は `I` / `M` で始まる)。先頭が `FFD8` でない場合は 1 つ目の条件で抜けて、テスト済み |
| 3 | `ImageResizeService` | `readJpegOrientation` | 298 | `while (offset + 4 <= bytes.length)` が偽(長さの不足でループを抜ける) | デコードできた JPEG は SOS(`0xDA`)以降に画像データを持つので、セグメントを読み進めると必ず SOS で `break` する。SOS の手前でファイルの終わりに達する JPEG は読めない(実測: 途中で切れた JPEG は `No image data present to read`) |
| 4 | `ImageResizeService` | `readJpegOrientation` | 303 | `marker == 0xD8` が真(2 つ目の SOI) | デコーダーが `Invalid JPEG file structure: two SOI markers` で拒否し、元のバイト列へフォールバックするので、向きの読み取りに来ない(実測) |
| 5 | `ImageResizeService` | `readJpegOrientation` | 307 | `marker == 0xD9` が真(SOS の前の EOI) | デコーダーが `No image data present to read` で拒否する(実測)。SOS の側(`0xDA`)はテスト済み |
| 6 | `ImageResizeService` | `readJpegOrientation` | 312 | `offset + 2 + segmentLength > bytes.length` が真 | セグメント長がファイルの終わりを超える JPEG はデコーダーが拒否する(実測)。`segmentLength < 2` の側は、長さ 1 の APP1 を持つ JPEG が読めるのでテスト済み |
| 7 | `ImageResizeService` | `parseExifOrientation` | 327 | `start + 6 > bytes.length` が真 | APP1 セグメントがファイルの中に収まらない(=ファイルが APP1 の途中で終わる)ことになり、デコーダーが拒否する(実測)。`length < 8` の側はテスト済み |
| 8 | `ImageResizeService` | `parseExifOrientation` | 335 | `tiffStart + 8 > bytes.length` が真 | 7 と同じ理由(TIFF ヘッダーがファイルの終わりを超えるのは、ファイルが途中で終わる場合だけ) |
| 9 | `PostPublishService` | `parseUploadedImages` | 217 | `json == null` が真 | 呼び出しは `findPost(...).map(PostBridgeResponse::uploadedImagesJson).map(this::parseUploadedImages)` で、`Optional.map` は null を次へ渡さず空にする。空文字・空白の側はテスト済み |
| 10 | `PostPublishService` | `serializeUploadedImages` | 230 | `uploadedImages == null` が真 | 引数は `replaceImageReferences` が返す `updatedUploads`(`new LinkedHashMap<>(...)`)で常に non-null。空の側はテスト済み |
| 11 | `PostPublishService` | `lookupRemoteMedia` | 401 | `found == null`(`? Map.of() : found` の `Map.of()` 側) | `CmsAdapter#findMediaBySha256` の実装(`WordPressAdapter` が委譲する SSH・agent の実装、既定の `Map.of()`)はいずれも null を返さない |
| 12 | `PostPublishService` | `getFileExtension` | 468 | `originalFilename == null` が真 | 呼び出しは `renameImageFile(reference, ...)` で、`reference` は直前の `reference == null \|\| reference.isBlank()` で除外済み(`continue`)なので null にならない |
| 13 | `PostPublishService` | `getFileExtension` | 468 | `originalFilename.isBlank()` が真 | 12 と同じ(空白の参照も `continue` で除外済み)。拡張子の無い参照の側(`.bin`)は 472 行でテスト済み |
| 14 | `PostPublishService` | `isProductionSite` | 556 | `project != null` が偽 | `ProjectServiceClient#getProject` は、空応答なら `IllegalStateException`、未登録なら `ProjectNotFoundException` で、null を返さない |
| 15 | `TermComparisonService` | `valueOf` | 355 | `switch (environment)` の default(`unknown environment`) | 呼び出しの `environment` は `ENVIRONMENT_ORDER`(`local` / `test` / `production`)の要素だけ |
| 16 | `TermComparisonService` | `awaitFetch` | 441 | `e.getCause() instanceof RuntimeException` が偽 | `CompletionException` の原因が `RuntimeException` でなくなるのは、取得処理が `Error`(OOM など)を投げる場合だけ。`Supplier` はチェック例外を投げられず、外部の入力で `Error` を起こす手段が無い(`RuntimeException` の側は、`wpSlug` が無い managed サイトの `NullPointerException` でテスト済み) |
| 17 | `TermComparisonService` | `resolveSite` | 480 | `switch (environment)` の default(`null`) | 15 と同じ(`ENVIRONMENT_ORDER` の要素だけが渡る) |
| 18 | `PostComparisonService` | `deleteEverywhere` | 117 | `envPosts == null` が真 | `resolvePostsByEnvironment` は 3 環境すべてのキーに non-null の値を入れて返す(値が一時的に null なのは、取得の投入から待ち合わせまでの間だけ)。`posts() == null`(対象外・エラーの環境)の側はテスト済み |
| 19 | `PostComparisonService` | `updateStatusEverywhere` | 143 | `envPosts == null` が真 | 18 と同じ |
| 20 | `PostComparisonService` | `resolveSite` | 233 | `switch (environment)` の default(`null`) | 15 と同じ |
| 21 | `SiteService` | `resolveDataSource` | 59 | `credentials instanceof CmsCredentials.WordPressCredentials` が偽 | `CmsCredentials` は `sealed interface` で、許可された実装は `WordPressCredentials` だけ(`getCredentials` の `switch` も 1 種類) |
| 22 | `PullRequestArticleService` | `fetchForPublish` | 65 | `ref == null` が真 | `ref` は `GithubPullRequestClient#getPullRequest` が `head.sha` を `asText("")` で読んだ値で、null にならない。空文字の側はテスト済み |
| 23 | `PluginThemeComparisonService` | `resolveSite` | 368 | `switch (environment)` の default(`null`) | 15 と同じ |
| 24 | `BulkManagementService` | `siteIdOf` | 553 | `switch (environment)` の default(`null`) | 呼び出しは、`ENVIRONMENT_ORDER` を回す箇所と、`requireSiteBound` が先に `VALID_ENVIRONMENTS` で検証した後の箇所だけ(不正な環境名は `IllegalArgumentException` でここまで来ない。テスト済み) |
| 25 | `BulkInstallJobRunner` | `toEntry` | 118 | `r.createdAt() == null` が真 | `BulkOperationLog` を作るのは `BulkManagementService#saveLog` だけで、`createdAt` を必ず設定する。non-null の側はテスト済み |

### 書き直しで外した・置き換えたテスト

書き直しで、振る舞いの検証を弱めずに残せなかったテストは次の 5 件。いずれも、自前のクラスのモックで例外や null を
返させる前提のテストで、外部との通信の境界からは同じ状態を作れない(上の表の行、または既存の別のテストが同じ分岐を持つ)。

| テスト | 理由 |
| --- | --- |
| `BulkInstallJobRunnerTest#nullCreatedAt` | 表の 25(`createdAt` は必ず設定される) |
| `BulkInstallJobRunnerTest#completionLineIsSingleWhenDoneNotificationFails` | `GenerationJobClient#updateStatus` は通信の失敗を例外にせず握りつぶす(終端は 3 回再試行してログ)ので、`done` の通知が例外になる状態を、外部の応答から作れない。完了行が 1 行であること自体は、`completionLineOnSuccess` / `completionLineOnFailure` と `JobCompletionLog` のテストが持つ |
| `PostPublishServiceTest#publish_照会結果がnullでも新規アップロードして続行する` | 表の 11(`findMediaBySha256` は null を返さない) |
| `TermComparisonServiceTest#listCategoryComparison_agentの取得がErrorで落ちたときはCompletionExceptionとして伝わる` | 表の 16(`Error` を起こす入力が無い) |
| `PostComparisonServiceTest#deleteEverywhere_1環境の削除が実行時例外でも他環境の削除は最後まで実行される` | `BulkManagementService#deletePostAtEnvironment` は `RuntimeException` を `FAILED` のログにして返すので、実行時例外が伝わる状態を作れない。「1 環境の削除が失敗しても他環境の削除は最後まで実行される」という振る舞いは、`FAILED` のログとして検証し直した(`deleteEverywhere_1環境の削除が失敗しても他環境の削除は最後まで実行される`)。例外が呼び出し元へ伝わることは、`BulkManagementServiceTest` の `SiteNotFoundException` / `IllegalStateException` を使うテストが持つ |

名前を変えたもの: `TermComparisonServiceTest#listCategoryComparison_agentの取得が例外なら並列化前と同じ例外がそのまま伝わる` →
`..._agentの取得が想定外の実行時例外なら...`(`wpSlug` が無い managed サイトの `NullPointerException` で起こす)。

テストを無効化・skip したものは無い(`@Disabled`・`@Ignore`・`assumeTrue` なども追加していない)。
