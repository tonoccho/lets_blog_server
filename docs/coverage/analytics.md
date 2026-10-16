# services/analytics の分岐カバレッジ(#1827)

Epic #1711 の子 Issue #1827 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`services/analytics` の
`services/analytics/src/main/java/com/letsblog/analytics/` 配下すべて(50 ファイル)に適用した結果。
プロダクションコードは変更していない(この Issue の差分は `services/analytics/src/test/**` と本ファイルだけ。
`services/analytics/build.gradle` も変えていない — 追加の依存は要らなかった)。

この Issue の受け入れ基準は製品の振る舞いではなくテスト資産の状態なので、Gherkin の受け入れシナリオは書かない
(web UI から到達できない。CLAUDE.md → Where the tests live の例外)。検証は `services/analytics` の JUnit
(単体テストと、実 MySQL に対する統合テスト)で行う。

このファイルは次の 3 節で構成する。

1. 計測値(着手時・完了時)
2. モックの境界
3. テストから到達できない分岐

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(CLAUDE.md → Coverage のとおり、`&&` / `||` もバイトコード上は別の分岐なので、
C1 と C2 を兼ねる)。値は `jacocoTestReport.xml` の各 `<package>` 直下の `<counter type="BRANCH">` から取る。
`services/analytics` のテスト全体を 1 回走らせた結果なので、あるパッケージのクラスを間接的に通す他パッケージ
(`integration/` など)のテストも数に入る。

計測コマンド(着手時・完了時とも同じ):

```bash
./gradlew :services:analytics:cleanTest :services:analytics:test :services:analytics:jacocoTestReport
```

計測条件: ホストの MySQL(`127.0.0.1:3306`、ADR-0006・#1769)が起動した状態で実行した。DB を使う統合テストは
テスト専用スキーマ `lbs_analytics_test` に接続する。`services/analytics/build.gradle` の `jacoco` 設定に除外は無い。

### 着手時

| 項目 | 値 |
| --- | --- |
| コミット | `b9ea1fc5`(develop 起点、ブランチ `test/1827-analytics-coverage` の作成直後。変更なし) |
| コマンド | 上のとおり |
| テスト | 186 件実行、失敗 0・エラー 0・skipped 0(`build/test-results/test/*.xml` の合計) |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/analytics/adsense` | 44 | 0 | 100.0% |
| `com/letsblog/analytics/analytics` | 45 | 3 | 93.8% |
| `com/letsblog/analytics/client` | 7 | 9 | 43.8% |
| `com/letsblog/analytics/config` | 7 | 1 | 87.5% |
| `com/letsblog/analytics/domain` | 21 | 11 | 65.6% |
| `com/letsblog/analytics/service` | 63 | 9 | 87.5% |
| `com/letsblog/analytics`・`controller`・`dto`・`messaging`・`repository` | 2 | 0 | 100.0%(`messaging` の 2。ほかは分岐なし) |
| **合計** | **189** | **33** | **85.1%** |

着手時の未カバーのクラス別内訳(missed の数。合計 33): `AnalyticsCredentials` 11・`CurrentActorService` 7・
`ProjectBridgeClient` 5・`IdentityBridgeClient` 4・`GoogleAnalyticsClient` 3・`KeycloakRealmRoleConverter` 1・
`ProjectAnalyticsSettingsService` 1・`AdSenseReportService` 1。

着手時のテスト: 19 ファイル、うち 12 ファイルが Mockito を使い、自前のクラスをモックしていた
(「着手時にモックの境界の外だった型」を参照)。

### 完了時

| 項目 | 値 |
| --- | --- |
| コミット | `b2417015`(テストの最終コミット。この後のコミットは本ファイルの追加だけで、テストとプロダクションコードは変わらない) |
| コマンド | 上のとおり |
| テスト | 275 件実行、失敗 0・エラー 0・skipped 0 |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/analytics/adsense` | 44 | 0 | 100.0% |
| `com/letsblog/analytics/analytics` | 48 | 0 | 100.0% |
| `com/letsblog/analytics/client` | 16 | 0 | 100.0% |
| `com/letsblog/analytics/config` | 8 | 0 | 100.0% |
| `com/letsblog/analytics/domain` | 32 | 0 | 100.0% |
| `com/letsblog/analytics/service` | 72 | 0 | 100.0% |
| `com/letsblog/analytics`・`controller`・`dto`・`messaging`・`repository` | 2 | 0 | 100.0%(`messaging` の 2。ほかは分岐なし) |
| **合計** | **222** | **0** | **100.0%** |

分母(covered + missed)は着手時も完了時も 222 で変わらない(プロダクションコードを変えていない)。未カバーは 0 で、
下の「テストから到達できない分岐」に載せる分岐は無い。

完了時のテストは 30 ファイル(`testsupport/` の部品 4 ファイルを含む)。`@Test` 221 件・`@ParameterizedTest` 9 件
(うち `AuthorizationMatrixIntegrationTest` の 1 件が 23 エンドポイント分に展開される)。

### 突き合わせの手順(受け入れ基準 3 の再現)

`jacocoTestReport.xml` から、分岐数と、残る未カバー分岐を行ごとに取り出す。

```bash
python3 -I - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('services/analytics/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
covered = missed = 0
for pkg in root.findall('package'):
    for c in pkg.findall('counter'):
        if c.get('type') == 'BRANCH':
            covered += int(c.get('covered'))
            missed += int(c.get('missed'))
    for sf in pkg.findall('sourcefile'):
        for line in sf.findall('line'):
            if int(line.get('mb')) > 0:
                print(pkg.get('name') + '/' + sf.get('name'), line.get('nr'),
                      'missed', line.get('mb'), 'covered', line.get('cb'))
print('BRANCH covered', covered, 'missed', missed)
PY
```

完了時(`b2417015`)の出力は次の 1 行だけで、未カバーの行が 1 件も出ない。

```text
BRANCH covered 222 missed 0
```

着手時(`b9ea1fc5`)の出力(33 件の内訳。これが RED の記録にあたる。行番号は当時のファイルのもの):

```text
com/letsblog/analytics/client/ProjectBridgeClient.java 60 missed 2 covered 0
com/letsblog/analytics/client/ProjectBridgeClient.java 65 missed 2 covered 0
com/letsblog/analytics/client/ProjectBridgeClient.java 94 missed 1 covered 3
com/letsblog/analytics/client/IdentityBridgeClient.java 55 missed 4 covered 0
com/letsblog/analytics/domain/AnalyticsCredentials.java 92 missed 2 covered 6
com/letsblog/analytics/domain/AnalyticsCredentials.java 102 missed 4 covered 0
com/letsblog/analytics/domain/AnalyticsCredentials.java 106 missed 5 covered 1
com/letsblog/analytics/config/KeycloakRealmRoleConverter.java 24 missed 1 covered 3
com/letsblog/analytics/service/CurrentActorService.java 43 missed 2 covered 0
com/letsblog/analytics/service/CurrentActorService.java 47 missed 4 covered 0
com/letsblog/analytics/service/CurrentActorService.java 86 missed 1 covered 3
com/letsblog/analytics/service/ProjectAnalyticsSettingsService.java 104 missed 1 covered 1
com/letsblog/analytics/service/AdSenseReportService.java 58 missed 1 covered 1
com/letsblog/analytics/analytics/GoogleAnalyticsClient.java 71 missed 1 covered 3
com/letsblog/analytics/analytics/GoogleAnalyticsClient.java 150 missed 1 covered 1
com/letsblog/analytics/analytics/GoogleAnalyticsClient.java 156 missed 1 covered 3
BRANCH covered 189 missed 33
```

(`package` と `sourcefile` の並びは XML の順で、毎回同じとは限らない。突き合わせるのは「ファイル名・行」の集合。)

注意: JaCoCo は、ブロックの最後まで実行が進んだときにだけそのブロックをカバー済みにする。例外を投げて終わる呼び出しだけを
通したテストでは、その呼び出しの後ろの分岐が「未通過」と出る。分岐を埋めるときは、例外になる経路と、例外にならず戻る経路の
両方を通す。

## モックの境界

`services/analytics/src/test/java/com/letsblog/analytics/` のテストで、Mockito のモック(`@Mock` / `@MockitoBean` /
`@MockitoSpyBean` / `mock()` / `spy()`)にしている型は次の **4 つだけ**。数え上げの確認コマンドと完了時(`b2417015`)の出力:

```bash
cd services/analytics/src/test/java/com/letsblog/analytics
{
  for f in $(find . -name '*.java'); do
    awk '/^[[:space:]]*@(Mock|MockitoBean|MockitoSpyBean|Spy)[[:space:]]*$/{getline n; print n}' "$f"
  done
  grep -rhoE "(Mockito\.)?(mock|spy)\([A-Za-z]+\.class" . | sed -E 's/.*\(//; s/\.class//'
} | sed -E 's/^[[:space:]]*(private|public|protected)?[[:space:]]*(final)?[[:space:]]*//; s/[[:space:]]+[a-zA-Z]+;?[[:space:]]*$//; s/ = .*//' \
  | sort | uniq -c | sort -rn
```

```text
      2 ConnectionFactory
      2 AnalyticsCredentialsRepository
      1 ProcessedEventRepository
      1 JwtDecoder
```

(左の数は宣言の出現数。`AnalyticsCredentialsRepository` は `testsupport/AnalyticsWorld` の `mock()` 1 と
`EventMessageListenerTest` の `@Mock` 1。)

| 型 | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `AnalyticsCredentialsRepository`(`com.letsblog.analytics.repository`) | Mockito `mock()`。`AnalyticsWorld` では `findByProjectId` / `save` / `deleteByProjectId` をメモリ上の `Map` で応答させ、「テーブル」として読み書きできるようにしている | **DB アクセス**(Spring Data のリポジトリ。自前のコードを持たないインタフェースで、`analytics_credentials` テーブルの読み書きそのもの) | `AnalyticsWorld` 経由で `AnalyticsCredentialsServiceTest`・`ProjectAnalyticsSettingsServiceTest`・`GoogleAnalyticsReportServiceTest`・`AdSenseReportServiceTest`・`AdminAuthorizationServiceTest`・`CurrentActorServiceTest`・`*ControllerTest`。`EventMessageListenerTest` は `@Mock` で、`deleteByProjectId` の呼び出しを確かめる |
| `ProcessedEventRepository` | `@Mock`。`existsById` / `save` を `HashSet` で応答させる | **DB アクセス**(`processed_events` テーブル。冪等性の記録) | `EventMessageListenerTest` |
| `ConnectionFactory`(`org.springframework.amqp.rabbit.connection`) | `mock()` | **MQ との接続**(`RabbitMqConfig#eventsListenerContainerFactory` に渡すだけの接続ファクトリ。接続は開かない) | `RabbitMqConfigTest` |
| `JwtDecoder`(`org.springframework.security.oauth2.jwt`) | `@MockitoBean`(`integration/AnalyticsIntegrationTestBase` に 1 つだけ) | **Keycloak の鍵取得を伴う JWT 検証**(JWKS エンドポイントへの HTTP。テストは署名済み JWT を直接返す) | `AdminAuthorizationIntegrationTest`・`AuthorizationMatrixIntegrationTest`(`JwtAzpAllowListIntegrationTest` は実物の `JwtDecoder` を `JwksTestServer` に向ける) |

Mockito 以外で外部との通信(または Spring のフレームワークの入口)を差し替えているものも、同じ表に載せる。いずれも
Mockito のモックではなく、自前のクラスの代わりでもない。

| 型・仕掛け | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `FakeHttpServer`(`testsupport`。JDK 標準 `com.sun.net.httpserver.HttpServer` をループバックで起動) | サーバー側フェイク。メソッド+パスごとに応答を登録し、受けた要求を記録する | **HTTP 通信**。自前の `IdentityBridgeClient`・`ProjectBridgeClient`・`GoogleAnalyticsClient`・`AdSenseClient` と、`packages/lbs-common` の `IdentityClient`(`SyncServiceClient`)は実物を、ベース URL だけこのサーバーに向けて通す | `AnalyticsWorld` を使う全テスト・`IdentityBridgeClientTest`・`ProjectBridgeClientEligibilityTest`・`integration/AnalyticsIntegrationTestBase` |
| `FakeBackends`(`testsupport`) | `FakeHttpServer` に、identity-service の `GET /api/identity/me`・プロジェクトメンバー判定、project-service の本番サイト有無・GA 連携の完了通知の応答を載せたもの。操作者・メンバー・プロジェクトは `actor` / `member` / `project` で登録し、未登録のトークンは 401、未登録のプロジェクトは 404 | **HTTP 通信**(identity-service・project-service) | `AnalyticsWorld` を使う全テスト・`AdminAuthorizationIntegrationTest`・`AuthorizationMatrixIntegrationTest` |
| `GoogleFakes`(`testsupport`) | `FakeHttpServer` に、GA4 Data API の runReport(合計・日次・チャネル別)と AdSense Management API の reports:generate(合計・日次・プラットフォーム別)の応答を載せたもの | **HTTP 通信**(Google の API) | `GoogleAnalyticsReportServiceTest`・`AdSenseReportServiceTest`・`ProjectDashboardControllerTest` |
| `MockRestServiceServer`(Spring Test) | `RestClient.Builder` に束ねるサーバー側フェイク(従来どおり) | **HTTP 通信**(Google の OAuth トークンエンドポイント / GA4 / AdSense。失敗応答の再現を含む) | `GoogleAnalyticsClientTest`・`AdSenseClientTest` |
| JDK 標準の `HttpServer`(`ProjectBridgeClientTest` が直接起動) | サーバー側フェイク(従来どおり)。応答のステータスだけ変える | **HTTP 通信**(project-service への GA 連携の完了通知。届かない場合も含む) | `ProjectBridgeClientTest` |
| `JwksTestServer`(`packages/lbs-common` の testFixtures) | 実物の `JwtDecoder` が鍵を取りに行く JWKS エンドポイントのサーバー側フェイク | **Keycloak の鍵取得**(JWKS への HTTP) | `JwtAzpAllowListIntegrationTest` |
| `MockMvc`(`standaloneSetup` と `@AutoConfigureMockMvc`)・`MockHttpServletRequest`・`JwtAuthenticationToken`(Spring Security) | Mockito ではないテスト用の実装 | **HTTP リクエストの入口**(`Authorization` ヘッダー、JWT の `sub`)。コントローラ・`CurrentActorService`・`GlobalExceptionHandler` は実物 | `AnalyticsWorld`(`mvc`・`asBearer`)・`*ControllerTest`・`CurrentActorServiceTest`・`GlobalExceptionHandlerTypeMismatchTest` |
| 実 MySQL(`lbs_analytics_test`、ADR-0006) | 差し替えない | **DB アクセス** | `integration/*`(`MigrationContractTest` など。`@SpringBootTest` は Flyway で実スキーマを作り、`ddl-auto: validate` で検証する) |

次の仕掛けは、外部との通信ではないが、モックでもない(実物のオブジェクトに、環境や入力を与える)。

| 仕掛け | 内容 | 使っているテスト |
| --- | --- | --- |
| `AnalyticsWorld#currentRequestProxy`(`java.lang.reflect.Proxy`) | `CurrentActorService` が受け取る `HttpServletRequest` を、Spring が request スコープの Bean に差し込むプロキシと同じく `RequestContextHolder` の現在のリクエストへ委譲する。Mockito は使っておらず、`MockMvc` は実行中にそのホルダーを設定し、サービス直接呼び出しのテストは `asBearer` で設定する | `AnalyticsWorld` を使う全テスト |
| `RabbitMqConfigTest` の `MethodInterceptor`(ラムダ) | 何もせず `proceed()` するだけの実物の advice(`mock(Advice.class)` の代わり) | `RabbitMqConfigTest` |

### 着手時にモックの境界の外だった型

着手時(`b9ea1fc5`)のテストは、12 ファイルで次の型をモックしていた(()内は宣言の出現数)。境界の中の DB・MQ・JWT 検証
(後述)を除き、すべて実物へ書き直した。完了時にこれらをモックしているテストは 0 件。

- 自前のクラス: `CurrentActorService`(4)・`AdminAuthorizationService`(4)・`AnalyticsCredentialsService`(3)・
  `ProjectAnalyticsSettingsService`(2)・`GoogleAnalyticsReportService`(1)・`AdSenseReportService`(1)
- 自前の通信ラッパー(`*Client`): `ProjectBridgeClient`(3)・`IdentityBridgeClient`(3)・`GoogleAnalyticsClient`(2)・
  `AdSenseClient`(2)
- `packages/lbs-common` の通信ラッパー: `IdentityClient`(2)
- ライブラリ: `Advice`(1。AOP の `Advice`)
- 境界の中(DB・MQ・JWT 検証。完了時も `mock()` / `@MockitoBean` のまま): `JwtDecoder`(2)・`ConnectionFactory`(2)・
  `AnalyticsCredentialsRepository`(2)・`ProcessedEventRepository`(1)

### 書き直しで形を変えたテスト

書き直しで、自前のクラスのモックが「例外や任意の戻り値を返す」前提だったテストは、外部との通信の境界(HTTP の応答・DB の戻り値)
から同じ状態を作る形にした。検証している振る舞いは減らしていない。形が変わったものを挙げる。

| テスト | 変更 |
| --- | --- |
| `AdminAuthorizationServiceTest` | `CurrentActorService` / `IdentityBridgeClient` のモックをやめ、実物を identity-service のフェイクに向けた。`isAdmin` の戻り値のスタブは操作者の `role`、`isProjectMember` のスタブはメンバー判定の応答になった。「操作者を解決できなければ例外」は、identity-service が 401 を返す(無効化ユーザー等)状態で作る。「adminならメンバー判定へ問い合わせない」「呼び出し元のトークンを転送する」の確認を足した |
| `AnalyticsCredentialsServiceTest` | `@Mock AnalyticsCredentialsRepository` の `when(...)` / `verify(save)` を、メモリ上の「テーブル」(`AnalyticsWorld#store`)の読み書きに置き換えた。保存されたかどうかは `store` の中身で確かめる(`verify` の回数ではなく状態) |
| `ProjectAnalyticsSettingsServiceTest` | `AnalyticsCredentialsService` / `AdSenseClient` / `GoogleAnalyticsClient` のモックをやめた。`verify(...).setGaOauthClient(...)` などは保存された行の内容(復号して比較)に、`when(googleAnalyticsClient.exchangeAuthorizationCode(...))` などは Google のフェイクの応答と、フェイクが受けたフォーム本文(`client_id`・`code` など)の確認になった。「クライアントシークレット未保存ならクライアント側で拒否」は実物のクライアントが例外にすることと、Google へ何も送られないことで確かめる。`selectGoogleAnalyticsProperty` の `null` を足した |
| `GoogleAnalyticsReportServiceTest` / `AdSenseReportServiceTest` | 5 つのモック(`ProjectBridgeClient`・`AnalyticsCredentialsService`・`*Client`・`AdminAuthorizationService`・`CurrentActorService`)をやめ、全部実物にした。`verify(client, never()).fetchReport(...)` は、Google のフェイクへ要求が届いていないことに置き換えた。取得失敗の `errorMessage` は、モックが投げた文言ではなく、実物のクライアントが Google の 500 / 400 を変換した文言で確かめる。`AdSenseReportService` のクライアントシークレット未保存の経路を足した |
| `ProjectDashboardControllerTest` / `InternalAnalyticsProjectSettingsControllerTest` / `ProjectAnalyticsApiKeyControllerTest` | コントローラのメソッドを直接呼び、サービスのモックへの `verify` / `InOrder` を確かめていた。実物のコントローラ・サービスを `MockMvc` に載せ、HTTP のステータス・本文と、保存された行・Google や project-service のフェイクへの要求で確かめる。「認可を先に行う」は、認可に失敗した全エンドポイントで保存も下流への通信も起きないこと(`認可に失敗したら全てのエンドポイントで保存も下流への通信も起きない`)で、「保存が拒否されたら project-service へ知らせない」は、フェイクに通知が届かないことで確かめる |
| `AdminAuthorizationIntegrationTest` / `AuthorizationMatrixIntegrationTest` | `@MockitoBean IdentityClient` / `IdentityBridgeClient` を、実物の Bean を `AnalyticsIntegrationTestBase` のフェイクの HTTP に向ける形にした(`@DynamicPropertySource` を親に 1 つだけ置いた)。`verify(identityClient).lookupProfile(...)` は、フェイクが `GET /api/identity/me` を `Authorization` ヘッダーつきで 1 回受けたことの確認に置き換えた(#916 の趣旨 — 呼び出し先が変わっても空振りに気付く — は同じ) |
| `RabbitMqConfigTest#相関ID_adviceが既存のadvice_chainの先頭に追加される` | `mock(Advice.class)` を、何もせず通すだけの実物の `MethodInterceptor` にした |

### 追加したテスト(未カバーだった分岐を通すもの)

| テスト | 通す分岐 |
| --- | --- |
| `CurrentActorServiceTest`(新規) | `getCurrentActorKeycloakSub`(認証なし / JWT 以外 / `sub` が空白・無し / あり)・`Authorization` ヘッダーが無い・空白・identity-service の 5xx(`IdentityServiceUnavailableException`)・同じリクエストの中での問い合わせ 1 回(キャッシュ) |
| `IdentityBridgeClientTest`(新規) | `setAuthorization` の `null` / 空白 / あり・本文が空・失敗応答・接続断 |
| `ProjectBridgeClientEligibilityTest`(新規) | `getProjectEligibility` の応答が空・404・404 以外の失敗・接続断・トークンの有無 |
| `AnalyticsCredentialsTest`(新規) | `hasAdsenseCredentials` / `hasAdsenseOauthClientSecret` / `hasAdsenseOauthClient` ほか、各条件(`null` / 空白 / 空配列)の組み合わせごとに結果が変わること |
| `KeycloakRealmRoleConverterTest`(新規) | `realm_access` が無い・`roles` が無い・`roles` が配列でない |
| `GoogleAnalyticsClientTest`(追加 3 件) | `refresh_token` が空白・プロパティ名に `properties/` が無い・`nextPageToken` が空文字 |

テストを無効化・skip したものは無い(`@Disabled`・`@Ignore`・`assumeTrue` なども追加していない)。

## テストから到達できない分岐

完了時に残る未カバー分岐は **0 件**。上の突き合わせの手順の出力(`BRANCH covered 222 missed 0`)のとおり、
`jacocoTestReport.xml` に未カバーの行が無いので、載せる分岐は無い。

着手時に「到達できないかもしれない」と見えた次の分岐は、プロダクションコードを変えずに通せた。

- `ProjectAnalyticsSettingsService#selectGoogleAnalyticsProperty` の `propertyId == null`(104 行): 公開 API の
  `@NotBlank` は通らないが、サービスのメソッドは直接呼べる。`null` を渡して `IllegalArgumentException` になることを
  `ProjectAnalyticsSettingsServiceTest#selectGoogleAnalyticsProperty_未連携や数字でないIDは拒否する` で確かめている。
- `CurrentActorService#getCurrentActorKeycloakSub` の `subject == null`(47 行): `sub` クレームを持たない `Jwt` を
  `JwtAuthenticationToken` に包んで `SecurityContextHolder` に置くことで通した(`CurrentActorServiceTest`)。
  この分岐は呼び出し元がプロダクションコードの中に無い(public メソッドだが未使用)ため、テストが唯一の入口である。
- `ProjectBridgeClient#getProjectEligibility` の `eligibility == null`(60 行): project-service が本文なしの 200 を返す
  状態を、フェイクの HTTP で作れる(`ProjectBridgeClientEligibilityTest#応答の本文が空ならIdentityServiceUnavailableException`)。
