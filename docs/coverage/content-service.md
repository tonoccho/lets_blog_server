# services/content `com.letsblog.content.service` の分岐カバレッジ(#1821)

Epic #1711 の子 Issue #1821 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`services/content` の
`com.letsblog.content.service` パッケージ(34 ファイル)に適用した結果。プロダクションコードは変更していない
(この Issue の差分は `services/content/src/test/**` と本ファイルだけ)。

このファイルは次の 3 節で構成する。

1. 計測値(着手時・完了時)
2. モックの境界
3. テストから到達できない分岐

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(CLAUDE.md → Coverage のとおり、`&&` / `||` もバイトコード上は別の分岐なので、
C1 と C2 を兼ねる)。値は `jacocoTestReport.xml` の `<package name="com/letsblog/content/service">` 直下の
`<counter type="BRANCH">` から取る。`services/content` のテスト全体を 1 回走らせた結果なので、`service` のクラスを
間接的に通す他パッケージ(`controller/`・`integration/` など)のテストも数に入る。`service` に下位パッケージは無い。

計測コマンド(着手時・完了時とも同じ):

```bash
./gradlew :services:content:cleanTest :services:content:test :services:content:jacocoTestReport
```

計測条件: ホストの MySQL(`127.0.0.1:3306`、ADR-0006・#1769)が起動した状態で実行した(`integration/` のテストが実 MySQL を使う)。
完了時の `services/content` 全体は 547 件、失敗 0、エラー 0、skipped 0
(`build/test-results/test/*.xml` の `tests` / `failures` / `errors` / `skipped` の合計)。

### 着手時

| 項目 | 値 |
| --- | --- |
| コミット | `c3ccd4c9`(develop 起点、ブランチ `test/1821-content-service-coverage` の作成直後。変更なし) |
| コマンド | 上のとおり |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/content/service` | 435 | 118 | 78.7% |

着手時の未カバーのクラス別内訳(missed の数。合計 118):

| クラス | missed |
| --- | ---: |
| `RechartsTagRenderService` | 26 |
| `CustomTagService` | 22 |
| `AmazonTagRenderService` | 16 |
| `CustomTagValidationService` | 11 |
| `TocStyleRenderService` | 10 |
| `BlogCardTagRenderService` | 8 |
| `CurrentActorService` | 8 |
| `CustomTagRenderService` | 5 |
| `AdminAuthorizationService` | 4 |
| `RenderedContentWrapperService` | 3 |
| `PlantUmlTagRenderService` | 2 |
| `PlantUmlEmbedService` | 1 |
| `CustomTagGenerationService` | 1 |
| `ProjectContentSettingsService` | 1 |

着手時のテスト: `service/` 配下 21 ファイル、`@Test` / `@ParameterizedTest` 226 件。うち 18 ファイルが Mockito を使い、
自前のクラス(後述の「着手時にモックの境界の外だった型」)をモックしていた。モックの上で動いていた分岐は、
行カバレッジの上では「通っている」ように見えても実際には動いていない(たとえば `CurrentActorService` 自体のテストは
無く、`TocStyleRenderService#buildStyle` は `CustomTagServiceTest` が `TocStyleRenderService` をモックしていたため
一度も動いていなかった)。

### 完了時

| 項目 | 値 |
| --- | --- |
| コミット | `b086182c`(テストの最終コミット。この後のコミットは本ファイルの追加だけで、テストとプロダクションコードは変わらない) |
| コマンド | 上のとおり |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/content/service` | 547 | 6 | 98.9% |

分母(covered + missed)は着手時も完了時も 553 で変わらない(プロダクションコードを変えていない)。残る 6 は、すべて下の
「テストから到達できない分岐」に載せた(クラス別: `AmazonTagRenderService` 2、`CustomTagValidationService` 2、
`CustomTagRenderService` 1、`RechartsTagRenderService` 1)。

完了時の `service/` 配下は 24 ファイル、`@Test` 343 件。テストの共有部品は
`services/content/src/test/java/com/letsblog/content/testsupport/`(`ContentWorld`・`FakeHttpServer`・`AsyncProxies`・
`BrokenObjectMapper`・`SharedCircuitBreakers`)と、`.../contentcache/TestOutboundUrlGuards.java` に置いた。

### 突き合わせの手順(受け入れ基準 3 の再現)

`jacocoTestReport.xml` から、`service` の分岐数と、残る未カバー分岐を行ごとに取り出す。

```bash
python3 -I - <<'EOF'
import xml.etree.ElementTree as ET
root = ET.parse('services/content/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
for pkg in root.findall('package'):
    if pkg.get('name') != 'com/letsblog/content/service':
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

完了時(`b086182c`)の出力は次のとおりで、`missed` の合計 6 が下の表の行数(6 行)と 1 対 1 に対応する
(`CustomTagValidationService.java 89` の 2 は、表では 2 つの分岐 No.2・No.3 に分けて載せた)。

```text
BRANCH covered 547 missed 6
CustomTagRenderService.java 203 missed 1 covered 1
CustomTagValidationService.java 89 missed 2 covered 2
AmazonTagRenderService.java 123 missed 1 covered 1
AmazonTagRenderService.java 125 missed 1 covered 3
RechartsTagRenderService.java 277 missed 1 covered 1
```

(`sourcefile` の並びは XML の順で、毎回同じとは限らない。突き合わせるのは「ファイル名・行」の集合。)

注意: JaCoCo は、ブロックの最後まで実行が進んだときにだけそのブロックをカバー済みにする。例外を投げて終わる呼び出しだけを
通したテストでは、その呼び出しの後ろの分岐が「未通過」と出る。分岐を埋めるときは、例外になる経路と、例外にならず戻る経路の
両方を通す。

## モックの境界

`com.letsblog.content.service` のテスト(`services/content/src/test/java/com/letsblog/content/service/` 配下と、
それらが共有する `.../testsupport/` 配下)で、Mockito のモック(`@Mock` / `@MockitoBean` / `@MockitoSpyBean` /
`mock()` / `spy()`)にしている型は次の **7 つだけ**。数え上げの確認コマンドと完了時(`b086182c`)の出力:

```bash
cd services/content/src/test/java/com/letsblog/content
{
  for f in service/*.java testsupport/*.java; do
    awk '/@Mock[[:space:]]*$|@MockitoBean|@MockitoSpyBean|@Spy[[:space:]]*$/{getline n; print n}' "$f"
  done
  grep -hoE "(Mockito\.)?(mock|spy)\([A-Za-z]+\.class" service/*.java testsupport/*.java | sed -E 's/.*\(//; s/\.class//'
} | sed -E 's/^[[:space:]]*(private|public)?[[:space:]]*(final)?[[:space:]]*//; s/[[:space:]]+[a-zA-Z]+;?[[:space:]]*$//; s/ = .*//' \
  | sort | uniq -c | sort -rn
```

```text
      2 CustomTagRepository
      1 RabbitTemplate
      1 ProjectContentSettingsRepository
      1 Page
      1 CustomTagTemplateRepository
      1 ContentCacheRepository
      1 Browser
```

| モックしている型 | どの外部通信にあたるか | 使っている場所 |
| --- | --- | --- |
| `CustomTagRepository`(Spring Data) | DB アクセス(`custom_tags`) | `testsupport/ContentWorld`(保存した行をメモリに持つ)、`CustomTagRenderServiceTest`(戻り値を指定) |
| `CustomTagTemplateRepository`(Spring Data) | DB アクセス(`custom_tag_templates`) | `testsupport/ContentWorld`(保存した行をメモリに持つ。JPQL の検索は `CustomTagTemplateServiceTest` が戻り値を指定) |
| `ProjectContentSettingsRepository`(Spring Data) | DB アクセス(`project_content_settings`) | `testsupport/ContentWorld`(保存した行をメモリに持つ) |
| `ContentCacheRepository`(Spring Data) | DB アクセス(`content_cache`) | `testsupport/ContentWorld`(保存した行をメモリに持つ) |
| `RabbitTemplate` | MQ への送信(監査ログの発行) | `testsupport/ContentWorld`(実物の `AuditLogService` に渡す) |
| `com.microsoft.playwright.Browser` | 外部プロセス(ヘッドレス Chromium) | `testsupport/ContentWorld`(`newPage()` が `Page` のモックを返す) |
| `com.microsoft.playwright.Page` | 外部プロセス(ヘッドレス Chromium) | `testsupport/ContentWorld`(`navigate` で開いた URL の HTML を `content()` が返す。実物の `PlaywrightPageFetcher` がこれを使う) |

`service/` 配下のテストファイルで `@Mock` を持つのは `CustomTagRenderServiceTest`(`CustomTagRepository`)だけで、
`@ExtendWith(MockitoExtension.class)` を持つのもこの 1 ファイルだけ。`CustomTagTemplateServiceTest` は
`world.customTagTemplateRepository`(上の表のリポジトリ)に対してだけ `Mockito.when` を使う。

Mockito 以外の差し替え(すべて外部との通信か、フレームワークの入口を模すもの。自前のクラスではない):

| 部品 | 何を差し替えるか | 備考 |
| --- | --- | --- |
| `testsupport/FakeHttpServer`(JDK の `HttpServer` をループバックで起動) | HTTP の相手(identity-service・project-service・media-service・ai-service・トークンエンドポイント) | サーバー側を模す層。`IdentityClient`・`IdentityBridgeClient`・`ProjectBridgeClient`・`MediaRenderClient`・`AiGenerationClient`・`GenerationJobClient`・`ServiceTokenClient` は実物がこれに向く |
| `contentcache/TestOutboundUrlGuards` | `OutboundUrlGuard` の名前解決(DNS) | `OutboundUrlGuard` の分類(`isBlocked`)は実物のまま。package-private コンストラクタに届くよう同じパッケージ名で置いた |
| `testsupport/BrokenObjectMapper` | 実物の `ObjectMapper` に、必ず失敗するシリアライザ(`SimpleModule`)を登録したもの | 「JSON 化に失敗しても空の JSON(`{}`)で続ける」分岐を通す。`ObjectMapper` を Mockito で模してはいない |
| `testsupport/AsyncProxies` | `@Async` を Spring の実際の AOP プロキシ(`AsyncAnnotationAdvisor`)で効かせ、実行先の `Executor` だけを渡す | ジョブのランナーは実物をそのまま包む。満杯の `Executor` で `TaskRejectedException` を起こす |
| `testsupport/SharedCircuitBreakers` | JVM 全体で共有されるサーキットブレーカーを閉じた状態へ戻す | テストの成否が実行順に左右されないようにするため(`ContentWorld` が毎回呼ぶ) |
| `MockHttpServletRequest` / `Jwt` / `JwtAuthenticationToken`(Spring Test / Spring Security) | リクエストの入口と認証 | Mockito のモックではない(Issue の定める「Spring Test が提供する実装」) |

### 着手時にモックの境界の外だった型(書き直したもの)

着手時(`c3ccd4c9`)に `service/` のテストがモックしていた型は次のとおり(出現数)。**リポジトリ(DB)の 3 種を除く 23 種**は
自前のクラスで、すべて実物を使う形に書き直した。

| 着手時にモックしていた型(出現数) | 完了時 |
| --- | --- |
| `CurrentActorService`(9)・`AdminAuthorizationService`(4)・`ProjectContentSettingsService`(2)・`LetsblogSyncNotifier`(2)・`RenderedContentWrapperService`(1)・`CustomTagService`(1)・`CustomTagRenderService`(1)・`CustomTagGenerationService`(1)・`CustomTagGenerationJobRunner`(1)・`CustomTagValidationService`(2)・`TocStyleRenderService`(2)・`BlogCardTagRenderService`(2)・`AmazonTagRenderService`(2)・`RechartsTagRenderService`(1)・`PlantUmlTagRenderService`(1)・`PlantUmlEmbedService`(1)・`MarkdownRenderer`(1) | 実物(`ContentWorld` が組み立てる) |
| `ProjectBridgeClient`(8)・`IdentityBridgeClient`(1)・`MediaRenderClient`(3)・`AiGenerationClient`(1)・`GenerationJobClient`(2) | 実物の `*Client`。相手の HTTP を `FakeHttpServer` で模す |
| `ContentCacheService`(2) | 実物。DB は `ContentCacheRepository` のモック、スクレイピング先のページは Playwright の `Browser` / `Page` のモック、名前解決は `TestOutboundUrlGuards` |
| `CustomTagRepository`(4)・`CustomTagTemplateRepository`(1)・`ProjectContentSettingsRepository`(1) | 境界の内側(DB)なので引き続きモック。`ContentWorld` がメモリ上の行を持つ形に統一した |

書き直した 18 ファイルのうち、アサーションを減らしたものは無い。プロダクションコードが実際に取る経路に合わせて期待値を
変えたのは次の 2 点(どちらもテストケース側の前提がモックだったためで、振る舞いの検証は残している):

- `PlantUmlEmbedServiceTest`: 旧テストは `MediaRenderClient` のモックが成功を返す 1 件だけだった。実物の
  `MediaRenderClient` は、通信の失敗を `SyncServiceException` のまま返さず `AiServiceException` /
  `PlantUmlRenderRejectedException` に包むため、`embedDiagramsForPreview` のプレースホルダへ差し替える分岐に入らず、
  media-service の障害は例外として呼び出し元へ伝わる。この実際の挙動をテストに固定した(Javadoc との食い違いは
  Issue #1860 に起票)。
- `ArticlePreviewServiceTest`: 旧テストは `InvalidPlantUmlTagException` をモックから投げていたが、本番コードはこの
  例外をどこからも投げない。実物のパイプラインで到達できる `PlantUmlServerUnavailableException`(media の 503)と
  `PlantUmlRenderRejectedException`(media の 400)の 2 つで同じ表示の検証を続けた。

### 追加・新設したテスト

| テスト | 内容 |
| --- | --- |
| `CurrentActorServiceTest`(新設、17 件) | subject の取り出し、操作者のキャッシュ(1 リクエスト 1 回)、401 は操作者なし・5xx は例外、`X-Forwarded-For`、`User-Agent` |
| `RenderedContentWrapperServiceTest`(新設、4 件) | null・空、projectId の有無、プレフィックスの設定 / slug へのフォールバック |
| `AuditLogServiceTest`(新設、2 件) | 監査ログの発行内容、キューへの発行失敗を握りつぶす |
| `AdminAuthorizationServiceTest` | `accessibleSiteIds`(admin / 非 admin / 操作者なし)、メンバー判定へ転送される Bearer |
| `TocStyleRenderServiceTest` | null・空の HTML、閉じタグが無い目次、`buildStyle` の customCss の有無 |
| `BlogCardTagRenderServiceTest`・`AmazonTagRenderServiceTest` | 実物の `ContentCacheService`(キャッシュ行・スクレイピング・内部アドレスの拒否)を通した描画、デザインの customCss / テンプレート、`http://`、円マークの補い方、空の項目 |
| `RechartsTagRenderServiceTest` | 属性・サイズ・テーマ・円グラフ・行数上限・レスポンシブ化・media-service の失敗 / 空の応答までの全分岐 |
| `CustomTagServiceTest` | CSS 走査(コメント・文字列・入れ子の at-rule・対応しない `}`・空のセレクタ)、組み込みタグの CSS を含む統合 CSS、認可 |
| `CustomTagValidationServiceTest` | null / 空白の HTML・CSS、コメント行、波括弧の不足、at-rule |
| `CustomTagRenderServiceTest` | 空の内容、1 段落の `<p>` 除去の条件、同名タグの選択 |
| `CustomTagGeneration*Test` | LLM の応答の抽出・検証、ジョブの終端通知(Bearer はサービス自身のトークン)、`@Async` の実プロキシでの満杯、JSON 化の失敗 |
| `LetsblogSyncPayloadServiceTest` | 実物の統合 CSS・タグデザインを含むペイロードとそのハッシュ、JSON 化の失敗 |

## テストから到達できない分岐

完了時(`b086182c`)に残る未カバー分岐は **6 件**(クラス 4 つ)。いずれも「プロダクションコードを変えずには、その分岐に入る入力や
状態を作れない」もの。

| No. | クラス | メソッド | 行 | 分岐 | 到達できない理由 |
| --- | --- | --- | ---: | --- | --- |
| 1 | `CustomTagRenderService` | `parseAttrs`(private) | 203 | `attrPart == null` が真 | 唯一の呼び出しは `expand` で、引数は `BLOCK_TAG_PATTERN` / `INLINE_TAG_PATTERN` の `matcher.group(2)`。この群は `([^\]\n]*)` で、パターン全体が一致したときは空文字を含めて必ず参加するので null にならない。メソッドが private なので外から null を渡せない |
| 2 | `CustomTagValidationService` | `validateCss`(private) | 89 | `cssContent == null` が真 | 唯一の呼び出しは `validate`(28 行)で、`cssContent != null && !cssContent.isBlank()` のときだけ呼ぶ。同じ検査を `validateCss` の先頭で繰り返す防御的な検査なので、null は渡らない |
| 3 | `CustomTagValidationService` | `validateCss`(private) | 89 | `cssContent.isBlank()` が真 | No.2 と同じ。空白だけの CSS は `validate` が弾いてから呼ぶため渡らない |
| 4 | `AmazonTagRenderService` | `renderCard`(private) | 123 | `response.lastCheckedAt() == null` が真 | `response` は `ContentCacheService#resolve` の `toResponse` が返し、`lastCheckedAt` は `UtcDateTimes.toInstant(cache.getLastCheckedAt())`。`content_cache.last_checked_at` は NOT NULL で、`resolve` 自身も `isStale` で同じ値を参照しており、null なら先に NullPointerException になる。実物の `ContentCacheService` を通す限り null の応答は作れず、`ContentCacheService` を差し替える(モックする)以外に入れない |
| 5 | `AmazonTagRenderService` | `renderCard`(private) | 125 | `fetchedAt.isEmpty()` が真(`price.isEmpty()` との `||` の左辺) | `fetchedAt` が空になるのは No.4 の `lastCheckedAt == null` のときだけ(`DateTimeFormatter#format` は空文字を返さない)。No.4 と同じ理由で作れない。右辺 `price.isEmpty()` は通っている |
| 6 | `RechartsTagRenderService` | `parseAttrs`(private) | 277 | `attrPart == null` が真 | 唯一の呼び出しは `render` で、引数は `RECHARTS_BLOCK_PATTERN` の `matcher.group(1)`(`([^\]\n]*)`)。No.1 と同じく null にならない |

### 参考: 分岐としては数えられないが、実物では到達できない記述

JaCoCo の `BRANCH` には出ない(例外表の項目で、分岐命令ではない)が、実物のクライアントを通すと使われないと分かった記述。
`BRANCH` の突き合わせには影響しないが、Issue #1860 に起票した。

- `PlantUmlEmbedService#renderOrPlaceholder` の `catch (SyncServiceException e)`: `MediaRenderClient#renderPlantUml` が
  `SyncServiceException` を `AiServiceException` / `PlantUmlRenderRejectedException` に包んで投げるため、入らない。
- `ArticlePreviewService#renderHtml` のマルチ catch の `InvalidPlantUmlTagException`: 本番コードのどこからも投げられない。
- `CustomTagValidationService#validateHtml` の `catch (Exception e)`(`html-parse-error`): `Jsoup.parseBodyFragment` は任意の
  文字列で例外を投げない。
- `JsonProcessingException` / `NoSuchAlgorithmException` を捕まえて `IllegalStateException` にする記述(`CustomTagRenderService#markerJson`
  ・`LetsblogSyncPayloadService#sha256`): 前者は `Map<String, Object>` の書き出しで失敗せず、後者は SHA-256 が必ず存在する。
