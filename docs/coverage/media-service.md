# services/media `com.letsblog.media.service` の分岐カバレッジ(#1819)

Epic #1711 の子 Issue #1819 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`services/media` の
`com.letsblog.media.service` パッケージ(41 ファイル)に適用した結果。プロダクションコードは変更していない
(この Issue の差分は `services/media/src/test/**` と本ファイルだけ)。

このファイルは次の 3 節で構成する。

1. 計測値(着手時・完了時)
2. モックの境界
3. テストから到達できない分岐

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(CLAUDE.md → Coverage のとおり、`&&` / `||` もバイトコード上は別の分岐なので、
C1 と C2 を兼ねる)。値は `jacocoTestReport.xml` の `<package name="com/letsblog/media/service">` 直下の
`<counter type="BRANCH">` から取る。`services/media` のテスト全体を 1 回走らせた結果なので、`service` のクラスを
間接的に通す他パッケージ(`controller/`・`integration/` など)のテストも数に入る。`service` に下位パッケージは無い。

計測コマンド(着手時・完了時とも同じ):

```bash
./gradlew :services:media:cleanTest :services:media:test :services:media:jacocoTestReport
```

計測条件: ホストの MySQL(`127.0.0.1:3306`、ADR-0006・#1769)が起動した状態で実行した(`integration/` のテストが実 MySQL を使う)。
完了時の `services/media` 全体は 777 件、失敗 0、エラー 0、skipped 0
(`build/test-results/test/*.xml` の `tests` / `failures` / `errors` / `skipped` の合計)。

### 着手時

| 項目 | 値 |
| --- | --- |
| コミット | `e73f7424`(develop 起点、ブランチ `test/1819-media-service-coverage` の作成直後。変更なし) |
| コマンド | 上のとおり(着手時は `cleanTest` 無しで実行。全テストが走ったことは 697 件・失敗 0 で確認) |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/media/service` | 453 | 85 | 84.2% |

着手時の未カバーのクラス別内訳(missed の数。合計 85):

| クラス | missed |
| --- | ---: |
| `ImageResizeService` | 47 |
| `ProjectImageDefaultsResolver` | 11 |
| `CurrentActorService` | 11 |
| `ImageModelService` | 6 |
| `MediaGarbageCollectionService` | 6 |
| `SafetyNegativePromptService` | 2 |
| `ImageGenerationJobTracker` | 1 |
| `MediaGarbageCollectionJobRunner` | 1 |

着手時のテスト: `service/` 配下 25 ファイル、`@Test` / `@ParameterizedTest` 256 件。うち 18 ファイルが Mockito を使い、
自前のクラス(後述の「着手時にモックの境界の外だった型」)をモックしていた。モックの上で動いていた分岐は、
行カバレッジの上では「通っている」ように見えても実際には動いていない(たとえば `ImageGenerationService` は
着手時の未カバーが 0 だったが、協力者がすべてモックだった)。

### 完了時

| 項目 | 値 |
| --- | --- |
| コミット | `ce5f6b1b`(テストの最終コミット。この後のコミットは本ファイルの追加だけで、テストとプロダクションコードは変わらない) |
| コマンド | 上のとおり |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/media/service` | 527 | 11 | 98.0% |

分母(covered + missed)は着手時も完了時も 538 で変わらない(プロダクションコードを変えていない)。残る 11 は、すべて下の
「テストから到達できない分岐」に載せた(クラス別: `ImageResizeService` 7、`GeneratedImageUploadService` 1、
`SafetyNegativePromptService` 1、`ImageGenerationJobRunner` 1、`MediaGarbageCollectionJobRunner` 1)。

完了時の `service/` 配下は 29 ファイル、`@Test` / `@ParameterizedTest` 336 件。テストの共有部品は
`services/media/src/test/java/com/letsblog/media/testsupport/`(`MediaWorld`・`FakeHttpServer`・`AsyncProxies`・
`BrokenObjectMapper`・`ChatGptEndpointPlatformServiceClient`・`SharedCircuitBreakers`・`ImageWriters`)に置いた。

### 突き合わせの手順(受け入れ基準 3 の再現)

`jacocoTestReport.xml` から、`service` の分岐数と、残る未カバー分岐を行ごとに取り出す。

```bash
python3 -I - <<'EOF'
import xml.etree.ElementTree as ET
root = ET.parse('services/media/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
for pkg in root.findall('package'):
    if pkg.get('name') != 'com/letsblog/media/service':
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

完了時(`ce5f6b1b`)の出力は次のとおりで、`missed` の合計 11 が下の表の行数(11 行)と 1 対 1 に対応する
(`ImageResizeService.java 470` の 2 は、表では 2 つの分岐 No.2・No.3 に分けて載せた)。

```text
BRANCH covered 527 missed 11
GeneratedImageUploadService.java 102 missed 1 covered 1
ImageResizeService.java 470 missed 2 covered 4
ImageResizeService.java 474 missed 1 covered 1
ImageResizeService.java 479 missed 1 covered 7
ImageResizeService.java 483 missed 1 covered 3
ImageResizeService.java 503 missed 1 covered 3
ImageResizeService.java 511 missed 1 covered 1
SafetyNegativePromptService.java 92 missed 1 covered 3
ImageGenerationJobRunner.java 84 missed 1 covered 1
MediaGarbageCollectionJobRunner.java 108 missed 1 covered 1
```

(`sourcefile` の並びは XML の順で、毎回同じとは限らない。突き合わせるのは「ファイル名・行」の集合。)

注意: JaCoCo は、ブロックの最後まで実行が進んだときにだけそのブロックをカバー済みにする。例外を投げて終わる呼び出しだけを
通したテストでは、その呼び出しの後ろの分岐が「未通過」と出る。分岐を埋めるときは、例外になる経路と、例外にならず戻る経路の
両方を通す。

## モックの境界

`com.letsblog.media.service` のテスト(`services/media/src/test/java/com/letsblog/media/service/` 配下と、
それらが共有する `.../testsupport/` 配下)で、Mockito のモック(`@Mock` / `@MockitoBean` / `@MockitoSpyBean` /
`mock()` / `spy()`)にしている型は次の **4 つだけ**。数え上げの確認コマンドと完了時(`ce5f6b1b`)の出力:

```bash
cd services/media/src/test/java/com/letsblog/media
{
  for f in service/*.java testsupport/*.java; do
    awk '/@Mock[[:space:]]*$|@MockitoBean|@MockitoSpyBean|@Spy[[:space:]]*$/{getline n; print n}' "$f"
  done
  grep -hoE "(Mockito\.)?(mock|spy)\([A-Za-z]+\.class" service/*.java testsupport/*.java | sed -E 's/.*\(//; s/\.class//'
} | sed -E 's/^[[:space:]]*(private|public)?[[:space:]]*(final)?[[:space:]]*//; s/[[:space:]]+[a-zA-Z]+;?[[:space:]]*$//; s/ = .*//' \
  | sort | uniq -c | sort -rn
```

```text
      1 RabbitTemplate
      1 ProjectImageSettingsRepository
      1 GeneratedImageSequenceRepository
      1 GeneratedImageRepository
```

4 つとも `testsupport/MediaWorld.java` が持つ(各テストは `MediaWorld` 越しに使う)。`service/` 配下のテストファイルに
`@Mock` / `@ExtendWith(MockitoExtension.class)` は 1 件も無い(`when` / `doThrow` / `verify` などの Mockito の静的メソッドを、
上の 4 つの型に対してだけ使う)。

| 型 | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `GeneratedImageRepository`(`com.letsblog.media.repository`) | Mockito `mock()`(`MediaWorld`)。`save` / `findById` はメモリ上の行を読み書きする | **DB アクセス**(Spring Data のリポジトリ。自前のコードを持たないインタフェースで、`generated_images` テーブルの読み書きそのもの) | `MediaWorld` を使う全テスト。保存の失敗(DB 障害)を作る `ImageGenerationServiceTest`、保存結果を調べる `UploadedImageTagServiceTest` などが直接 `when` / `verify` する |
| `ProjectImageSettingsRepository` | 同上 | **DB アクセス**(`project_image_settings` テーブル) | `ImageGenerationJobRunnerTest`(DB 障害の再現)・`ComfyUiModelServiceTest`・`ImageModelServiceTest`・`ProjectImageDefaultsResolverTest` ほか |
| `GeneratedImageSequenceRepository` | 同上(`insertIfAbsent` / `findByProjectKeyForUpdate` / `save` をメモリ上で再現) | **DB アクセス**(`generated_image_sequences` テーブル。ネイティブクエリ `INSERT ... ON DUPLICATE KEY UPDATE` を含む) | 画像を保存するテスト全般(`GeneratedImageCreationServiceTest` ほか) |
| `RabbitTemplate`(`org.springframework.amqp.rabbit.core`) | Mockito `mock()`(`MediaWorld` が持ち、実物の `DomainEventPublisher`・`AuditLogService` へ渡す) | **MQ との送受信**(`letsblog.events` exchange への `image.generated`、監査ログの exchange への `convertAndSend`) | `GeneratedImageCreationServiceTest`・`MediaGarbageCollectionJobRunnerTest`・`AuditLogServiceTest` ほか |

Mockito 以外で外部との通信(または Spring のフレームワークの入口・実行環境)を差し替えているものも、同じ表に載せる。いずれも
Mockito のモックではなく、自前のクラスの代わりでもない。

| 型・仕掛け | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `FakeHttpServer`(`testsupport`。JDK 標準 `com.sun.net.httpserver.HttpServer` をループバックで起動) | サーバー側フェイク。メソッド+パスごとに応答を登録し、受けた要求を記録する。`MediaWorld` が identity-service・project-service・ai-service(LLM・ジョブ・プロジェクト接続先・API キー)・platform-service・publishing-service・ComfyUI(`/prompt`・`/history`・`/view`・`/object_info/*`・`/upload/image`)・ChatGPT 画像 API・サービス間トークンのエンドポイント(Keycloak の代わり)のベース URL をすべてこの 1 台へ向ける | **HTTP 通信**。自前の `*Client`(`IdentityBridgeClient`・`ProjectServiceClient`・`AiGenerationClient`・`AiServiceConnectionClient`・`CmsBridgeClient`・`PlatformServiceClient`・`ComfyUiClient`・`ChatGptImageClient`)と、`packages/lbs-common` の `IdentityClient`・`GenerationJobClient`・`ServiceTokenClient` は実物をこのサーバーに向ける | `service/` のほぼ全て |
| `ChatGptEndpointPlatformServiceClient`(`testsupport`) | 実物の `PlatformServiceClient` を継承し、`chatGptBaseUrl()` の 1 メソッドだけを上書きしてテスト用 HTTP サーバーの URL を返す | **HTTP 通信**(ChatGPT 画像 API の接続先 URL)。本番の `chatGptBaseUrl()` は定数(`https://api.openai.com/v1`、`e2e-stubs` プロファイルでは `http://image-stub:8080`)で、設定で差し替える口が無い。プロダクションコードを変えないので、接続先の URL を返すだけの 1 メソッドに限って上書きした。ComfyUI の接続先・API キーの解決(platform-service・ai-service への HTTP)は実物がそのまま行う | `MediaWorld` 経由の全テスト(ChatGPT の経路は `ImageGenerationServiceTest`・`ImageGenerationJobRunnerTest`・`ImageGenerationServiceReferenceTest`) |
| `MockHttpServletRequest`・`MockEnvironment`(Spring Test)・`JwtAuthenticationToken`(Spring Security) | Mockito ではないテスト用の実装 | **HTTP リクエストの入口**(`Authorization`・`X-Forwarded-For`・`User-Agent` ヘッダー、JWT の `sub`)と Spring の環境。`CurrentActorService`・`OutboundAuthHeaders` は実物 | `CurrentActorServiceTest`・`AdminAuthorizationServiceTest` と、リクエストを使う全テスト |
| `AsyncProxies`(`testsupport`)+ 実際の `ThreadPoolTaskExecutor` | Spring の実際の AOP プロキシ(`AsyncAnnotationAdvisor`)で、自前のランナー・タグ付けサービスの `@Async` を効かせる。「実行を後に回す」はタスクを溜めるだけの `Executor`、「満杯」は実際の `ThreadPoolTaskExecutor`(core 1 / max 1 / queue 0)を塞いで再現する | フレームワークの入口(`@Async`)。満杯のときの `TaskRejectedException` も実物の Executor が投げる | `ImageGenerationJobStarterTest`・`GeneratedImageUploadServiceTest` |
| 保存先の一時ディレクトリ(`MediaWorld` が作成・後始末) | 差し替えない。実物の `GeneratedImageStorageService` が実際にファイルを書き・読み・削除する | **ファイルシステム** | 画像を保存・読み込むテスト全般 |
| `ImageWriters`(`testsupport`) | JDK の ImageIO のレジストリから JPEG / PNG の書き出し器を処理の間だけ外し、必ず元に戻す | 画像コーデックの有無という**実行環境の条件**(自前のクラスの差し替えでも外部との通信でもない)。書き出し器が無い環境で元のバイト列へ戻す分岐を通すためだけに使う | `ImageResizeServiceEncoderUnavailableTest` |
| `SharedCircuitBreakers`(`testsupport`) | `SyncServiceClient` が JVM 全体で共有するサーキットブレーカーを、反射で取り出して閉じた状態へ戻す(`MediaWorld` の生成時) | 外部との通信の**状態のリセット**。接続できない ai-service へ繰り返し呼び出す別のテストクラス(`integration/` のテスト)が開いたまま残すと、30 秒間は後続のテストの HTTP 呼び出しが「サーキットブレーカーが作動中」で失敗し、成否が実行順に左右された(完了時の全体実行で実際に起きた)ため | `MediaWorld` 経由の全テスト |

次の 2 つは、外部との通信ではないが、モックでもない(実物のオブジェクトに、失敗や環境の欠落を作る入力を与える)。

| 仕掛け | 内容 | 使っているテスト |
| --- | --- | --- |
| 実物の `ObjectMapper` に、必ず失敗するシリアライザ(`SimpleModule`)を登録したもの(`BrokenObjectMapper`) | 「JSON 化に失敗しても空の JSON(`{}`)で続ける」分岐を通す。`ObjectMapper` を Mockito で模してはいない | `ImageGenerationJobStarterTest`・`ImageGenerationJobTrackerTest`・`ImageGenerationJobRunnerTest`・`MediaGarbageCollectionServiceTest`・`MediaGarbageCollectionJobRunnerTest`・`ImageGenerationServiceTest` |
| `ImageGenerationJobTracker` のロックを、終端の書き込み(`complete`)の中で保持したまま別スレッドの `heartbeat()` を `BLOCKED` にし、再入できるロック保持者が対象ジョブを終端にする | 「周回の途中で終端になったジョブにはハートビートを書かない」分岐を、スレッドの状態(`Thread.getState()`)で同期して決定的に通す | `ImageGenerationJobTrackerTest` |

### 着手時にモックの境界の外だった型

着手時(`e73f7424`)の `service/` 配下のテストは、18 ファイルで次の型をモックしていた(()内は宣言・生成の出現数)。
境界の中の DB・MQ(後述)を除き、すべて実物へ書き直した。完了時にこれらをモックしているテストは 0 件。

- 自前のクラス: `GeneratedImageStorageService`(5)・`GeneratedImageCreationService`(5)・`ReferenceImageService`(7)・
  `ProjectImageDefaultsResolver`(3)・`ProhibitedContentFilterService`(3)・`ImageModelService`(3)・`DomainEventPublisher`(3)・
  `ComfyUiModelService`(3)・`ImageGenerationService`(2)・`UploadedImageTagService`・`ProjectImageSettingsService`・
  `MediaGarbageCollectionJobRunner`・`ImageGenerationJobRunner`・`CurrentActorService`・`AuditLogService`(2)・
  `ImageResizeService`(各 1)
- 自前の通信ラッパー(`*Client`): `GenerationJobClient`(9)・`ComfyUiClient`(4)・`AiGenerationClient`(4)・
  `ChatGptImageClient`(3)・`CmsBridgeClient`(2)・`ProjectServiceClient`・`IdentityBridgeClient`(各 1)
- `packages/lbs-common` の通信ラッパー: `ServiceTokenClient`(1)
- `ObjectMapper`(2)
- フレームワークの入口: `HttpServletRequest`(3。`MockHttpServletRequest` に置き換えた)
- 境界の中(DB・MQ): `GeneratedImageRepository`(6。完了時も `mock()` のまま)。`RabbitTemplate` は着手時は
  `DomainEventPublisher` / `AuditLogService` ごとモックしており、完了時は `RabbitTemplate` だけをモックして
  `DomainEventPublisher` / `AuditLogService` は実物にした。`ProjectImageSettingsRepository`・`GeneratedImageSequenceRepository` は
  着手時はどのテストもモックしておらず(`ProjectImageSettingsService` ごとモックしていた)、実物のサービスを動かすために
  新たにリポジトリをモックした。

### 本 Issue の範囲外のテスト

`controller/`・`integration/` のテストは `*Service` を `@MockitoBean` などで模しているが、主な対象は
コントローラ(HTTP の入口)であり `service` パッケージではない。`client/OutboundAuthHeadersTest` は
`client` パッケージが対象。いずれも本 Issue の「`service` パッケージのクラスを主対象にするテスト」に当たらないので、
書き直していない(他パッケージを受け持つ兄弟 Issue の範囲)。

## テストから到達できない分岐

完了時に残る 11 分岐。プロダクションコードを変えずには、その分岐に入る入力や状態を作れない理由を具体的に書く。
理由の根拠になる「入力がデコードの時点で拒否される」「呼び出し元が必ず 1 以上を渡す」ことは、テストで固定してある
(根拠のテスト名を末尾の列に書いた)。

| # | クラス | メソッド | 行 | 未カバーの分岐 | 到達できない理由 |
| ---: | --- | --- | ---: | --- | --- |
| 1 | `GeneratedImageUploadService` | `imageForTagging` | 102 | `for (int edge : TAGGING_FALLBACK_LONG_EDGES_PX)` が 3 周とも `break` せず、ループが尽きて終わる | 尽きるのは「1568px へ JPEG 化したコピーが 4MB を超え、さらに 1024px・640px の JPEG も 4MB を超える」ときだけ。1 周目の前の結果が 4MB 超(PNG)でも、1 周目で `resizeToJpeg(…, 1568)` した JPEG は、圧縮が最も効かない乱数ノイズの 1568×1176 画像で約 1.4MB(測定)で、4MB に届く入力を作れない。2 周目以降へ進む条件(JPEG が 4MB 超)が成り立たないので、ループが尽きることもない。1 周目で 4MB 以下になり `break` する側と、PNG が大きく JPEG 化へ進む側は `GeneratedImageUploadServiceTest#大きな画像はタグ付けにだけ縮小コピーを渡す` ほかで通している |
| 2 | `ImageResizeService` | `readJpegOrientation` | 470 | `bytes.length < 4` が真 | 呼び出し元(`resizeToLongEdge`・`reencodeKeepingResolution`・`applyEdits`)はすべて、先に `ImageIO` でデコードした後に呼ぶ。4 バイト未満のバイト列は `ImageIO` のどの読み取り器にも画像として読めず、デコードの時点で拒否される(`ImageResizeServiceOrientationTest#短すぎるバイト列`)ので、ここへ来ない |
| 3 | `ImageResizeService` | `readJpegOrientation` | 470 | 先頭が `0xFF` で 2 バイト目が `0xD8` でない(`(bytes[1] & 0xFF) != 0xD8` が真) | JDK の `ImageIO` が読める形式のうち、先頭バイトが `0xFF` なのは JPEG(`FF D8`)だけ(PNG は `89`、GIF は `47`、BMP は `42`、TIFF は `49`/`4D`、WBMP は `00`)。デコード済みのバイト列で先頭が `0xFF` なら 2 バイト目は必ず `0xD8`。先頭が `0xFF` でない側(JPEG と申告された PNG など)は `ImageResizeServiceOrientationTest#JPEGでないバイト列` で通している |
| 4 | `ImageResizeService` | `readJpegOrientation` | 474 | `while (offset + 4 <= bytes.length)` が偽(セグメントを読み尽くして抜ける) | 実際のデコーダがデコードできる JPEG は、必ず SOS(`FF DA`)に達する。ループは SOS(または不正なセグメント)で `break` するので、SOS に達する前に残りが 4 バイト未満になる JPEG はデコードできない。SOS を越えて読み進める JPEG(長さがファイルの外へはみ出すセグメントなど)もデコードの時点で拒否される(`ImageResizeServiceOrientationTest#デコードで拒否される入力`) |
| 5 | `ImageResizeService` | `readJpegOrientation` | 479 | `marker == 0xD8` が真(SOI の再出現を読み飛ばす) | ヘッダ部に 2 つ目の SOI がある JPEG はデコーダが拒否する(`ImageResizeServiceOrientationTest#デコードで拒否される入力`)。TEM(`FF 01`)と RSTn(`FF D0`)の側は `ImageResizeServiceOrientationTest#パラメータを持たないマーカー` で通している |
| 6 | `ImageResizeService` | `readJpegOrientation` | 483 | `marker == 0xD9` が真(EOI) | SOS より前に EOI(`FF D9`)がある JPEG は画像データを持たず、デコーダが拒否する(`ImageResizeServiceOrientationTest#デコードで拒否される入力`)。SOS(`0xDA`)で抜ける側は、通常の JPEG すべてで通っている |
| 7 | `ImageResizeService` | `parseExifOrientation` | 503 | `start + 6 > bytes.length`(`\|\|` の 2 つ目)が真 | 呼び出し側(`readJpegOrientation`)が、セグメント全体がファイル内に収まること(`offset + 2 + segmentLength <= bytes.length`)を確かめた後にだけ呼ぶ。`length >= 8` の通過後は `start + 8 <= offset + 2 + segmentLength <= bytes.length` なので、`start + 6 > bytes.length` は成り立たない。`length < 8` の側は `ImageResizeServiceOrientationTest#Exifの見出しが不正` で通している |
| 8 | `ImageResizeService` | `parseExifOrientation` | 511 | `tiffStart + 8 > bytes.length` が真 | `tiffStart + 8 = start + 14`。Exif セグメントの後ろには、デコードできる JPEG なら必ず SOF・SOS・画像データが続き 14 バイト以上ある(セグメント全体がファイル内に収まることは No.7 と同じく呼び出し側が確かめている)。ファイルが Exif セグメントのすぐ後で終わる JPEG はデコードできない |
| 9 | `SafetyNegativePromptService` | `wordsLowerCase` | 92 | `value == null`(`\|\|` の 1 つ目)が真 | `private` で、呼び出しは `appendCategory(base, …)` の `wordsLowerCase(base)` だけ。`base` は `appendSafetyWords` の冒頭で `negativePrompt == null ? "" : negativePrompt` と置き換えた `result`(以降の連結結果)で、`null` にならない。空白だけ(`isBlank()`)の側は `SafetyNegativePromptServiceTest#negativePromptが空文字でも抑制語だけを設定する` で通している |
| 10 | `ImageGenerationJobRunner` | `percent` | 84 | `total > 0` が偽(総数 0 のとき `0` を返す) | 呼び出しは `generateBatch` のリスナー(リピートが 1 つ終わるたびに `repeatFinished(done, total)`)だけで、`total` はリピート回数 `batchCount`。リスナーはリピートのループの中でしか呼ばれないので、`total` は 1 以上。`batchCount` が 0 の要求はループが回らず、リスナーも呼ばれない(`ImageGenerationJobRunnerTest#リピート回数が0なら進捗を報告せず空の結果をdoneで残す`) |
| 11 | `MediaGarbageCollectionJobRunner` | `reportProgress` | 108 | `total > 0` が偽(総数 0 のとき `100` を返す) | 呼び出しは `runDelete` の `for (i < mediaIds.size())` の中だけで、`total = mediaIds.size()` は 1 以上。`mediaIds` が空だとループが回らず `reportProgress` も呼ばれない(`MediaGarbageCollectionJobRunnerTest#runDelete_対象が空でも完了として報告する`) |

### 到達できる形に直した分岐

着手時に「到達できない」かもしれないと見えた次の分岐は、プロダクションコードを変えずに通せた(上の表には載せない)。

- `ImageGenerationService#addSuppressedIfDistinct` の `target != candidate` が偽(同じ例外インスタンスが続く、366〜368 行): DB(リポジトリ)の
  `save` が毎回同じ例外インスタンスを投げる状態を作る(`ImageGenerationServiceTest#同じ例外インスタンスが続けて投げられても自分自身を添えずに最初の失敗を投げる`)。
  HTTP のクライアントは失敗のたびに新しい例外を作るので、HTTP の失敗からは作れず、DB の障害として作った。
- `ImageResizeService` の「JPEG / PNG の書き出し器が無い」分岐(`encode` の `data != null` / `written`、`encodeJpeg` の `!writers.hasNext()`、
  `resizeToLongEdge` / `resizeToJpeg` / `encodeKeepingFormat` の `null` の扱い。96・125・234・329・333・338 行): JDK の ImageIO から
  書き出し器を処理の間だけ外す(`ImageWriters`。`ImageResizeServiceEncoderUnavailableTest`)。
- `ImageGenerationJobTracker#heartbeat` の `progress == null`(周回の途中で終端になったジョブ。72 行): ロックを持ったまま別スレッドの
  `heartbeat()` を `Thread.State.BLOCKED` まで待たせ、再入できるロック保持者が対象を `complete` する
  (`ImageGenerationJobTrackerTest#周回の途中で終端になったジョブにはハートビートを書かない`)。スレッドの状態で同期するので、待ち合わせは決定的。
- JPEG の Exif 読み取りの分岐(`ImageResizeService#readJpegOrientation` / `parseExifOrientation` の大半): SOI の直後に手組みのセグメントを差し込んだ実際の
  JPEG のバイト列で、8 方向の変換・ビッグエンディアン・壊れた Exif・範囲外の値・TEM / RSTn・詰め物のバイトなどを通す
  (`ImageResizeServiceOrientationTest`)。
- `ProjectImageDefaultsResolver`・`ImageModelService`・`CurrentActorService`・`AuditLogService` ほか、着手時に未カバーだったクラスの分岐:
  それぞれ専用のテスト(`ProjectImageDefaultsResolverTest`・`ImageModelServiceTest`・`CurrentActorServiceTest`・`AuditLogServiceTest`)を追加した。

### 書き直しで形を変えたテスト

書き直しで、自前のクラスのモックが「例外や任意の戻り値を返す」前提だったテストは、外部との通信の境界(HTTP の応答・DB の戻り値・接続の失敗)から
同じ状態を作る形にした。自前のクラスへの `verify(...)` は、DB のリポジトリ・MQ のテンプレートといった境界のモックへの `verify` か、HTTP 要求・ファイル・保存された行・送信されたメッセージの観測に置き換えた。境界のモックへの `never()` / `times()` は残している(`ReferenceImageServiceTest` の「他プロジェクトの画像ではファイルを読まない」「プロジェクト未指定では `findById` しない」も、書き直しの途中で一度落としたが復元した)。テストごと失ったのは、次の表で **削除した** と書いた 1 件(`GeneratedImageUploadServiceTest#縮小しても大きければ最後の結果を渡す`)だけで、これが唯一の例外である。形が変わったものは次のとおり。

| テスト | 変更 |
| --- | --- |
| `ImageGenerationServiceTest` の ComfyUI 呼び出しの検証 | `ArgumentCaptor` で `ComfyUiGenerationParams` を捕まえる代わりに、ComfyUI へ実際に投入されたワークフロー(`POST /prompt` の JSON)からパラメータ(プロンプト・negative・steps・cfg・サンプラー・seed・幅・高さ・checkpoint・LoRA・denoise)を読む(`MediaWorld#comfyParams`) |
| `ImageGenerationServiceTest#禁止コンテンツの検査は解決済みプロンプトに対して行う` | `verify(filter).check("a cat, masterpiece", true, false, true)` をやめ、禁止語が利用者のプロンプトではなくプロジェクト既定の品質プロンプトだけに含まれるときにブロックされる(`ProhibitedContentException`・生成されない)ことで確かめる。あわせて、OFF のカテゴリの禁止語は通ることも確かめる(`禁止コンテンツの検査はONのカテゴリだけが対象になる`) |
| `ImageGenerationServiceTest#リピート間で変わらない既定値はリピート回数によらず1回だけ引く` | リゾルバごとの `verify(times(1))` をやめ、既定値の解決のたびに起きる project-service への存在確認の回数が、`batchCount` が 1 のときと 16 のときで同じであることで確かめる |
| `ImageGenerationServiceTest` の「プロバイダの例外」 | `thenThrow(new IllegalStateException(...))` を、ComfyUI の `POST /prompt` が 500 を返す状態に置き換えた。投げられる例外は `AiServiceException`(原因 `HttpServerErrorException`)になり、メッセージに応答本文が含まれる。「最初の失敗を投げる」「2 件目以降を `addSuppressed` で添える」は、応答本文の違い(失敗1・失敗2)で確かめる |
| `ImageGenerationServiceTest#品質プロンプトが空ならプロンプトへ何も足さない` ほか | 品質プロンプトの解決をモックで `null` / 空白にしていたものを、サーバー既定値(`app.default-quality-prompt`)を空白・`null` にした `MediaWorld` で再現した(プロジェクト設定の空白は、サーバー既定値に落ちる仕様) |
| `ImageGenerationServiceReferenceTest#denoise未指定ならそのままnullを渡しComfyUIClientの既定に任せる` | 「`null` を渡す」ではなく、`ComfyUiClient` の既定(0.6)が実際のワークフローに入ることで確かめる。参照画像の「同じ `ReferenceImage` を渡す」(`assertSame`)は、`/upload/image` の要求本文に参照画像のバイト列が入ることで確かめる |
| `ImageGenerationServiceReferenceTest#参照画像はリピートの数によらず1回だけ読み込み全リピートへ渡す` | `verify(times(1)).load` をやめ、最初のアップロードを受けた時点で保存先のファイルを消し、3 リピートとも同じバイト列がアップロードされる(読み込み直していれば 2 回目以降が失敗する)ことで確かめる |
| `ImageGenerationJobRunnerTest` の失敗種別 | `thenThrow(new ProhibitedContentException(...))` などを、実際の外部の失敗に置き換えた: 禁止語を含むプロンプト(`prohibited_content`)・ComfyUI の接続先が接続拒否(`provider_unreachable`)・ChatGPT 画像 API が 429(`rate_limited`)・ComfyUI の接続先が未設定 / ChatGPT が 500 / ChatGPT の接続先が接続拒否(`generation_failed`)・DB の障害(`generation_failed`。メッセージが `null` の場合も) |
| `ImageGenerationJobRunnerTest#進捗の総数が0でも0除算せずpercentは0になる` | リスナーを `repeatFinished(0, 0)` で直接呼ぶ形はモックのサービスでしか作れないため、実物では起きない状態(No.10)として、`batchCount` が 0 の要求が進捗を報告せず `done` になるテスト(`リピート回数が0なら進捗を報告せず空の結果をdoneで残す`)に置き換えた |
| `MediaGarbageCollectionJobRunnerTest#runDelete_AuditLogService呼び出し自体が例外を投げてもジョブはfailedとして反映される` | `AuditLogService` のモックが例外を投げる代わりに、MQ(`RabbitTemplate`)が `AmqpException` 以外の `IllegalStateException` を投げる状態にする(`AuditLogService` は `AmqpException` だけを握りつぶす) |
| `MediaGarbageCollectionJobRunnerServiceTokenTest`(3 件) | `MediaGarbageCollectionJobRunnerTest` に統合した。専用の `HttpServer` と `ServiceTokenClient` のモックは、`MediaWorld` のフェイク HTTP サーバーと実物の `ServiceTokenClient`(トークンエンドポイントもフェイク)に置き換え、アサーション(削除リクエストの `Authorization` がサービストークン・401 でも続行・全件 401 で `failed`・WARN ログ 1 件)はそのまま持ち込んだ |
| `GeneratedImageCreationServiceSourceTest`・`GeneratedImageCreationServiceMimeTest` | `GeneratedImageCreationServiceTest` に統合した(アサーションは減らしていない)。ストレージのモックは実物の `GeneratedImageStorageService`(一時ディレクトリへ実際に書く)に置き換えた |
| `GeneratedImageUploadServiceTest#縮小しても大きければ最後の結果を渡す` | **削除した**。`ImageResizeService` をモックして「どの縮小でも 4MB 超」を作るテストで、実物では成り立たない(No.1)。モックを使わずには作れず、到達できない分岐としてこの表に載せた。ほかの `GeneratedImageUploadServiceTest` の全テストは残している |
| `UploadedImageTagServiceTest` ほか、`generateWithImage` などの失敗 | `AiServiceException` を投げるモックを、ai-service が 400 を返す状態に置き換えた(5xx にしないのは、`SyncServiceClient` の共有サーキットブレーカーを開けて他のテストへ波及させないため) |

テストを無効化・skip したものは無い(`@Disabled`・`@Ignore`・`assumeTrue` なども追加していない)。
