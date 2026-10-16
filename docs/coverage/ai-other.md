# services/ai の `service` 以外のパッケージの分岐カバレッジ(#1824)

Epic #1711 の子 Issue #1824 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`services/ai` の `com.letsblog.ai` 配下のうち
`service` パッケージを除くすべて(`ai`・`client`・`config`・`controller`・`domain`・`dto`・`github`・`messaging`・
`repository` とルート)に適用した結果。プロダクションコードは変更していない(この Issue の差分は
`services/ai/src/test/**` と本ファイルだけ)。`service` パッケージは兄弟 Issue #1823 が受け持ち、
[`ai-service.md`](ai-service.md) に記録した。

このファイルは次の 3 節で構成する。

1. 計測値(着手時・完了時)
2. モックの境界
3. テストから到達できない分岐

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(CLAUDE.md → Coverage のとおり、`&&` / `||` もバイトコード上は別の分岐なので、
C1 と C2 を兼ねる)。値は `jacocoTestReport.xml` の、`com/letsblog/ai/service` 以外の各 `<package>` 直下の
`<counter type="BRANCH">` から取る。`services/ai` のテスト全体を 1 回走らせた結果なので、`service` のテストが
間接的に通す分岐(たとえば `service` のテストが使う `*Client`)も数に入る。

計測コマンド(着手時・完了時とも同じ):

```bash
./gradlew :services:ai:cleanTest :services:ai:test :services:ai:jacocoTestReport
```

計測条件: ホストの MySQL(`127.0.0.1:3306`、ADR-0006・#1769)が起動した状態で実行した(`integration/` のテストが実 MySQL を使う)。

### 着手時

| 項目 | 値 |
| --- | --- |
| コミット | `c36036ad`(develop 起点、ブランチ `test/1824-ai-other-coverage` の作成直後。変更なし) |
| コマンド | 上のとおり |
| `services/ai` 全体のテスト | 812 件、失敗 0、エラー 0、skipped 0 |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/ai/ai` | 115 | 7 | 94.3% |
| `com/letsblog/ai/client` | 28 | 16 | 63.6% |
| `com/letsblog/ai/config` | 7 | 1 | 87.5% |
| `com/letsblog/ai/controller` | 22 | 0 | 100.0% |
| `com/letsblog/ai/domain` | 10 | 2 | 83.3% |
| `com/letsblog/ai/dto` | 2 | 0 | 100.0% |
| `com/letsblog/ai/github` | 21 | 7 | 75.0% |
| `com/letsblog/ai/messaging` | 2 | 0 | 100.0% |
| `com/letsblog/ai/repository`・`com/letsblog/ai`(ルート) | 0 | 0 | 分岐なし |
| **合計(service を除く)** | **207** | **33** | **86.3%** |

着手時の未カバーのクラス別内訳(missed の数。合計 33):

| クラス | missed |
| --- | ---: |
| `PlatformServiceClient` | 9 |
| `GithubClient` | 7 |
| `ProjectBridgeClient` | 3 |
| `RemoteLlmConfigProvider` | 4 |
| `IdentityBridgeClient` | 2 |
| `PublishingServiceClient` | 2 |
| `BraveSearchClient` | 2 |
| `ProjectAiSettings` | 2 |
| `KeycloakRealmRoleConverter` | 1 |
| `LlmClient` | 1 |

着手時のテスト: `service` 以外は 49 ファイル(`testsupport/` を含む)、`@Test` / `@ParameterizedTest` 279 件。
Mockito を使うのは 26 ファイルで、モックの出現は 83 件、自前のクラス(`PlatformServiceClient`・`IdentityBridgeClient`・
`AdminAuthorizationService`・`CurrentActorService`・`ProjectAiSettingsService`・`CredentialCipher`・`AiAssistService`・
`ArticlePlanService`・`LlmConfigProvider` など 17 種)を含んでいた(後述「着手時にモックの境界の外だった型」)。
この種のモックは、行カバレッジの上では「通っている」ように見えても、そのテストの中では実際には動かない。
着手時の `controller/` が 100% なのも、サービスをモックして「呼んだか」だけを見ていたためで、サービスの中の分岐は
コントローラのテストでは動いていなかった。

### 完了時

| 項目 | 値 |
| --- | --- |
| コミット | `493ea1b8`(テストの最終コミット。この後のコミットは本ファイルの追加だけで、テストとプロダクションコードは変わらない) |
| コマンド | 上のとおり |
| `services/ai` 全体のテスト | 880 件、失敗 0、エラー 0、skipped 0(`build/test-results/test/*.xml` の `tests` / `failures` / `errors` / `skipped` の合計) |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/ai/ai` | 120 | 2 | 98.4% |
| `com/letsblog/ai/client` | 41 | 3 | 93.2% |
| `com/letsblog/ai/config` | 8 | 0 | 100.0% |
| `com/letsblog/ai/controller` | 22 | 0 | 100.0% |
| `com/letsblog/ai/domain` | 12 | 0 | 100.0% |
| `com/letsblog/ai/dto` | 2 | 0 | 100.0% |
| `com/letsblog/ai/github` | 28 | 0 | 100.0% |
| `com/letsblog/ai/messaging` | 2 | 0 | 100.0% |
| `com/letsblog/ai/repository`・`com/letsblog/ai`(ルート) | 0 | 0 | 分岐なし |
| **合計(service を除く)** | **235** | **5** | **97.9%** |

分母(covered + missed)は着手時も完了時も 240 で変わらない(プロダクションコードを変えていない)。残る 5 は、すべて下の
「テストから到達できない分岐」に載せた(クラス別: `PlatformServiceClient` 2、`ProjectBridgeClient` 1、
`RemoteLlmConfigProvider` 1、`LlmClient` 1)。

完了時の `service` 以外のテストは 59 ファイル、`@Test` / `@ParameterizedTest` 321 件(着手時 279 件)。共有部品は
`services/ai/src/test/java/com/letsblog/ai/testsupport/` の `AiWorld`・`FakeHttpServer`・`InMemoryRepositories`(#1823 のもの。
`InMemoryRepositories` には `GenerationJobRepository#findAll` の保存済み行の返却を足した)に、本 Issue で
`BackendIntegrationTest`・`IntegrationBackends` を足した。

### 突き合わせの手順(受け入れ基準 3 の再現)

`jacocoTestReport.xml` から、`service` 以外の各パッケージの分岐数と、残る未カバー分岐を行ごとに取り出す。

```bash
python3 -I - <<'EOF'
import xml.etree.ElementTree as ET
root = ET.parse('services/ai/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
total_covered = total_missed = 0
for pkg in root.findall('package'):
    if pkg.get('name') == 'com/letsblog/ai/service':
        continue
    counters = [c for c in pkg.findall('counter') if c.get('type') == 'BRANCH']
    covered = int(counters[0].get('covered')) if counters else 0
    missed = int(counters[0].get('missed')) if counters else 0
    total_covered += covered
    total_missed += missed
    print(pkg.get('name'), 'covered', covered, 'missed', missed)
    for sf in pkg.findall('sourcefile'):
        for line in sf.findall('line'):
            if int(line.get('mb')) > 0:
                print('   ', sf.get('name'), line.get('nr'), 'missed', line.get('mb'), 'covered', line.get('cb'))
print('TOTAL covered', total_covered, 'missed', total_missed)
EOF
```

完了時(`493ea1b8`)の出力の未カバー行は次のとおりで、`missed` の合計 5 が下の表の行数(5 行)と 1 対 1 に対応する
(パッケージの並びは XML の順で、毎回同じとは限らない。突き合わせるのは「ファイル名・行」の集合)。

```text
com/letsblog/ai/client covered 41 missed 3
    PlatformServiceClient.java 109 missed 1 covered 3
    PlatformServiceClient.java 144 missed 1 covered 3
    ProjectBridgeClient.java 101 missed 1 covered 3
com/letsblog/ai/ai covered 120 missed 2
    RemoteLlmConfigProvider.java 155 missed 1 covered 2
    LlmClient.java 181 missed 1 covered 1
TOTAL covered 235 missed 5
```

注意: JaCoCo は、ブロックの最後まで実行が進んだときにだけそのブロックをカバー済みにする。例外を投げて終わる呼び出しだけを
通したテストでは、その呼び出しの後ろの分岐が「未通過」と出る。分岐を埋めるときは、例外になる経路と、例外にならず戻る経路の
両方を通す。

## モックの境界

`service` 以外のテスト(`services/ai/src/test/java/com/letsblog/ai/` のうち `service/` 配下を除くすべて。
`testsupport/` を含む)で、Mockito のモック(`@Mock` / `@MockitoBean` / `@MockitoSpyBean` / `mock()` / `spy()`)にしている型は
次の **7 つだけ**で、すべて外部との通信(DB・MQ・Keycloak の鍵取得の 3 種類)。数え上げの確認コマンドと完了時(`493ea1b8`)の出力:

```bash
cd services/ai/src/test/java/com/letsblog/ai
{
  for f in $(find . -name '*.java' -not -path './service/*'); do
    awk '/^[[:space:]]*@(Mock[[:space:]]*|MockitoBean|MockitoSpyBean|Spy[[:space:]]*)$/{getline n; print n}' "$f"
  done
  grep -rhoE "(Mockito\.)?(mock|spy)\([A-Za-z]+\.class" --include='*.java' --exclude-dir=service . | sed -E 's/.*\(//; s/\.class//'
} | sed -E 's/^[[:space:]]*(private|public)?[[:space:]]*(final)?[[:space:]]*//; s/[[:space:]]+[a-zA-Z]+;?[[:space:]]*$//; s/ = .*//' \
  | sort | uniq -c | sort -rn
```

```text
      8 JwtDecoder
      2 ProjectAiSettingsRepository
      2 ConnectionFactory
      1 ProjectReviewStepSettingRepository
      1 ProcessedEventRepository
      1 GenerationJobRepository
      1 ArticlePlanSessionRepository
```

| モックしている型 | どの外部通信にあたるか | 使っている場所 |
| --- | --- | --- |
| `JwtDecoder`(Spring Security) | Keycloak の JWK セット取得を伴う JWT の検証(HTTP) | `integration/` の 8 クラス(`@MockitoBean`)。トークン文字列ごとに `Jwt` を返す / `BadJwtException` を投げる |
| `ConnectionFactory`(Spring AMQP) | MQ(RabbitMQ)への接続 | `config/RabbitMqConfigTest`(2 件。リスナーコンテナのファクトリを組み立てるだけで、接続はしない) |
| `ProjectAiSettingsRepository`(Spring Data) | DB アクセス(`project_ai_settings`) | `testsupport/InMemoryRepositories`(保存した行をメモリに持つ。`AiWorld` が組み立てる)、`messaging/EventMessageListenerTest`(`deleteByProjectId` の呼び出しを数える) |
| `ProcessedEventRepository`(Spring Data) | DB アクセス(`processed_events`) | `messaging/EventMessageListenerTest` |
| `GenerationJobRepository`(Spring Data) | DB アクセス(`generation_jobs`) | `testsupport/InMemoryRepositories` |
| `ArticlePlanSessionRepository`(Spring Data) | DB アクセス(`article_plan_sessions`) | `testsupport/InMemoryRepositories` |
| `ProjectReviewStepSettingRepository`(Spring Data) | DB アクセス(`project_review_step_settings`) | `testsupport/InMemoryRepositories` |

(出力の 7 行が表の 7 行。`ProjectAiSettingsRepository` の 2 は、`InMemoryRepositories` と `EventMessageListenerTest` の 1 つずつ。)

`@ExtendWith(MockitoExtension.class)` を持つ `service` 以外のテストは、リポジトリだけをモックする
`messaging/EventMessageListenerTest` の 1 ファイル。`integration/` のテストは、`IdentityClient`・`IdentityBridgeClient`・
`PlatformServiceClient` などアプリケーションの実物の Bean をそのまま使い、`JwtDecoder` 以外は `@MockitoBean` にしない。

Mockito 以外の差し替え(すべて外部との通信か、フレームワークの入口を模すもの。自前のクラスではない):

| 部品 | 何を差し替えるか | 備考 |
| --- | --- | --- |
| `testsupport/FakeHttpServer`(JDK の `HttpServer` をループバックで起動) | HTTP の相手(identity-service・platform-service・project-service・publishing-service・GitHub API・Brave Search・LLM の OpenAI 互換 API / Ollama) | サーバー側を模す層。`client/` の 5 クラスの新しいテスト、`github/GithubClientEdgeCasesTest`、`ai/BraveSearchClientTest` は実物の `*Client` がこれに向く。`FakeHttpServer.onAllInterfaces()` / `lanBaseUrl()` を使うテスト(`LlmClientToolsTokenTest`・`LlmClientConnectionGuardTest`)は、ループバック・169.254/16 以外の起動中の IPv4 インタフェースが無いホストでは失敗する(skip はしない) |
| `testsupport/IntegrationBackends` + `testsupport/BackendIntegrationTest` | `@SpringBootTest` が向く他サービスのベースURL(`app.identity-service-uri` など)を、JVM で 1 台の `FakeHttpServer` へ向ける | `@DynamicPropertySource` を `BackendIntegrationTest` の 1 か所に置く。クラスごとに宣言すると、Spring のコンテキストキャッシュのキーが変わって、クラスごとに DB の接続プールが作られ、MySQL の接続数(`Too many connections`)を使い切る |
| `ai/RedirectedLlmConfigProvider`(`RemoteLlmConfigProvider` のサブクラス、#1823) | コード内で固定された `api.openai.com` のホスト | `baseUrlFor` が OPENAI のとき `FakeHttpServer` の `/cloud` を返すだけ。設定の解決・プロジェクト上書き・API キーの復号・宛先検査・キャッシュは実物。`LlmClient` の Claude 呼び出しは `ANTHROPIC_BASE_URL` 定数を直接使うので向け替えられない(下の No.5) |
| `ConnectionDestinationGuard`(実物)の構成 | 名前解決とネットワークインタフェースの列挙 | `RemoteLlmConfigProviderConnectionGuardTest` は DNS とインタフェース列挙を関数で差し替える(外部=DNS・OS の境界)。検査の規則(`DestinationAddressRules`)は実物。`AiWorld` は名前解決を実物の DNS にし、コンテナ判定だけを固定する |
| `MockRestServiceServer`(Spring Test) | GitHub API の応答 | `github/GithubClientTest`(既存)。Spring Test が提供する HTTP のサーバー側を模す層で、Mockito のモックではない |
| JDK の `HttpServer` を直接起動 | HTTP の相手 | `client/PlatformServiceClientAiConnectionsTest`・`client/ProjectBridgeClientGithubAccessTest`(既存) |
| `ToolsTokenProvider.fromSupplier(...)` | extensions のツール用トークンの取得元(Keycloak のトークンエンドポイント) | 取得に成功する / 失敗する `Supplier` を渡す。`ToolsTokenProvider` 自体は実物 |
| `new Advice() { }`(`org.aopalliance.aop.Advice`) | リトライ advice の代わりの目印 | `config/RabbitMqConfigTest`。`Advice` は何もメソッドを持たない目印のインタフェースで、相手の呼び出しを模すものではない |
| `LlmClient` のテスト専用コンストラクタ(`LlmClient(String, String, String, long)`) | 固定値の接続設定 | **プロダクションコード側が持つ**匿名の `LlmConfigProvider`(テストが書いた偽物ではない)。`stripThinkingBlocks` の検証と、API キーが null / 空白のときの失敗に使う。実物の `RemoteLlmConfigProvider` は null のキーを返さないため、この経路はこのコンストラクタ経由でしか通らない |
| `MockHttpServletRequest` / `Jwt` / `JwtAuthenticationToken` / `SecurityContextHolder` / `MockMvc`(Spring Test / Spring Security) | リクエストの入口と認証 | Mockito のモックではない(Issue の定める「Spring Test が提供する実装」) |
| `CredentialCipher`(実物。固定の鍵) | API キーの暗号化 | モックしない。`AiWorld` が固定の 32 バイト鍵で作る |

### 着手時にモックの境界の外だった型(書き直したもの)

着手時(`c36036ad`)に `service` 以外のテストがモックしていた型は次のとおり(出現数)。**リポジトリ(DB)の 5 種・`ConnectionFactory`・
`JwtDecoder` を除く 17 種**は自前のクラスで、すべて実物を使う形に書き直した。

| 着手時にモックしていた型(出現数) | 完了時 |
| --- | --- |
| `AdminAuthorizationService`(8)・`CurrentActorService`(6)・`ProjectAiSettingsService`(5)・`CredentialCipher`(5) | 実物(`AiWorld` が組み立てる。操作者は identity-service の HTTP フェイクが返す) |
| `AiAssistService`(2)・`AiConnectionService`(1)・`ArticlePlanService`(1)・`OllamaPullService`(1)・`ProjectConnectionService`(1)・`LlmModelService`(1)・`ReviewStepModelService`(1)・`OllamaPullJobRunner`(1) | 実物。`controller/` のテストは、コントローラ → サービス → `*Client` → HTTP フェイクまでを通す。`OllamaPullIntegrationTest` は実行部を動かし、Ollama の `POST /api/pull` をフェイクで受ける |
| `PlatformServiceClient`(8)・`IdentityBridgeClient`(8)・`IdentityClient`(8) | `integration/` ではアプリケーションの実物の Bean。相手の HTTP を `IntegrationBackends` の `FakeHttpServer` で模す。`ai/` のテストは `AiWorld` の実物 |
| `LlmConfigProvider`(3) | 実物の `RemoteLlmConfigProvider`(`LlmClient*Test`)。`LlmClientTest` にあった手書きの `StubConfigProvider` も廃止した |
| `Advice`(1) | 目印の匿名クラス(上表) |
| `GenerationJobRepository`(4)・`ArticlePlanSessionRepository`(4)・`ProjectAiSettingsRepository`(2)・`ProjectReviewStepSettingRepository`(1)・`ProcessedEventRepository`(1) | 境界の内側(DB)なので引き続きモック。`controller/`・`dto/` は `InMemoryRepositories` に統一した |

変更したのは既存の 28 ファイル(`controller/` 12、`integration/` 8、`ai/` 5、`dto/` 1、`config/` 1、`testsupport/` 1)で、新設は 10 ファイル(`client/` 4、`github/` 1、`ai/` 1、`config/` 1、`domain/` 1、`testsupport/` 2)。

### 書き直しで外した・置き換えたテスト

着手時の `@Test` / `@ParameterizedTest` のメソッド名のうち、完了時に同じ名前では無くなったのは 16 件。**検証を失ったものは無い。**

- `ArticlePlanControllerTest` の `*_認可拒否ならForbidden` 15 件(`chat`・`listSessions`・`getSession`・`getSessionByIssue`・`getIssueDescription`・
  `listIssues`・`suggestTitles`・`acceptPlan`・`suggestStructure`・`acceptStructure`・`suggestMetadata`・`listTags`・`listCategories`・
  `listCategoriesWithParents`・`assignIssue`): 15 エンドポイントを列挙した 1 つのパラメータ化テスト `認可拒否ならForbidden` に統合した。
  拒否されることに加えて、メンバー判定以外の外部通信(LLM・GitHub・project-service・platform-service)が一度も起きないこと、
  ジョブとセッションが保存されないことを確かめるようになった(従来は `ForbiddenException` を投げることだけ)。
- `ProjectLlmModelControllerTest#updateReviewStepSetting_認可拒否ならForbidden`: `…ならForbiddenで保存しない` に改名し、保存が無いことも確かめる。
  同ファイルの他の `*_認可拒否ならForbidden` も、保存・外部通信が無いことを確かめる形にした(名前は `…Forbiddenで保存しない` のものを含む)。

検証の中身が変わった点(弱めたのではなく、モックでしか見られなかったものを実物の観測に置き換えた):

- `verify(x).foo(...)` で「呼んだ」ことを見ていたものは、結果(HTTP フェイクが受けた要求の Authorization・クエリ、メモリ上のリポジトリの行、
  応答の中身)で確かめる形にした。
- `RemoteLlmConfigProviderTest` の `verify(cipher, never()).decrypt(any())`(キーを保存していないプロジェクトで復号を呼ばない)は、
  実物の `CredentialCipher` では見られない。代わりに「エラーになり、システム設定のキーがメッセージに現れない」ことを確かめる。
- `RemoteLlmConfigProviderTest` の `verify(projectSettings, never()).getOllamaBaseUrl(7L)` などは、実物の `ProjectAiSettingsService` の
  先にあるリポジトリ(DB)の `findByProjectId` が呼ばれないこと、で確かめる。
- `LlmClientTest#useProject_接続設定プロバイダーへ委譲する`(`verify(provider).useProject(9L)`)は、宣言したプロジェクトのキーで
  `Authorization` が付くこと、`useProject(null)` で解除されることで確かめる。
- `LlmClientTest#generate_APIキーがnullでも…`(CLAUDE とキー null のスタブ)は、実物の `RemoteLlmConfigProvider` が null のキーを返さないので、
  プロダクションコード側のテスト専用コンストラクタ(上表)で、null と空白の 2 つに分けて通す。

### 追加・新設したテスト

| テスト | 内容 |
| --- | --- |
| `client/PlatformServiceClientTest`(新設) | システム全体の Brave キー(空の応答は null、失敗しても null)、`llm-config` のプロバイダー指定(null・空・空白でクエリを付けない)、空の応答、HTTP エラー(本文あり・なし)、接続不可、認可ヘッダー(null・空・空白で付けない) |
| `client/IdentityBridgeClientTest`(新設) | メンバー判定の結果、空の応答はメンバーではない、認可ヘッダー、identity-service の障害 |
| `client/PublishingServiceClientTest`(新設) | 既存カテゴリ・タグ・親つきカテゴリ、空の応答と取得失敗は空リスト、認可ヘッダー |
| `client/ProjectBridgeClientTest`(新設) | アクセス情報、空の応答、本文の無い HTTP エラー、接続不可、認可ヘッダー |
| `github/GithubClientEdgeCasesTest`(新設) | `body` が null、空の応答、配列でない応答、`assignees` が無い / 配列でない、認証ユーザー取得の 401 とそれ以外 |
| `ai/BraveSearchClientTest`(新設) | キーが null・空・空白、結果の組み立て、空の応答、HTTP エラー |
| `config/KeycloakRealmRoleConverterTest`(新設) | `realm_access` が無い、`roles` が無い、`roles` が配列でない |
| `domain/ProjectAiSettingsTest`(新設) | 3 種類のキーを「持つ」判定(null・空・値あり) |
| `ai/RemoteLlmConfigProviderTest` | プロバイダー null(システム既定)の解決、応答がプロバイダー名を持たない場合のキャッシュ |
| `ai/LlmClientTest` | API キーが null / 空白のとき呼び出す前に失敗する |

## テストから到達できない分岐

完了時(`493ea1b8`)に残る未カバー分岐は **5 件**(クラス 4 つ)。いずれも「プロダクションコードを変えずには、その分岐に入る入力や
状態を作れない」もの。

| No. | クラス | メソッド | 行 | 分岐 | 到達できない理由 |
| --- | --- | --- | ---: | --- | --- |
| 1 | `PlatformServiceClient` | `resolveLlmConfig` | 109 | `(body == null \|\| body.isBlank()) ? e.getMessage() : body` の `body == null` が真 | `body` は `RestClientResponseException#getResponseBodyAsString()` の戻り値。Spring Framework はコンストラクタで本文が無いときに空のバイト列へ置き換えるため(`responseBody != null ? responseBody : new byte[0]`)、戻り値は null にならない。本文が空の経路(`isBlank()` の真)と本文がある経路は通っている(`llmConfig_HTTPエラーで本文が空ならステータスのメッセージを使う` / `llmConfig_HTTPエラーは本文を例外のメッセージにする`)。`RestClientResponseException` はテストから null の本文で作る手段が無い(サーバーのフェイクが返す HTTP 応答を経由する) |
| 2 | `PlatformServiceClient` | `resolveAiConnectionsConfig` | 144 | 同上(`body == null` が真) | No.1 と同じ。本文が空 / ありの経路は `PlatformServiceClientAiConnectionsTest` が通している |
| 3 | `ProjectBridgeClient` | `bodyOrMessage`(private) | 101 | `(body != null && !body.isBlank()) ? body : e.getMessage()` の `body != null` が偽 | No.1 と同じ理由で `getResponseBodyAsString()` は null を返さない。`!body.isBlank()` の真偽(本文あり / 空)は `ProjectBridgeClientTest#HTTPエラーで本文が空ならステータスのメッセージを使う` と `ProjectBridgeClientGithubAccessTest` が通している |
| 4 | `RemoteLlmConfigProvider` | `projectKeyOfCurrentProject`(private) | 155 | `switch (provider)` の暗黙の `default` | `AiProvider` の 3 つの値(`OPENAI` / `CLAUDE` / `OLLAMA`)はすべて `case` にあり、3 つとも通っている(`OLLAMA` は `null` を返す)。列挙型の `switch` 式のバイトコードが持つ暗黙の `default`(`MatchException`)は、`AiProvider` に新しい値を足して再コンパイルしたときだけ入る |
| 5 | `LlmClient` | `generate(String, ImageInput, String, AiProvider)` | 181 | `provider == AiProvider.CLAUDE` が真 | Claude の呼び出し(`generateWithClaude`)の接続先は定数 `ANTHROPIC_BASE_URL`(`https://api.anthropic.com`)で、`LlmConfigProvider` からも設定からも変えられない(`RemoteLlmConfigProvider#baseUrlFor` もこの定数を返す)。この分岐の先を通すには本物の Anthropic API へ HTTPS で接続するしかなく、テストを外部のネットワークと本物のサービスに依存させることになる。CLAUDE は「プロジェクトにキーが無ければ接続前に失敗する」経路(`generate_CLAUDEもAPIキー未設定なら呼び出す前に例外`)と、メッセージ組み立て(`claudeUserMessage`)で確かめている。偽の相手へ向けるには `https_proxy` などの JVM 全体のプロキシと TLS 証明書の差し替えが要り、プロダクションコードを変えずに「通信だけ」を差し替える範囲を超える |

### 参考: 分岐ではないが、通らない記述

JaCoCo の `BRANCH` には出ないが、同じ理由で通らないもの。

- `LlmClient#generateWithClaude` の本体(No.5 と同じ理由で、ネットワーク無しには呼べない)。分岐を持たない行なので、上の件数には現れない。
