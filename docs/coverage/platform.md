# services/platform の分岐カバレッジ(#1825)

Epic #1711 の子 Issue #1825 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。このときに
プロダクションコードを変更してはならない」を、`services/platform` の `com.letsblog.platform` 全体に適用した結果。
プロダクションコードは変更していない(この Issue の差分は `services/platform/src/test/**`・
`services/platform/build.gradle`(テスト実行時の環境変数だけ)・本ファイルだけ。`.claude/hooks/paths.py` の
`is_production()` が True になるパスは 0 件)。

このファイルは次の 3 節で構成する。

1. 計測値(着手時・完了時)
2. モックの境界
3. テストから到達できない分岐

受け入れ試験(Gherkin)は書いていない。この Issue の受け入れ基準は製品の振る舞いではなくテスト資産の状態(計測値・モックの型・
未カバー分岐の一覧)で、web UI から到達できない。対象のコードも、設定サービス・状態確認・バックアップの外部プロセス呼び出し・
VSCode 拡張のビルドなどの内部の分岐で、web UI から外部(identity-service・docker-socket-proxy・RabbitMQ 管理 API・
`mysqldump` ほか)の応答を作り分けて到達することはできない(バックアップの復元は受入テスト環境の全 DB を壊すので、
そもそも受入テストの到達手段にできない)。サービスレベルのテスト(JUnit)で表した(CLAUDE.md → Test-First Implementation →
Where the tests live が認める、web UI から到達できない場合)。

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(CLAUDE.md → Coverage のとおり、`&&` / `||` もバイトコード上は別の分岐なので、
C1 と C2 を兼ねる)。値は `jacocoTestReport.xml` の各 `<package>` 直下の `<counter type="BRANCH">` から取る。

計測コマンド(着手時・完了時とも同じ):

```bash
./gradlew :services:platform:cleanTest :services:platform:test :services:platform:jacocoTestReport
```

計測条件: ホストの MySQL(`127.0.0.1:3306`、ADR-0006・#1769)が起動した状態で実行した(`@SpringBootTest` の統合テストと
Flyway のマイグレーションのテストが使う)。着手時・完了時とも、`services/platform` 全体で失敗 0、エラー 0、skipped 0
(`build/test-results/test/*.xml` の `tests` / `failures` / `errors` / `skipped` の合計)。

### 着手時

| 項目 | 値 |
| --- | --- |
| コミット | `fd71bc4a`(develop 起点、ブランチ `test/1825-platform-coverage` の作成直後。変更なし) |
| コマンド | 上のとおり |
| 全体のテスト | 563 件、失敗 0、skipped 0 |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/platform/service` | 487 | 55 | 89.9% |
| `com/letsblog/platform/keycloak` | 6 | 12 | 33.3% |
| `com/letsblog/platform/controller` | 16 | 4 | 80.0% |
| `com/letsblog/platform/cli` | 9 | 3 | 75.0% |
| `com/letsblog/platform/domain` | 7 | 1 | 87.5% |
| `com/letsblog/platform/ai` | 3 | 1 | 75.0% |
| `com/letsblog/platform/aop` | 19 | 1 | 95.0% |
| `com/letsblog/platform/config` | 15 | 1 | 93.8% |
| `com/letsblog/platform/render` | 7 | 1 | 87.5% |
| `com/letsblog/platform/client` | 4 | 0 | 100.0% |
| **合計** | **573** | **79** | **87.9%** |

`com/letsblog/platform/dto`・`repository`・ルートのクラスは、分岐を持つクラスが無く(record・インタフェース・分岐の無いクラスだけ)、
`BRANCH` カウンタ自体が出ない(分母 0。着手時も完了時も同じ)。

着手時の未カバーのクラス別内訳(missed の数。合計 79): `KeycloakAdminClient` 12・`BackupService` 12・`ContainerStatusService` 8・
`VscodeExtensionBuildService` 7・`CurrentActorService` 7・`ConnectedServiceStatusService` 3・`SystemSettingService` 4・
`ConnectionDefaultsSeeder` 4・`InternalPlatformSettingsController` 4・`LetsBlogServiceStatusService` 3・
`ExtensionsConnectionImportService` 3・`AdminPasswordResetRunner` 3・`RabbitMqQueueStatusService` 2・
`AdminAuthorizationService` 1・`ProjectPluginRegistrationService` 1・`SystemSetting` 1・`AiProvider` 1・`AuditLogAspect` 1・
`KeycloakRealmRoleConverter` 1・`PlantUmlEncoder` 1(自前のサービスをモックしたため、モックの先の分岐が動いていなかったものを含む)。

着手時のテスト: 62 ファイル(`src/test/java` の `.java`)、`@Test` / `@ParameterizedTest` 496 件(`git grep` で数えた。Issue 本文の 47 / 417 は
起票時の `bc9f9082` での値)。Mockito でモック(`@Mock` / `@MockitoBean` / `mock()` / `spy()`)を
作っていたファイルは 38(モックの型は次の「着手時にモックの境界の外だった型」に列挙)。

### 完了時

| 項目 | 値 |
| --- | --- |
| コミット | `fa215a2c`(テストの最終コミット。この後のコミットは本ファイルの追加だけで、テストとプロダクションコードは変わらない) |
| コマンド | 上のとおり |
| 全体のテスト | 658 件、失敗 0、skipped 0 |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/platform/service` | 529 | 13 | 97.6% |
| `com/letsblog/platform/keycloak` | 18 | 0 | 100.0% |
| `com/letsblog/platform/controller` | 18 | 2 | 90.0% |
| `com/letsblog/platform/cli` | 12 | 0 | 100.0% |
| `com/letsblog/platform/domain` | 8 | 0 | 100.0% |
| `com/letsblog/platform/ai` | 4 | 0 | 100.0% |
| `com/letsblog/platform/aop` | 20 | 0 | 100.0% |
| `com/letsblog/platform/config` | 16 | 0 | 100.0% |
| `com/letsblog/platform/render` | 8 | 0 | 100.0% |
| `com/letsblog/platform/client` | 4 | 0 | 100.0% |
| **合計** | **637** | **15** | **97.7%** |

分母(covered + missed)は着手時 652、完了時 652 で変わらない(プロダクションコードを変えていない)。

残る 15 は、すべて下の「テストから到達できない分岐」に載せた(クラス別: `ConnectedServiceStatusService` 5、
`ConnectionDefaultsSeeder` 4、`InternalPlatformSettingsController` 2、`VscodeExtensionBuildService` 2、
`BackupService` 1、`ExtensionsConnectionImportService` 1)。

完了時のテストは 77 ファイル(`src/test/java` の `.java`。`testsupport/` と `PenpotCheckerFactory` 等の補助を含む)、
`@Test` / `@ParameterizedTest` 591 件(パラメータ化の展開後の実行数が上の 658 件)。新規・書き直しの要点:

- 追加した土台(`src/test/java/com/letsblog/platform/testsupport/` ほか): `FakeHttpServer`(サーバー側の HTTP フェイク)、
  `PlatformWorld`(identity-service のフェイクの上に `CurrentActorService`・`IdentityBridgeClient`・`AdminAuthorizationService`・
  `AuditLogService` を実物で組む)、`StatusFixture`(設定サービス・gateway 状況・RabbitMQ 管理 API・Penpot/GitLab チェッカーを実物で組む)、
  `InMemoryRepositories`(Spring Data のリポジトリのメモリ上の差し替え)、`SharedIdentityFake`(統合テスト共有の identity-service
  フェイク)、`FakeBin`・`KeycloakFakes`、`service/PluginFixture`・`StatusServices`・`PenpotCheckerFactory`(パッケージ限定の
  テスト用コンストラクタを使うための入口)。
- 外部プロセスの代役: `src/test/fake-bin/`(`mysqldump` / `mysql` / `pg_dump` / `pg_restore` / `npm` / `npx`。実体は
  `fake-external-command` 1 本へのシンボリックリンク)。`services/platform/build.gradle` の `test` タスクが `PATH` の先頭に置く。
  `BackupService` と `VscodeExtensionBuildService` は実物(`ProcessBuilder` での起動を含む)のまま、本物の DB や npm ではなく
  この代役と話す。
- 書き直し: 自前のサービスをモックしていたテストの全部(下の表)。`BackupServiceTest`(`spy(service)` をやめ、代役の実行ファイルへ)、
  `VscodeExtensionBuildService*Test` 4 本(サービスを継承して `build` を差し替えるのをやめ、代役の `npm`/`npx` へ)、
  `AuditLogAspectTest`(`ProceedingJoinPoint`・`MethodSignature` のモックをやめ、Spring の AOP プロキシへ)、`controller/` の 8 本
  (スタンドアロン `MockMvc` で本物のコントローラ・サービスを通す)、`integration/` の 7 本(`@MockitoBean` の
  `IdentityClient` / `IdentityBridgeClient` / `BackupService` をやめる)。
- 追加: `ai/AiProviderTest`・`config/KeycloakRealmRoleConverterTest`・`render/PlantUmlEncoderTest`・
  `integration/SystemSettingPersistenceIntegrationTest`、`KeycloakAdminClientTest` への `setPassword` / `findUserIdByEmail` 系、
  `RabbitMqQueueStatusServiceTest` / `LetsBlogServiceStatusServiceTest` / `ContainerStatusServiceTest` /
  `ConnectedServiceStatusServiceTest` への失敗系・境界値。

### 突き合わせの手順(受け入れ基準 3 の再現)

`jacocoTestReport.xml` から、services/platform に残る未カバー分岐を行ごとに取り出す。

```bash
python3 -I - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('services/platform/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
for pkg in root.findall('package'):
    for sf in pkg.findall('sourcefile'):
        for line in sf.findall('line'):
            if int(line.get('mb')) > 0:
                print(pkg.get('name'), sf.get('name'), line.get('nr'), 'missed', line.get('mb'), 'covered', line.get('cb'))
PY
```

完了時(`fa215a2c`)の出力は次の 13 行で、`missed` の合計 15 が下の表の分岐数(15 件)と対応する(表は 13 行で、`ConnectionDefaultsSeeder` の 2 行が各 2 分岐)。

```text
com/letsblog/platform/service BackupService.java 423 missed 1 covered 1
com/letsblog/platform/service ConnectedServiceStatusService.java 284 missed 1 covered 1
com/letsblog/platform/service ConnectedServiceStatusService.java 401 missed 1 covered 3
com/letsblog/platform/service ConnectedServiceStatusService.java 416 missed 1 covered 3
com/letsblog/platform/service ConnectedServiceStatusService.java 429 missed 1 covered 3
com/letsblog/platform/service ConnectedServiceStatusService.java 458 missed 1 covered 3
com/letsblog/platform/service ConnectionDefaultsSeeder.java 50 missed 2 covered 0
com/letsblog/platform/service ConnectionDefaultsSeeder.java 52 missed 2 covered 0
com/letsblog/platform/service ExtensionsConnectionImportService.java 196 missed 1 covered 3
com/letsblog/platform/service VscodeExtensionBuildService.java 331 missed 1 covered 1
com/letsblog/platform/service VscodeExtensionBuildService.java 341 missed 1 covered 1
com/letsblog/platform/controller InternalPlatformSettingsController.java 106 missed 1 covered 3
com/letsblog/platform/controller InternalPlatformSettingsController.java 134 missed 1 covered 3
```

(行の並びは XML の順で、毎回同じとは限らない。突き合わせるのは「ファイル名・行」の集合で、
`missed` の合計が 1+1+1+1+1+1+2+2+1+1+1+1+1 = 15。)

## モックの境界

services/platform のテスト(`services/platform/src/test/java/` 全体)で、Mockito のモック(`@Mock` / `@MockitoBean` /
`@MockitoSpyBean` / `@Spy` / `mock()` / `spy()`)にしている型は次の **7 型だけ**。確認コマンドと完了時(`fa215a2c`)の出力:

```bash
grep -rnE -A1 "@Mock\b|@MockitoBean|@MockitoSpyBean|@Spy\b|[^a-zA-Z.]mock\(|[^a-zA-Z]spy\(|Mockito\.(mock|spy)\(" \
  services/platform/src/test/java | grep -v "import " | grep -v "^--"
```

```text
.../service/*Test.java        @Mock (次の行: private SystemSettingRepository repository;)        AppSetting* 4 本・ConnectionDefaultsSeederTest
.../service/PluginRegistrationServiceTest.java / ProjectPluginRegistrationServiceTest.java   @Mock (PluginRegistrationRepository / ProjectPluginRegistrationRepository)
.../service/ConnectedServiceStatusService*Test.java   @Mock DataSource / @Mock Connection      Mockito.mock(DataSource.class) / Mockito.mock(Connection.class)(ほか 3 本)
.../integration/*IntegrationTest.java                 @MockitoBean (次の行: private JwtDecoder jwtDecoder;)
.../testsupport/InMemoryRepositories.java             Mockito.mock(SystemSettingRepository / PluginRegistrationRepository / ProjectPluginRegistrationRepository)
.../testsupport/PlatformWorld.java                    Mockito.mock(RabbitTemplate.class)
```

| 型 | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `SystemSettingRepository`(`com.letsblog.platform.repository`) | Mockito `mock()`(`InMemoryRepositories` がメモリ上の保存を再現する)、`@Mock` | **DB アクセス**(Spring Data のリポジトリ。自前のコードを持たないインタフェースで、`system_settings` テーブルの読み書きそのもの) | `AppSetting*Test`・`SystemSettingServiceTest`・`ConnectionDefaultsSeederTest`、`StatusFixture` を使う状態確認・コントローラのテスト |
| `PluginRegistrationRepository` | Mockito `mock()`(`InMemoryRepositories`)、`@Mock` | **DB アクセス**(`plugin_registrations` テーブル) | `PluginRegistrationServiceTest`、`PluginFixture` を使うコントローラのテスト |
| `ProjectPluginRegistrationRepository` | Mockito `mock()`(`InMemoryRepositories`)、`@Mock` | **DB アクセス**(`project_plugin_registrations` テーブル) | `ProjectPluginRegistrationServiceTest`・`PluginRegistrationServiceTest`、`PluginFixture` を使うコントローラのテスト |
| `DataSource` | Mockito `mock()` / `@Mock` | **DB 接続**(`ConnectedServiceStatusService` が疎通確認に使う JDBC の接続) | `ConnectedServiceStatusService*Test`・`*BroadcasterTest`・`DashboardControllerTest` |
| `Connection`(`java.sql`) | Mockito `mock()` / `@Mock` | **DB 接続**(`isValid` / `close`) | 同上 |
| `RabbitTemplate`(`org.springframework.amqp.rabbit.core`) | Mockito `mock()`(`PlatformWorld` が持ち、実物の `AuditLogService` へ渡す) | **MQ との送受信**(監査ログ exchange への `convertAndSend`) | `PlatformWorld` を使うテスト(`BackupServiceTest`・`AuditLogAspectTest`・サービス/コントローラのテスト) |
| `JwtDecoder`(`org.springframework.security.oauth2.jwt`) | `@MockitoBean` | **Keycloak の鍵取得を伴う JWT の検証**(JWK Set の HTTP 取得) | `integration/` の `@SpringBootTest` 全部 |

Mockito 以外で外部との通信(または Spring のフレームワークの入口)を差し替えているものも、同じ表に載せる。いずれも
Mockito のモックではなく、自前のクラスの代わりでもない。

| 型・仕掛け | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `FakeHttpServer`(`testsupport`。JDK 標準 `com.sun.net.httpserver.HttpServer` をループバックで起動) | サーバー側フェイク。メソッド+パスごとに応答を登録し、受けた要求を記録する | **HTTP 通信**。identity-service(`IdentityClient`・`IdentityBridgeClient`)、Keycloak(トークンと Admin API。`KeycloakAdminClient`)、gateway(`LetsBlogServiceStatusService`)、RabbitMQ 管理 API(`RabbitMqQueueStatusService`)、Penpot/GitLab/extensions のポータル、docker-socket-proxy。自前の `*Client` / `*Service` は実物をこのサーバーに向ける | `PlatformWorld`・`StatusFixture` を使うテスト、`*ControllerTest`、`RabbitMqQueueStatusServiceTest`、`ContainerStatusBroadcasterTest` |
| `SharedIdentityFake`(`testsupport`。`FakeHttpServer` を 1 台、全統合テストで共有) | サーバー側フェイク。`@DynamicPropertySource` で `app.identity-service-uri` を向ける | **HTTP 通信**(identity-service の `/api/identity/me` とプロジェクトメンバー判定) | `integration/` の `@SpringBootTest` |
| `MockRestServiceServer`(Spring Test) | HTTP クライアントの下層を差し替えるサーバー側の模擬(Mockito ではない) | **HTTP 通信**(ComfyUI・PlantUML・WordPress provision-agent・Ollama・docker-socket-proxy・Keycloak) | `ConnectedServiceStatusService*Test`・`ContainerStatusServiceTest`・`LetsBlogServiceStatusServiceTest`・`KeycloakAdminClientTest` |
| JDK 標準 `HttpServer` を直接起動したもの | サーバー側フェイク(既存テストの手書き) | **HTTP 通信**(extensions のポータル、Penpot の到達性、identity-service のメンバー判定) | `ExtensionsConnectionImportService*Test`・`PenpotReachabilityCheckerTest`・`GitLabReachabilityCheckerTest`・`IdentityBridgeClientTest` |
| `src/test/fake-bin/` の `mysqldump` / `mysql` / `pg_dump` / `pg_restore`(`FakeBin` が振る舞いを決め、呼び出しを記録する) | `PATH` の先頭に置いた代役の実行ファイル(`build.gradle` の `test` タスクの環境変数)。**`BackupService#runProcess` は実物のまま** `ProcessBuilder` で起動する | 外部プロセス(DB のダンプ/リストアのコマンド)。Issue の Q2 の既定案の「プロセスを起動する最下層」を、クラスのモックではなく実行ファイルの代役で表した | `BackupServiceTest`、`MultipartSizeLimitIntegrationTest`・`MultipartOversizeIntegrationTest` |
| `src/test/fake-bin/` の `npm` / `npx` | 同上(`npm ci` / `npm run compile` は何もせず、`npx vsce package --out <path>` は成果物のファイルを書く。作業ディレクトリの内容を記録する) | 外部プロセス(VSCode 拡張のビルド) | `VscodeExtensionBuildService*Test`・`VscodeExtensionControllerTest` |
| `MockHttpServletRequest`・`MockMvc`(standalone)・`Jwt`・`JwtAuthenticationToken`(Spring Test / Spring Security) | Mockito ではないテスト用の実装 | **HTTP リクエストの入口**(`Authorization`・`X-Forwarded-For`・`User-Agent` ヘッダー、JWT の `sub`・`realm_access`) | `PlatformWorld` を使うテスト、`controller/` |
| `AspectJProxyFactory`(Spring の実際の AOP プロキシ) | `AuditLogAspect` を本番と同じ Spring AOP の仕組みで対象のメソッドへ適用する(`ProceedingJoinPoint`・`MethodSignature` を Mockito で模さない) | フレームワークの入口(`@Around`) | `AuditLogAspectTest` |
| `GenericApplicationContext`(Spring) | Mockito ではない実物 | フレームワークの入口(`ConfigurableApplicationContext`) | `AdminPasswordResetRunnerTest` |
| JDK 標準の `InputStream` の匿名サブクラス | 読み込みが `IOException` になる入力 | アップロードされたアーカイブの読み込み(ネットワーク由来のストリーム) | `BackupServiceTest` |
| 実 MySQL(`lbs_platform_test`、ADR-0006) | 差し替えず実物を使う | DB アクセス | `integration/` の `@SpringBootTest`・Flyway のマイグレーションのテスト |

### 着手時にモックの境界の外だった型

着手時(`fd71bc4a`)の services/platform のテストでは、次の型を Mockito でモックしていた(()内は出現数。`@Mock` / `@MockitoBean` の
次の行の型と、`mock(X.class)` / `spy(x)` の対象を、上の確認コマンドと同じ grep(`git grep ... fd71bc4a`)で数えた。38 ファイル)。
DB・Keycloak の 6 型(`SystemSettingRepository` 6・`PluginRegistrationRepository` 1・`ProjectPluginRegistrationRepository` 2・
`DataSource` 5・`Connection` 5・`JwtDecoder` 8)を除くものは境界の外で、すべて実物へ書き直した。
完了時にこれらをモックしているテストは 0 件(上の確認コマンドの出力に、これらの型は現れない)。

- 自前のサービス: `AdminAuthorizationService`(11)・`AppSettingService`(9)・`SystemSettingService`(7)・
  `LetsBlogServiceStatusService`(5)・`RabbitMqQueueStatusService`(5)・`CurrentActorService`(3)・`AuditLogService`(2)・
  `ConnectedServiceStatusService`(2)・`ContainerStatusService`(2)・`PluginRegistrationService`(2)・`PenpotReachabilityChecker`(2)・
  `GitLabReachabilityChecker`(2)・`BackupService`(`@MockitoBean` 2 と、`spy(service)` 4)・`ConnectedServiceStatusBroadcaster`(1)・
  `ContainerStatusBroadcaster`(1)・`ExtensionsConnectionImportService`(1)・`ProjectPluginRegistrationService`(1)・
  `VscodeExtensionBuildService`(1)
- 自前のクライアント(通信を包むもの): `IdentityClient`(7。`packages/lbs-common`)・`IdentityBridgeClient`(2)・
  `KeycloakAdminClient`(2)。いずれも実物を、HTTP のサーバー側フェイクに向けて使う
- フレームワークの入口: `HttpServletRequest`(1)→ `MockHttpServletRequest`、`ProceedingJoinPoint`(1)・`MethodSignature`(1)→ Spring の
  AOP プロキシ、`ConfigurableApplicationContext`(1)→ `GenericApplicationContext`
- 自前のクラスの一部を本物の代わりにする継承(Mockito ではないが、境界の外): `RabbitMqQueueStatusServiceTest` の
  `fetchQueues` を上書きしたサブクラス、`VscodeExtensionBuildService*Test` の `build` を上書きしたサブクラス 3 つ。HTTP/外部プロセスを
  代役にして実物を通す形に書き直した
- 境界の中(これは完了時も `mock()` のまま): `*Repository` 3 種・`DataSource`・`Connection`・`JwtDecoder`
  (完了時は、サービスを実物にしたため、そのサービスの先の DB がモックの対象になった。`RabbitTemplate` は、`AuditLogService` を
  実物にしたことで、MQ への送信として新たにモックの対象に加わった)

### 書き直しで外した・置き換えたテスト

外したテストは無い。書き直しは、モックで返させていた値を、外部(HTTP のフェイク・DB のリポジトリ・外部プロセスの代役)の
応答や状態として作り直したもので、アサーションは減らしていない(モックの呼び出しの検証 `verify(...)` / `verifyNoInteractions(...)` は、
外部に実際に届いたリクエスト・リポジトリへの保存・権限エラーで外部へ何も届かないことの検証へ置き換えた)。次の 2 点は、モックの
先が動いていなかったために意味を持てなかったテストの置き換えで、検証内容をより強くしている:

- `BackupServiceTest` の「Backup Archive Structure」「Backup Verification」: テスト自身が組み立てた ZIP の中身を調べるだけで
  `BackupService` を呼んでいなかったため、`createBackup()` の実際の出力(代役のダンプ付き)を調べる形にした。
- `ConnectedServiceStatusServiceComfyUiUrlTest` の「接続先が `null`」: 実物の `AppSettingService` は `null` を返さない
  (環境変数の既定が無い項目は空文字)ので、`null` のケースは削除した(空文字・空白のケースは残している)。
  この `null` の側は「テストから到達できない分岐」の 401 / 416 / 429 / 106 / 134 に載せた。

テストを無効化・skip したものは無い(`@Disabled`・`@Ignore`・`test.skip` 等は追加していない。`Assumptions` を使うテストも無い)。

書き直しで見つかった、モックでは見えなかった点(いずれもテスト側を実物の振る舞いに合わせた。プロダクションは不変):

- `InMemoryRepositories` のモックは `lenient`: `MockitoExtension` の厳格なスタブ検査は、テストごとに使わないメソッドもスタブする
  メモリ上のリポジトリには合わないため。
- 統合テスト: `IdentityClient` の `@MockitoBean` をやめたことでコンテキストの数が増え、MySQL の `Too many connections` に
  なった。`@DynamicPropertySource` を共通の親クラス(`IdentityFakeIntegrationTestBase`)に 1 つだけ置いて、Spring のコンテキストの
  キャッシュを共有させた。
- 実物の `AppSettingService` では Ollama / ComfyUI / Penpot / GitLab の接続先に環境変数の既定が無いため、「環境変数由来の
  `ENVIRONMENT`」を返す状態は作れない(`ollamaBaseUrlSource` を `ENVIRONMENT` にするテストは `DATABASE` に置き換えた)。

## テストから到達できない分岐

完了時に残る 15 分岐。プロダクションコードを変えずには、その分岐に入る入力や状態を作れない理由を具体的に書く。

| # | クラス | メソッド | 行 | 未カバーの分岐 | 到達できない理由 |
| ---: | --- | --- | ---: | --- | --- |
| 1 | `VscodeExtensionBuildService` | `runCommand` | 331 | `!finished`(`npm`/`npx` が 300 秒で終わらない)が真 | 待ち時間は `private static final int TIMEOUT_SECONDS = 300` で、コンストラクタにも引数にも注入点が無い。通すには代役の `npx` を 300 秒以上眠らせるテストが要り(1 件ごとに 5 分超)、`waitFor` のタイムアウトを短くする手段がない |
| 2 | `VscodeExtensionBuildService` | `runCommand` | 341 | `catch (IOException \| InterruptedException e)` の `e instanceof InterruptedException` が**偽**(`IOException`: プロセスの起動に失敗) | 起動失敗は `npm`/`npx` が `PATH` のどこにも無い(または実行できない)ときだけ。テストは `PATH` の先頭に代役を置いた JVM で動き、`ProcessBuilder` は親プロセスの `PATH` を引くので、テストの途中で `PATH` を変えられない。実行権限を外した代役を置いても、`execvpe` は次の `PATH` 要素の本物の `npm` を探しに行き、実環境の `npm` が走ってしまう(実際に試して確認し、危険なので採用しなかった)。割り込み側(真)はテスト済み |
| 3 | `BackupService` | `runProcess` | 423 | `!finished`(外部プロセスが 300 秒で終わらない)が真 | 待ち時間は `private static final int TIMEOUT_SECONDS = 300`(注入点なし)。1 と同じ理由で、通すには 5 分以上かかるテストになる。起動失敗(`IOException`)と割り込みの分岐はテスト済み |
| 4 | `ConnectedServiceStatusService` | `checkDatabase` | 284 | `try (Connection connection = dataSource.getConnection())` の暗黙の `close` 呼び出しの `connection != null` が**偽** | `DataSource#getConnection()` は `null` を返さない契約(`null` を返す `DataSource` を作ると `connection.isValid` で `NullPointerException` になり、`checkDatabase` の `catch (SQLException)` にも届かず、ダッシュボード全体が失敗する。これは仕様として守るべき振る舞いではない)。コンパイラが生成する `try-with-resources` の `null` 検査で、ソース上の条件ではない |
| 5 | `ConnectedServiceStatusService` | `checkComfyUi` | 401 | `baseUrl == null`(`baseUrl == null \|\| baseUrl.isBlank()` の左辺が真) | `baseUrl` は `AppSettingService#getComfyUiBaseUrl()` の戻り値で、`resolve(key)` は DB の値が無ければ `envDefaults.getOrDefault(key, "")`(ComfyUI の接続先には環境変数の既定が無く空文字)を返し、`null` を返す経路が無い。空文字・空白だけの値(右辺が真)はテスト済み |
| 6 | `ConnectedServiceStatusService` | `checkPenpot` | 416 | `baseUrl == null` が真 | 5 と同じ(`getPenpotBaseUrl()` は `resolve` で、`null` を返さない) |
| 7 | `ConnectedServiceStatusService` | `checkGitLab` | 429 | `baseUrl == null` が真 | 5 と同じ(`getGitLabBaseUrl()` は `resolve` で、`null` を返さない) |
| 8 | `ConnectedServiceStatusService` | `renderPlantUml` | 458 | `png != null && png.length > 0` の `png.length > 0` が偽(`png` は非 `null` で長さ 0) | `RestClient#toEntity(byte[].class)` は本文が空の応答を `null` として返し、長さ 0 の `byte[]` を返さない(Spring の `IntrospectingClientHttpResponse` が空の本文を検出して `null` にする)。`withSuccess(new byte[0], IMAGE_PNG)` でも `null` になることをテストで確認した。`png == null` 側(「PNGデータが空です」の警告)はテスト済み |
| 9 | `ConnectionDefaultsSeeder` | `seedIfAbsent` | 50 | `.filter(value -> !value.isBlank())` の真偽(2 分岐とも) | `seedIfAbsent` は `private` で、呼び出し元は `run` の `defaults.forEach(this::seedIfAbsent)` だけ。`defaults` は `run` の中で作る空の `LinkedHashMap` で、既定値を持つ接続先は現在無い(Javadoc のとおり。Ollama は #1745、ComfyUI は #1746 で既定値を撤去)ため、`seedIfAbsent` は一度も呼ばれない。引数で `defaults` を渡す経路も無い |
| 10 | `ConnectionDefaultsSeeder` | `seedIfAbsent` | 52 | `if (!present)` の真偽(2 分岐とも) | 9 と同じ(メソッドに入れない) |
| 11 | `ExtensionsConnectionImportService` | `readConnections` | 196 | `root == null`(`root == null \|\| !root.isArray()` の左辺が真) | `root` は `JSON.readTree(response.body())` の戻り値。`response.body()` は `HttpResponse.BodyHandlers.ofString()` なので `null` ではなく、Jackson の `readTree(String)` は空文字に対しても `MissingNode` を返し `null` を返さない(空の本文が「配列ではありません」になることをテストで確認した)。`!root.isArray()` 側は真偽ともテスト済み |
| 12 | `InternalPlatformSettingsController` | `gitLabConfig` | 106 | `baseUrl != null` が偽(`baseUrl == null`) | `baseUrl` は `AppSettingService#getGitLabBaseUrl()` で、`null` を返さない(5 と同じ)。空文字(`!isBlank()` が偽)はテスト済み |
| 13 | `InternalPlatformSettingsController` | `urlConfig` | 134 | `baseUrl != null` が偽(`baseUrl == null`) | `baseUrl` は `getLlmOllamaBaseUrl()` / `getComfyUiBaseUrl()` で、`null` を返さない(5 と同じ)。空文字はテスト済み |

(表は 13 行、未カバーの分岐は 15。#9 と #10 がそれぞれ 2 分岐を持つため。`missed` の合計 = 1+1+1+1+1+1+1+1+2+2+1+1+1 = 15 で、上の
突き合わせの出力の `missed` の合計と一致する。)

なお、`VscodeExtensionBuildService` の `build` が実行時例外(`VscodeExtensionBuildException` 以外)で失敗した場合の合流者の
ラップ(`awaitJoinedBuild`)は到達でき、テスト済み(進行中のビルドをリフレクションで組み立てて失敗させる。
`VscodeExtensionBuildServiceConcurrentAccessTest`)。
