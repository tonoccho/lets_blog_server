# services/log-writer の分岐カバレッジ(#1829)

Epic #1711 の子 Issue #1829 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`services/log-writer` の
`services/log-writer/src/main/java/com/letsblog/logwriter/` 配下すべてに適用した結果。
プロダクションコードは変更していない(この Issue の差分は `services/log-writer/src/test/**` と本ファイルだけ)。

この Issue の受け入れ基準はテスト資産の状態なので、Gherkin の受け入れシナリオは書かない(web UI から到達できない。
CLAUDE.md → Where the tests live の例外)。検証は JUnit(単体テストと、実 MySQL に対する統合テスト)で行う。

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(`&&` / `||` もバイトコード上は別の分岐なので C1 と C2 を兼ねる)。値は
`jacocoTestReport.xml` の各 `<package>` 直下の `<counter type="BRANCH">` から取る。

計測コマンド(着手時・完了時とも同じ。ホストの MySQL `127.0.0.1:3306`、ADR-0006・#1769 が起動した状態):

```bash
./gradlew :services:log-writer:cleanTest :services:log-writer:test :services:log-writer:jacocoTestReport
```

### 着手時

コミット `cc1af349`(develop 起点、変更なし)。テスト 186 件、失敗 0・skipped 0。

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `dto` | 13 | 1 | 92.9% |
| `service` | 123 | 16 | 88.5% |
| `config` | 7 | 1 | 87.5% |
| `controller` | 21 | 1 | 95.5% |
| `listener` | 19 | 3 | 86.4% |
| **合計** | **183** | **22** | **89.3%** |

### 完了時

コミット: 本 Issue のブランチ `test/1829-log-writer-coverage` の先頭(本ファイルを含むコミット)。テスト 229 件、失敗 0・skipped 0。

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `dto` | 14 | 0 | 100.0% |
| `service` | 139 | 0 | 100.0% |
| `config` | 8 | 0 | 100.0% |
| `controller` | 22 | 0 | 100.0% |
| `listener` | 22 | 0 | 100.0% |
| **合計** | **205** | **0** | **100.0%** |

`domain`・`repository`・ルートのパッケージは分岐を持たない。

## モックの境界

完了時にテストがモック(`@Mock` / `@MockitoBean` / `@MockitoSpyBean` / `mock()` / `spy()`)している型は次の 5 種だけ。
いずれも外部との通信にあたる。

| 型 | 使う場所 | どの外部通信か |
| --- | --- | --- |
| `RabbitTemplate` | `testsupport/LogWriterIntegrationTestBase`(`@MockitoBean`) | MQ(ブローカーへの発行) |
| `ConnectionFactory`(`org.springframework.amqp.rabbit.connection`) | `RabbitMqConfigTest`・`RabbitMqCorrelationPropagationTest`(`mock()`) | MQ(ブローカーへの接続) |
| `JwtDecoder` | `testsupport/LogWriterIntegrationTestBase`・`AuthorizationMatrixIntegrationTest`(`@MockitoBean`) | Keycloak の JWKS への HTTP |
| `AuditLogRepository` | `LogRetentionDeletionIntegrationTest`(`@MockitoSpyBean`) | DB(Spring Data のリポジトリ。自前のコードを持たない。実 MySQL に流しつつ呼び出しを観測する) |
| `OperationLogRepository` | 同上(`@MockitoSpyBean`) | DB(同上) |

identity-service・ai-service への HTTP は、Mockito ではなく `testsupport/FakeHttpServer`(JDK の `HttpServer` を
ループバックで起動するサーバー側のフェイク)で模し、`IdentityClient` / `GenerationJobClient` の実物を通す。
着手時にモックしていた `AdminAuthorizationService`・`CurrentActorService`・`AuditLogService`・`OperationLogService`・
`UnifiedOperationLogService`・`FrontendErrorLogService`・`IdentityClient`・`GenerationJobClient` ほかの自前クラスは、
すべて実物に置き換えた。`HttpServletRequest` などのフレームワークの入口は MockMvc / Spring Test の実装で通す。

## テストから到達できない分岐

なし。完了時の `jacocoTestReport.xml` で、`services/log-writer` の未カバー分岐は 0 件(205 / 205)。
