# packages/lbs-common の分岐カバレッジ(#1831)

Epic #1711 の子 Issue #1831 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`packages/lbs-common` の
`packages/lbs-common/src/main/java/com/letsblog/common/` 配下すべてに適用した結果。
プロダクションコードは変更していない(この Issue の差分は `packages/lbs-common/src/test/**` と本ファイルだけ)。

この Issue の受け入れ基準はテスト資産の状態なので、Gherkin の受け入れシナリオは書かない(web UI から到達できない。
CLAUDE.md → Where the tests live の例外)。検証は JUnit で行う。新しく足したテストは、プロダクションコードが
すでに存在するので、足した時点で通る(RED の対象になる未実装の振る舞いは無い)。代わりに、足す前の
`jacocoTestReport.xml` で該当の分岐が missed だったことを、着手時の計測値が示している。

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(`&&` / `||` もバイトコード上は別の分岐なので C1 と C2 を兼ねる)。値は
`jacocoTestReport.xml` の各 `<package>` 直下の `<counter type="BRANCH">` から取る。

計測コマンド(着手時・完了時とも同じ):

```bash
./gradlew :packages:lbs-common:cleanTest :packages:lbs-common:test :packages:lbs-common:jacocoTestReport
```

### 着手時

コミット `55dd4dbe`(develop 起点、変更なし)。テスト 398 件、失敗 0・skipped 0。

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `crypto` | 6 | 0 | 100.0% |
| `migration` | 11 | 3 | 78.6% |
| `web` | 23 | 1 | 95.8% |
| `async` | 2 | 0 | 100.0% |
| `client` | 76 | 18 | 80.9% |
| `scheduling` | 12 | 0 | 100.0% |
| `db` | 33 | 1 | 97.1% |
| `messaging` | 21 | 1 | 95.5% |
| `net` | 140 | 6 | 95.9% |
| `job` | 6 | 0 | 100.0% |
| `auth` | 26 | 2 | 92.9% |
| **合計** | **356** | **32** | **91.8%** |

### 完了時

コミット: 本 Issue のブランチ `test/1831-lbs-common-coverage` の、テストを足したコミット `2c716508`
(本ファイルを足すコミットは `docs/` だけを変えるので、計測値は変わらない)。テスト 435 件、失敗 0・skipped 0。

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `crypto` | 6 | 0 | 100.0% |
| `migration` | 13 | 1 | 92.9% |
| `web` | 24 | 0 | 100.0% |
| `async` | 2 | 0 | 100.0% |
| `client` | 91 | 3 | 96.8% |
| `scheduling` | 12 | 0 | 100.0% |
| `db` | 33 | 1 | 97.1% |
| `messaging` | 22 | 0 | 100.0% |
| `net` | 143 | 3 | 97.9% |
| `job` | 6 | 0 | 100.0% |
| `auth` | 28 | 0 | 100.0% |
| **合計** | **380** | **8** | **97.9%** |

## モックの境界

Mockito(`@Mock` / `@MockitoBean` / `@MockitoSpyBean` / `mock()` / `spy()`)を使うテストは、着手時も完了時も 0 件。
したがって「モックしている型」は無く、表に載せるべき型も無い。通信の相手を模すときは、次のテスト用の実物・
フェイクを使う(いずれも外部との通信の層にあたり、自前クラスの代役ではない)。

| 型 | 使う場所 | どの外部通信か |
| --- | --- | --- |
| JDK の `HttpServer`(ループバックで起動) | `SyncServiceClientTest`・`SyncServiceClientBranchTest`・`GenerationJobClientTest`・`IdentityClientTest` ほか | HTTP(相手サービスのサーバー側) |
| `MockRestServiceServer` | `ServiceTokenClientTest`・`CorrelationIdRestClientCustomizerTest` | HTTP(トークンエンドポイントなど、サーバー側) |
| `MockClientHttpRequest` / `MockClientHttpResponse` | `ExternalCallLoggingInterceptorTest` | HTTP(送受信するメッセージそのもの) |
| `RestClient` のインターセプタが投げる `IOException` | `SyncServiceClientBranchTest` | HTTP の通信路の失敗(タイムアウト・切断) |
| `java.lang.reflect.Proxy` で作った `DataSource` | `SchemaMigrationJobTest`(接続の取得に失敗する・null を返す場合) | DB(接続口。`getConnection` だけを差し替える) |
| H2(インメモリ DB) | `SchemaMigrationJobTest`・`DbQueryMetricsDataSourcePostProcessorTest` ほか | DB(実物のデータベースエンジン) |
| 名前解決・NIC 列挙を渡すラムダ | `ConnectionDestinationGuardTest` | DNS・ネットワークインタフェース |
| `keytool` で作った証明書 | `SniHostnameTrustManagerSanTest` | TLS(IP アドレスの SAN を持つ実物の証明書) |

`HttpServletRequest` などのフレームワークの入口は `MockHttpServletRequest` / `MockHttpServletResponse` など、
Spring Test の実装で通す(Mockito のモックではない)。

## テストから到達できない分岐

完了時の `jacocoTestReport.xml` で未カバーの分岐は 8 件(7 行)。いずれも、プロダクションコードを変えない限り
入力で起こせない。

| クラス | メソッド | 行 | 未カバー | 理由 |
| --- | --- | ---: | ---: | --- |
| `migration.SchemaMigrationJob` | `run(String, MigrationBatchStep, int)` | 67 | 1 | try-with-resources が出す `connection != null` の判定のうち、正常終了時に null だった場合。接続が null なら直後の `ensureStateTable` が NullPointerException になり、正常終了に到達しない(例外側は `DataSource がnullの接続を返すとNullPointerExceptionになる` で通っている)。 |
| `client.SyncServiceClient` | `isTimeout` | 277 | 1 | `cause != null` の false 側。`ResourceAccessException` を作るのは `RestClient` で、原因には送受信で起きた `IOException` を必ず入れる。cause が null の `ResourceAccessException` は、外部通信からは作れない。 |
| `client.SyncServiceClient` | `bodyOrMessage` | 284 | 1 | `body != null` の false 側。`RestClientResponseException#getResponseBodyAsString()` は本文が無くても空文字を返し、null を返さない(空文字の側は `本文が空の4xxは...` で通っている)。 |
| `client.GenerationJobClient` | `updateTerminalStatus` | 219 | 1 | `lastFailure == null ? null : ...` の null 側。ここへ来るのは再試行回数(定数 `TERMINAL_UPDATE_MAX_ATTEMPTS` ≥ 1)をすべて `SyncServiceException` で使い切った場合だけで、その時点で `lastFailure` は必ず入っている。 |
| `db.DbQueryMetricsDataSourcePostProcessor` | `plainStatementTemplate` | 105 | 1 | `args != null && args[0] instanceof String` の「引数があるが先頭が String ではない」側。記録対象の実行メソッド(`execute`・`executeQuery`・`executeUpdate`・`executeLargeUpdate`・`executeBatch`・`executeLargeBatch`)は、引数があれば先頭が必ず SQL 文字列で、引数が無い場合(バッチ)は `args == null` になる。 |
| `net.ConnectionDestinationGuard` | `check` | 118 | 1 | `host.endsWith(".") && !host.contains(":")` の「末尾がドットで、かつコロンを含む」側。コロンを含むのは IPv6 リテラルで、`java.net.URI` は末尾がドットの IPv6 リテラルを受け付けない。 |
| `net.ConnectionDestinationGuard` | `check` | 123 | 1 | `BUNDLED_NAMES.contains(host)` が true の側。`BUNDLED_NAMES` は `Set.of()`(空)の定数で、どのホスト名も含まれない。 |
| `net.ConnectionDestinationGuard` | `parse` | 140 | 1 | `uri.getHost().isEmpty()` の true 側。`java.net.URI#getHost()` はホストが空のとき空文字ではなく null を返す(null 側は `urlWithAnEmptyAuthorityIsRejected` で通っている)。 |

## 再現手順

完了時の数字は次で再現できる(ホストの MySQL は要らない。この module のテストは H2 を使う)。

```bash
./gradlew :packages:lbs-common:cleanTest :packages:lbs-common:test :packages:lbs-common:jacocoTestReport
python3 -I - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('packages/lbs-common/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
for package in root.iter('package'):
    for source in package.findall('sourcefile'):
        for line in source.findall('line'):
            if int(line.get('mb')) > 0:
                print(package.get('name') + '/' + source.get('name'), line.get('nr'), 'missed', line.get('mb'))
PY
```

出力される行(クラス・行・未カバー数)が、上の表の行と過不足なく一致する。モックの数え上げは
`grep -rEl "Mockito|@Mock|mock\(|spy\(" packages/lbs-common/src` が 0 件になることで確かめる。
