# services/project `com.letsblog.project.service` の分岐カバレッジ(#1817)

Epic #1711 の子 Issue #1817 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`services/project` の
`com.letsblog.project.service` パッケージ(46 ファイル)に適用した結果。プロダクションコードは変更していない
(この Issue の差分は `services/project/src/test/**` と本ファイルだけ)。

このファイルは次の 3 節で構成する。

1. 計測値(着手時・完了時)
2. モックの境界
3. テストから到達できない分岐

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(CLAUDE.md → Coverage のとおり、`&&` / `||` もバイトコード上は別の分岐なので、
C1 と C2 を兼ねる)。値は `jacocoTestReport.xml` の `<package name="com/letsblog/project/service">` 直下の
`<counter type="BRANCH">` から取る。`services/project` のテスト全体を 1 回走らせた結果なので、`service` のクラスを
間接的に通す他パッケージ(`controller/` など)のテストも数に入る。`service` に下位パッケージは無い。

計測コマンド(着手時・完了時とも同じ):

```bash
./gradlew :services:project:cleanTest :services:project:test :services:project:jacocoTestReport
```

計測条件: ホストの MySQL(`127.0.0.1:3306`、ADR-0006・#1769)が起動した状態で実行した。完了時の `services/project` 全体は
985 件、失敗 0、エラー 0、skipped 0(`build/test-results/test/*.xml` の `tests` / `failures` / `errors` / `skipped` の合計)。

### 着手時

| 項目 | 値 |
| --- | --- |
| コミット | `57394420`(develop 起点、ブランチ `test/1817-project-service-coverage` の作成直後。変更なし) |
| コマンド | 上のとおり |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/project/service` | 491 | 112 | 81.4% |

着手時の未カバーのクラス別内訳(missed の数。合計 112):

| クラス | missed |
| --- | ---: |
| `SiteService` | 47 |
| `ProjectService` | 25 |
| `ProjectEnvironmentSyncService` | 12 |
| `AdminAuthorizationService` | 10 |
| `CurrentActorService` | 4 |
| `SiteService$SiteDataSource` | 4 |
| `TagDesignSettingService` | 2 |
| `ProvisioningService` | 2 |
| `SnsFacebookService` / `TagDesignGenerationService` / `StaticContentGenerationService` / `SnsTemplateService` / `WordPressSiteProvisioningService` / `SnsXService` | 各 1 |

着手時のテスト: `service/` 配下 31 ファイル、`@Test` / `@ParameterizedTest` 432 件。うち 28 ファイルが Mockito を使い、
自前のクラス(後述の「着手時にモックの境界の外だった型」)をモックしていた。

### 完了時

| 項目 | 値 |
| --- | --- |
| コミット | `0dfe08aa`(テストの最終コミット。この後のコミットは本ファイルの追加だけで、テストとプロダクションコードは変わらない) |
| コマンド | 上のとおり |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/project/service` | 597 | 6 | 99.0% |

分母(covered + missed)は着手時も完了時も 603 で変わらない(プロダクションコードを変えていない)。残る 6 は、すべて下の
「テストから到達できない分岐」に載せた(クラス別: `ProjectService` 2、`SiteService` 2、`TagDesignSettingService` 1、
`ProjectEnvironmentSyncService` 1)。

完了時の `service/` 配下は 34 ファイル(`CurrentActorServiceTest`・`AdminAuthorizationServiceTest`・
`SiteServiceCredentialsTest` を追加)、`@Test` / `@ParameterizedTest` 533 件。テストの共有部品は
`services/project/src/test/java/com/letsblog/project/testsupport/`(`FakeHttpServer`・`ProjectWorld`・`SnsFixture`・
`AsyncProxies`)に置いた。

### 突き合わせの手順(受け入れ基準 3 の再現)

`jacocoTestReport.xml` から、`service` の分岐数と、残る未カバー分岐を行ごとに取り出す。

```bash
python3 -I - <<'EOF'
import xml.etree.ElementTree as ET
root = ET.parse('services/project/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
for pkg in root.findall('package'):
    if pkg.get('name') != 'com/letsblog/project/service':
        continue
    for c in pkg.findall('counter'):
        if c.get('type') == 'BRANCH':
            print('BRANCH covered', c.get('covered'), 'missed', c.get('missed'))
    for sf in pkg.findall('sourcefile'):
        for line in sf.findall('line'):
            if int(line.get('mb')) > 0:
                print(sf.get('name'), line.get('nr'), 'missed', line.get('mb'), 'covered', line.get('cb'))
EOF
```

完了時(`0dfe08aa`)の出力は次のとおりで、`missed` の合計 6 が下の表の行数(6 行)と 1 対 1 に対応する。

```text
BRANCH covered 597 missed 6
ProjectService.java 131 missed 1 covered 3
ProjectService.java 149 missed 1 covered 3
TagDesignSettingService.java 69 missed 1 covered 3
SiteService.java 469 missed 1 covered 3
SiteService.java 487 missed 1 covered 3
ProjectEnvironmentSyncService.java 121 missed 1 covered 3
```

(`sourcefile` の並びは XML の順で、毎回同じとは限らない。突き合わせるのは「ファイル名・行」の集合。)

注意: JaCoCo は、ブロックの最後まで実行が進んだときにだけそのブロックをカバー済みにする。例外を投げて終わる呼び出しだけを
通したテストでは、その呼び出しの後ろの分岐が「未通過」と出る。分岐を埋めるときは、例外になる経路と、例外にならず戻る経路の
両方を通す。

## モックの境界

`com.letsblog.project.service` のテスト(`services/project/src/test/java/com/letsblog/project/service/` 配下と、
それらが共有する `.../testsupport/` 配下)で、Mockito のモック(`@Mock` / `@MockitoBean` / `@MockitoSpyBean` /
`mock()` / `spy()`)にしている型は次の **9 つだけ**。数え上げの確認コマンドと完了時(`0dfe08aa`)の出力:

```bash
cd services/project/src/test/java/com/letsblog/project
{
  for f in service/*.java testsupport/*.java; do
    awk '/@Mock[[:space:]]*$|@MockitoBean|@MockitoSpyBean|@Spy[[:space:]]*$/{getline n; print n}' "$f"
  done
  grep -hoE "(Mockito\.)?(mock|spy)\([A-Za-z]+\.class" service/*.java testsupport/*.java | sed -E 's/.*\(//; s/\.class//'
} | sed -E 's/^[[:space:]]*(private|public)?[[:space:]]*(final)?[[:space:]]*//; s/[[:space:]]+[a-zA-Z]+;?[[:space:]]*$//; s/ = .*//' \
  | sort | uniq -c | sort -rn
```

```text
     10 ProjectRepository
      3 TagDesignSettingRepository
      3 StaticContentRepository
      3 SshKeyPairRepository
      3 SiteRepository
      1 RabbitTemplate
      1 ProjectSnsTemplateRepository
      1 ProjectPvSyncStateRepository
      1 ProjectPvRuleRepository
```

(左の数は宣言の出現数。`@MockitoSettings` のようなモックの宣言でない注釈は数えない。)

| 型 | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `ProjectRepository`(`com.letsblog.project.repository`) | Mockito `mock()` / `@Mock` | **DB アクセス**(Spring Data のリポジトリ。自前のコードを持たないインタフェースで、`projects` テーブルの読み書きそのもの) | `ProjectServiceTest`・`LetsblogSyncServiceTest`・`PvRuleServiceTest`・`SnsTemplateServiceTest`・`ProjectEnvironmentSync*Test`・`JobServiceTokenWiringTest`・`SnsFixture` を使う SNS 連携の各テスト |
| `SiteRepository` | 同上(`ProjectWorld` が持つ。`SiteServiceTest` は自前) | **DB アクセス**(`sites` テーブル) | 上記のほぼ全て(`ProjectWorld` / `SiteServiceTest` / `LegacyAppPasswordCleanupTest`) |
| `SshKeyPairRepository` | 同上 | **DB アクセス**(`ssh_key_pairs` テーブル) | `SshKeyPairServiceTest`・`SiteServiceTest`・`ProjectWorld` |
| `StaticContentRepository` | 同上 | **DB アクセス**(`static_content` テーブル) | `StaticContentGenerationServiceTest`・`TextGenerationJob*Test` |
| `TagDesignSettingRepository` | 同上 | **DB アクセス**(`tag_design_settings` テーブル) | `TagDesignSettingServiceTest`・`TextGenerationJob*Test` |
| `ProjectPvRuleRepository` | 同上 | **DB アクセス**(`project_pv_rules` テーブル) | `PvRuleServiceTest` |
| `ProjectPvSyncStateRepository` | 同上 | **DB アクセス**(`project_pv_sync_state` テーブル) | `PvRuleServiceTest` |
| `ProjectSnsTemplateRepository` | 同上 | **DB アクセス**(`project_sns_templates` テーブル) | `SnsTemplateServiceTest` |
| `RabbitTemplate`(`org.springframework.amqp.rabbit.core`) | Mockito `mock()`(`ProjectWorld` が持ち、実物の `DomainEventPublisher` へ渡す) | **MQ との送受信**(`letsblog.events` exchange への `convertAndSend`) | `ProjectServiceTest`・`WordPressSiteProvisioningServiceTest`・`WordPressSiteProvisioningServiceJobTest` |

Mockito 以外で外部との通信(または Spring のフレームワークの入口)を差し替えているものも、同じ表に載せる。いずれも
Mockito のモックではなく、自前のクラスの代わりでもない。

| 型・仕掛け | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `FakeHttpServer`(`testsupport`。JDK 標準 `com.sun.net.httpserver.HttpServer` をループバックで起動) | サーバー側フェイク。メソッド+パスごとに応答を登録し、受けた要求を記録する。`ProjectWorld` が identity-service・publishing-service(内部 CMS ブリッジ)・ai-service(生成・ジョブ)・analytics-service・content-service・provision-agent・サービス間トークンのエンドポイント(Keycloak の代わり)のベース URL をすべてこの 1 台へ向ける | **HTTP 通信**。自前の `*Client`(`IdentityClient`・`IdentityBridgeClient`・`CmsProvisioningBridgeClient`・`AiGenerationClient`・`AnalyticsBridgeClient`・`ContentBridgeClient`・`WordPressProvisioningClient`・`WordPressSyncClient`・`WordPressAgentPluginsClient`・`XApiClient`・`ThreadsApiClient`・`LinkedinApiClient`・`FacebookApiClient`・`HatenaApiClient`)と、`packages/lbs-common` の `GenerationJobClient`・`ServiceTokenClient` は実物をこのサーバーに向ける | `service/` のほぼ全て |
| `MockHttpServletRequest`(Spring Test)・`JwtAuthenticationToken`(Spring Security) | Mockito ではないテスト用の実装 | **HTTP リクエストの入口**(`Authorization`・`X-Forwarded-For` ヘッダー、JWT の `sub`)。`CurrentActorService` は実物 | `CurrentActorService*Test` と、操作者を使う全テスト |
| `AsyncProxies`(`testsupport`)+ 実際の `ThreadPoolTaskExecutor` | Spring の実際の AOP プロキシ(`AsyncAnnotationAdvisor`)で、自前のランナーの `@Async` を効かせる。「実行を後に回す」はタスクを溜めるだけの `Executor`、「満杯」は実際の `ThreadPoolTaskExecutor`(core 1 / max 1 / queue 0)を塞いで再現する | フレームワークの入口(`@Async`)。満杯のときの `TaskRejectedException` も実物のExecutorが投げる | `ManagedSiteProvisioningJobStarterTest`・`ProjectEnvironmentSyncJobStarterTest`・`TextGenerationJobStarterTest` |
| ホストの `ssh-keygen`(`SshKeyGenerationService` が起動する外部プロセス) | 差し替えない。実物の `SshKeyGenerationService` が実際にコマンドを実行する(`crypto/SshKeyGenerationServiceTest` と同じ前提) | **外部プロセス** | `SshKeyPairServiceTest` |

次の 2 つは、外部との通信ではないが、モックでもない(実物のオブジェクトに、失敗や環境の欠落を作る入力を与える)。

| 仕掛け | 内容 | 使っているテスト |
| --- | --- | --- |
| 実物の `ObjectMapper` に、必ず失敗するシリアライザ(`SimpleModule`)を登録したもの | 「JSON 化に失敗しても空の JSON で続ける」分岐を通す。`ObjectMapper` を Mockito で模してはいない | `JobHeartbeatTrackerTest` |
| `JobHeartbeatTracker` のロックを、終端の書き込み(`complete`)の中で保持したまま別スレッドの `heartbeat()` を `BLOCKED` にし、再入できるロック保持者が対象ジョブを終端にする | 「周回の途中で終端になったジョブにはハートビートを書かない」分岐を、スレッドの状態(`Thread.getState()`)で同期して決定的に通す | `JobHeartbeatTrackerTest` |

### 着手時にモックの境界の外だった型

着手時(`57394420`)の `service/` 配下のテストは、28 ファイルで次の型をモックしていた(()内はモックしていたファイル数)。
境界の中の DB・MQ(後述)を除き、すべて実物へ書き直した。完了時にこれらをモックしているテストは 0 件。

- 自前のクラス: `SiteService`(14)・`CurrentActorService`(12)・`XAuthorizationStore`(4)・`SnsXService`(4)・
  `DomainEventPublisher`(3)・`WordPressSiteProvisioningService`(2)・`StaticContentGenerationService`(2)・
  `JobHeartbeatTracker`(2)・`TagDesignSettingService`・`TagDesignGenerationService`・`ProvisioningService`・
  `ProjectEnvironmentSyncService`・`ProjectEnvironmentSyncJobRunner`・`TextGenerationJobRunner`・
  `ManagedSiteProvisioningJobRunner`・`HatenaAuthorizationStore`・`FacebookPageSelectionStore`・
  `AdminAuthorizationService`・`SshKeyGenerationService`(各 1)
- 自前の通信ラッパー(`*Client`): `GenerationJobClient`(8)・`WordPressSyncClient`(4)・`IdentityClient`(4)・
  `CmsProvisioningBridgeClient`(4)・`WordPressProvisioningClient`(2)・`AiGenerationClient`(2)・`XApiClient`・
  `ThreadsApiClient`・`LinkedinApiClient`・`FacebookApiClient`・`HatenaApiClient`・`WordPressAgentPluginsClient`・
  `ContentBridgeClient`・`AnalyticsBridgeClient`・`IdentityBridgeClient`(各 1)
- `packages/lbs-common` の通信ラッパー: `ServiceTokenClient`(1)
- `ObjectMapper`(1)
- 境界の中(DB・MQ。完了時も `mock()` のまま): `SiteRepository`(12)・`ProjectRepository`(7)・`SshKeyPairRepository`(2)・
  `TagDesignSettingRepository`・`StaticContentRepository`・`ProjectSnsTemplateRepository`・`ProjectPvSyncStateRepository`・
  `ProjectPvRuleRepository`(各 1)。`RabbitTemplate` は着手時は `DomainEventPublisher` ごとモックしており、完了時は
  `RabbitTemplate` だけをモックして `DomainEventPublisher` は実物にした。

## テストから到達できない分岐

完了時に残る 6 分岐。プロダクションコードを変えずには、その分岐に入る入力や状態を作れない理由を具体的に書く。

| # | クラス | メソッド | 行 | 未カバーの分岐 | 到達できない理由 |
| ---: | --- | --- | ---: | --- | --- |
| 1 | `ProjectService` | `bindEnvironment` | 131 | `switch (environment)` の `default`(`IllegalArgumentException`) | 同じメソッドの 120 行で先に `requireValidEnvironment(environment)` を呼ぶ。許す値は `VALID_ENVIRONMENTS = {"local", "test", "production"}`(29 行)で、`switch` の 3 つの `case` と同じ。それ以外は検証が `IllegalArgumentException` にして、ここへ来ない(検証で落ちる側は `bindEnvironment_不正な環境名は例外` で通している) |
| 2 | `ProjectService` | `unbindEnvironment` | 149 | `switch (environment)` の `default` | 1 と同じ(148 行の `requireValidEnvironment` が先に弾く。`unbindEnvironment_localとproductionも外せ_不正な環境名は拒否する`) |
| 3 | `TagDesignSettingService` | `resolveHtmlTemplate` | 69 | `filter(template -> template != null && ...)` の `template != null` が偽 | 直前が `.map(TagDesignSetting::getHtmlTemplate)` で、`Optional.map` は変換結果が `null` なら空の `Optional` を返し、`filter` へ `null` を渡さない。`null` のテンプレートは `map` で消えるので、`filter` の中の `template != null` は常に真。空白(`isBlank()`)の側は `resolveHtmlTemplate_保存済みだが空欄の場合はnull` で通している |
| 4 | `SiteService` | `resolveHasAdminCapability` | 469 | `site.getCmsType() != CmsType.WORDPRESS` が真(`\|\|` の 2 つ目) | 呼び出しは `checkConnection`(347 行)だけで、その直前の 339 行で `site.getCmsType().name()` を `try` の中で評価する。`cmsType` が `null`(`Site` は `@Setter` なので作れる)だと 339 行で `NullPointerException` になり、`catch (Exception)` が「接続失敗」を返して 347 行へ進まない。`null` 以外は `CmsType` の定数が `WORDPRESS` だけ(`cms/CmsType.java`)なので、この比較が真になる値が無い。1 つ目の `!ok()` が真の側は `checkConnection_疎通に失敗したらadmin権限は不明でnull`、`null` の入力が 339 行で止まることは `checkConnection_cmsTypeがnullなら例外にせず接続失敗として返す` で確かめている |
| 5 | `SiteService` | `validateCredentials` | 487 | `cmsType == CmsType.WORDPRESS` が偽(`&&` の 1 つ目) | 479 行(同じ `cmsType == WORDPRESS`)は `null` の入力で偽側を通せる(`register_cmsTypeがnullなら認証情報の検証の途中でNullPointerExceptionになり何も保存しない`)が、その直後の 482 行 `requiredCredentialKeys(cmsType, ...)` が `switch (cmsType)`(493 行)で `cmsType == null` なら `NullPointerException` になり、487 行へ進まない。`null` 以外は `WORDPRESS` しか無い |
| 6 | `ProjectEnvironmentSyncService` | `resolveSite` | 121 | `switch (environment)` の `default`(`IllegalArgumentException`) | 呼び出しは `sync(...)` だけで、その冒頭(56・57 行)で `fromEnvironment` と `toEnvironment` の両方を `requireValidEnvironment`(`VALID_ENVIRONMENTS = {"local", "test", "production"}`)で検証済み。`switch` の 3 つの `case` と同じ集合なので、`default` に来る値は存在しない(検証で落ちる側は `sync_不正な環境名は例外` で通している) |

### 到達できる形に直した分岐

着手時に「到達できない」かもしれないと見えた次の分岐は、プロダクションコードを変えずに通せた(上の表には載せない)。

- `JobHeartbeatTracker#heartbeat` の `phase == null`(周回の途中で終端になったジョブ。72 行): ロックを持ったまま別スレッドの
  `heartbeat()` を `Thread.State.BLOCKED` まで待たせ、再入できるロック保持者が対象を `complete` する
  (`JobHeartbeatTrackerTest#jobCompletedDuringRoundIsSkipped`)。スレッドの状態で同期するので、待ち合わせは決定的。
- `SiteService#resolveDataSource` の `getCmsType() != WORDPRESS`(207 行)と `validateCredentials` の `cmsType == WORDPRESS` が偽(479 行): `Site` は `@Setter`、`SiteRegisterRequest` は直接生成できるので、`cmsType` に `null` を与えて通す(`resolveDataSource_cmsTypeがnullの非managedサイトは使えないデータソースになる`・`register_cmsTypeがnullなら…`)。
- `ProjectEnvironmentSyncService#resolveSite` の `case "production"`(本番を同期元にする): `sync_本番環境を同期元にできる`。

### 書き直しで形を変えたテスト

書き直しで、自前のクラスのモックが「例外や任意の戻り値を返す」前提だったテストは、外部との通信の境界(HTTP の応答・DB の戻り値)から
同じ状態を作る形にした。検証している振る舞いは減らしていない(アサーションを減らしたテストは無い)。

| テスト | 変更 |
| --- | --- |
| `JobHeartbeatTrackerTest#oneFailureDoesNotStopOthers` | `GenerationJobClient` は進捗(`running`)の HTTP 失敗を握りつぶすため、書き込みの例外をモックで起こせない。例外になるのはサービストークンの取得失敗なので、トークンエンドポイントを 1 回だけ失敗させ、「失敗した後も次のジョブへ進む(書き込みが 1 件通る)」ことを確かめる形にした |
| `JobHeartbeatTrackerTest#jsonFailureFallsBack` | `ObjectMapper` のモックをやめ、必ず失敗するシリアライザを登録した実物の `ObjectMapper` にした |
| `JobHeartbeatTrackerTest#jobCompletedDuringRoundIsSkipped` | モックの `doAnswer`(同一スレッドで `complete` を呼ぶ)をやめ、上記のロックの再入で再現した |
| `SnsHatenaServiceTest#startAuthorization_リダイレクト先に既にクエリがあれば連結する` | `HatenaAuthorizationStore#newState` をモックして `+` や `/` を含む state を作っていたが、実物の state は `[A-Za-z0-9_.-]` だけでエンコードの有無が変わらない。「既存のクエリへ `&state=` で連結する」ことだけを確かめる形にした(state のエンコードは `URLEncoder` の呼び出しで、分岐ではない) |
| `SnsX/Threads/Linkedin/Facebook/Hatena` の `*Test` | 「`SiteService` / `*ApiClient` が例外を投げる」を、HTTP の 4xx/5xx 応答で起こす形にした。例外の文言を確かめていたアサーションは、応答本文に載せた文言が例外のメッセージに含まれることで確かめる |

テストを無効化・skip したものは無い(`@Disabled`・`@Ignore`・`assumeTrue` なども追加していない)。
