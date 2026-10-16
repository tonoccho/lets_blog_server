# services/ai `com.letsblog.ai.service` の分岐カバレッジ(#1823)

Epic #1711 の子 Issue #1823 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`services/ai` の `com.letsblog.ai.service` パッケージ
(23 ファイル)に適用した結果。プロダクションコードは変更していない(この Issue の差分は `services/ai/src/test/**` と本ファイルだけ)。

このファイルは次の 3 節で構成する。

1. 計測値(着手時・完了時)
2. モックの境界
3. テストから到達できない分岐

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(CLAUDE.md → Coverage のとおり、`&&` / `||` もバイトコード上は別の分岐なので、
C1 と C2 を兼ねる)。値は `jacocoTestReport.xml` の `<package name="com/letsblog/ai/service">` 直下の
`<counter type="BRANCH">` から取る。`services/ai` のテスト全体を 1 回走らせた結果なので、`service` のクラスを
間接的に通す他パッケージ(`controller/`・`integration/` など)のテストも数に入る。`service` に下位パッケージは無い。

計測コマンド(着手時・完了時とも同じ):

```bash
./gradlew :services:ai:cleanTest :services:ai:test :services:ai:jacocoTestReport
```

計測条件: ホストの MySQL(`127.0.0.1:3306`、ADR-0006・#1769)が起動した状態で実行した(`integration/` のテストが実 MySQL を使う)。
完了時の `services/ai` 全体は 812 件、失敗 0、エラー 0、skipped 0
(`build/test-results/test/*.xml` の `tests` / `failures` / `errors` / `skipped` の合計)。

### 着手時

| 項目 | 値 |
| --- | --- |
| コミット | `29dd6b6e`(develop 起点、ブランチ `test/1823-ai-service-coverage` の作成直後。変更なし) |
| コマンド | 上のとおり |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/ai/service` | 462 | 36 | 92.8% |

着手時の未カバーのクラス別内訳(missed の数。合計 36):

| クラス | missed |
| --- | ---: |
| `ArticlePlanService` | 12 |
| `AiAssistService` | 8 |
| `CurrentActorService` | 7 |
| `OllamaPullJobRunner` | 3 |
| `AiConnectionService` | 2 |
| `WebSearchService` | 1 |
| `ProviderModelCatalog` | 1 |
| `LlmModelService` | 1 |
| `ProjectAiSettingsService` | 1 |

着手時のテスト: `service/` 配下 18 ファイル、`@Test` / `@ParameterizedTest` 342 件。そのうち 14 ファイルが Mockito を使い、
自前のクラス(`CurrentActorService`・`ProjectAiSettingsService`・`PlatformServiceClient`・`LlmConfigProvider`・
`LlmModelService`・`LlmClient` など 19 種)をモックしていた(後述「着手時にモックの境界の外だった型」)。モックの先の分岐は、
行カバレッジの上では「通っている」ように見えても、そのテストの中では実際には動いていなかった。たとえば
`CurrentActorService` 自体のテストは無く、`RemoteLlmConfigProvider` を通すテストも `service/` には無かった。

### 完了時

| 項目 | 値 |
| --- | --- |
| コミット | `768b2eba`(テストの最終コミット。この後のコミットは本ファイルの追加だけで、テストとプロダクションコードは変わらない) |
| コマンド | 上のとおり |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/ai/service` | 489 | 9 | 98.2% |

分母(covered + missed)は着手時も完了時も 498 で変わらない(プロダクションコードを変えていない)。残る 9 は、すべて下の
「テストから到達できない分岐」に載せた(クラス別: `AiAssistService` 3、`OllamaPullJobRunner` 2、`AiConnectionService` 1、
`ArticlePlanService` 1、`ProviderModelCatalog` 1、`ProjectAiSettingsService` 1)。

完了時の `service/` 配下は 19 ファイル、`@Test` / `@ParameterizedTest` 400 件(着手時 342 件)。テストの共有部品は
`services/ai/src/test/java/com/letsblog/ai/testsupport/`(`AiWorld`・`FakeHttpServer`・`InMemoryRepositories`・
`SharedCircuitBreakers`)と、`.../ai/RedirectedLlmConfigProvider.java` に置いた。

### 突き合わせの手順(受け入れ基準 3 の再現)

`jacocoTestReport.xml` から、`service` の分岐数と、残る未カバー分岐を行ごとに取り出す。

```bash
python3 -I - <<'EOF'
import xml.etree.ElementTree as ET
root = ET.parse('services/ai/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
for pkg in root.findall('package'):
    if pkg.get('name') != 'com/letsblog/ai/service':
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

完了時(`768b2eba`)の出力は次のとおりで、`missed` の合計 9 が下の表の行数(9 行)と 1 対 1 に対応する。

```text
BRANCH covered 489 missed 9
AiConnectionService.java 164 missed 1 covered 3
ArticlePlanService.java 545 missed 1 covered 1
AiAssistService.java 467 missed 1 covered 3
AiAssistService.java 527 missed 1 covered 1
AiAssistService.java 684 missed 1 covered 1
OllamaPullJobRunner.java 168 missed 1 covered 3
OllamaPullJobRunner.java 183 missed 1 covered 1
ProviderModelCatalog.java 159 missed 1 covered 3
ProjectAiSettingsService.java 87 missed 1 covered 3
```

(`sourcefile` の並びは XML の順で、毎回同じとは限らない。突き合わせるのは「ファイル名・行」の集合。)

注意: JaCoCo は、ブロックの最後まで実行が進んだときにだけそのブロックをカバー済みにする。例外を投げて終わる呼び出しだけを
通したテストでは、その呼び出しの後ろの分岐が「未通過」と出る。分岐を埋めるときは、例外になる経路と、例外にならず戻る経路の
両方を通す。

## モックの境界

`com.letsblog.ai.service` のテスト(`services/ai/src/test/java/com/letsblog/ai/service/` 配下と、それらが共有する
`.../testsupport/` 配下)で、Mockito のモック(`@Mock` / `@MockitoBean` / `@MockitoSpyBean` / `mock()` / `spy()`)にしている型は
次の **4 つだけ**で、すべて Spring Data のリポジトリ(DB アクセス)。数え上げの確認コマンドと完了時(`768b2eba`)の出力:

```bash
cd services/ai/src/test/java/com/letsblog/ai
{
  for f in service/*.java testsupport/*.java; do
    awk '/@Mock[[:space:]]*$|@MockitoBean|@MockitoSpyBean|@Spy[[:space:]]*$/{getline n; print n}' "$f"
  done
  grep -hoE "(Mockito\.)?(mock|spy)\([A-Za-z]+\.class" service/*.java testsupport/*.java | sed -E 's/.*\(//; s/\.class//'
} | sed -E 's/^[[:space:]]*(private|public)?[[:space:]]*(final)?[[:space:]]*//; s/[[:space:]]+[a-zA-Z]+;?[[:space:]]*$//; s/ = .*//' \
  | sort | uniq -c | sort -rn
```

```text
      4 GenerationJobRepository
      3 ProjectAiSettingsRepository
      1 ProjectReviewStepSettingRepository
      1 ArticlePlanSessionRepository
```

| モックしている型 | どの外部通信にあたるか | 使っている場所 |
| --- | --- | --- |
| `GenerationJobRepository`(Spring Data) | DB アクセス(`generation_jobs`) | `testsupport/InMemoryRepositories`(保存した行をメモリに持つ。`AiWorld` が組み立てる)、`OllamaPullJobRunnerTest`・`OllamaPullJobRunnerToolsTokenTest`(`findById` / `save` を指定。ジョブの途中経過を保存のたびに記録する)、`StaleGenerationJobSweepServiceTest`(`findByStatusAndUpdatedAtBefore` の戻り値を指定) |
| `ProjectAiSettingsRepository`(Spring Data) | DB アクセス(`project_ai_settings`) | `testsupport/InMemoryRepositories`(保存した行をメモリに持つ)、`ProjectAiSettingsServiceTest`・`ProjectConnectionUrlDenylistTest`(`findByProjectId` / `save` を指定し、保存した値を検証) |
| `ArticlePlanSessionRepository`(Spring Data) | DB アクセス(`article_plan_sessions`) | `testsupport/InMemoryRepositories`(保存した行をメモリに持つ) |
| `ProjectReviewStepSettingRepository`(Spring Data) | DB アクセス(`project_review_step_settings`) | `testsupport/InMemoryRepositories`(保存した行をメモリに持つ) |

`service/` 配下のテストファイルで `@ExtendWith(MockitoExtension.class)` を持つのは、リポジトリだけをモックする
`ProjectAiSettingsServiceTest`・`ProjectConnectionUrlDenylistTest`・`StaleGenerationJobSweepServiceTest`・`OllamaPullJobRunnerTest` の 4 ファイル。
`MQ`(`RabbitTemplate`)・SSH・外部プロセスは `service` のコードが使わないので、モックは無い。

Mockito 以外の差し替え(すべて外部との通信か、フレームワークの入口を模すもの。自前のクラスではない):

| 部品 | 何を差し替えるか | 備考 |
| --- | --- | --- |
| `testsupport/FakeHttpServer`(JDK の `HttpServer` をループバックで起動) | HTTP の相手(identity-service・platform-service・project-service・publishing-service・GitHub API・Brave Search・LLM の OpenAI 互換 API / Ollama) | サーバー側を模す層。`IdentityClient`・`IdentityBridgeClient`・`PlatformServiceClient`・`ProjectBridgeClient`・`PublishingServiceClient`・`GithubClient`・`BraveSearchClient`・`LlmClient`・`ProviderModelCatalog` は実物がこれに向く |
| `FakeHttpServer.onAllInterfaces()` / `lanBaseUrl()` | ループバックでないアドレスで待ち受けるサーバー | `ConnectionDestinationGuard`(実物)はループバックを必ず拒否する。プロジェクト単位の上書き先(宛先検査を通る接続先)を実物の検査のまま通すために使う。**このホストにループバック・169.254/16 以外の起動中の IPv4 インタフェースが無い環境では、該当テストは失敗する**(skip はしない) |
| `ai/RedirectedLlmConfigProvider`(`RemoteLlmConfigProvider` のサブクラス) | コード内で固定された `api.openai.com` / `api.anthropic.com` のホスト | `baseUrlFor` が OPENAI / CLAUDE のとき `FakeHttpServer` の `/cloud` を返すだけ。設定の解決・プロジェクト上書き・API キーの復号・宛先検査・キャッシュは実物。ネットワーク層でのリダイレクトに相当する。なお `LlmClient` の Claude 呼び出しは `ANTHROPIC_BASE_URL` 定数を直接使うので向け替えられず、CLAUDE は「キー未設定で失敗する」経路でだけ確かめている |
| `ConnectionDestinationGuard`(実物)の構成 | 名前解決は実物の DNS、ネットワークインタフェースの列挙とコンテナ判定は「コンテナ外」に固定 | CI のコンテナの中でも同じ結果になるようにするため。検査の規則(`DestinationAddressRules`)は実物 |
| `MockRestServiceServer`(Spring Test) | Ollama / ComfyUI の疎通確認先 | `AiConnectionServiceTest`・`AiConnectionServiceToolsTokenTest`。Spring Test が提供する HTTP のサーバー側を模す層で、Mockito のモックではない |
| `ToolsTokenProvider.fromSupplier(...)` | extensions のツール用トークンの取得元(Keycloak のトークンエンドポイント) | 取得に成功する / 失敗する `Supplier` を渡す。`ToolsTokenProvider` 自体は実物 |
| `testsupport/SharedCircuitBreakers` | JVM 全体で共有されるサーキットブレーカーを閉じた状態へ戻す | テストの成否が実行順に左右されないようにするため(`AiWorld` が毎回呼ぶ) |
| `MockHttpServletRequest` / `Jwt` / `JwtAuthenticationToken` / `SecurityContextHolder`(Spring Test / Spring Security) | リクエストの入口と認証 | Mockito のモックではない(Issue の定める「Spring Test が提供する実装」) |
| `CredentialCipher`(実物。固定の鍵) | API キーの暗号化 | モックしない。`AiWorld` が固定の 32 バイト鍵で作る |

### 着手時にモックの境界の外だった型(書き直したもの)

着手時(`29dd6b6e`)に `service/` のテストがモックしていた型は次のとおり(出現数)。**リポジトリ(DB)の 4 種を除く 19 種**は
自前のクラスで、すべて実物を使う形に書き直した。

| 着手時にモックしていた型(出現数) | 完了時 |
| --- | --- |
| `CurrentActorService`(8)・`ProjectAiSettingsService`(5)・`LlmModelService`(3)・`WebSearchService`(2)・`ProviderModelCatalog`(2)・`ReviewStepModelService`(1)・`ProjectConnectionService`(1)・`OllamaPullJobRunner`(1)・`ArticlePlanService`(1)・`CredentialCipher`(1)・`ConnectionDestinationGuard`(2) | 実物(`AiWorld` が組み立てる。`ConnectionDestinationGuard` は名前解決だけ実物の DNS、インタフェース列挙を固定した構成) |
| `PlatformServiceClient`(4)・`PublishingServiceClient`(1)・`ProjectBridgeClient`(1)・`IdentityBridgeClient`(1)・`GithubClient`(1)・`BraveSearchClient`(1) | 実物の `*Client`。相手の HTTP を `FakeHttpServer` で模す |
| `LlmClient`(2)・`LlmConfigProvider`(4) | 実物の `LlmClient` と `RemoteLlmConfigProvider`(後者は固定のクラウドホストだけ `RedirectedLlmConfigProvider` で向け替え)。LLM は OpenAI 互換 `chat/completions` の HTTP フェイク |
| `GenerationJobRepository`(6)・`ProjectAiSettingsRepository`(2)・`ProjectReviewStepSettingRepository`(1)・`ArticlePlanSessionRepository`(1) | 境界の内側(DB)なので引き続きモック。`InMemoryRepositories` がメモリ上の行を持つ形に統一した(個別に戻り値を指定するテストは残る) |

書き直したのは 14 ファイル(`AdminAuthorizationServiceTest`・`AiAssistServiceTest`・`AiConnectionServiceTest`・`AiConnectionServiceToolsTokenTest`・
`ArticlePlanServiceTest`・`LlmModelServiceTest`・`OllamaPullJobRunnerTest`・`OllamaPullJobRunnerToolsTokenTest`・`OllamaPullServiceTest`・
`ProjectConnectionServiceTest`・`ProviderModelCatalogTest`・`ProviderModelCatalogToolsTokenTest`・`ReviewStepModelServiceTest`・`WebSearchServiceTest`)。
着手時の `@Test` / `@ParameterizedTest` メソッドのうち、完了時に同じ名前では無くなったのは 10 件(別に補助メソッド 5 件も消えた)。
内訳は次のとおりで、**検証を失ったのは 3 件だけ**(どれもモックだから書けたケースで、実物では起こらない)。

名前を変えて、実物が実際に取る経路の検証に置き換えたもの(7 件):

- `AdminAuthorizationServiceTest#requireProjectMemberOrAdmin_プロジェクトメンバーなら許可` → `…トークンを転送する`(同じ検証に、転送した Bearer の検証を足した)。
- `ReviewStepModelServiceTest#listSettings_Claudeの候補はProviderModelCatalogが返すAnthropicの一覧になる` → `…Anthropicの一覧になる`。
- `WebSearchServiceTest#searchSafely_projectId未指定時はシステム全体設定をブリッジ経由で使う` → `…呼び出し元のトークンを転送する`。
- `ArticlePlanServiceTest#chat_プロバイダー未設定ならnullを渡しシステム既定へ任せる` → `chat_プロバイダー未設定ならシステム既定へ任せる`、
  `suggestTitles_プロバイダー未設定ならnullを渡す` → `…システム既定で生成する`: モックは「LlmClient へ null を渡した」ことを見ていた。
  実物では、LLM のリクエストが実際にシステム既定の接続先(`/v1/chat/completions`)へ向かうことを見る。
- `AiAssistServiceTest#執筆支援_projectId未指定でprovider指定ならそのproviderとnullモデルを渡す` → `…そのproviderとシステム既定モデルを渡す`:
  プロジェクトが無いので ChatGPT のキーが引けず、LLM へ送る前にそのプロバイダーで失敗することを見る。
- `AiAssistServiceTest#執筆支援_プロジェクトで未選択ならプロバイダー解決結果のnullをそのまま渡す` → `…システム既定のモデルとプロバイダーを使う`:
  実物の `LlmModelService#getSelectedProvider` は null を返さない(未選択ならシステム既定プロバイダー)。

検証を失ったもの(3 件):

- `ProviderModelCatalogTest#openai_APIキーがnullなら問い合わせず空を返す`: 実物の `RemoteLlmConfigProvider#apiKeyFor` は null を返さない
  (キーが無ければ `AiServiceException` を投げ、あれば復号した文字列)。null を返す `LlmConfigProvider` はモックでしか作れなかった。
  `AiServiceException` を握りつぶす経路(`openai_APIキーが未設定なら…`)・空白の経路は残している。下の No.8 に載せた。
- `ProviderModelCatalogTest#openai_接続先が_v1_で終わらない場合も_v1_models_を呼ぶ`: OpenAI の接続先はコード内で固定されており、
  `/v1` の有無を変えられない。`/v1` の有無の判定は Ollama の 3 ケース(`/v1`・`/v1/`・根のまま)で通している。
- `AiAssistServiceTest#factCheck_失敗理由が無い検索失敗でも理由文字列は空にならない`: 検索失敗の理由が null の `WebSearchOutcome` を
  モックから返していた。実物では、Web 検索の準備(DB 読み出し)が `RuntimeException` で失敗したときだけ理由が null / 空白になるので、
  その形に置き換えた(`factCheck_検索の準備でメッセージを持たない障害が起きても理由不明としてスキップする` /
  `…空白だけのメッセージの障害…`、2 件。null と空白の両方を通す)。この 1 件は置き換え先があるので、実質は検証を保っている。

プロバイダー(Claude)の扱いが実物になって変わった点: 着手時は `AiProvider.CLAUDE` を `LlmClient` のモックへ渡して振り分けだけを
確かめていた。実物の `LlmClient` は Anthropic の URL にそのまま接続するため、振り分けの検証は OPENAI(プロジェクトのキー、
`/cloud/chat/completions`)と OLLAMA(システム設定、`/v1/chat/completions`)で行い、CLAUDE は「プロジェクトにキーが無ければ LLM へ
送らず `CLAUDE` を含むメッセージで失敗する」ことで確かめている(`generateForBridge_CLAUDEはプロジェクトにキーが無ければLLMへ送らず失敗する`・
`ArticlePlanServiceTest#suggestTitles_プロジェクトの選択プロバイダーで生成する`)。

### 追加・新設したテスト

| テスト | 内容 |
| --- | --- |
| `CurrentActorServiceTest`(新設、13 件) | subject の取り出し(JWT なし・JWT 以外・空白・クレーム無し)、Authorization ヘッダー、操作者の ID と admin 判定、1 リクエスト 1 回のキャッシュ(操作者なしもキャッシュ)、401 は操作者なし・5xx は例外 |
| `AdminAuthorizationServiceTest` | 操作者なし(ヘッダー無し)で `requireAdmin`、メンバー判定へ転送される Bearer、admin は判定を呼ばない |
| `LlmModelServiceTest` | プロバイダー別の値が無く旧値が空白、`selectProvider` が指定プロバイダーを保存して一覧を返す |
| `ProviderModelCatalogTest` | 待ち時間が負、プロジェクトにキーが無い。宛先検査(実物)を通るアドレスへの接続とループバックの拒否は、モックの `GuardedTarget` ではなく実物の `ConnectionDestinationGuard` で通す |
| `ReviewStepModelServiceTest` | システム既定プロバイダーが解決できないときの既定の候補、未知のプロバイダー(`InvalidReviewInputException`)、システム既定プロバイダーの解決 |
| `WebSearchServiceTest` | 空のバイト列のキーからのフォールバック、キーがどこにも無い・Brave が 429、静的な整形(`formatForPrompt` / `toSources` / `buildSearchNote`) |
| `AiConnectionServiceTest` | 取得元が null / 未知の接続先は `Source.NONE` のまま疎通確認する |
| `ProjectConnectionServiceTest` | 保存済みの上書きが空白だけなら上書き無し、保存してから解決結果を返す(解除を含む) |
| `OllamaPullServiceTest` / `OllamaPullJobRunnerTest` | 上書き先(宛先検査を通る別アドレス)とシステム設定の接続先の振り分け、ループバックの上書きは拒否、成功行のあとに止まったストリーム、区切りだけの接続先 |
| `ArticlePlanServiceTest` | タイトル生成の失敗時のフォールバック(無題・40 文字への切り詰め)、検索結果つきのチャット、壊れた履歴 JSON、JSON の括弧の欠け・逆順、`titles` が空でも `slugs` / `tags` があれば再試行しない、GitHub の失敗、親カテゴリつきの既存カテゴリ |
| `AiAssistServiceTest` | プロジェクトの Ollama 上書きの宣言と解除、各機能の LLM 失敗時のジョブ、未知のステップキーの失敗ジョブ、fact-check の `originalText` 空文字・検索準備での DB 障害、`categories` / `tags` が配列でない応答、括弧の欠け・逆順 |

## テストから到達できない分岐

完了時(`768b2eba`)に残る未カバー分岐は **9 件**(クラス 6 つ)。いずれも「プロダクションコードを変えずには、その分岐に入る入力や
状態を作れない」もの。

| No. | クラス | メソッド | 行 | 分岐 | 到達できない理由 |
| --- | --- | --- | ---: | --- | --- |
| 1 | `AiConnectionService` | `checkApiKey`(private) | 164 | `config != null && …` の `config != null` が偽(`config` が null) | 唯一の呼び出しは `listConnections` で、引数は `ProjectConnectionService.applyOverrides` が返した `AiConnectionsConfig` の `openai()` / `claude()`。`applyOverrides` は `hasOpenAiApiKey ? PROJECT_KEY : NO_KEY`(`hasClaudeApiKey` も同様)で、どちらも非 null の定数を返す。platform-service の応答の `openai` / `claude` が null でも、ここへは届かない。メソッドが private なので外から null を渡せない。`configured()` の真偽は両方通っている |
| 2 | `ArticlePlanService` | `parseTitles`(private) | 545 | `!node.isArray()` が真 | `readTree` に渡すのは `extractJsonArray(raw)` の結果で、`[` から `]` までの部分文字列か、`[` / `]` が無いときの `"[]"`。どちらも先頭が `[` なので、構文として読めたなら必ず配列になる(読めなければ例外で、その経路は通っている)。配列でない JSON は作れない |
| 3 | `AiAssistService` | `generateSection` | 467 | `switch (request.mode())` の `default`(`未対応のmodeです`) | この `switch` に入る前に `SECTION_PROMPT_TEMPLATES.get(request.mode())` が null なら `IllegalArgumentException` で終わる。`SECTION_PROMPT_TEMPLATES` のキーは `body` / `lead` / `lead-subsections` の 3 つだけで、`switch` の `case` と同じ。`default` は到達しない防御的な記述 |
| 4 | `AiAssistService` | `buildSearchQuery`(private) | 527 | `text == null` が真 | 呼び出し元は `ask`(`request.question()`)・`draft`(`request.text()`)・`generateSection`(文字列連結の結果)。`ask` と `draft` は、呼び出しの前に `startJob(…, Map.of("question", request.question()))` / `Map.of(…, "text", request.text())` で値を渡しており、`Map.of` は null を `NullPointerException` で拒否するため、null のまま `buildSearchQuery` に届くことはない。`generateSection` は null になりえない |
| 5 | `AiAssistService` | `generateReviewStepSuggestions(Long, ReviewStepKey, String)` | 684 | `template == null` が真 | `FACT_CHECK` は直前で別メソッドへ分岐する。残る 4 つ(`JAPANESE` / `PROOFREADING` / `READER_PERSPECTIVE` / `STYLE`)は `buildReviewStepPromptTemplates`(167・180・193・208 行)が全部 `put` している。`ReviewStepKey` のどの値でも `get` は null にならない。新しい `ReviewStepKey` を足したときだけ入る、到達しない防御的な記述 |
| 6 | `OllamaPullJobRunner` | `readStream`(private) | 168 | `System.nanoTime() - lastLineAt >= idleTimeout && stalled.compareAndSet(false, true)` の `compareAndSet` が偽 | 1 回目の無通信検知で `stalled` を真にして `lines.close()` したあと、読み取りスレッドが例外で抜けて `finally` の `watchdog.shutdownNow()` が走るまでに、見張りの次の周期(最短 10 ms)が重なったときだけ偽になる。スレッドの競合に依存し、テストから決定的に作る手段が無い。無通信の検知自体(真)は、行が来ないストリームと、成功行のあとに止まるストリームの 2 通りで通している |
| 7 | `OllamaPullJobRunner` | `readStream`(private) | 183 | `stalled.get()` が真(`finally` の後) | この分岐に入るのは、`readLines` が例外なしに戻ったうえで `stalled` が真のときだけ。`stalled` を真にするのは見張りスレッドの `lines.close()` の直前で、`close()` が走るのは読み取りスレッドが次の行を待って塞がっている間である。`HttpResponse.BodyHandlers.ofLines` の `Stream` を `close()` すると、塞がっている読み取りは `UncheckedIOException` で抜ける(実測: 行が来ないストリームと、成功行のあとに止まるストリームの 2 通りとも、`catch (RuntimeException)` の `stalled.get()`、つまり 1 つ上の分岐で処理される)。残る経路は、読み取りが最後の行を読み終えて EOF で正常に戻ったちょうどその間に見張りが `stalled` を真にする場合だけで、スレッドの競合に依存し、テストから決定的に作る手段が無い。なお「例外なしで戻る」を実測で確かめたわけではなく、上の 2 通りが例外経路になることを根拠にしている |
| 8 | `ProviderModelCatalog` | `requireApiKey`(private) | 159 | `apiKey == null` が真 | 唯一の `LlmConfigProvider` 実装 `RemoteLlmConfigProvider#apiKeyFor` は、OPENAI / CLAUDE ではプロジェクトのキーが無ければ `AiServiceException` を投げ、あれば `CredentialCipher#decrypt`(`new String(…)`)の結果を返す。null は返らない。`isBlank()` の真(空白だけのキー)は通っている |
| 9 | `ProjectAiSettingsService` | `setLlmModel` | 87 | `switch (provider)` の暗黙の `default` | `AiProvider` の 3 つの値(`OLLAMA` / `OPENAI` / `CLAUDE`)はすべて `case` にあり、3 つとも通っている。列挙型の `switch` 文のバイトコードが持つ暗黙の `default` は、`AiProvider` に新しい値を足したときだけ入る |

### 参考: 到達できなくなった・できない記述(分岐ではない)

JaCoCo の `BRANCH` には出ないが、実物を通して分かったもの。

- `IdentityBridgeClient` / `ProjectBridgeClient` / `PlatformServiceClient` の `RestClientException` を `IdentityServiceUnavailableException` /
  `IllegalStateException` に包む記述は、これらのクラス(`com.letsblog.ai.client`)の側のテストが担う。`service` のテストでは、HTTP フェイクが
  5xx を返したときに上位まで伝わることを `CurrentActorServiceTest`・`AiConnectionServiceTest` で確かめている。
- `service` のテストが他パッケージ(`ai/`・`controller/`・`integration/`)のクラスを主対象にするテストは、この Issue の範囲外(#1824)。
  それらがまだ `service` のクラスをモックしている(`RemoteLlmConfigProviderTest` が `PlatformServiceClient` / `CurrentActorService` /
  `ProjectAiSettingsService` / `CredentialCipher` を、`OllamaPullIntegrationTest` が `OllamaPullJobRunner` などを `@MockitoBean` にしている)
  ことは確認しており、#1824 で扱う。
