# services/project の `service` 以外のパッケージの分岐カバレッジ(#1818)

Epic #1711 の子 Issue #1818 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。このときに
プロダクションコードを変更してはならない」を、`services/project` の `com.letsblog.project` のうち `service/` を除く
パッケージ(`aop`・`client`・`cms`・`config`・`controller`・`crypto`・`domain`・`dto`・`messaging`・`provisioning`・
`repository` と、ルートのクラス)に適用した結果。プロダクションコードは変更していない(この Issue の差分は
`services/project/src/test/**` と本ファイルだけ。`.claude/hooks/paths.py` の `is_production()` が True になるパスは 0 件)。

`service/`(#1817 側、`docs/coverage/project-service.md`)は別の子 Issue の対象で、本ファイルの数には含めない。

このファイルは次の 3 節で構成する。

1. 計測値(着手時・完了時)
2. モックの境界
3. テストから到達できない分岐

受け入れ試験(Gherkin)は書いていない。この Issue の受け入れ基準は製品の振る舞いではなくテスト資産の状態(計測値・モックの型・
未カバー分岐の一覧)で、web UI から到達できない。対象のコードも、コントローラ・クライアント・ドメインの内部の分岐で、web UI から
外部(identity-service・publishing-service・ai-service・各 SNS の API)の応答を作り分けて到達することはできない。サービスレベルの
テスト(JUnit)で表した(CLAUDE.md → Test-First Implementation → Where the tests live が認める、web UI から到達できない場合)。

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(CLAUDE.md → Coverage のとおり、`&&` / `||` もバイトコード上は別の分岐なので、
C1 と C2 を兼ねる)。値は `jacocoTestReport.xml` の各 `<package>` 直下の `<counter type="BRANCH">` から取る。
`services/project` のテスト全体を 1 回走らせた結果なので、`service/` を通すテストも数に入る。

計測コマンド(着手時・完了時とも同じ):

```bash
./gradlew :services:project:cleanTest :services:project:test :services:project:jacocoTestReport
```

計測条件: ホストの MySQL(`127.0.0.1:3306`、ADR-0006・#1769)が起動した状態で実行した(`@SpringBootTest` の統合テストが使う)。
`-Dlbs.dockerAvailable` は付けていない。着手時・完了時とも、`services/project` 全体で失敗 0、エラー 0、skipped 0
(`build/test-results/test/*.xml` の `tests` / `failures` / `errors` / `skipped` の合計。着手時 985 件、完了時 1058 件)。

### 着手時

| 項目 | 値 |
| --- | --- |
| コミット | `39cba09e`(develop 起点、ブランチ `test/1818-project-other-coverage` の作成直後。変更なし) |
| コマンド | 上のとおり |
| 全体のテスト | 985 件、失敗 0、skipped 0 |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/project/aop` | 19 | 1 | 95.0% |
| `com/letsblog/project/client` | 221 | 15 | 93.6% |
| `com/letsblog/project/config` | 0 | 4 | 0.0% |
| `com/letsblog/project/controller` | 19 | 25 | 43.2% |
| `com/letsblog/project/crypto` | 5 | 5 | 50.0% |
| `com/letsblog/project/domain` | 6 | 6 | 50.0% |
| `com/letsblog/project/dto` | 12 | 0 | 100.0% |
| `com/letsblog/project/messaging` | 2 | 0 | 100.0% |
| `com/letsblog/project/provisioning` | 2 | 2 | 50.0% |
| **合計(下の 4 つを除く)** | **286** | **58** | **83.1%** |

`com/letsblog/project/cms`・`com/letsblog/project/repository`・`com/letsblog/project`(ルート)は、分岐を持つクラスが無く
(record・インタフェース・分岐の無いクラスだけ)、`BRANCH` カウンタ自体が出ない(分母 0。着手時も完了時も同じ)。`service/` は対象外。

着手時の未カバーのクラス別内訳(missed の数。合計 58): `ProjectInternalController` 13・`SiteController` 8・
`ProjectGithubTokenController` 4・`IdentityBridgeClient` 4・`HatenaApiClient` 4・`KeycloakRealmRoleConverter` 4・
`IdentityClient` 3・`CmsProvisioningBridgeClient` 2・`WordPressAgentPluginsClient` 2・`Project` 6・
`SshKeyGenerationService` 5・`AiGenerationClient` 1・`LinkedinApiClient` 1・`AuditLogAspect` 1(コントローラの未カバーは、
自前のサービスをモックしたため、モックの先の分岐が動いていなかったものを含む)。

着手時のテスト(本ファイルの対象のパッケージ): 54 ファイル(`testsupport/` を含む)、`@Test` / `@ParameterizedTest` 351 件。
そのうち 26 ファイルが Mockito でモックを作っていた。

### 完了時

| 項目 | 値 |
| --- | --- |
| コミット | `8d82fa59`(テストの最終コミット。この後のコミットは本ファイルの追加だけで、テストとプロダクションコードは変わらない) |
| コマンド | 上のとおり |
| 全体のテスト | 1058 件、失敗 0、skipped 0 |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/project/aop` | 20 | 0 | 100.0% |
| `com/letsblog/project/client` | 234 | 2 | 99.2% |
| `com/letsblog/project/config` | 4 | 0 | 100.0% |
| `com/letsblog/project/controller` | 44 | 0 | 100.0% |
| `com/letsblog/project/crypto` | 7 | 3 | 70.0% |
| `com/letsblog/project/domain` | 12 | 0 | 100.0% |
| `com/letsblog/project/dto` | 12 | 0 | 100.0% |
| `com/letsblog/project/messaging` | 2 | 0 | 100.0% |
| `com/letsblog/project/provisioning` | 4 | 0 | 100.0% |
| **合計(下の 4 つを除く)** | **339** | **5** | **98.5%** |

分母(covered + missed)は着手時 344、完了時 344 で変わらない(プロダクションコードを変えていない)。

残る 5 は、すべて下の「テストから到達できない分岐」に載せた(クラス別: `HatenaApiClient` 2、`SshKeyGenerationService` 3)。

完了時の本ファイルの対象のテストは 61 ファイル(`@Test` / `@ParameterizedTest` 424 件)。新規・書き直しの要点:

- 書き直し: `controller/` の 19 ファイルすべて(自前のサービス・`AdminAuthorizationService` などのモックをやめ、実物へ)、
  `aop/AuditLogAspectTest`(`ProceedingJoinPoint`・`MethodSignature` のモックをやめ、Spring の AOP プロキシへ)、
  `client/CmsProvisioningBridgeClientSnsTest`・`CmsProvisioningBridgeClientLetsblogSyncTest`(`HttpServletRequest` を
  `MockHttpServletRequest` へ、`ServiceTokenClient` を実物へ)、`client/OutboundAuthHeadersTest`・`BridgeClientJobAuthTest`
  (`ServiceTokenClient` を実物へ)、`integration/AuthorizationMatrixIntegrationTest`(`IdentityClient` のモックをやめ、HTTP のフェイクへ)。
- 追加: `testsupport/ProjectApp`(自前のクラスをすべて実物で組み立てる土台)、`client/ClientBranchTest`・
  `client/ThirdPartyClientBranchTest`・`config/KeycloakRealmRoleConverterTest`・`domain/ProjectTest`・
  `provisioning/WordPressAgentPluginsClientTest`・`controller/ProjectGithubTokenControllerTest`、
  `crypto/SshKeyGenerationServiceTest` への失敗系 2 件。

### 突き合わせの手順(受け入れ基準 3 の再現)

`jacocoTestReport.xml` から、`service/` を除くパッケージに残る未カバー分岐を行ごとに取り出す。

```bash
python3 -I - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('services/project/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
P = 'com/letsblog/project'
for pkg in root.findall('package'):
    name = pkg.get('name')
    if name == P + '/service' or name.startswith(P + '/service/'):
        continue
    for sf in pkg.findall('sourcefile'):
        for line in sf.findall('line'):
            if int(line.get('mb')) > 0:
                print(name, sf.get('name'), line.get('nr'), 'missed', line.get('mb'), 'covered', line.get('cb'))
PY
```

完了時(`8d82fa59`)の出力は次の 5 行で、`missed` の合計 5 が下の表の行数(5 行)と 1 対 1 に対応する。

```text
com/letsblog/project/client HatenaApiClient.java 153 missed 1 covered 1
com/letsblog/project/client HatenaApiClient.java 208 missed 1 covered 1
com/letsblog/project/crypto SshKeyGenerationService.java 41 missed 1 covered 1
com/letsblog/project/crypto SshKeyGenerationService.java 45 missed 1 covered 1
com/letsblog/project/crypto SshKeyGenerationService.java 59 missed 1 covered 1
```

(行の並びは XML の順で、毎回同じとは限らない。突き合わせるのは「ファイル名・行」の集合。)

## モックの境界

本ファイルの対象のパッケージのテスト(`services/project/src/test/java/com/letsblog/project/` のうち `service/` 以外の配下と、
それらが共有する `.../testsupport/`)で、Mockito のモック(`@Mock` / `@MockitoBean` / `@MockitoSpyBean` / `@Spy` /
`mock()` / `spy()`)にしている型は次の **10 型だけ**。確認コマンドと完了時(`8d82fa59`)の出力:

```bash
grep -rnE -A1 "@Mock\b|@MockitoBean|@MockitoSpyBean|@Spy\b|[^a-zA-Z.]mock\(|[^a-zA-Z]spy\(|Mockito\.(mock|spy)\(" \
  services/project/src/test/java/com/letsblog/project \
  | grep -v "/service/" | grep -v "import " | grep -v "^--"
```

```text
.../messaging/DomainEventPublisherTest.java:31:    @Mock            (次の行: private RabbitTemplate rabbitTemplate;)
.../integration/AuthorizationMatrixIntegrationTest.java:50:    @MockitoBean     (次の行: private JwtDecoder jwtDecoder;)
.../controller/TagDesignInternalControllerTest.java:30:  Mockito.mock(TagDesignSettingRepository.class)
.../testsupport/ProjectWorld.java:72-75:   Mockito.mock(SiteRepository.class) / Mockito.mock(SshKeyPairRepository.class) / Mockito.mock(RabbitTemplate.class)
.../testsupport/SnsFixture.java:26:        Mockito.mock(ProjectRepository.class)
.../testsupport/ProjectApp.java:51-56:     Mockito.mock(ProjectRepository.class) / TagDesignSettingRepository / StaticContentRepository / ProjectPvRuleRepository / ProjectPvSyncStateRepository / ProjectSnsTemplateRepository
```

(出力のうち `-A1` が付ける次の行や、同じファイルの連続する行は、型の確認用にまとめた。`ProjectWorld` の Javadoc 内の
`Mockito mock` の記述は行にヒットするだけで、モックではない。)

| 型 | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `SiteRepository`(`com.letsblog.project.repository`) | Mockito `mock()` | **DB アクセス**(Spring Data のリポジトリ。自前のコードを持たないインタフェースで、`sites` テーブルの読み書きそのもの) | `ProjectWorld` を使う全テスト(`controller/`・`client/`・`aop/` ほか) |
| `SshKeyPairRepository` | Mockito `mock()` | **DB アクセス**(`ssh_key_pairs` テーブル) | `ProjectWorld` を使う全テスト |
| `ProjectRepository` | Mockito `mock()` | **DB アクセス**(`projects` テーブル) | `SnsFixture`・`ProjectApp` を使う `controller/` のテスト |
| `TagDesignSettingRepository` | Mockito `mock()` | **DB アクセス**(`tag_design_settings` テーブル) | `ProjectApp` を使う `TagDesignSettingControllerTest`・`GlobalTagDesignSettingControllerTest`、`TagDesignInternalControllerTest` |
| `StaticContentRepository` | Mockito `mock()` | **DB アクセス**(`static_contents` テーブル) | `ProjectApp` を使う `SiteStaticContentControllerTest` |
| `ProjectPvRuleRepository` | Mockito `mock()` | **DB アクセス**(PV 達成ルールのテーブル) | `ProjectSnsPvControllerTest`・`ProjectPvInternalControllerTest` |
| `ProjectPvSyncStateRepository` | Mockito `mock()` | **DB アクセス**(PV ルールの送信状態のテーブル) | `ProjectSnsPvControllerTest`・`ProjectPvInternalControllerTest` |
| `ProjectSnsTemplateRepository` | Mockito `mock()` | **DB アクセス**(告知文テンプレートのテーブル) | `ProjectSnsTemplateControllerTest` |
| `RabbitTemplate`(`org.springframework.amqp.rabbit.core`) | Mockito `mock()`(`ProjectWorld` が持ち、実物の `DomainEventPublisher`・`AuditLogService` へ渡す)、`DomainEventPublisherTest` は `@Mock` | **MQ との送受信**(`letsblog.events`・ログ exchange への `convertAndSend`) | `ProjectWorld` を使う全テスト(`AuditLogAspectTest` ほか)・`DomainEventPublisherTest` |
| `JwtDecoder`(`org.springframework.security.oauth2.jwt`) | `@MockitoBean` | **Keycloak の鍵取得を伴う JWT の検証**(JWK Set の HTTP 取得) | `AuthorizationMatrixIntegrationTest` |

Mockito 以外で外部との通信(または Spring のフレームワークの入口)を差し替えているものも、同じ表に載せる。いずれも
Mockito のモックではなく、自前のクラスの代わりでもない。

| 型・仕掛け | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `FakeHttpServer`(`testsupport`。JDK 標準 `com.sun.net.httpserver.HttpServer` をループバックで起動) | サーバー側フェイク。メソッド+パスごとに応答を登録し、受けた要求を記録する。`ProjectWorld` が identity-service・publishing-service(CMS ブリッジ)・ai-service・analytics-service・content-service・provision-agent・サービス間トークンのエンドポイントを 1 台にまとめる | **HTTP 通信**。自前の `*Client`(`IdentityClient`・`IdentityBridgeClient`・`CmsProvisioningBridgeClient`・`AiGenerationClient`・`AnalyticsBridgeClient`・`ContentBridgeClient`・`WordPress*Client`・`XApiClient` ほか各 SNS の `*ApiClient`)・`ServiceTokenClient` は実物をこのサーバーに向ける | `controller/`・`client/`・`aop/`・`provisioning/`・`integration/` |
| JDK 標準 `HttpServer` を直接起動したもの | サーバー側フェイク(既存テストの手書き) | **HTTP 通信**(受けた `Authorization` ヘッダーの記録) | `OutboundAuthHeadersTest`・`BridgeClientJobAuthTest`・`CmsProvisioningBridgeClient*Test` |
| `MockHttpServletRequest`・`MockMvc`(standalone)・`Jwt`・`JwtAuthenticationToken`(Spring Test / Spring Security) | Mockito ではないテスト用の実装 | **HTTP リクエストの入口**(`Authorization`・`X-Forwarded-For`・`User-Agent` ヘッダー、JWT の `sub`・`realm_access`) | `controller/`・`aop/`・`client/`・`config/` |
| `AspectJProxyFactory`(Spring の実際の AOP プロキシ) | `AuditLogAspect` を本番と同じ Spring AOP の仕組みで対象のメソッドへ適用する(`ProceedingJoinPoint`・`MethodSignature` を Mockito で模さない) | フレームワークの入口(`@Around`) | `AuditLogAspectTest` |
| `AsyncProxies`(`testsupport`)+ 呼び出しをためる `Executor` | Spring の実際の AOP プロキシ(`AsyncAnnotationAdvisor`)で、自前のランナーの `@Async` を効かせる。実行は `ProjectApp.drainJobs()` で呼び出し順に行う | フレームワークの入口(`@Async`) | `ProjectControllerTest`・`SiteControllerTest`・`TagDesign*ControllerTest`・`SiteStaticContentControllerTest`・`LetsblogSyncInternalControllerTest` |
| ホストの `ssh-keygen` | 本物のコマンドを実行する(`SshKeyGenerationServiceTest`) | 外部プロセス(モック不可、openssh-client が必要) | `SshKeyGenerationServiceTest`・`SiteControllerTest` |

### 着手時にモックの境界の外だった型

着手時(`39cba09e`)の本ファイルの対象のテストでは、次の型を Mockito でモックしていた(()内は出現数。`@Mock` / `@MockitoBean` の
次の行の型と、`mock(X.class)` の型を数えた)。DB・MQ・Keycloak の 5 型(`SiteRepository` 6・`SshKeyPairRepository` 1・
`ProjectRepository` 1・`RabbitTemplate` 2・`JwtDecoder` 1)を除くものは境界の外で、すべて実物へ書き直した。
完了時にこれらをモックしているテストは 0 件(上の確認コマンドの出力に、これらの型は現れない)。

- 自前のサービス: `AdminAuthorizationService`(14)・`LetsblogSyncService`(4)・`TagDesignSettingService`(3)・`ProjectService`(3)・
  `TextGenerationJobStarter`(3)・`TagDesignGenerationService`(2)・`SnsXService`(2)・`SiteService`(2)・`PvRuleService`(2)・
  `CurrentActorService`(2)・`ProjectEnvironmentSyncJobStarter`(1)・`ManagedSiteProvisioningJobStarter`(1)・
  `WordPressSiteProvisioningService`(1)・`StaticContentGenerationService`(1)・`SnsThreadsService`(1)・`SnsTemplateService`(1)・
  `SnsLinkedinService`(1)・`SnsHatenaService`(1)・`SnsFacebookService`(1)・`ProjectEnvironmentSyncService`(1)・
  `AuditLogService`(1)・`SshKeyGenerationService`(1)
- 自前のクライアント(通信を包むもの): `IdentityClient`(1)・`IdentityBridgeClient`(1)
- 自前のクラスを包む通信ラッパー・暗号: `ServiceTokenClient`(4。`packages/lbs-common`。実物を、トークンのエンドポイントを
  フェイクHTTPサーバーにして使う)・`CredentialCipher`(1。実物)
- フレームワークの入口: `HttpServletRequest`(3)→ `MockHttpServletRequest`、`ProceedingJoinPoint`(1)・`MethodSignature`(1)→ Spring の AOP プロキシ
- 境界の中(これは完了時も `mock()` のまま): `SiteRepository`・`SshKeyPairRepository`・`ProjectRepository`・`RabbitTemplate`・`JwtDecoder`
  (完了時は、コントローラのテストが使うリポジトリが 6 種に増えた。コントローラが依存する自前のサービスを実物にしたため、
  そのサービスの先の DB がモックの対象になった)

### 書き直しで外した・置き換えたテスト

外したテストは無い。書き直しは、モックで返させていた値を、外部(HTTP のフェイク・DB のリポジトリ)の応答や状態として作り直したもので、
アサーションは減らしていない(モックの呼び出しの検証 `verify(...)` / `verifyNoInteractions(...)` は、外部に実際に届いたリクエスト・
リポジトリへの保存・権限エラーで外部へ何も届かないことの検証へ置き換えた)。テストを無効化・skip したものは無い
(`@Disabled`・`@Ignore`・`assumeTrue` なども追加していない)。

書き直しで見つかった、モックでは見えなかった点(いずれもテスト側を実物の振る舞いに合わせた。プロダクションは不変):

- `TagDesign*ControllerTest`: モックでは任意の文字列だったプリセット ID が、実物の `TagDesignSettingService` では
  `DesignPreset.fromId` で検証されるため、有効な `"dark"` を使う。
- `ProjectControllerTest`: 実物の `ProjectService.listProjects` は並べ替えのため可変リストと `createdAt` を要求する。

## テストから到達できない分岐

完了時に残る 5 分岐。プロダクションコードを変えずには、その分岐に入る入力や状態を作れない理由を具体的に書く。

| # | クラス | メソッド | 行 | 未カバーの分岐 | 到達できない理由 |
| ---: | --- | --- | ---: | --- | --- |
| 1 | `HatenaApiClient` | `formBody` | 153 | `out.length() > 0` が真(2 個目以降の項目) | `formBody` の呼び出し元は `form(...)` だけで、渡す `formParams` は `fetchRequestToken` の `Map.of("scope", SCOPE)`(1 項目)と `fetchAccessToken` の `Map.of()`(0 項目)の 2 通りしか無い。いずれも `private` で、外から別の項目数を渡す経路が無い。1 項目目で偽(カバー済み)、0 項目でループに入らない側はカバー済みだが、2 項目以上の本文を作る入力が存在しない |
| 2 | `HatenaApiClient` | `signature` | 208 | ソートの比較で `a[0].equals(b[0])`(エンコード後の名前が同じ)が真 | 比較の対象 `pairs` は `Map<String, String> params` の各エントリから作る。`Map` のキーは重複しないうえ、パーセントエンコードは単射(異なる名前は異なるエンコード結果になる)なので、エンコード後の名前が等しい 2 項目は作れない。`signature` はパッケージ限定の `static` で、`params` の型が `Map` である以上、同名の 2 項目を渡す入力が無い |
| 3 | `SshKeyGenerationService` | `generateEd25519` | 41 | `!finished`(`ssh-keygen` が 10 秒で終わらない)が真 | `ssh-keygen -t ed25519 -N "" -C <comment> -f <新規の一時ディレクトリ配下>` は入力に関わらず数十ミリ秒で終わる(コメントは 100 KB でも 130 KB でも成功することを実測)。外部プロセスを遅らせる手段(PATH の差し替え・タイムアウト値の注入)がコンストラクタにも引数にも無く、`TIMEOUT_SECONDS` は `private static final` |
| 4 | `SshKeyGenerationService` | `generateEd25519` | 45 | `process.exitValue() != 0` が真 | 鍵ファイルのパスは、メソッド自身が作る新規の一時ディレクトリ配下の固定名で、既存ファイルとの衝突も書き込み不可も起こらない。`-C` に渡せる値(NUL 以外の任意の文字列)ではどれも `ssh-keygen` は 0 で終了する(NUL を含むとプロセスの起動前に `IOException` になり、`ssh-keygen` の終了コードまで届かない。これはテスト済み)。失敗させるには `ssh-keygen` 自体を差し替えるしかない |
| 5 | `SshKeyGenerationService` | `generateEd25519` | 59 | `finally` の `dir != null` が偽(一時ディレクトリを作れなかった) | `Files.createTempDirectory` が失敗するのは一時領域が書き込めないときだけ。その場所(`java.io.tmpdir`)は JVM が最初の利用で固定して以後読み直さず、テストから差し替えると JVM 全体のテストに影響する。ほかに `dir` が `null` のまま `finally` に入る経路は無い |

なお、`ssh-keygen` の起動失敗(`IOException`)と割り込み(`InterruptedException`)の分岐は到達でき、テスト済み
(`SshKeyGenerationServiceTest`)。
