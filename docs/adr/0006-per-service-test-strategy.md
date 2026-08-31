# ADR-0006: サービス別のテスト戦略(DB・JWTフィクスチャ・契約テスト・モック方針)

## Status

Accepted

## Context

[ADR-0001](0001-domain-based-microservices.md)によりサービスをドメイン単位で分割することを
決定した。分割前は単一の`api`プロジェクトを前提に、Testcontainers(MySQL)とSpring Boot Test
でテストが書かれていたが、Gradleマルチプロジェクト化(`libs/lbs-common`、
`services/legacy-api`、`services/log-writer`、`services/identity`、`services/gateway`)後は、
以下をサービス横断でどう扱うかが定まっていなかった(#587)。

- テストで使う実DB(Testcontainersを使うか、既存の共有MySQLを使うか)
- 認証(JWT)を伴うテストの書き方
- サービス間の契約テスト方針(Spring Cloud Contractを入れるか、OpenAPI互換性検証で済ませるか)
- 他サービス呼び出しのモック方針
- カバレッジ目標のサービス別再設定

CI自体は#557で`services:`マトリクス構成が既に整備済みで、`.github/workflows/api-services-test.yml`
が`lbs-common`/`legacy-api`/`log-writer`/`gateway`/`identity`の各サービスを独立して
`lint`+`test`+`jacocoTestReport`し、Codecovへサービス別`flags:`でカバレッジをアップロードする。
GitHub Actions自体は本リポジトリ全体で意図的に無効化されている(`README.md`/`.claude/CLAUDE.md`
参照)ため、このワークフローは「CIが有効化された際に何を実行するか」の定義であり、本ADRでは
これをそのまま前提として扱う。

調査の過程で、`services/legacy-api/src/test/java/com/letsblog/api/config/`配下に
`TestcontainersConfiguration.java`(`MySQLContainer`を`@Bean`定義する`@TestConfiguration`)が
残存していたが、`@Testcontainers`/`@Container`アノテーションでの参照も、他クラスからの
インポートも一切無く、実際には使われていないデッドコードだったことが判明した
(`org.testcontainers:*`のGradle依存も同様)。実際にDBへ接続するテストは、
`services/legacy-api/src/test/resources/application-test.yml`が指す`localhost:3306`の
実MySQLに対して行われており、これはローカルではリポジトリのdocker-compose MySQL
コンテナ、CIでは`.github/workflows/api-services-test.yml`の
素の`services: mysql: image: mysql:8.0`ブロックが提供する。つまりTestcontainersは
「今後採用される可能性のある方式」ではなく、単に使われないまま残った実装で、放置すると
将来の実装者が誤ってTestcontainersを本来の方針と誤認しかねない状態だった。

## Decision

### DB test strategy: 実MySQL(Testcontainersは使わない)

各サービスでDBに接続するテストが必要な場合、per-test Testcontainersではなく、実際に
稼働している共有MySQLに接続する方式を正式なテンプレートとする。

- ローカル: リポジトリのdocker-compose MySQLコンテナ。テスト用スキーマとユーザーは
  `mysql/init/02-create-test-schemas.sh`が作る(このADR記載時は`scripts/setup-test-db.sh`だったが、
  #762で全10スキーマが前者に集約され、後者は参照されなくなったため#846で削除した)
- CI: `.github/workflows/api-services-test.yml`の`services: mysql:`ブロック
- 接続設定: サービスごとの`src/test/resources/application-test.yml`(`legacy-api`に既存例あり)

未使用だった`services/legacy-api/src/test/java/com/letsblog/api/config/TestcontainersConfiguration.java`
および`services/legacy-api/build.gradle`の`org.testcontainers:*`依存は、実装が伴わないまま
残っていた紛らわしい残骸として本ADRの一環で削除した(#587)。今後DBを必要とする新規サービス
(`services/log-writer`、`services/gateway`、将来Phase 19で分割されるサービス)も、この
実MySQL方式をテンプレートとする。

### JWT/auth test fixture: `libs/lbs-common`の`JwtTestFixtures`

JWTを必要とするエンドポイント・認証ロジックのテストは、`libs/lbs-common`の
`java-test-fixtures`として提供する
`com.letsblog.common.testfixtures.JwtTestFixtures`を使う(#587)。

- `JwtTestFixtures.jwt(subject, realmRoles...)` — `SecurityContextHolder`へ手動で
  `JwtAuthenticationToken`を設定する単体テスト向け(例: `CurrentActorServiceTest`のスタイル)
- `JwtTestFixtures.jwtRequestPostProcessor(subject, realmRoles...)` —
  `@SpringBootTest`+MockMvcの統合テストで`mockMvc.perform(...).with(...)`に渡す
  `RequestPostProcessor`
- `JwtTestFixtures.serviceJwt(clientId)` — [ADR-0005](0005-service-to-service-client-credentials.md)
  のサービス間通信トークン(`azp`クレームのみ、`sub`無し)を模したJWT。実際にこれを受理する
  実装はまだ存在しないため、将来のサービストークン認証テスト向けの先行提供。

利用側は`testImplementation testFixtures(project(':libs:lbs-common'))`を追加する
(`services/legacy-api`/`services/identity`は本ADRの一環で追加済み)。既存の
`CurrentActorServiceTest`/`KeycloakRealmRoleConverterTest`(legacy-api/identity-service両方)は
このフィクスチャを使うようリファクタ済みで、重複していたJWT組み立てのボイラープレートが
解消されている。

### 契約テスト方針: OpenAPIベースの互換性検証で済ませる(Spring Cloud Contractは導入しない)

サービス間の契約テストとして、Spring Cloud Contractは**導入しない**。代わりに、
`.claude/CLAUDE.md`セクション18に記載の既存`orval`ベースのAPIクライアント生成パイプライン
(`web`/`extension`向け、サービスごとのOpenAPI specから`sdk/api-client`を生成)が提供する
ビルド時の互換性シグナルに委ねる。破壊的なOpenAPI変更は、クライアント生成・TypeScript型
チェックの失敗という形でビルド時に検出される。

この判断は[ADR-0005](0005-service-to-service-client-credentials.md)が別の論点(サービス間
認証方式)について述べているのと同じ理由による。2026-08時点で、ドメインサービス同士が
直接HTTPで呼び合う経路自体が実在しない(Phase 19の各サービス抽出Issueが未着手で、
`gateway`の各ルートは`publishing`/`content`/`analytics`/`project`等の分割前の単一
`legacy-api`コンテナを指している)。契約テストが検証すべき「サービス間の実際の呼び出し」が
無い現時点でSpring Cloud Contractのスタブ配布・consumer駆動契約の仕組みを導入しても、
検証対象が存在しないため運用コストに見合わない。

### モックの方針: 将来WireMockを使う(現時点では未導入)

他サービス呼び出しのスタブ化には、将来Phase 19でサービス間の実際のHTTP呼び出しが実装された
時点でWireMockを使うことを推奨する。契約テストと同じ理由により、現時点でWireMockの依存や
スタブコードは追加しない。

### カバレッジ目標: サービス別に`docs/COVERAGE_TARGETS.md`で管理

各サービスのカバレッジ目標値は本ADRでは重複させず、`docs/COVERAGE_TARGETS.md`を正とする。
同ドキュメントを、単一`api/`プロジェクト前提の記述から現行のGradleマルチプロジェクト構成
(`legacy-api`/`identity`/`log-writer`/`gateway`/`lbs-common`)に合わせて更新した(#587)。
カバレッジの計測自体は、`.github/workflows/api-services-test.yml`がサービスごとに
`jacocoTestReport`を実行し、Codecovへサービス別`flags:`でアップロードする既存の仕組みを使う。

## Consequences

**利点**

- DB接続テストの方式が「実MySQLに接続する」の一択で統一され、Testcontainersという別の
  選択肢が(実際には使われていないのに)存在するように見える紛らわしさが解消された
- JWTを伴うテストの記述量が、各テストファイルでの手組みから`JwtTestFixtures`の1行呼び出しに
  削減され、KeycloakのJWT形状(`realm_access.roles`等)の知識がテストコードへ分散しなくなった
- 契約テスト・モックについて「今は導入しない」ことと「なぜ今は不要か」が明文化され、
  Phase 19で実際のサービス間呼び出しが生まれた際に何を検討すべきかが追跡できる

**欠点・トレードオフ**

- 実MySQL方式は、CI・ローカルの両方でDBコンテナの起動を前提とするため、
  Testcontainers方式が持つ「テストごとに独立した使い捨てDB」という利点(テスト間の
  データ汚染に強い)は得られない。既存の`legacy-api`のテストはこのトレードオフを
  既に受け入れて運用されており、本ADRはその現状を追認するものである
- 契約テスト・モックの仕組みを先送りしたことで、Phase 19で実際のサービス間呼び出しが
  実装されるタイミングで、これらの整備が別途必要になる

## Notes: このIssue(#587)時点での実装範囲

2026-08時点で実装したのは以下のみ。

- `libs/lbs-common`の`com.letsblog.common.testfixtures.JwtTestFixtures`(テストフィクスチャ)
- `services/legacy-api`/`services/identity`の既存テスト(`CurrentActorServiceTest`、
  `KeycloakRealmRoleConverterTest`)を上記フィクスチャ利用へリファクタ
- 未使用だった`TestcontainersConfiguration.java`および関連Gradle依存の削除
- `docs/TEST_DOCUMENTATION.md`・`docs/COVERAGE_TARGETS.md`の現行構成への更新
- 本ADR

以下は、[ADR-0005](0005-service-to-service-client-credentials.md)のNotesと同様、Phase 19で
実際のサービス間呼び出しが生まれた後続Issueに委ねる。

- WireMockの導入と、実際の他サービス呼び出しに対するスタブテストの実装
- OpenAPI互換性検証だけでは不十分と判明した場合の、Spring Cloud Contract等の再検討
- `JwtTestFixtures.serviceJwt(...)`を実際に消費する、サービストークン認証の受理側テスト

## Alternatives considered

**Testcontainersをサービス別DBテストの正式な方式として採用する(却下)**

サービスごとに独立した使い捨てMySQLコンテナを使えるため、テスト間のデータ汚染に強く、
CI環境に依存しない再現性が得られる。しかし以下の理由で、既存の実MySQL方式を正式な
テンプレートとして採用し、Testcontainersは見送った。

- `legacy-api`の既存テストは既に実MySQL(ローカルdocker-compose/CIの`services:`ブロック)に
  対して書かれており、これを置き換えるだけの動機(実際に困っている問題)が無い
- 残存していた`TestcontainersConfiguration.java`が実際には未使用だった事実自体が、
  「使われるかもしれない」程度の位置づけでは定着しないことを示している
- CI環境でのTestcontainers利用にはDocker-in-Docker相当の権限が必要になる場合があり、
  GitHub Actions自体が現状無効化されている本リポジトリでは、この権限要件を今検証する
  緊急性が無い

**Spring Cloud Contractを導入する(却下)**

consumer駆動契約テストにより、サービス間APIの破壊的変更をより早く・より意図的な形で
検出できる。しかし以下の理由で見送った。

- 検証対象となる「サービス間の実際のHTTP呼び出し」自体が2026-08時点で存在しない
  (Phase 19未着手)
- 既存の`orval`ベースのクライアント生成パイプライン(`.claude/CLAUDE.md`セクション18)が、
  `web`/`extension`向けとはいえ、OpenAPI specの破壊的変更をビルド時に検出する仕組みを
  既に提供しており、これと重複する仕組みを今から追加する必要性が薄い
- Stub Runnerの配布・バージョニングの運用設計は、実際に呼び合うサービスの組が確定してから
  でないと的確に決められない
