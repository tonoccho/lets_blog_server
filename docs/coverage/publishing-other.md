# services/publishing の `cms`・`service` 以外のパッケージの分岐カバレッジ(#1816)

Epic #1711 の子 Issue #1816 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。このときに
プロダクションコードを変更してはならない」を、`services/publishing` の `com.letsblog.publishing` のうち
`cms/`・`service/` を除くパッケージ(`aop`・`client`・`config`・`controller`・`domain`・`dto`・`github`・`messaging`・
`provisioning`・`render`・`repository` と、ルートのクラス)に適用した結果。プロダクションコードは変更していない
(この Issue の差分は `services/publishing/src/test/**` と本ファイルだけ。`.claude/hooks/paths.py` の
`is_production()` が True になるパスは 0 件)。

`cms/`(#1817 側)と `service/`(#1815 側、`docs/coverage/publishing-service.md`)は別の子 Issue の対象で、
本ファイルの数には含めない。

このファイルは次の 3 節で構成する。

1. 計測値(着手時・完了時)
2. モックの境界
3. テストから到達できない分岐

受け入れ試験(Gherkin)は書いていない。対象はコントローラ・クライアント・ドメインの内部の分岐で、web UI からは、
外部(WordPress・GitHub・各サービス)の応答や状態を作り分けて到達できない。サービスレベルのテスト(JUnit)で表した
(CLAUDE.md → Test-First Implementation → Where the tests live が認める、web UI から到達できない場合)。

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(CLAUDE.md → Coverage のとおり、`&&` / `||` もバイトコード上は別の分岐なので、
C1 と C2 を兼ねる)。値は `jacocoTestReport.xml` の各 `<package>` 直下の `<counter type="BRANCH">` から取る。
`services/publishing` のテスト全体を 1 回走らせた結果なので、`cms/` や `service/` を通すテストも数に入る。

計測コマンド(着手時・完了時とも同じ):

```bash
./gradlew :services:publishing:cleanTest :services:publishing:test :services:publishing:jacocoTestReport
```

計測条件:

- MySQL: ホストの `127.0.0.1:3306`(ADR-0006、#1769)が起動した状態で実行した(`@SpringBootTest` の統合テストが使う)。
- `-Dlbs.dockerAvailable` は**付けていない**(既定の `false`)。`HikariDeadConnectionRecoveryIntegrationTest`
  (`config` パッケージ。#1095)の 2 件は skipped になる。着手時も完了時も同じ条件で、このテストは本ファイルの
  対象のクラスの分岐を通さないので、数には影響しない。skipped はこの 2 件だけ
  (完了時の全体: 1511 件、失敗 0、skipped 2)。

### 着手時

| 項目 | 値 |
| --- | --- |
| コミット | `170cad1d`(develop 起点、ブランチ `test/1816-publishing-other-coverage` の作成直後。変更なし) |
| コマンド | 上のとおり(`-Dlbs.dockerAvailable` なし) |
| 全体のテスト | 1419 件、失敗 0、skipped 2 |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/publishing/domain` | 8 | 7 | 53.3% |
| `com/letsblog/publishing/dto` | 8 | 0 | 100.0% |
| `com/letsblog/publishing/controller` | 28 | 11 | 71.8% |
| `com/letsblog/publishing/aop` | 19 | 1 | 95.0% |
| `com/letsblog/publishing/config` | 17 | 2 | 89.5% |
| `com/letsblog/publishing/client` | 70 | 32 | 68.6% |
| `com/letsblog/publishing/render` | 11 | 1 | 91.7% |
| `com/letsblog/publishing/provisioning` | 14 | 0 | 100.0% |
| `com/letsblog/publishing/github` | 73 | 3 | 96.1% |
| **合計(下の 3 つを除く)** | **248** | **57** | **81.3%** |

`com/letsblog/publishing/repository`・`com/letsblog/publishing/messaging`・`com/letsblog/publishing`(ルート)は、
分岐を持つクラスが無く、`BRANCH` カウンタ自体が出ない(分母 0。着手時も完了時も同じ)。

着手時のテスト(本ファイルの対象のパッケージ): 69 ファイル(`testsupport/` を含む)、`@Test` / `@ParameterizedTest` 322 件。
そのうち 20 ファイルが、下の「着手時にモックの境界の外だった型」の型をモックしていた。

### 完了時

| 項目 | 値 |
| --- | --- |
| コミット | `67875b95`(テストの最終コミット。この後のコミットは本ファイルの追加だけで、テストとプロダクションコードは変わらない) |
| コマンド | 上のとおり(`-Dlbs.dockerAvailable` なし) |
| 全体のテスト | 1511 件、失敗 0、skipped 2 |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/publishing/domain` | 15 | 0 | 100.0% |
| `com/letsblog/publishing/dto` | 8 | 0 | 100.0% |
| `com/letsblog/publishing/controller` | 38 | 1 | 97.4% |
| `com/letsblog/publishing/aop` | 20 | 0 | 100.0% |
| `com/letsblog/publishing/config` | 19 | 0 | 100.0% |
| `com/letsblog/publishing/client` | 99 | 3 | 97.1% |
| `com/letsblog/publishing/render` | 12 | 0 | 100.0% |
| `com/letsblog/publishing/provisioning` | 14 | 0 | 100.0% |
| `com/letsblog/publishing/github` | 74 | 2 | 97.4% |
| **合計(下の 3 つを除く)** | **299** | **6** | **98.0%** |

分母(covered + missed)は着手時も完了時も 305 で変わらない(プロダクションコードを変えていない)。

残る 6 は、すべて下の「テストから到達できない分岐」に載せた(クラス別: `ContentServiceClient` 1、
`ProjectServiceClient` 1、`IdentityBridgeClient` 1、`GithubPullRequestClient` 2、`CmsProvisioningBridgeController` 1)。

完了時の本ファイルの対象のテストは 74 ファイル(`@Test` / `@ParameterizedTest` 365 件)。追加したテストクラス:
`client/ClientBranchTest`・`config/KeycloakRealmRoleConverterTest`・`domain/BulkOperationTypeTest`・
`github/GithubPullRequestClientEmptyResponseTest`・`testsupport/SpringWorld`(統合テスト用の部品)。

### 突き合わせの手順(受け入れ基準 3 の再現)

`jacocoTestReport.xml` から、`cms/`・`service/` を除くパッケージに残る未カバー分岐を行ごとに取り出す。

```bash
python3 -I - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('services/publishing/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
for pkg in root.findall('package'):
    name = pkg.get('name')
    if name.startswith('com/letsblog/publishing/cms') or name == 'com/letsblog/publishing/service':
        continue
    for sf in pkg.findall('sourcefile'):
        for line in sf.findall('line'):
            if int(line.get('mb')) > 0:
                print(name, sf.get('name'), line.get('nr'), 'missed', line.get('mb'), 'covered', line.get('cb'))
PY
```

完了時(`67875b95`)の出力は次の 6 行で、`missed` の合計 6 が下の表の行数(6 行)と 1 対 1 に対応する。

```text
com/letsblog/publishing/controller CmsProvisioningBridgeController.java 162 missed 1 covered 1
com/letsblog/publishing/client IdentityBridgeClient.java 129 missed 1 covered 3
com/letsblog/publishing/client ProjectServiceClient.java 256 missed 1 covered 3
com/letsblog/publishing/client ContentServiceClient.java 226 missed 1 covered 3
com/letsblog/publishing/github GithubPullRequestClient.java 364 missed 1 covered 3
com/letsblog/publishing/github GithubPullRequestClient.java 398 missed 1 covered 1
```

(行の並びは XML の順で、毎回同じとは限らない。突き合わせるのは「ファイル名・行」の集合。)

## モックの境界

本ファイルの対象のパッケージのテスト(`services/publishing/src/test/java/com/letsblog/publishing/` のうち
`cms/`・`service/` 以外の配下と、それらが共有する `.../testsupport/`)で、Mockito のモック(`@Mock` /
`@MockitoBean` / `@MockitoSpyBean` / `@Spy` / `mock()` / `spy()`)にしている型は次の **3 つだけ**。確認コマンドと
完了時(`67875b95`)の出力:

```bash
grep -rnE -A1 "@Mock|@MockitoBean|@MockitoSpyBean|@Spy|[^a-zA-Z.]mock\(|[^a-zA-Z]spy\(|Mockito\.(mock|spy)\(" \
  services/publishing/src/test/java/com/letsblog/publishing/{aop,client,config,controller,domain,dto,github,integration,provisioning,render,testsupport} \
  | grep -v "import " | grep -v "^--"
```

```text
.../controller/ArticleReviewFeedbackControllerTest.java:51:        repository = mock(ArticleReviewRepository.class);
.../controller/ArticleReviewApprovalControllerTest.java:51:        repository = mock(ArticleReviewRepository.class);
.../controller/ArticleReviewControllerTest.java:59:        repository = mock(ArticleReviewRepository.class);
.../dto/ArticleReviewFeedbackDateTimeSerializationTest.java:37:    private final ArticleReviewRepository repository = mock(ArticleReviewRepository.class);
.../integration/MultipartSizeLimitIntegrationTest.java:83:    @MockitoBean            (次の行: private JwtDecoder jwtDecoder;)
.../integration/MultipartSizeLimitIntegrationTest.java:87:    @MockitoBean            (次の行: private RabbitTemplate rabbitTemplate;)
.../integration/MultipartOversizeIntegrationTest.java:80:    @MockitoBean            (次の行: private JwtDecoder jwtDecoder;)
.../integration/MultipartOversizeIntegrationTest.java:84:    @MockitoBean            (次の行: private RabbitTemplate rabbitTemplate;)
.../integration/AuthorizationMatrixIntegrationTest.java:50:    @MockitoBean            (次の行: private JwtDecoder jwtDecoder;)
.../testsupport/PublishingWorld.java:104:    public final RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
```

(出力のうち `-A1` が付ける次の行は、`@MockitoBean` の型の確認用に括弧書きでまとめた。`ArticleReviewRepository`・
`JwtDecoder`・`RabbitTemplate` の 3 型だけで、下の表に載っている。表に無い型をモックしているテストは 0 件。)

| 型 | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `ArticleReviewRepository`(`com.letsblog.publishing.repository`) | Mockito `mock()` | **DB アクセス**(Spring Data のリポジトリ。自前のコードを持たないインタフェースで、`article_reviews` テーブルの読み書きそのもの) | `ArticleReviewControllerTest`・`ArticleReviewApprovalControllerTest`・`ArticleReviewFeedbackControllerTest`・`ArticleReviewFeedbackDateTimeSerializationTest` |
| `RabbitTemplate`(`org.springframework.amqp.rabbit.core`) | Mockito `mock()`(`PublishingWorld` が持ち、実物の `DomainEventPublisher`・`AuditLogService` へ渡す)、統合テストでは `@MockitoBean` | **MQ との送受信**(`letsblog.events`・ログ exchange への `convertAndSend`) | `PublishingWorld` を使う全テスト(`AuditLogAspectTest`・`PostControllerTest`・`BulkManagementControllerTest` など)・`MultipartSizeLimitIntegrationTest`・`MultipartOversizeIntegrationTest` |
| `JwtDecoder`(`org.springframework.security.oauth2.jwt`) | `@MockitoBean` | **Keycloak の鍵取得を伴う JWT の検証**(JWK Set の HTTP 取得) | `AuthorizationMatrixIntegrationTest`・`MultipartSizeLimitIntegrationTest`・`MultipartOversizeIntegrationTest` |

Mockito 以外で外部との通信(または Spring のフレームワークの入口)を差し替えているものも、同じ表に載せる。いずれも
Mockito のモックではなく、自前のクラスの代わりでもない。

| 型・仕掛け | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `FakeHttpServer`(`testsupport`。JDK 標準 `com.sun.net.httpserver.HttpServer` をループバックで起動) | サーバー側フェイク。メソッド+パスごとに応答を登録し、受けた要求を記録する。`GithubStubs`・`AgentListings` が応答の組み立てを補う | **HTTP 通信**: project-service・identity-service・content-service・media-service・ai-service の内部ブリッジ、GitHub REST API、provision-agent、サービス間トークンのエンドポイント。自前の `*Client`(`ProjectServiceClient`・`ContentServiceClient`・`IdentityClient`・`IdentityBridgeClient`・`MediaRenderClient`・`GithubPullRequestClient`・`WordPressBulkManagementClient` など)は実物をこのサーバーに向ける | `controller/`・`client/`・`github/`・`aop/`・`dto/`・`integration/` |
| JDK 標準 `HttpServer` を直接起動したもの | サーバー側フェイク(既存テストの手書き) | **HTTP 通信**(project-service・GitHub・content-service の応答の作り分け) | `ProjectServiceClient*Test`・`ContentServiceClientTest`・`GithubPullRequestClientTest` |
| `FakeWordPress`・`FakeSshCommandExecutor`(`testsupport` / `cms` のテストにある手書きフェイク) | `SshCommandExecutor` の手書きフェイク。`FakeWordPress` は wp-cli のコマンドを解釈して、(ホスト, wpPath) ごとの投稿・メディア・ターム・ユーザー・プラグイン・テーマの状態を持つ | **SSH(wp-cli)/SFTP**。自前の `WordPressAdapter`・`WordPressSshOperations`・`CmsAdapterFactory` は実物 | `controller/`・`integration/` |
| `SpringWorld.SshBoundary`(`testsupport`。`@TestConfiguration` の `@Primary` Bean) | `@SpringBootTest` のコンテキストで、`SshjCommandExecutor` の代わりに `FakeWordPress` を使わせる | **SSH**(外部通信の境界の差し替え。自前の Bean は実物のまま) | `MultipartSizeLimitIntegrationTest`・`MultipartOversizeIntegrationTest` |
| `MockHttpServletRequest`・`MockMultipartFile`・`MockMvc`(standalone)・`Jwt`・`TestingAuthenticationToken`(Spring Test / Spring Security) | Mockito ではないテスト用の実装 | **HTTP リクエストの入口**(`Authorization`・`X-Forwarded-For`・`User-Agent` ヘッダー、multipart のファイル、JWT の `sub`・`realm_access`) | `controller/`・`aop/`・`client/`・`config/` |
| `AspectJProxyFactory`(Spring の実際の AOP プロキシ) | `AuditLogAspect` を、本番と同じ Spring AOP の仕組みで対象のメソッドへ適用する(`ProceedingJoinPoint`・`MethodSignature` を Mockito で模さない) | フレームワークの入口(`@Around`) | `AuditLogAspectTest` |
| `AsyncProxies`(`testsupport`)+ 本番と同じ `AsyncJobConfig` の Executor | Spring の実際の AOP プロキシ(`AsyncAnnotationAdvisor`)で、自前のランナーの `@Async` を効かせる | フレームワークの入口(`@Async`) | `BulkManagementControllerTest` |

### 着手時にモックの境界の外だった型

着手時(`170cad1d`)の本ファイルの対象のテストは、20 ファイルで次の型をモックしていた(()内はモックしていたファイル数。
34 型のうち、DB・MQ・Keycloak の 3 型を除く 31 型が境界の外)。これらはすべて実物へ書き直した。完了時にこれらを
モックしているテストは 0 件。

- 自前のサービス: `AdminAuthorizationService`(8)・`CurrentActorService`(7)・`SiteService`(3)・`ProjectService`(3)・
  `BulkManagementService`(3)・`PostPublishService`(3)・`AuditLogService`(1)・`ArticlePreviewService`(1)・
  `ArticleReviewApprovalService`(1)・`ArticleReviewFeedbackService`(1)・`ArticleReviewPublishService`(1)・
  `ArticleSubmissionService`(1)・`PullRequestArticleService`(1)・`AuthorProvisioningService`(1)・
  `TermComparisonService`(1)・`PluginThemeComparisonService`(1)・`PostComparisonService`(1)・
  `ProvisioningService`(1)・`PostDeleteService`(1)・`BulkInstallJobStarter`(1)・`AssetImageUploadJobStarter`(1)
- 自前の CMS 層: `CmsAdapterFactory`(4)・`CmsAdapter`(4)・`WordPressSshOperations`(1)
- 自前のクライアント(通信を包むもの): `ProjectServiceClient`(3)・`GithubPullRequestClient`(2)・
  `MediaGeneratedImageClient`(1)・`IdentityClient`(1)
- 自前のクラスを包む通信ラッパー: `ServiceTokenClient`(3。`packages/lbs-common`。実物を、トークンのエンドポイントを
  `FakeHttpServer` にして使う)
- フレームワークの入口: `ProceedingJoinPoint`(1)・`MethodSignature`(1)(Spring の AOP プロキシへ書き直した)
- 境界の中(これは完了時も `mock()` のまま): `ArticleReviewRepository`(2)・`RabbitTemplate`(1)・`JwtDecoder`(3)

### 書き直しで外した・置き換えたテスト

外したテストは無い。書き直しは、モックで返させていた値を、外部(HTTP のフェイク・SSH のフェイク)の応答や状態として
作り直したもので、アサーションは減らしていない(モックの呼び出しの検証 `verify(...)` は、外部に実際に届いたリクエスト・
WordPress のフェイクに実際に書かれた状態の検証へ置き換えた)。テストを無効化・skip したものは無い
(`@Disabled`・`@Ignore`・`assumeTrue` なども追加していない)。

まとめたテスト: `BulkManagementControllerTest` の「admin権限がなければForbidden」(エンドポイントごとに 1 件ずつ、
23 件あった)は、`admin権限がなければどのエンドポイントもForbiddenで外部へ触れない` の 1 件にまとめた(全エンドポイントを
1 つのテストの中で、エンドポイント名つきで個別にアサートする。外部(agent・SSH・ai-service・media-service・
project-service)へ一切触れていないことの検証が加わっている)。

## テストから到達できない分岐

完了時に残る 6 分岐。プロダクションコードを変えずには、その分岐に入る入力や状態を作れない理由を具体的に書く。

| # | クラス | メソッド | 行 | 未カバーの分岐 | 到達できない理由 |
| ---: | --- | --- | ---: | --- | --- |
| 1 | `ContentServiceClient` | `bodyOrMessage` | 226 | `body != null` が偽 | `body` は `RestClientResponseException#getResponseBodyAsString()` の戻り値で、本文が無いときも null ではなく空文字を返す(Spring の `RestClientResponseException` は応答本文を空の配列で初期化する)。本文が空・空白の側(`isBlank()` が真)はテスト済み(`ClientBranchTest`) |
| 2 | `ProjectServiceClient` | `bodyOrMessage` | 256 | `body != null` が偽 | 1 と同じ(同じ実装の複製) |
| 3 | `IdentityBridgeClient` | `bodyOrMessage` | 129 | `body != null` が偽 | 1 と同じ(同じ実装の複製) |
| 4 | `GithubPullRequestClient` | `getFileContent` | 364 | `raw != null && raw.length == 0`(`raw == null` が偽で、`raw.length == 0` が真) | `raw` は `RestClient` の `body(byte[].class)` の戻り値で、本文が空(Content-Length 0 でも、チャンク形式の 0 バイトでも)のときは空配列ではなく null になる(実測: 本文が空のレスポンスは、どちらの形でも `raw == null` の側に入る。空配列にはならない)。`raw == null` が真の側と、本文があって `raw.length != 0` の側はテスト済み(`GithubPullRequestClientEmptyResponseTest`) |
| 5 | `GithubPullRequestClient` | `errorMessage` | 398 | `e.getResponseHeaders() == null` が真 | `e` は `RestClient` が HTTP の応答(403)から作る `RestClientResponseException` で、`DefaultResponseErrorHandler` は必ず応答のヘッダーを渡して生成する(null は、例外をヘッダー無しの形で手で作ったときだけ)。HTTP のフェイクが返す応答では、ヘッダーは常に non-null。ヘッダーがある側(残数 0 / 残数あり)はテスト済み |
| 6 | `CmsProvisioningBridgeController` | `requireWordPress` | 162 | `credentials instanceof CmsCredentials.WordPressCredentials` が偽 | `CmsCredentials` は `sealed interface` で、許可された実装は `WordPressCredentials` だけ。`buildCredentials` の `switch (cmsType)` も `WORDPRESS` の 1 種類しか無く、`CmsType.valueOf` が他の値を `IllegalArgumentException` で弾く(テスト済み)ので、別の実装の `CmsCredentials` はここへ来ない |
