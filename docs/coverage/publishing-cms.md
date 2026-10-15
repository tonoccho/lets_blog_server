# services/publishing `com.letsblog.publishing.cms` の分岐カバレッジ(#1814)

Epic #1711 の子 Issue #1814 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`services/publishing` の
`com.letsblog.publishing.cms` パッケージ(`cms/ssh`・`cms/agent` を含む)に適用した結果。

このファイルは次の 3 節だけで構成する。

1. 計測値(着手時・完了時)
2. モックの境界
3. テストから到達できない分岐

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(CLAUDE.md → Coverage のとおり、`&&` / `||` もバイトコード上は
別の分岐なので、C1 と C2 を兼ねる)。値は `jacocoTestReport.xml` の `<package>` 直下の
`<counter type="BRANCH">` から取る。`services/publishing` のテスト全体を 1 回走らせた結果なので、
`cms` のクラスを間接的に通す他パッケージ(`service/` など)のテストも数に入る。

計測コマンド(着手時・完了時とも同じ):

```bash
./gradlew :services:publishing:test :services:publishing:jacocoTestReport
```

計測条件:

- MySQL: ホストの `127.0.0.1:3306`(ADR-0006、#1769)が起動した状態で実行した。
- `HikariDeadConnectionRecoveryIntegrationTest`(`config` パッケージ。#1095)は、既定の
  `-Dlbs.dockerAvailable=false` のため**実行されない**(2 件とも skipped)条件で測った。このテストは
  `cms` のクラスを通さないので、`cms` の数には影響しない。

### 着手時

| 項目 | 値 |
| --- | --- |
| コミット | `23eaa229`(develop 起点、ブランチ `test/1814-publishing-cms-coverage` の作成直後) |
| コマンド | `./gradlew :services:publishing:test :services:publishing:jacocoTestReport` |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/publishing/cms` | 148 | 46 | 76.3% |
| `com/letsblog/publishing/cms/agent` | 74 | 18 | 80.4% |
| `com/letsblog/publishing/cms/ssh` | 309 | 103 | 75.0% |
| **合計** | **531** | **167** | **76.1%** |

### 完了時

| 項目 | 値 |
| --- | --- |
| コミット | `183e252e`(テストの最終コミット。この後のコミットは本ファイルの追加だけで、テストと本番コードは変わらない) |
| コマンド | `./gradlew :services:publishing:test :services:publishing:jacocoTestReport` |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/publishing/cms` | 194 | 0 | 100.0% |
| `com/letsblog/publishing/cms/agent` | 92 | 0 | 100.0% |
| `com/letsblog/publishing/cms/ssh` | 406 | 6 | 98.5% |
| **合計** | **692** | **6** | **99.1%** |

分母(covered + missed)は着手時も完了時も 698 で変わらない(本番コードを変えていない)。
残る 6 は、すべて下の「テストから到達できない分岐」に載せた。

### 突き合わせの手順(受け入れ基準 3 の再現)

`jacocoTestReport.xml` から、`cms` に残る未カバー分岐を行ごとに取り出す。

```bash
python3 -I - <<'EOF'
import xml.etree.ElementTree as ET
root = ET.parse('services/publishing/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
for pkg in root.findall('package'):
    if not pkg.get('name').startswith('com/letsblog/publishing/cms'):
        continue
    for sf in pkg.findall('sourcefile'):
        for line in sf.findall('line'):
            if int(line.get('mb')) > 0:
                print(sf.get('name'), line.get('nr'), 'missed', line.get('mb'), 'covered', line.get('cb'))
EOF
```

完了時(`183e252e`)の出力は次の 5 行で、すべて「テストから到達できない分岐」の表の行と 1 対 1 に対応する。

```text
SshjCommandExecutor.java 166 missed 1 covered 1
SshjCommandExecutor.java 200 missed 1 covered 1
SshjCommandExecutor.java 259 missed 2 covered 2
WordPressSshOperations.java 179 missed 1 covered 3
WordPressSshOperations.java 775 missed 1 covered 3
```

(missed の合計 1 + 1 + 2 + 1 + 1 = 6 が、完了時の表の `ssh` の missed 6 と一致する。)

注意: JaCoCo は、ブロックの最後まで実行が進んだときにだけそのブロックをカバー済みにする。例外を投げて
終わる呼び出しだけを通したテストでは、その呼び出しの行が「未通過」と出る
(`applyPluginTheme` の `case THEME_INSTALL` が、失敗系のテストだけのとき未通過になった)。
分岐を埋めるときは、例外になる経路と、例外にならず戻る経路の両方を通す。

## モックの境界

`com.letsblog.publishing.cms` のテスト(`services/publishing/src/test/java/com/letsblog/publishing/cms/` 配下)で、
Mockito のモック(`@Mock` / `@MockitoBean` / `@MockitoSpyBean` / `mock()` / `spy()`)にしている型は
**`SshCommandExecutor` の 1 つだけ**(`ssh/WordPressSshOperationsTest.java` の `@Mock`)。
確認コマンド:

```bash
grep -rnE "@Mock|@MockitoBean|@MockitoSpyBean|@Spy|[^a-zA-Z]mock\(|spy\(" \
  services/publishing/src/test/java/com/letsblog/publishing/cms
```

Mockito 以外で外部との通信を差し替えているものも、同じ表に載せる。

| 型・仕掛け | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `SshCommandExecutor`(`com.letsblog.publishing.cms.ssh`) | Mockito `@Mock` | SSH でのコマンド実行・SFTP 転送(`SshjCommandExecutor` の抽象。リモートホストとの通信そのもの) | `ssh/WordPressSshOperationsTest` |
| `SshCommandExecutor` | 手書きフェイク `FakeSshCommandExecutor`(実行コマンドの記録と、コマンド文字列ごとの固定応答) | 同上(Mockito を使わない書き方) | `WordPressAdapterTest`、`ssh/WordPressSshOperationsBranchTest` |
| `MockRestServiceServer`(`RestClient.Builder` に束縛) | Spring Test が提供するサーバー側の模擬 | provision-agent への HTTP(`/wp-cli/*`)。Mockito のモックではない | `agent/WordPressAgentOperations*Test` |
| `FakeProvisionAgent`(JDK 標準 `com.sun.net.httpserver.HttpServer` をループバックで起動) | サーバー側フェイク。パスごとの応答を登録し、受けたリクエストを記録する | provision-agent への HTTP(`/wp-cli/*` と、`WordPressBulkManagementClient` が呼ぶ `/categories`・`/tags`) | `WordPressAdapterTest` |
| Apache MINA SSHD のサーバー(同一 JVM 内で起動。テスト依存 `org.apache.sshd:sshd-core` / `sshd-sftp`) | サーバー側フェイク。コマンドと SFTP を実際のプロトコルで受ける | リモート SSH サーバーそのもの。`SshjCommandExecutor`(sshj を直接呼ぶ実装)を実際の SSH/SFTP で通す | `ssh/SshjCommandExecutorTest` |

自前のクラス(`WordPressSshOperations`・`WordPressAgentOperations`・`WordPressAdapter`・
`WordPressBulkManagementClient`・`SshjCommandExecutor`)は、どのテストでもモックせず実物を使う。
`ObjectMapper` と DTO(record)も実物を使う。

書き直しの内訳(Issue 着手時にモックしていた型 → 完了時):

| 着手時にモックしていた型 | 完了時 |
| --- | --- |
| `WordPressSshOperations`(`WordPressAdapterTest`) | 実物。SSH の境界だけ `FakeSshCommandExecutor` に差し替え |
| `WordPressAgentOperations`(`WordPressAdapterTest`) | 実物。HTTP の境界だけ `FakeProvisionAgent` に差し替え |
| `WordPressBulkManagementClient`(`WordPressAdapterTest`) | 実物。HTTP の境界だけ `FakeProvisionAgent` に差し替え |
| `SshCommandExecutor`(`WordPressSshOperationsTest`) | 境界なので Mockito のまま(上の表の最初の行) |

範囲外の注記: `cms` の型(`CmsAdapter`・`WordPressSshOperations` など)を**使う側**の
`service/`・`controller/`・`config/` のテストは、`cms` のテストではなく、それぞれ別の子 Issue が受け持つ
パッケージのテストなので、この表の対象にしていない(Issue #1814 の Scope: `cms/` を対象にするテスト)。

## テストから到達できない分岐

プロダクションコードを変えずには、その分岐に入る入力や状態を作れないものだけを載せる。
5 行・未カバー分岐 6 件。

| クラス | メソッド | 行 | 未カバーの分岐 | 到達できない理由 |
| --- | --- | ---: | --- | --- |
| `cms/ssh/SshjCommandExecutor` | `currentFingerprint` | 166 | `connection != null ? … : null` の `null` 側(1 件) | 呼び出し元は `exec` / `execAll` が `withConnection` へ渡すラムダの中だけで、`withConnection` は先に `pool.computeIfAbsent(poolKeyOf(params), …)` で同じキーのエントリを入れている。エントリを消す処理は無い(`evictIdleConnections` は `client` を閉じるだけでエントリは残す)ので、`pool.get(poolKeyOf(params))` は `null` を返せない。防御的な null 検査 |
| `cms/ssh/SshjCommandExecutor` | `connect` | 200 | `finally` 内の `if (keyFile != null)` の `null` 側(1 件) | `keyFile` が `null` のまま `finally` に入るのは、`writePrivateKeyToTempFile` が例外を投げたときだけ(`Files.createTempDirectory` / `Files.writeString` の `IOException`、またはランタイム例外)。秘密鍵 PEM に `null` は渡せない(`poolKeyOf` の `sha256(null)` が先に `NullPointerException` になる)。一時ディレクトリが書けない状態は、JVM の `java.io.tmpdir`(`Files.createTempDirectory` は起動時に読んだ値を保持する)を実行中に差し替えられないため、テストから作れない。ホストのファイルシステム障害でしか起きない |
| `cms/ssh/SshjCommandExecutor` | `evictIdleConnections` | 259 | ロック取得後の再確認 `connection.client != null && now - connection.lastUsedAtMillis >= IDLE_TIMEOUT_MILLIS` の偽側(2 件。`client == null` 側と、時間条件が偽の側) | 252 行の事前確認(`client == null` または使用直後ならスキップ)を通ってから `tryLock` に成功するまでの間に、別スレッドが接続を閉じる/使う競合が起きたときだけ偽になる。事前確認と `tryLock` の間に処理を差し込む手段が無く(どちらも同じメソッド内で連続している)、プロダクションコードを変えずには、この 2 つの間でだけ状態を変えられない |
| `cms/ssh/WordPressSshOperations` | `parseJsonArray` | 179 | `node != null && node.isArray()` の `node == null` 側(1 件) | `node` は `objectMapper.readTree(String)` の戻り値で、Jackson 2 の `readTree(String)` は空文字列で `MissingNode`、`null` リテラルで `NullNode` を返し、`null` を返さない。`ObjectMapper` はモックしない方針(実物を使う)なので、`null` を返させる手段が無い。防御的な null 検査 |
| `cms/ssh/WordPressSshOperations` | `parseJsonObject` | 775 | `node == null \|\| node.isMissingNode()` の `node == null` 側(1 件) | 上の 179 行と同じ理由(`readTree(String)` は `null` を返さない。空出力は `isMissingNode()` 側で通している) |

行番号は `183e252e` 時点の `cms/ssh/SshjCommandExecutor.java` と `cms/ssh/WordPressSshOperations.java` のもの。
