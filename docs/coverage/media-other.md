# services/media の `service` 以外のパッケージの分岐カバレッジ(#1820)

Epic #1711 の子 Issue #1820 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`services/media` の `com.letsblog.media` 配下のうち
`service` を除くパッケージ(`ai`・`client`・`config`・`controller`・`domain`・`dto`・`messaging`・`render`・
`repository` とルートのクラス)に適用した結果。プロダクションコードは変更していない
(この Issue の差分は `services/media/src/test/**` と本ファイルだけ)。`service` パッケージは #1819
(`docs/coverage/media-service.md`)が受け持つ。

このファイルは次の 3 節で構成する。

1. 計測値(着手時・完了時)
2. モックの境界
3. テストから到達できない分岐

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(CLAUDE.md → Coverage のとおり、`&&` / `||` もバイトコード上は別の分岐なので、
C1 と C2 を兼ねる)。値は `jacocoTestReport.xml` の `<package name="com/letsblog/media/...">` 直下の
`<counter type="BRANCH">` から取り、`service` を除いて足す。`services/media` のテスト全体を 1 回走らせた結果なので、
他パッケージを間接的に通すテストも数に入る。

計測コマンド(着手時・完了時とも同じ):

```bash
./gradlew :services:media:test :services:media:jacocoTestReport
```

計測条件: ホストの MySQL(`127.0.0.1:3306`、ADR-0006・#1769)が起動した状態で実行した(`integration/` のテストが実 MySQL を使う)。
完了時の `services/media` 全体は 851 件、失敗 0、エラー 0、skipped 0
(`build/test-results/test/*.xml` の `tests` / `failures` / `errors` / `skipped` の合計)。

`messaging`・ルート(`com/letsblog/media`)のパッケージには `BRANCH` カウンタが無い(分岐を持たないクラスだけ。
`messaging` は `LINE` のみ 13 行、ルートは `MediaApplication` の 1 クラス)ので、下の表に行が無い。

### 着手時

| 項目 | 値 |
| --- | --- |
| コミット | `3f829b85`(develop 起点、ブランチ `test/1820-media-other-coverage` の最初のコミット `311717ed` の親。変更なし。別の作業ツリーに取り出して計測した) |
| コマンド | 上のとおり |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `ai` | 121 | 2 | 98.4% |
| `client` | 47 | 17 | 73.4% |
| `config` | 13 | 3 | 81.3% |
| `controller` | 105 | 8 | 92.9% |
| `domain` | 0 | 2 | 0.0% |
| `dto` | 10 | 8 | 55.6% |
| `render` | 27 | 3 | 90.0% |
| `repository` | 8 | 0 | 100.0% |
| **合計** | **331** | **43** | **88.5%** |

Issue 起票時の静的な数では、テスト(`service/` を含む)65 ファイルのうち 33 ファイルが Mockito を使い、その中で自前のクラス(`AdminAuthorizationService`・
`IdentityClient`・`IdentityBridgeClient`・`ServiceTokenClient`・`MediaGarbageCollectionService`・
`GeneratedImageCreationService`・`GeneratedImageFolderService`・`ImageModelService`・`ComfyUiClient`・
`ChatGptImageClient`・`GenerationJobClient` など)をモックしているものがあった。モックの上で動いていた分岐は、
行カバレッジの上では「通っている」ように見えても実際には動いていない。

### 完了時

| 項目 | 値 |
| --- | --- |
| コミット | `53333f5b`(テストの最終コミット。この後のコミットは本ファイルの追加だけで、テストとプロダクションコードは変わらない) |
| コマンド | 上のとおり |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `ai` | 123 | 0 | 100.0% |
| `client` | 63 | 1 | 98.4% |
| `config` | 16 | 0 | 100.0% |
| `controller` | 113 | 0 | 100.0% |
| `domain` | 2 | 0 | 100.0% |
| `dto` | 18 | 0 | 100.0% |
| `render` | 28 | 2 | 93.3% |
| `repository` | 8 | 0 | 100.0% |
| **合計** | **371** | **3** | **99.2%** |

分母(covered + missed)は着手時 374、完了時 374 で変わらない(プロダクションコードを変えていない)。残る 3 は、すべて下の
「テストから到達できない分岐」に載せた。

やったこと:

- 自前のクラスをモックしていたテストを、実物を使う形に書き直した。`ai`・`client`・`controller`・`dto` のテストは
  `testsupport/MediaWorld`(実物のサービス・クライアントを組み、HTTP は `FakeHttpServer`、DB・MQ だけ Mockito)を使う。
  `@SpringBootTest` の `integration/` は、共通の土台 `testsupport/ServiceStubbedIntegrationTest` に寄せ、外部サービスの
  接続先(`app.*-service-uri`・Keycloak のトークン)を `testsupport/ServiceStubs` のフェイク HTTP サーバーに向けて、
  実物の `IdentityClient` / `IdentityBridgeClient` / `MediaGarbageCollectionService` などを通す。
  `@MockitoBean` で残したのは `JwtDecoder`(Keycloak の鍵取得)だけ。
- 足りなかった分岐のテストを足した: `ProjectServiceClientTest`・`ClientEmptyResponseTest`(本文の無い 200・Bearer の有無)・
  `ProjectImageSettingsControllerTest`・`ImageGenerationControllerTest`・`UpdateImageGenerationSizeDefaultsRequestTest`・
  `GeneratedImageSequenceTest`(`domain`)・`KeycloakRealmRoleConverterTest`・`PlantUmlEncoderTest`、および既存の
  `ChatGptImageClientTest`・`ComfyUiClientToolsTokenTest`・`DiagramControllerTest`・`RenderBodySizeLimitFilterTest` への追加。
- `services/media/build.gradle` に `testRuntimeOnly 'org.reactivestreams:reactive-streams:1.0.4'` を足した(テスト専用)。
  `CmsBridgeClient#uploadMedia` を実物で通すと、本番の実行時クラスパスに `reactive-streams` が無いために
  `NoClassDefFoundError` になる(#1859)。プロダクションコードは変えないので、テストのクラスパスにだけ足している。
  #1859 で本番側が直ったら外す。

### 突き合わせの手順(受け入れ基準 1・3 の再現)

`jacocoTestReport.xml` から、`service` を除くパッケージ別の分岐数と、残る未カバー分岐を行ごとに取り出す。

```bash
python3 -I - <<'EOF'
import xml.etree.ElementTree as ET
root = ET.parse('services/media/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
tc = tm = 0
for pkg in root.findall('package'):
    name = pkg.get('name').replace('com/letsblog/media', '').strip('/') or '(root)'
    if name.split('/')[0] == 'service':
        continue
    for c in pkg.findall('counter'):
        if c.get('type') == 'BRANCH':
            print(name, 'BRANCH covered', c.get('covered'), 'missed', c.get('missed'))
            tc += int(c.get('covered')); tm += int(c.get('missed'))
    for sf in pkg.findall('sourcefile'):
        for line in sf.findall('line'):
            if int(line.get('mb')) > 0:
                print(name, sf.get('name'), line.get('nr'), 'missed', line.get('mb'), 'covered', line.get('cb'))
print('TOTAL covered', tc, 'missed', tm)
EOF
```

完了時(`53333f5b`)の出力のうち未カバーの行は次のとおりで、`missed` の合計 3 が下の表の行数(3 行)と 1 対 1 に対応する。

```text
client ProjectServiceClient.java 76 missed 1 covered 3
render PlantUmlClient.java 71 missed 1 covered 1
render RechartsRenderer.java 152 missed 1 covered 1
TOTAL covered 371 missed 3
```

注意: JaCoCo は、ブロックの最後まで実行が進んだときにだけそのブロックをカバー済みにする。例外を投げて終わる呼び出しだけを
通しても、そのブロックの後ろの分岐は未カバーのまま残る。

## モックの境界

`service/` を除く `src/test/java/com/letsblog/media/` で、`@Mock` / `@MockitoBean` / `@MockitoSpyBean` / `mock()` / `spy()` で
Mockito のモックにしている型は次の 9 種で、すべて外部との通信にあたる。`spy()` / `@MockitoSpyBean` は使っていない。

| 型 | 外部通信の種類 | 使っているテスト(`service/` を除く) |
| --- | --- | --- |
| `GeneratedImageRepository` | DB(Spring Data のリポジトリ。自前のコードを持たないインタフェース) | `testsupport/MediaWorld`(保存した行をメモリに持つ)、`dto/ResponseDateTimeSerializationTest` |
| `ProjectImageSettingsRepository` | DB(同上) | `testsupport/MediaWorld` |
| `GeneratedImageSequenceRepository` | DB(同上) | `testsupport/MediaWorld`、`ai/GeneratedImageSequenceServiceTest`、`ai/GeneratedImageStorageServiceTest`、`ai/GeneratedImageStorageServiceExtensionTest` |
| `GeneratedImageFolderRepository` | DB(同上) | `testsupport/MediaWorld` |
| `DiagramRepository` | DB(同上) | `controller/DiagramControllerTest`、`dto/ResponseDateTimeSerializationTest` |
| `RabbitTemplate` | MQ への送信 | `testsupport/MediaWorld`(実物の `DomainEventPublisher` / `AuditLogService` へ渡す) |
| `JwtDecoder` | Keycloak の鍵取得を伴う JWT の検証(HTTP) | `testsupport/ServiceStubbedIntegrationTest`(`integration/` の全 `@SpringBootTest`) |
| `Browser` | 外部プロセス(Playwright が起動するヘッドレス Chromium) | `controller/RenderControllerTest`、`controller/RenderControllerConcurrentAccessTest`、`render/RechartsRendererTest`、`render/RechartsRendererConcurrentAccessTest` |
| `Page` | 外部プロセス(同上。`Browser#newPage()` の戻り) | `controller/RenderControllerTest`、`controller/RenderControllerConcurrentAccessTest`、`render/RechartsRendererTest`、`render/RechartsRendererConcurrentAccessTest` |

モックにしていない外部通信:

- HTTP(identity・project・publishing・ai・platform の各サービス、ComfyUI、OpenAI、PlantUML): サーバー側を模す層。
  `testsupport/FakeHttpServer`(JDK の `HttpServer`)と、`MockRestServiceServer`(`ai/ChatGptImageClientTest` ほか)、
  `testsupport/ServiceStubs`(`@SpringBootTest` 用に JVM で共有する `FakeHttpServer`)。実物の `*Client` がその HTTP を通る。
- DB: `integration/` の `@SpringBootTest` は実 MySQL(ADR-0006)。

補足: `ai/` の一部のテストが持つ `ImageGenerationConfigProvider` の手書きの実装(`StubConfigProvider` や無名クラス)は、
Mockito のモックではない(CLAUDE.md の記述 / 受け入れ基準 2 の対象外)が、設定の取得先を固定する小さな代役である。
`ComfyUiClient` / `ChatGptImageClient` の分岐を通すのが目的で、`PlatformServiceClient` の分岐は `client/` のテスト
(`PlatformServiceClientProjectUrlTest`・`PlatformServiceClientConnectionGuardTest`・`ClientEmptyResponseTest`)が実物で通す。

`service/` のテストが使う型(同じ境界で、`GeneratedImageRepository` ほか)は `docs/coverage/media-service.md` を参照。

## テストから到達できない分岐

完了時に残る未カバー分岐は 3 件(上の突き合わせの出力と 1 対 1)。いずれも、プロダクションコードを変えずには、
その分岐に入る入力や状態を作れない。

| No. | クラス | メソッド | 行 | 未カバーの分岐 | 理由 |
| ---: | --- | --- | ---: | --- | --- |
| 1 | `client.ProjectServiceClient` | `bodyOrMessage` | 76 | `body != null && !body.isBlank()` の `body == null` 側 | `RestClientResponseException#getResponseBodyAsString()` は、本文が無いときも `null` ではなく空文字列を返す(Spring の `RestClientResponseException` は本文 `byte[]` を `null` のとき空配列にして保持する)。呼び出し元は `RestClientResponseException` だけを渡すので、HTTP の応答をどう作っても `body` は `null` にならない。`null` を作るには例外クラスを偽装するしかなく、それは自前ではない型の振る舞いを書き換える(境界外のモック)ことになる。分岐は防御的な null 検査。 |
| 2 | `render.PlantUmlClient` | `rejectionDetail` | 71 | `e.getResponseHeaders() == null ? null : ...` の `getResponseHeaders() == null` 側 | `rejectionDetail` は `RestClientResponseException` が HTTP 応答から作られたときだけ呼ばれ、このとき Spring は応答の `HttpHeaders` を必ず渡す(`null` にならない)。ヘッダー無しの応答(`sendResponseHeaders(status, -1)` で本文もヘッダーも付けない)でも `HttpHeaders` は空のオブジェクトで、`null` ではない。`null` を持つ `RestClientResponseException` を作れるのは、例外を直接 `new` するときだけで、呼び出し元の `renderPng` は HTTP の層から来た例外しか渡さない。 |
| 3 | `render.RechartsRenderer` | `loadBundleJs` | 152 | synchronized ブロック内の `if (bundleJs == null)` の `false`(すでに別スレッドが読み込み済み)側 | ダブルチェック・ロックの 2 回目の検査。`loadBundleJs()` の唯一の呼び出し元は `render()` で、`render()` は全体を `synchronized (this)` で直列化している(`RechartsRenderer` の Javadoc、issue #1047)。そのため `loadBundleJs()` 内の `synchronized (this)` には常に 1 スレッドしか入らず、1 回目の検査(`cached != null`)を通ったあとに別スレッドが先に読み込み済みにする競合が成立しない。`loadBundleJs()` は `private` で、リフレクションで直接呼べば入れるが、それは実際の呼び出し経路ではない状態を作ることになるので、テストにしていない。 |

(No.1・2 は `RestClientResponseException` が HTTP 応答から作られる限り成り立たない防御的な null 検査、No.3 は `render()` の
直列化で成り立たない競合のための再検査。)
