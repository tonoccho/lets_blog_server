# services/identity の分岐カバレッジ(#1826)

Epic #1711 の子 Issue #1826 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`services/identity` の
`services/identity/src/main/java/com/letsblog/identity/` 配下すべて(84 ファイル)に適用した結果。
プロダクションコードは変更していない(この Issue の差分は `services/identity/src/test/**` と本ファイルだけ。
`services/identity/build.gradle` も変えていない — 追加の依存は要らなかった)。

この Issue の受け入れ基準は製品の振る舞いではなくテスト資産の状態なので、Gherkin の受け入れシナリオは書かない
(web UI から到達できない。CLAUDE.md → Where the tests live の例外)。検証は `services/identity` の JUnit
(単体テストと、実 MySQL に対する統合テスト)で行う。

このファイルは次の 3 節で構成する。

1. 計測値(着手時・完了時)
2. モックの境界
3. テストから到達できない分岐

## 計測値(着手時・完了時)

数え方は JaCoCo の `BRANCH` カウンタ(CLAUDE.md → Coverage のとおり、`&&` / `||` もバイトコード上は別の分岐なので、
C1 と C2 を兼ねる)。値は `jacocoTestReport.xml` の各 `<package>` 直下の `<counter type="BRANCH">` から取る。
`services/identity` のテスト全体を 1 回走らせた結果なので、あるパッケージのクラスを間接的に通す他パッケージ
(`integration/` など)のテストも数に入る。

計測コマンド(着手時・完了時とも同じ):

```bash
./gradlew :services:identity:cleanTest :services:identity:test :services:identity:jacocoTestReport
```

計測条件: ホストの MySQL(`127.0.0.1:3306`、ADR-0006・#1769)が起動した状態で実行した。DB を使う統合テストは
テスト専用スキーマ `lbs_identity_test` に接続する。`services/identity/build.gradle` の `jacoco` 設定に除外は無い。

### 着手時

| 項目 | 値 |
| --- | --- |
| コミット | `bb156f11`(develop 起点、ブランチ `test/1826-identity-coverage` の作成直後。変更なし) |
| コマンド | 上のとおり |
| テスト | 355 件実行、失敗 0・エラー 0・skipped 0(`build/test-results/test/*.xml` の合計) |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/identity/controller` | 8 | 8 | 50.0% |
| `com/letsblog/identity/config` | 19 | 1 | 95.0% |
| `com/letsblog/identity/client` | 6 | 8 | 42.9% |
| `com/letsblog/identity/keycloak` | 34 | 14 | 70.8% |
| `com/letsblog/identity/dto` | 4 | 0 | 100.0% |
| `com/letsblog/identity/service` | 253 | 27 | 90.4% |
| `com/letsblog/identity/domain` | 3 | 17 | 15.0% |
| `com/letsblog/identity`・`messaging`・`repository` | 0 | 0 | (分岐なし) |
| **合計** | **327** | **75** | **81.3%** |

着手時の未カバーのクラス別内訳(missed の数。合計 75): `KeycloakAdminClient` 14・`ProjectUserSyncService` 9・
`AvatarImageProcessor` 8・`ProjectUserId` 8・`UserSiteAuthorId` 8・`ProjectServiceClient` 7・`CurrentActorService` 5・
`InternalProjectUserController` 4・`UserService` 2・`AdminAuthorizationService` 2・`UserController` 2・
`AuthSetupController` 2・`RoleService` 1・`User` 1・`KeycloakRealmRoleConverter` 1・`PublishingServiceClient` 1。

着手時のテスト: 37 ファイル、うち 23 ファイルが Mockito(`@Mock`・`@MockitoBean`・`mock()`)を使い、自前のクラスをモックしていた
(「着手時にモックの境界の外だった型」を参照)。

### 完了時

| 項目 | 値 |
| --- | --- |
| コミット | `4e79b38b`(テストの最終コミット。この後のコミットは本ファイルの追加だけで、テストとプロダクションコードは変わらない) |
| コマンド | 上のとおり |
| テスト | 506 件実行、失敗 0・エラー 0・skipped 0 |

| パッケージ | covered | missed | 率 |
| --- | ---: | ---: | ---: |
| `com/letsblog/identity/controller` | 16 | 0 | 100.0% |
| `com/letsblog/identity/config` | 20 | 0 | 100.0% |
| `com/letsblog/identity/client` | 13 | 1 | 92.9% |
| `com/letsblog/identity/keycloak` | 48 | 0 | 100.0% |
| `com/letsblog/identity/dto` | 4 | 0 | 100.0% |
| `com/letsblog/identity/service` | 278 | 2 | 99.3% |
| `com/letsblog/identity/domain` | 20 | 0 | 100.0% |
| `com/letsblog/identity`・`messaging`・`repository` | 0 | 0 | (分岐なし) |
| **合計** | **399** | **3** | **99.3%** |

分母(covered + missed)は着手時も完了時も 402 で変わらない(プロダクションコードを変えていない)。残る 3 は、すべて下の
「テストから到達できない分岐」に載せた(クラス別: `ProjectServiceClient` 1、`UserService` 2)。

完了時のテストは 52 ファイル(`testsupport/` の部品 6 ファイルを含む)。`@Test` 454 件・`@ParameterizedTest` 5 件。

### 突き合わせの手順(受け入れ基準 3 の再現)

`jacocoTestReport.xml` から、分岐数と、残る未カバー分岐を行ごとに取り出す。

```bash
python3 -I - <<'PY'
import xml.etree.ElementTree as ET
root = ET.parse('services/identity/build/reports/jacoco/test/jacocoTestReport.xml').getroot()
covered = missed = 0
for pkg in root.findall('package'):
    for c in pkg.findall('counter'):
        if c.get('type') == 'BRANCH':
            covered += int(c.get('covered'))
            missed += int(c.get('missed'))
    for sf in pkg.findall('sourcefile'):
        for line in sf.findall('line'):
            if int(line.get('mb')) > 0:
                print(pkg.get('name') + '/' + sf.get('name'), line.get('nr'),
                      'missed', line.get('mb'), 'covered', line.get('cb'))
print('BRANCH covered', covered, 'missed', missed)
PY
```

完了時(`4e79b38b`)の出力は次のとおりで、`missed` の合計 3 が下の表の行数(3 行)と 1 対 1 に対応する。

```text
com/letsblog/identity/client/ProjectServiceClient.java 111 missed 1 covered 3
com/letsblog/identity/service/UserService.java 143 missed 1 covered 1
com/letsblog/identity/service/UserService.java 212 missed 1 covered 1
BRANCH covered 399 missed 3
```

(`package` と `sourcefile` の並びは XML の順で、毎回同じとは限らない。突き合わせるのは「ファイル名・行」の集合。)

注意: JaCoCo は、ブロックの最後まで実行が進んだときにだけそのブロックをカバー済みにする。例外を投げて終わる呼び出しだけを
通したテストでは、その呼び出しの後ろの分岐が「未通過」と出る。分岐を埋めるときは、例外になる経路と、例外にならず戻る経路の
両方を通す。

## モックの境界

`services/identity/src/test/java/com/letsblog/identity/` のテストで、Mockito のモック(`@Mock` / `@MockitoBean` /
`@MockitoSpyBean` / `mock()` / `spy()`)にしている型は次の **7 つだけ**。数え上げの確認コマンドと完了時(`4e79b38b`)の出力:

```bash
cd services/identity/src/test/java/com/letsblog/identity
{
  for f in $(find . -name '*.java'); do
    awk '/@Mock[[:space:]]*$|@MockitoBean|@MockitoSpyBean|@Spy[[:space:]]*$/{getline n; print n}' "$f"
  done
  grep -rhoE "(Mockito\.)?(mock|spy)\([A-Za-z]+\.class" . | sed -E 's/.*\(//; s/\.class//'
} | sed -E 's/^[[:space:]]*(private|public)?[[:space:]]*(final)?[[:space:]]*//; s/[[:space:]]+[a-zA-Z]+;?[[:space:]]*$//; s/ = .*//' \
  | sort | uniq -c | sort -rn
```

```text
      6 JwtDecoder
      5 UserRepository
      2 RoleRepository
      1 UserSiteAuthorRepository
      1 RabbitTemplate
      1 ProjectUserRepository
      1 ConnectionFactory
```

(左の数は宣言の出現数。`IdentityWorld` が持つ `mock()` は 1 回ずつ数える。)

| 型 | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `UserRepository`(`com.letsblog.identity.repository`) | Mockito `mock()` | **DB アクセス**(Spring Data のリポジトリ。自前のコードを持たないインタフェースで、`users` テーブルの読み書きそのもの) | `IdentityWorld`(経由で `UserService*Test`・`ProjectUserSyncServiceTest`・`AuditLogServiceTest`・`*ControllerTest`・`AdminAuthorizationServiceTest` など)・`CurrentActorServiceTest`・`RoleServiceTest`・`AvatarServiceTest`・`UserMigrationPersisterTest` |
| `RoleRepository` | 同上 | **DB アクセス**(`roles` テーブル) | `IdentityWorld`・`RoleServiceTest` |
| `ProjectUserRepository` | 同上 | **DB アクセス**(`project_users` テーブル) | `IdentityWorld`(`ProjectUserSyncServiceTest`・`EventMessageListenerTest`・`InternalProjectUserControllerTest`) |
| `UserSiteAuthorRepository` | 同上 | **DB アクセス**(`user_site_authors` テーブル) | `IdentityWorld`(同上) |
| `RabbitTemplate`(`org.springframework.amqp.rabbit.core`) | 同上(`IdentityWorld` が持ち、実物の `DomainEventPublisher` と `AuditLogService` へ渡す。送られた `AuditLogMessage` / イベントは `Mockito.mockingDetails(...).getInvocations()` で読む) | **MQ との送受信**(`letsblog.logs` / `letsblog.events` exchange への `convertAndSend`) | `IdentityWorld` を使う全テスト |
| `ConnectionFactory`(`org.springframework.amqp.rabbit.connection`) | 同上 | **MQ との接続**(`RabbitMqConfig#eventsListenerContainerFactory` に渡すだけの接続ファクトリ。接続は開かない) | `RabbitMqConfigTest` |
| `JwtDecoder`(`org.springframework.security.oauth2.jwt`) | `@MockitoBean` | **Keycloak の鍵取得を伴う JWT 検証**(JWKS エンドポイントへの HTTP。テストは署名済み JWT を MockMvc の `jwt()` で直接差し込む) | `AuthorizationMatrixIntegrationTest`・`SelfPreferencesIntegrationTest`・`DisabledUserTokenIntegrationTest`・`LastAdminGuardIntegrationTest`・`UserWriteAuthorizationIntegrationTest`・`SelfPrivilegeEscalationIntegrationTest` |

Mockito 以外で外部との通信(または Spring のフレームワークの入口)を差し替えているものも、同じ表に載せる。いずれも
Mockito のモックではなく、自前のクラスの代わりでもない。

| 型・仕掛け | 差し替え方 | どの外部通信にあたるか | 使っているテスト |
| --- | --- | --- | --- |
| `FakeHttpServer`(`testsupport`。JDK 標準 `com.sun.net.httpserver.HttpServer` をループバックで起動) | サーバー側フェイク。メソッド+パスごとに応答を登録し、受けた要求を記録する。`IdentityWorld` が project-service(`/api/internal/project/**`)と publishing-service(`/api/internal/publishing/**`)のベース URL をこの 1 台へ向ける | **HTTP 通信**。自前の `ProjectServiceClient`・`PublishingServiceClient`・`OutboundAuthHeaders` と、`packages/lbs-common` の `ServiceTokenClient` は実物をこのサーバーに向ける | `ProjectUserSyncServiceTest`・`EventMessageListenerTest`・`InternalProjectUserControllerTest`・`OutboundAuthHeadersTest`・`ProjectServiceClient*Test`・`PublishingServiceClientTest` |
| `FakeKeycloak` / `SharedFakeKeycloak`(`testsupport`。`FakeHttpServer` の上に、トークンエンドポイントと Keycloak Admin REST API のユーザー・realm ロールの応答を載せたもの) | サーバー側フェイク。受けた操作を `ops()` に記録し、失敗は `fail(...)` で注入する。単体テストは `IdentityWorld` が実物の `KeycloakAdminClient` を向ける。統合テストは `KeycloakFakeSupport` の `@DynamicPropertySource` で `keycloak.admin.token-uri` / `admin-base-uri` をこのフェイクへ向け、本物の `KeycloakAdminClientConfig` が作る `KeycloakAdminClient` を使う | **HTTP 通信**(Keycloak Admin API)。`KeycloakAdminClient` は自前の通信ラッパーなので、モックにせず実物を通す | `UserServiceTest`・`UserServiceLastAdminGuardTest`・`UserControllerTest`・`AuthSetupControllerTest`・`DisabledUserTokenIntegrationTest`・`AuthSetupIntegrationTest`・`LastAdminGuardIntegrationTest`・`SetupInitialAdminRaceIntegrationTest`・`UserWriteAuthorizationIntegrationTest`・`SelfPrivilegeEscalationIntegrationTest` |
| `MockRestServiceServer`(Spring Test) | `RestClient.Builder` に束ねるサーバー側フェイク(従来どおり) | **HTTP 通信**(Keycloak Admin API / トークンエンドポイント。接続断の再現を含む) | `KeycloakAdminClientTest` |
| `MockHttpServletRequest`・`MockMultipartFile`・`MockMvc`(Spring Test)・`JwtAuthenticationToken`(Spring Security) | Mockito ではないテスト用の実装 | **HTTP リクエストの入口**(`Authorization`・`X-Forwarded-For`・`User-Agent` ヘッダー、multipart のファイル、JWT の `sub`)。`CurrentActorService`・各コントローラは実物 | `IdentityWorld` を使う全テスト・`*ControllerTest`・`CurrentActorServiceTest`・`OutboundAuthHeadersTest` |
| `@TempDir`(JUnit)の一時ディレクトリ | アバターの保存先(`app.avatar-storage-path`)を一時ディレクトリへ向ける。実物の `AvatarStorageService` が実際にファイルを書く | **ファイルシステム**(専用ボリューム) | `AvatarServiceTest`・`AvatarControllerTest` |
| 実 MySQL(`lbs_identity_test`、ADR-0006) | 差し替えない | **DB アクセス** | `integration/*`(`MigrationContractTest` など) |

次の仕掛けは、外部との通信ではないが、モックでもない(実物のオブジェクトに、失敗や環境の欠落を作る入力を与える)。

| 仕掛け | 内容 | 使っているテスト |
| --- | --- | --- |
| 実物の `ObjectMapper` に、必ず失敗するシリアライザ(`SimpleModule`)を登録したもの | 「JSON 化に失敗しても監査ログの発行は続ける」「JSON 化に失敗したら発行しない」分岐を通す。`ObjectMapper` を Mockito で模してはいない | `AuditLogServiceTest` |
| `IIORegistry`(JDK の ImageIO)から JPEG の書き込み実装を一時的に外し、`finally` で必ず戻す | 「JPEG のエンコーダが無い」防御的な分岐を、実物の `AvatarImageProcessor` で通す | `AvatarImageProcessorNoEncoderTest` |
| `AvatarImageProcessor` の private メソッド(`readJpegOrientation` / `parseExifOrientation`)をリフレクションで直接呼ぶ | マーカー解析の異常系は、復号できる実画像の中には現れない組み合わせのため、公開 API からは踏めない(従来どおり) | `AvatarImageProcessorOrientationParsingTest` |
| `UserService#resolveDefaultRole` をリフレクションで直接呼ぶ | 未知の role は公開 API が先に弾くため(従来どおり) | `UserServiceTest#resolveDefaultRole_未知のroleはnullを返す` |

### 着手時にモックの境界の外だった型

着手時(`bb156f11`)のテストは、23 ファイルで次の型をモックしていた(()内は宣言の出現数)。境界の中の DB・MQ・JWT 検証(後述)を除き、
すべて実物へ書き直した。完了時にこれらをモックしているテストは 0 件。

- 自前のクラス: `KeycloakAdminClient`(8)・`CurrentActorService`(3)・`AuditLogService`(3)・`UserMigrationPersister`(2)・
  `DomainEventPublisher`(2)・`AdminAuthorizationService`(2)・`UserService`・`RoleService`・`ProjectUserSyncService`・
  `PermissionAuthorizationService`・`AvatarService`・`AvatarImageProcessor`・`AvatarStorageService`・
  `OutboundAuthHeaders`(各 1)
- 自前の通信ラッパー(`*Client`): `ProjectServiceClient`・`PublishingServiceClient`(各 1)
- `packages/lbs-common` の通信ラッパー: `ServiceTokenClient`(1)
- 自前の DTO・フレームワークの入口・ライブラリ: `UserProfileResponse`(3。`mock(UserProfileResponse.class)` で戻り値を作っていた)・
  `ObjectMapper`(1)・`HttpServletRequest`(1)・`Advice`(1。AOP の `Advice`)
- 境界の中(DB・MQ・JWT 検証。完了時も `mock()` / `@MockitoBean` のまま): `UserRepository`(7)・`RoleRepository`(3)・
  `JwtDecoder`(6)・`UserSiteAuthorRepository`・`ProjectUserRepository`・`RabbitTemplate`・`ConnectionFactory`(各 1)。
  `RabbitTemplate` は着手時は `DomainEventPublisher` ごとモックしていたものがあり、完了時は `RabbitTemplate` だけをモックして
  `DomainEventPublisher` と `AuditLogService` は実物にした。

## テストから到達できない分岐

完了時に残る 3 分岐。プロダクションコードを変えずには、その分岐に入る入力や状態を作れない理由を具体的に書く。

| # | クラス | メソッド | 行 | 未カバーの分岐 | 到達できない理由 |
| ---: | --- | --- | ---: | --- | --- |
| 1 | `ProjectServiceClient` | `bodyOrMessage` | 111 | `(body != null && !body.isBlank())` の `body != null` が偽 | 引数は `RestClientResponseException` で、`body` は `e.getResponseBodyAsString()` の戻り値。Spring の `RestClientResponseException` はコンストラクタで本文が `null` なら `new byte[0]` に置き換えて保持し、`getResponseBodyAsString()` は常に `String`(本文なしは空文字)を返す。`null` を返す入力が無い。空文字の側(`!body.isBlank()` が偽)は `ProjectServiceClientTest#getProject_本文が空のエラーは例外のメッセージで代える` で通している |
| 2 | `UserService` | `create` | 143 | `if (defaultRole != null)` が偽 | `defaultRole` は 118 行で `resolveDefaultRole(request.role())` の戻り値。`null` を返すのは `role` が `LEGACY_ROLE_TO_ROLE_NAME` のキー(`admin` / `user`)以外のときだけ。ところが 114 行の `validateRole(request.role())` が `VALID_ROLES = {"admin", "user"}`(同じ 2 値)以外を `InvalidRoleException` にしており、118 行へ来る時点で `role` は必ず `admin` か `user`。マスタデータ欠落は `null` ではなく `IllegalStateException` になる(`create_RBACのロールが未投入なら作成せずに落ちる_issue956`)。`resolveDefaultRole` 自体の `null` 戻り値は `resolveDefaultRole_未知のroleはnullを返す`(リフレクション)で通している |
| 3 | `UserService` | `setupInitialAdmin` | 212 | `if (adminRole != null)` が偽 | `adminRole` は 211 行で `resolveDefaultRole("admin")`(リテラル)の戻り値。`"admin"` は `LEGACY_ROLE_TO_ROLE_NAME` のキーなので `null` にならない(`roles` に無ければ `IllegalStateException`)。呼び出し元は `AuthSetupController#setup` だけで、引数の `role` を取らない |

### 到達できる形に直した分岐

着手時に「到達できない」かもしれないと見えた次の分岐は、プロダクションコードを変えずに通せた(上の表には載せない)。

- `AvatarImageProcessor#encodeJpeg` の `!writers.hasNext()`(JPEG の書き込み実装が無い。101 行): ImageIO のレジストリから JPEG の
  `ImageWriterSpi` を一時的に外して `process` を呼び、`finally` で戻す(`AvatarImageProcessorNoEncoderTest`)。同じ JVM の他のテストは
  直列に動くので、戻した後の変換も同じテストで確かめている。
- `AvatarImageProcessor#readJpegOrientation` の「SOI 直後が `0xFF` でない」「EOI・SOS で打ち切る」(233・241 行)と
  `parseExifOrientation` の Exif マジック 6 バイトそれぞれの不一致・`II` / `MM` の 2 文字目の不一致(263・272・274 行):
  既存の `AvatarImageProcessorOrientationParsingTest` は、`while (offset + 4 <= bytes.length)` に入る長さが無く、判定に届かない
  テストが 3 件あった(EOI・SOS・「`0xFF` で始まらない」。戻り値 1 を確かめるだけで通っていたが、その分岐は踏んでいなかった)。
  アサーションは変えずに、ループに入る長さまで続きのバイトを足した。足りない不一致は 1 バイトずつの `@ParameterizedTest` で足した。
- `CurrentActorService` の `X-Forwarded-For`(あり・空白・なし)・`getCurrentActorKeycloakSub`(JWT 以外の認証・認証なし)・
  `subject` が空白、`AdminAuthorizationService#requireAdminAndNotSelf` の「操作者 ID が解決できない」(`id` が `null` の
  `users` 行を実物の `CurrentActorService` に解決させる)・`requireSelfOrAdmin` の「操作者なし」:
  `MockHttpServletRequest` と実際の JWT で通した(`HttpServletRequest` / `CurrentActorService` のモックは不要になった)。
- `KeycloakAdminClient` の姓名の空白・`Location` ヘッダーの欠落・401/409 のメッセージ・各操作の接続断・ロール一覧が配列でない/空本文/
  `id` なし: `MockRestServiceServer` で応答を作って通した(`KeycloakAdminClientTest`)。
- `ProjectUserId` / `UserSiteAuthorId` の `equals` / `hashCode`(JPA は直接呼ばない): 値オブジェクトとして直接呼んで通した
  (`CompositeIdTest`)。

### 書き直しで形を変えたテスト

書き直しで、自前のクラスのモックが「例外や任意の戻り値を返す」前提だったテストは、外部との通信の境界(HTTP の応答・DB の戻り値・MQ への送信)
から同じ状態を作る形にした。検証している振る舞いは減らしていない。形が変わったものを挙げる。

| テスト | 変更 |
| --- | --- |
| `UserServiceTest`(Keycloak 関連の全件) | `verify(keycloakAdminClient).grantRealmRole(...)` などを、フェイクが記録した HTTP 操作(`grant:<sub>:admin`)の確認に置き換えた。`revokeRealmRole` は「付与済みのときだけ DELETE する」という実物のクライアントの挙動なので、剥奪を確かめるテストは `keycloak.alreadyAssigned(sub, "admin")` で付与済みの状態を作る |
| `UserServiceTest#migrateToKeycloak_ローカル保存はUserMigrationPersisterへ委譲される_issue964` | 旧: `userMigrationPersister` のモックに対する `verify(...).saveAndFlush(user)` と `verify(userRepository, never()).saveAndFlush(...)`(委譲先がモックであること)。新: 実物の `UserMigrationPersister` がリポジトリの `saveAndFlush` を 1 回だけ呼ぶことを確かめる。「別トランザクション(REQUIRES_NEW)で実行される」という構造は、新規の `UserMigrationPersisterTest#saveAndFlushは呼び出し元と独立した新規トランザクションで実行される` が `@Transactional(propagation = REQUIRES_NEW)` の宣言で固定する。Hibernate セッションの実際の分離までは、従来どおり単体テストでは確かめられない |
| `UserServiceTest#migrateToKeycloak_ロールバックの保存自体が失敗してもバッチ全体は中断しない_issue966` | `userMigrationPersister` のモックを `doNothing().doThrow(...)` で 2 回目だけ失敗させていた。実物のペルシスターが呼ぶ `userRepository.saveAndFlush` を `doReturn(user).doThrow(...)` で同じ順に失敗させる |
| `UserServiceLastAdminGuardTest` の `InOrder`(2 件) | `inOrder(userRepository, keycloakAdminClient)` を、ロック取得・ローカル保存の瞬間にフェイク Keycloak へ何も(または期待した操作だけ)届いていないことの確認に置き換えた。「ロックを先に取ってから Keycloak を触る」という順序は同じく検証している |
| `AdminAuthorizationServiceTest#requireAdminAndNotSelf_操作者IDが解決できない場合は自己判定をスキップ` | モックの `getCurrentActorId()` が `null` を返す前提だった。実物の `CurrentActorService` では、`admin` だが `id` が `null` の `users` 行を操作者に解決させて同じ状態を作る |
| `PermissionAuthorizationServiceTest` | `User` の `spy`(`hasPermission` の戻り値だけ差し替え)をやめ、実物の `Role` に `Permission` を持たせた `User` にした。「操作者に対応するユーザー行が消えている」場合の拒否を足した |
| `AuditLogServiceTest#logUserRoleUpdated_JSON生成に失敗してもRabbitMQへの発行自体は継続する` | `ObjectMapper` のモックをやめ、必ず失敗するシリアライザを登録した実物の `ObjectMapper` にした |
| `AvatarServiceTest#アップロード時に渡したcontentTypeがそのまま画像処理へ渡る` | `ArgumentCaptor` で `AvatarImageProcessor` へ渡る引数を確かめていた。実物の処理は `contentType` が JPEG のときだけ Exif を読むため、「`image/png` / `image/webp` と宣言された入力も 512x512 の JPEG になる」ことを実画像で確かめる `PNGとWebPと宣言された入力も処理される` に置き換えた(引数の受け渡しそのものは、実物の経路を通る全件が前提にしている) |
| `AvatarControllerTest` / `UserControllerTest` | コントローラのメソッドを直接呼んで、モックの `requireSelfOrAdmin` への `verify` を確かめていた。実物のコントローラ・サービスを MockMvc に載せ、HTTP のステータスと副作用(保存・Keycloak への操作)で確かめる。「認可に失敗したら下流を呼ばない」は、リポジトリ・フェイク Keycloak に何も届かないことで確かめる |
| `RabbitMqConfigTest#prependCorrelationAdviceは既存chainの先頭に相関ID_adviceを足す` | `mock(Advice.class)` を、何もせず通すだけの実物の `MethodInterceptor` にした |
| `OutboundAuthHeadersTest`・`ProjectServiceClientBridgeDateTimeTest` | `mock(ServiceTokenClient)` / `mock(OutboundAuthHeaders)` をやめ、実物の `ServiceTokenClient` がフェイクのトークンエンドポイントからトークンを取る形にした(「ユーザーの Bearer を転送するときはトークンを取りに行かない」の確認を足した) |
| `EventMessageListenerTest` | `ProjectUserSyncService` のモックへの `verify` から、実物のサービスが内部ブリッジ(フェイク HTTP)へ著者プロビジョニングを送り、`user_site_authors` へ保存するところまで確かめる形にした |
| `ProjectUserSyncServiceTest` | `ProjectServiceClient` / `PublishingServiceClient` / `AuditLogService` のモックをやめ、HTTP フェイクと実物の監査ログに置き換えた。例外メッセージの確認は、クライアントが HTTP エラーを `PublishingServiceException` に変換した後の文言(`502` の応答本文に載せた理由)で行う |

統合テスト(`integration/*`)の `@MockitoBean KeycloakAdminClient` は、本物の `KeycloakAdminClient` を Keycloak のフェイクへ向ける形にした
(`KeycloakFakeSupport`)。`@DynamicPropertySource` のメソッドをテストクラスごとに持つと、Spring がそれぞれを別のコンテキスト設定とみなして
DB の接続プールをクラスごとに作り、MySQL の接続上限(151。他のサービスが約 90 を使っている)を超えて落ちたため、メソッドを共通の親に
1 つだけ置いた。

テストを無効化・skip したものは無い(`@Disabled`・`@Ignore`・`assumeTrue` なども追加していない)。
