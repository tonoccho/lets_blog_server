# services/gateway の分岐カバレッジ(#1830)

Epic #1711 の子 Issue #1830 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`services/gateway` の
`services/gateway/src/main/java/com/letsblog/gateway/` 配下すべてに適用した結果。
プロダクションコードは変更していない(この Issue の差分は `services/gateway/src/test/**` と本ファイルだけ)。

この Issue の受け入れ基準はテスト資産の状態なので、Gherkin の受け入れシナリオは書かない(web UI から到達できない。
CLAUDE.md → Where the tests live の例外)。検証は JUnit で行う。

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(`&&` / `||` もバイトコード上は別の分岐なので C1 と C2 を兼ねる)。値は
`services/gateway/build/reports/jacoco/test/jacocoTestReport.xml` の各 `<package>` 直下の
`<counter type="BRANCH">` から取る。

計測コマンド(着手時・完了時とも同じ):

```bash
./gradlew :services:gateway:cleanTest :services:gateway:test :services:gateway:jacocoTestReport
```

### 着手時

コミット `8d4cafe6`(develop 起点、変更なし)。テスト 577 件、失敗 0・skipped 0。

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `config` | 105 | 6 | 94.6% |
| **合計** | **105** | **6** | **94.6%** |

`com.letsblog.gateway` 直下のクラス(アプリケーション起動クラス)は分岐を持たない。

### 完了時

コミット: 本 Issue のブランチ `test/1830-gateway-coverage` の先頭(本ファイルを含むコミット)。テスト 584 件、失敗 0・skipped 0。

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `config` | 109 | 2 | 98.2% |
| **合計** | **109** | **2** | **98.2%** |

残る 2 分岐は、すべて下の「テストから到達できない分岐」に載せた。

## モックの境界

完了時に、`services/gateway` のテストが Mockito でモック(`@Mock` / `@MockitoBean` / `@MockitoSpyBean` / `mock()` / `spy()`)
している型は **0 種**(Mockito の import 自体が無い)。

着手時にモックしていた型は `WebFilterChain`(`RateLimitWebFilterTest`・`RateLimitUploadBucketSyncTest`)だけで、
フレームワークの入口にあたり外部通信ではないので、実物に置き換えた。`WebFilterChain` は関数型インタフェースなので、
後段への到達を記録するラムダ(`recordingChain`)にした。`verify(chain, times(n)).filter(...)` は、
記録したリストへのアサーション(`assertEquals(List.of(exchange), passed)` など)に置き換えており、検証の強さは同じ。

| 型 | 使う場所 | どの外部通信か |
| --- | --- | --- |
| (なし) | - | - |

下流サービスへの HTTP(`ProxyHandler` の `WebClient`)は、Mockito ではなく `ExchangeFunction` の差し替え、
または `MockServerWebExchange` / `MockServerHttpRequest`(Spring Test の実装。Mockito のモックではない)で扱っている。

## テストから到達できない分岐

完了時の `jacocoTestReport.xml` で未カバーの分岐は 2 件。

| クラス | メソッド | 行 | 分岐 | 到達できない理由 |
| --- | --- | ---: | --- | --- |
| `ProxyHandler` | `handle(ServerRequest)` | 83 | `route != null && ...` の `route != null` が false になる側 | 直前の 74〜81 行で `route == null` なら 404 を返して return しているため、83 行に来る時点で `route` は常に非 null。防御的な null 検査で、プロダクションコードを変えずに `route == null` のまま 83 行へ到達する入力は作れない。 |
| `ProxyHandler` | `handle(ServerRequest)` | 101 | `entity.getBody() != null ? ... : Flux.empty()` の null 側 | `WebClient.ResponseSpec#toEntityFlux(Class)` が組み立てる `ResponseEntity` の本文は、応答本文が空でも空の `Flux` で、null にはならない(`ExchangeFunction` を差し替えても同じ)。呼び出し元(`flatMap` のラムダ)が受け取る `entity.getBody()` に null を渡す経路は無い。 |

## 受け入れ基準 3 の再現手順

```bash
./gradlew :services:gateway:cleanTest :services:gateway:test :services:gateway:jacocoTestReport
python3 -I - <<'PY'
import xml.etree.ElementTree as ET
r = ET.parse('services/gateway/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
for p in r.findall('package'):
    for s in p.findall('sourcefile'):
        for l in s.findall('line'):
            if int(l.get('mb')) > 0:
                print(s.get('name'), l.get('nr'), 'missed', l.get('mb'))
PY
```

出力は `ProxyHandler.java 83 missed 1` と `ProxyHandler.java 101 missed 1` の 2 行だけで、上の表と一致する。

モックしている型の数え上げ:

```bash
grep -rnE "org\.mockito|@Mock|@MockitoBean|@MockitoSpyBean|\bmock\(|\bspy\(" services/gateway/src/test
```

出力は 0 件。
