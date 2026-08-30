# ADR-0008: 認証ゲートは各サービス自身の SecurityConfig で担い、gateway では実施しない

## Status

Accepted

## Context

[ADR-0001](0001-domain-based-microservices.md)によるドメイン単位の分割(Epic #551 Phase 19)で、
legacy-api から platform / project / publishing / content / media / ai / analytics / identity /
log-writer の各サービスを抽出した。この過程で「有効な Keycloak JWT が無ければ401」という
**認証ゲートを誰が実施するのか**が、どこにも記録されないまま宙に浮いた。

- 各サービスの `SecurityConfig` の Javadoc には「gatewayが実際のエンドユーザートラフィックの
  検証を担う想定」という趣旨の記述が入った。
- 一方 gateway の `SecurityConfig` の Javadoc には「実際の認証・認可判定は下流サービス
  (legacy-apiは#566で対応済み、他サービスは#568で対応予定)に委ねる」と書かれた。
  しかし #568 は legacy-api のみを対象として 2026-08-23 にクローズされており、
  新設サービスを対象にする計画は存在しなかった。

つまり gateway と各サービスが互いに相手が認証ゲートを担うと記述しており、結果として
**どちらの層でも認証必須化が行われていない**サービスが生まれた。これは #705 として実際に顕在化した。
legacy-api 時代には401だった `GET /api/system/vscode-extension` 等が、platform-service への移設後
(#693/#694/#695/#696)は Authorization ヘッダー無しでも200を返すようになっていた。移設先の
`SecurityConfig` が他の抽出サービスのテンプレート通り全経路 `permitAll()` だったためである。

#705 の修正(PR #744)で platform-service は明示的な `PUBLIC_PATHS` を除き
`anyRequest().authenticated()` へ変更され、同時に「gatewayを一律 deny-by-default にする案」を
検討したうえで却下した経緯が実装クラスの Javadoc に記録された。ただしその記録は1クラスの
Javadoc にしか存在せず、後続の実装Issue(残り8サービスを揃える #772)が参照できる一次情報が
無い状態だった。本ADRはその実質的に確定済みの方針を、プロジェクトの正式な決定として記録する。

## Decision

**認証ゲート(有効な Keycloak JWT が無ければ401)は、各サービス自身の `SecurityConfig` が担う。**

- 各サービスは、無認証で到達させてよいパスだけを列挙した明示的な `PUBLIC_PATHS` 許可リストを持ち、
  それ以外は `anyRequest().authenticated()`(WebFlux の場合は `anyExchange().authenticated()`)とする。
  すなわちサービス単位の deny-by-default である。
- `PUBLIC_PATHS` に入れてよいのは、ヘルスチェック(Actuator。docker-compose の healthcheck と
  gateway の `DownstreamHealthConfig` が無認証で叩く)、API ドキュメント(`/v3/api-docs/**`、
  `/swagger-ui/**`)、およびそのサービス固有の公開エンドポイント(legacy-api の `/api/health`、
  `/api/auth/setup`、`/api/auth/setup-status` 等)に限る。
- **gateway は素通しのリバースプロキシに留まる。** `authorizeExchange` は
  `anyExchange().permitAll()` のままとし、Bearer トークンが提示された場合は
  `oauth2ResourceServer().jwt()` の標準動作で検証する(不正・期限切れ・署名不正なら401)が、
  **トークンが無いことを理由にリクエストを拒否しない。**

### 参照実装

- 実装: `services/platform/src/main/java/com/letsblog/platform/config/SecurityConfig.java`
  (`PUBLIC_PATHS` を定数で明示し、`requestMatchers(PUBLIC_PATHS).permitAll()` の後に
  `anyRequest().authenticated()` を置く形。同じ形は
  `services/legacy-api/src/main/java/com/letsblog/api/config/SecurityConfig.java` にもある)
- テストのテンプレート:
  `services/platform/src/test/java/com/letsblog/platform/integration/AuthorizationMatrixIntegrationTest.java`
  (gateway 経由で公開する全エンドポイントに対して「Authorization ヘッダー無しなら401」を
  パラメータ化テストで網羅する)

新規サービスを追加するとき、および既存サービスの認証ゲートを変更するときは、この2ファイルを
テンプレートとして複製する。

### 本ADRが決めないこと

- ロールベースの認可(admin/editor/viewer の権限分岐、`@PreAuthorize` へのフル移行)は本ADRの
  対象外。現行どおり `AdminAuthorizationService`/`CurrentActorService` による手続き的チェックが担う。
  「認証済みなら誰でも到達できる」エンドポイントが残っていることは
  `docs/AUTHORIZATION_MATRIX.md` の「既知のギャップ」節に記載済みの別問題である。
- サービス間内部ブリッジ(`/api/internal/{owning-service}/**`)の扱いは本ADRの一般則の例外として
  サービスごとに判断する。platform-service の `/api/internal/platform/**` を `authenticated()` に
  するかは **#742 で別途決定**する(現状は唯一の呼び出し元である legacy-api の
  `PlatformServiceClient` が Bearer トークンを転送しないため `permitAll` で据え置かれている)。
  project-service / publishing-service は逆に `/api/internal/**` のみ `authenticated()` としている。

## Consequences

**利点**

- 公開パスの許可リストが、そのパスを所有するサービス自身の1ファイルに閉じる。エンドポイントの
  追加・移設と同じコミット・同じレビューで許可リストを更新でき、所有者が明確になる。
- gateway を経由せずサービスを直接叩いた場合(コンテナネットワーク内、開発時のポート直叩き)にも
  同じ認証ゲートが効く。gateway 一元化では守れない経路が守られる。
- gateway のルーティング変更が認証の強さに影響しない。ルート追加時に gateway 側の許可リスト更新を
  忘れて公開エンドポイントが401になる、という類の事故が起きない。

**欠点・トレードオフ**

- 同じ形の `SecurityConfig` が全サービスに重複する。共通化はせず、テンプレートの複製として運用する。
- **新サービス追加時に `permitAll` のままのテンプレートをコピーすると、認証ゲートが静かに
  抜け落ちる。** これはまさに #705 で起きた後退であり、本方式の最大のリスクである。

**リスクの検知手段(必須)**

上記の後退を検知するため、**REST API サーフェスを持つサービスを新設するとき、および既存サービスの
`SecurityConfig` を変更するときは、`AuthorizationMatrixIntegrationTest` を必ず追加・更新する。**
テンプレートは上記
`services/platform/src/test/java/com/letsblog/platform/integration/AuthorizationMatrixIntegrationTest.java`。
`SecurityConfig` が `permitAll` に戻れば、このテストの「未認証なら401」アサーションが落ちる。
併せて、どのランタイムに統合テストが整備済みかを `docs/AUTHORIZATION_MATRIX.md` の
「認証ゲートの実施レイヤー」節の一覧で追跡する。

## Notes: 本ADR(#713)時点の実施状況

本ADRは方針の記録であり、全サービスの実装追随は伴わない。2026-08-30 時点の実態は次のとおり。

| ランタイム | 状態 |
|---|---|
| gateway | 方針どおり(`anyExchange().permitAll()`。認証ゲートは担わない) |
| legacy-api | 方針どおり(#566) |
| platform | 方針どおり(#705 / PR #744。参照実装) |
| project / publishing | 部分的(`/api/internal/**` のみ `authenticated()`) |
| content / ai / analytics / media / identity / log-writer | 未実施(`anyRequest().permitAll()`) |

残り8サービス(content / ai / analytics / media / identity / project / publishing / log-writer)の
`SecurityConfig` を本ADRの形へ揃える作業と、各サービスへの `AuthorizationMatrixIntegrationTest`
追加は **#772** が担当する。

これら8サービスの `SecurityConfig` の Javadoc に残る「gatewayが実際のエンドユーザートラフィックの
検証を担う想定」という記述は本ADRと矛盾するが、**その是正も #772 に委ねる**(本ADRを追加した
#713 では8ファイルとも変更しない)。理由は、当該コメントが直下の `authorizeHttpRequests` の記述と
一体であり、コメントだけを先に直すと #772 のマージ時に同一 hunk で衝突するうえ、その中間状態は
「コメントは正しいが挙動は `permitAll` のまま」という最も誤解を招く状態になるためである。
gateway だけは方針上そもそも挙動を変えない(素通しのままが正しい)ため例外とし、#713 で
Javadoc のみを是正した。

## Alternatives considered

**gateway での一元的な deny-by-default(却下)**

gateway の `authorizeExchange` を `anyExchange().authenticated()` にし、公開パスだけを許可する案。
認証ゲートが1ファイルに集約され、各サービスの `SecurityConfig` を触らずに全サービスを一度に
守れるという利点がある。しかし以下の理由で却下した(却下理由は #705 の修正 PR #744 が
`services/platform/src/main/java/com/letsblog/platform/config/SecurityConfig.java` の Javadoc に
記録したものと同一である)。

- **gateway は全サービス共通の経路であり、全サービス分の公開パス許可リストを gateway 側に
  二重管理することになる。** legacy-api の `/api/auth/setup`・`/api/auth/setup-status`・`/api/health`、
  各サービスの Actuator(`/actuator/**`)・API ドキュメント(`/v3/api-docs/**`・`/swagger-ui/**`)まで
  gateway が知る必要が生じる。サービス側で公開パスを1本増やすたびに gateway の許可リストも
  更新しなければならず、更新漏れは「公開のはずのエンドポイントが401」という形で本番障害になる。
- **サービスを直接叩く経路が守られない。** gateway を経由しないコンテナ間の呼び出しや開発時の
  ポート直叩きには gateway の判定が効かないため、結局サービス側にもゲートが必要になる。
- **既存の先例と整合しない。** project-service / publishing-service は既に `/api/internal/**` に
  対して「所有サービス自身の `SecurityConfig` で閉じる」方式を採っている。

**現状維持(gateway も各サービスも `permitAll` のまま、認可はサービス層の手続き的チェックのみ)(却下)**

`CurrentActorService`/`AdminAuthorizationService` が JWT の sub クレームから actor を解決し、
admin/権限チェックを手続き的に行っているため、admin 限定操作は JWT 無しでは通らない。
しかし、これらのチェックを呼ばないエンドポイント(`docs/AUTHORIZATION_MATRIX.md` の
「既知のギャップ」節に列挙)は無認証で到達できてしまう。#705 で実際に
`GET /api/system/vscode-extension` が無認証で200を返していたのがこの状態であり、
「認証ゲートは全エンドポイントに一律で掛かる」という不変条件を持てないため却下した。

**`@PreAuthorize` による宣言的認可へのフル移行(本ADRでは採用しない)**

エンドポイントごとに必要なロールを宣言できるため表現力は高いが、本ADRが解こうとしている問題
(認証ゲートの実施レイヤーが決まっていない)に対しては過剰であり、全コントローラの改修を伴う。
認証ゲートを `SecurityConfig` で確立することと、その上のロール認可をどう表現するかは独立に
決められるため、後者は別途判断する。
