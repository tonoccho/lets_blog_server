# services/content の `service` 以外のパッケージの分岐カバレッジ(#1822)

Epic #1711 の子 Issue #1822 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。このときに
プロダクションコードを変更してはならない」を、`services/content` の `com.letsblog.content` のうち `service/` を除く
パッケージ(`aop`・`client`・`config`・`contentcache`・`controller`・`domain`・`dto`・`markdown`・`messaging`・`render`・
`repository` と、ルートのクラス)に適用した結果。プロダクションコードは変更していない(この Issue の差分は
`services/content/src/test/**` と本ファイルだけ。`.claude/hooks/paths.py` の `is_production()` が True になるパスは 0 件)。

`service/`(`docs/coverage/content-service.md`)は別の子 Issue の対象で、本ファイルの数には含めない。
なお `service/` を通すテストが共有する部品(`testsupport/ContentWorld`・`FakeHttpServer` など)は、本ファイルの対象の
パッケージのテストも使う。

このファイルは次の 3 節で構成する。

1. 計測値(着手時・完了時)
2. モックの境界
3. テストから到達できない分岐

受け入れ試験(Gherkin)は書いていない。この Issue の受け入れ基準は製品の振る舞いではなくテスト資産の状態(計測値・モックの型・
未カバー分岐の一覧)で、web UI から到達できない。対象のコードも、クライアント・コントローラ・ドメイン・パーサーの内部の分岐で、
web UI から外部(identity-service・project-service・DNS・ブラウザ)の応答を作り分けて到達することはできない。サービスレベルの
テスト(JUnit)で表した(CLAUDE.md → Test-First Implementation → Where the tests live が認める、web UI から到達できない場合)。

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(CLAUDE.md → Coverage のとおり、`&&` / `||` もバイトコード上は別の分岐なので、
C1 と C2 を兼ねる)。値は `jacocoTestReport.xml` の各 `<package>` 直下の `<counter type="BRANCH">` から取る。
`services/content` のテスト全体を 1 回走らせた結果なので、`service/` を通すテストも数に入る。

計測コマンド(着手時・完了時とも同じ):

```bash
./gradlew :services:content:cleanTest :services:content:test :services:content:jacocoTestReport
```

計測条件: ホストの MySQL(ADR-0006・#1769)が起動した状態で実行した(`@SpringBootTest` の統合テストが使う)。着手時・完了時とも、
`services/content` 全体で失敗 0、エラー 0、skipped 0(`build/test-results/test/*.xml` の `tests` / `failures` / `errors` /
`skipped` の合計)。

### 着手時

| 項目 | 値 |
| --- | --- |
| コミット | `a2b7d2da`(この Issue の最初のテストコミット `dd696455` の 1 つ前。別 worktree で計測) |
| コマンド | 上のとおり |
| 全体のテスト | 555 件、失敗 0、skipped 0 |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/content/aop` | 19 | 1 | 95.0% |
| `com/letsblog/content/client` | 14 | 10 | 58.3% |
| `com/letsblog/content/config` | 7 | 1 | 87.5% |
| `com/letsblog/content/contentcache` | 84 | 44 | 65.6% |
| `com/letsblog/content/controller` | 15 | 21 | 41.7% |
| `com/letsblog/content/domain` | 2 | 2 | 50.0% |
| `com/letsblog/content/dto` | 8 | 0 | 100.0% |
| `com/letsblog/content/markdown` | 15 | 7 | 68.2% |
| `com/letsblog/content/messaging` | 2 | 0 | 100.0% |
| `com/letsblog/content/render` | 10 | 0 | 100.0% |
| **合計** | **176** | **86** | **67.2%** |

`com/letsblog/content/repository` と `com/letsblog/content`(ルート。`ContentServiceApplication`)は、分岐を持つクラスが無く
(インタフェース・分岐の無いクラスだけ)、`BRANCH` カウンタ自体が出ない(分母 0。着手時も完了時も同じ)。`service/` は対象外。

着手時の未カバーのクラス別内訳(missed の数。合計 86): `AmazonProductParser` 14・`ContentCacheService` 13・`OutboundUrlGuard` 13・`PostController` 10・
`CustomTagTemplateController` 7・`ProjectBridgeClient` 6・`MarkdownTableParser` 6・`IdentityBridgeClient` 4・
`ProjectContentSettingsController` 4・`OgpMetadataParser` 4・`CustomTagTemplate` 2・`AuditLogAspect` 1・
`KeycloakRealmRoleConverter` 1・`MarkdownRenderer` 1(コントローラ・`contentcache` の未カバーの多くは、自前のクラスをモックしていたため、
モックの先の分岐が動いていなかったもの)。

この Issue のテストは 3 回に分けて積んだ(`dd696455` AuditLogAspect、`5c4fc1ff` contentcache、`731b58b8` controller と結合テスト、
最後に `5582d1c7` 残りの分岐の穴埋めと自前クラスのモックの書き直し)。

### 完了時

| 項目 | 値 |
| --- | --- |
| コミット | `5582d1c7`(テストの最終コミット。この後のコミットは本ファイルの追加だけで、テストとプロダクションコードは変わらない) |
| コマンド | 上のとおり |
| 全体のテスト | 680 件、失敗 0、skipped 0 |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/content/aop` | 20 | 0 | 100.0% |
| `com/letsblog/content/client` | 24 | 0 | 100.0% |
| `com/letsblog/content/config` | 8 | 0 | 100.0% |
| `com/letsblog/content/contentcache` | 124 | 4 | 96.9% |
| `com/letsblog/content/controller` | 36 | 0 | 100.0% |
| `com/letsblog/content/domain` | 4 | 0 | 100.0% |
| `com/letsblog/content/dto` | 8 | 0 | 100.0% |
| `com/letsblog/content/markdown` | 22 | 0 | 100.0% |
| `com/letsblog/content/messaging` | 2 | 0 | 100.0% |
| `com/letsblog/content/render` | 10 | 0 | 100.0% |
| **合計** | **258** | **4** | **98.5%** |

分母(covered + missed)は着手時 262、完了時 262 で変わらない(プロダクションコードを変えていない)。

残る 4 は、すべて下の「テストから到達できない分岐」に載せた(クラス別: `OutboundUrlGuard` 2、`ContentCacheService` 1、`OgpMetadataParser` 1)。

テスト件数は着手時 555、完了時 680(+125)。最終コミットでの新規・書き直しの要点:

- 追加: `client/ProjectBridgeClientBranchTest`・`client/IdentityBridgeClientBranchTest`(応答の本文が空・失敗・Authorization の有無)、
  `controller/CustomTagTemplateControllerTest`(search → category → showAll → 公開済みの絞り込み)、
  `controller/ProjectContentSettingsControllerTest`(参照・更新・空白の接頭辞を未設定にする・権限)、
  `config/KeycloakRealmRoleConverterTest`、`domain/CustomTagTemplateLifecycleTest`(`@PrePersist` / `@PreUpdate`)、
  `markdown/MarkdownTableParserTest`・`markdown/MarkdownRendererTest`。
- 書き直し: `dto/ResponseDateTimeSerializationTest`(自前の `PlaywrightPageFetcher`・`OgpMetadataParser`・`AmazonProductParser`・
  `OutboundUrlGuard`・`ProjectBridgeClient`・`CurrentActorService`・`AdminAuthorizationService` のモックをやめ、`ContentWorld` の実物へ)、
  `client/OutboundAuthHeadersTest`(`ServiceTokenClient` のモックをやめ、トークンのエンドポイントを持つ HTTP のフェイクへ向けた実物へ)、
  `config/RabbitMqConfigTest`(`Advice` のモックをやめ、実際の `Advice` 実装へ)。

### 突き合わせの手順(受け入れ基準 3 の再現)

`jacocoTestReport.xml` から、`service/` を除くパッケージに残る未カバー分岐を行ごとに取り出す。

```bash
python3 -I - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('services/content/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
P = 'com/letsblog/content'
for pkg in root.findall('package'):
    name = pkg.get('name')
    if name == P + '/service' or name.startswith(P + '/service/'):
        continue
    for sf in pkg.findall('sourcefile'):
        for line in sf.findall('line'):
            if int(line.get('mb')) > 0:
                print(name, sf.get('name'), line.get('nr'), 'missed', line.get('mb'), 'covered', line.get('cb'))
PY
```

完了時(`5582d1c7`)の出力は次の 4 行で、`missed` の合計 4 が下の表の行数(4 行)と 1 対 1 に対応する。

```text
com/letsblog/content/contentcache OutboundUrlGuard.java 59 missed 1 covered 3
com/letsblog/content/contentcache OutboundUrlGuard.java 109 missed 1 covered 1
com/letsblog/content/contentcache ContentCacheService.java 126 missed 1 covered 1
com/letsblog/content/contentcache OgpMetadataParser.java 55 missed 1 covered 3
```

(行の並びは XML の順で、毎回同じとは限らない。突き合わせるのは「ファイル名・行」の集合。)

## モックの境界

本ファイルの対象のパッケージのテスト(`services/content/src/test/java/com/letsblog/content/` のうち `service/` 以外の配下と、
それらが共有する `testsupport/`)で、Mockito のモック(`@Mock` / `@MockitoBean` / `@MockitoSpyBean` / `@Spy` /
`mock()` / `spy()` / `Mockito.mock()`)にしている型は次の **13 型だけ**。確認コマンド:

```bash
grep -rnE -A1 "@Mock\b|@MockitoBean|@MockitoSpyBean|@Spy\b|[^a-zA-Z.]mock\(|[^a-zA-Z]spy\(|Mockito\.(mock|spy)\(" \
  services/content/src/test/java/com/letsblog/content \
  | grep -v "/service/" | grep -v "import " | grep -v "^--"
```

| 型 | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `CustomTagRepository`(`com.letsblog.content.repository`) | Mockito `mock()` | **DB アクセス**(Spring Data のリポジトリ。自前のコードを持たないインタフェースで、`custom_tags` テーブルの読み書きそのもの) | `ContentWorld` を使う全テスト |
| `CustomTagTemplateRepository` | Mockito `mock()` | **DB アクセス**(`custom_tag_templates` テーブル) | `ContentWorld` を使う全テスト(`CustomTagTemplateControllerTest` ほか) |
| `ProjectContentSettingsRepository` | Mockito `mock()`・`@Mock` | **DB アクセス**(`project_content_settings` テーブル) | `ContentWorld` を使う全テスト、`messaging/EventMessageListenerTest` |
| `ContentCacheRepository` | Mockito `mock()`・`@Mock` | **DB アクセス**(`content_cache` テーブル) | `ContentWorld` を使う全テスト、`contentcache/ContentCacheSsrfGuardTest` |
| `PostRepository` | Mockito `mock()`・`@Mock` | **DB アクセス**(`posts` テーブル) | `controller/PostControllerTest`・`controller/InternalPostBridgeControllerTest`・`dto/ResponseDateTimeSerializationTest`・`dto/PostBridgeResponseDateTimeSerializationTest`・`messaging/EventMessageListenerTest` |
| `ProcessedEventRepository` | `@Mock` | **DB アクセス**(処理済みイベントのテーブル) | `messaging/ProcessedEventStoreImplTest`・`messaging/EventMessageListenerTest` |
| `RabbitTemplate`(`org.springframework.amqp.rabbit.core`) | Mockito `mock()`(`ContentWorld` が持ち、実物の `AuditLogService` へ渡す) | **MQ との送受信**(ログ exchange への `convertAndSend`) | `ContentWorld` を使う全テスト |
| `ConnectionFactory`(`org.springframework.amqp.rabbit.connection`) | Mockito `mock()` | **MQ への接続**(RabbitMQ ブローカーへのコネクションの工場) | `config/RabbitMqConfigTest` |
| `JwtDecoder`(`org.springframework.security.oauth2.jwt`) | `@MockitoBean` | **Keycloak の鍵取得を伴う JWT の検証**(JWK Set の HTTP 取得) | `testsupport/ContentIntegrationTestBase` を継承する統合テスト |
| `Browser`・`Page`(`com.microsoft.playwright`) | Mockito `mock()`・`@Mock`・`@MockitoBean` | **外部プロセス**(ヘッドレス Chromium。外部サイトの取得) | `ContentWorld`・`contentcache/PlaywrightPageFetcher*Test`・`contentcache/ContentCacheSsrfGuardTest`・`controller/ContentCacheControllerConcurrentAccessTest` |
| `Route`・`Request`(`com.microsoft.playwright`) | `@Mock` | **外部プロセス**(Chromium のネットワーク要求の遮断。`PlaywrightPageFetcher` に渡されるブラウザ側の値) | `contentcache/PlaywrightPageFetcherRouteTest` |

(`Browser` と `Page`、`Route` と `Request` はそれぞれ同じ外部プロセスの一部なので 1 行にまとめた。数えると、リポジトリ 6 型・`RabbitTemplate`・`ConnectionFactory`・`JwtDecoder`・`Browser`・`Page`・`Route`・`Request` の 13 型。)

Mockito 以外で外部との通信(または Spring のフレームワークの入口)を差し替えているものも、同じ表に載せる。いずれも
Mockito のモックではなく、自前のクラスの代わりでもない。

| 型・仕掛け | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `FakeHttpServer`(`testsupport`。JDK 標準 `com.sun.net.httpserver.HttpServer` をループバックで起動) | サーバー側フェイク。メソッド+パスごとに応答を登録し、受けた要求を記録する。`ContentWorld` が identity-service・project-service・ai-service・media-service・サービス間トークンのエンドポイントを 1 台にまとめる | **HTTP 通信**。自前の `*Client`(`IdentityClient`・`IdentityBridgeClient`・`ProjectBridgeClient`・`AiGenerationClient`・`MediaRenderClient`・`GenerationJobClient`)・`ServiceTokenClient` は実物をこのサーバーに向ける | `client/`・`controller/`・`dto/`・`render/` ほか |
| JDK 標準 `HttpServer` を直接起動したもの | サーバー側フェイク(既存テストの手書き) | **HTTP 通信**(受けた `Authorization` ヘッダー・本文の記録、トークンのエンドポイント) | `client/OutboundAuthHeadersTest`・`client/ProjectBridgeClientLetsblogSyncTest`・`client/ProjectBridgeClientBridgeDateTimeTest` |
| `TestOutboundUrlGuards`(`contentcache`) | 実物の `OutboundUrlGuard` を、名前解決(`HostResolver`)だけ差し替えて作る。アドレスの分類 `isBlocked` は実物 | **DNS の問い合わせ** | `ContentWorld`・`contentcache/` |
| `MockHttpServletRequest`・`Jwt`・`JwtAuthenticationToken`(Spring Test / Spring Security) | Mockito ではないテスト用の実装 | **HTTP リクエストの入口**(`Authorization`・`X-Forwarded-For` ヘッダー、JWT の `sub`・`realm_access`) | `controller/`・`aop/`・`client/`・`config/` |
| `AspectJProxyFactory`(Spring の実際の AOP プロキシ) | `AuditLogAspect` を本番と同じ Spring AOP の仕組みで対象のメソッドへ適用する(`ProceedingJoinPoint`・`MethodSignature` を Mockito で模さない) | フレームワークの入口(`@Around`) | `aop/AuditLogAspectTest` |
| `AsyncProxies`(`testsupport`)+ 呼び出しをためる `Executor` | Spring の実際の AOP プロキシで、自前のランナーの `@Async` を効かせる | フレームワークの入口(`@Async`) | `service/CustomTagGenerationJobStarterTest`(`service/` 側の対象だが、部品は共有) |

### 着手時にモックの境界の外だった型

着手時(`a2b7d2da`)の本ファイルの対象のテストでは、DB・MQ・Keycloak・外部プロセスの型(上の表)に加えて、次の型を Mockito
(または `@MockitoBean`)でモックしていた(()内は `@Mock` / `@MockitoBean` の次の行の型と `mock(X.class)` の型の出現数)。
境界の外のものは、すべて実物へ書き直した。完了時にこれらをモックしているテストは 0 件(上の確認コマンドの出力に、これらの型は現れない)。

- 自前のクラス: `ProjectBridgeClient`(7)・`OutboundUrlGuard`(5)・`CurrentActorService`(4)・`AdminAuthorizationService`(4)・
  `IdentityBridgeClient`(4)・`PlaywrightPageFetcher`(2)・`OgpMetadataParser`(2)・`AmazonProductParser`(2)・
  `LetsblogSyncPayloadService`・`CustomTagValidationService`・`CustomTagService`・`CustomTagGenerationService`・
  `CustomTagGenerationJobStarter`・`AuditLogService`・`ArticlePreviewService`(各 1)
- 自前のクライアントの下の共通クライアント: `IdentityClient`(4。`packages/lbs-common`)・`ServiceTokenClient`(1。同)
  — 実物を、トークンや identity-service のエンドポイントを持つ HTTP のフェイクへ向けて使う
- フレームワークの入口: `ProceedingJoinPoint`(1)・`MethodSignature`(1)→ Spring の AOP プロキシ、`Advice`(1。AOP Alliance の
  マーカーインタフェース)→ 実際の `Advice` 実装
- 境界の中(これは完了時も `mock()` のまま): DB のリポジトリ・`RabbitTemplate`・`ConnectionFactory`・`JwtDecoder`・
  Playwright の `Browser` / `Page` / `Route` / `Request`

(`HttpServer` が 1 件出るのは JDK 標準の `HttpServer` を直接起動する既存のテストで、Mockito のモックではない。)

### 書き直しで外した・置き換えたテスト

外したテストは無い。書き直しは、モックで返させていた値を、外部(HTTP のフェイク・DB のリポジトリ・DNS)の応答や状態として作り直した
もので、アサーションは減らしていない(モックの呼び出しの検証 `verify(...)` は、外部に実際に届いたリクエスト・リポジトリへの保存・
権限エラーで外部へ何も届かないことの検証へ置き換えた)。テストを無効化・skip したものは無い(`@Disabled`・`@Ignore`・`assumeTrue`
なども追加していない)。

## テストから到達できない分岐

完了時に残る 4 分岐。プロダクションコードを変えずには、その分岐に入る入力や状態を作れない理由を具体的に書く。

| # | クラス | メソッド | 行 | 未カバーの分岐 | 到達できない理由 |
| ---: | --- | --- | ---: | --- | --- |
| 1 | `OutboundUrlGuard` | `requireAllowed` | 59 | `host.isBlank()` が真(`host == null` は偽、つまりホストが空白だけの文字列) | `host` は `java.net.URI#getHost()` の戻り値。`URI` は空のホストを `null` にする(`http:///x`・`http://:80/x`・`http://@/x`・`http://%20/x` はいずれも `getHost()` が `null` になるか、`URISyntaxException` で `new URI` の段階で拒否される。空白を含むホストも `URISyntaxException`)。空文字や空白だけの非 null のホストを `getHost()` が返す入力が存在しない。`null` 側はカバー済み |
| 2 | `OutboundUrlGuard` | `isBlocked` | 109 | `bytes.length == 4` が偽(アドレス長が 16 でも 4 でもない) | `isBlocked` の引数は `InetAddress`。`Inet4Address` は 4 バイト、`Inet6Address` は 16 バイトだけで、直前の `bytes.length == 16` を通らなかった残りは必ず 4 バイト。それ以外の長さのアドレスを `InetAddress` の実物で作る手段が JDK に無い(`InetAddress` のコンストラクタは非公開で、`getByAddress` は 4 / 16 バイト以外を `UnknownHostException` で拒否する)。末尾の `return false` も同じ理由で到達しない |
| 3 | `ContentCacheService` | `resolveType` | 126 | `uri.getHost() == null` が真(ホストの無い URI) | `resolveType` を呼ぶのは `resolve` だけで、その前に `parseUrl` が `uri.getHost() == null` の URI を `IllegalArgumentException`(「http/https形式の絶対URLを指定してください」)で拒否する。`resolveType` は `private` で、`parseUrl` を通らずにホストの無い `URI` を渡す経路が無い。`parseUrl` 側の拒否はカバー済み |
| 4 | `OgpMetadataParser` | `firstNonBlank` | 55 | `value != null` が偽(`null` の候補値) | `firstNonBlank` の引数は `ogContent`・`doc.title()`・`metaContent`・`hostOf`・`pageUrl`。前の 4 つは `""` を返し `null` を返さない。`pageUrl` が `null` のときだけ `null` の候補になりうるが、`parse` は同じ呼び出しの中で先に `hostOf(pageUrl)` を評価し、`URI.create(null)` は `NullPointerException`(`hostOf` の `catch` は `IllegalArgumentException` のみ)を投げるので、`firstNonBlank` に `null` が届く前にメソッドが例外で終わる。`private` で外から `null` を渡す経路も無い。空白だけの値(`value.isBlank()` が真)側はカバー済み |

なお、`OutboundUrlGuard#requireAllowed` の他の拒否(不正な URL・名前解決の失敗・解決結果が空・内部アドレス)と、
`OgpMetadataParser#parse` の `null` の HTML・不正な `pageUrl`・ホストの無い `pageUrl` は到達でき、テスト済み
(`OutboundUrlGuardTest`・`OgpMetadataParserTest`)。
