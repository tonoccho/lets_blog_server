# 認可マトリクス

本ドキュメントは、どのサービスのどのエンドポイントが、誰の認証ゲートで守られるかを引くための
一次情報である。冒頭の「認証ゲートの実施レイヤー」でランタイム横断の方針と実施状況を示し、
以降にエンドポイント単位のマトリクスを置く。

## 認証ゲートの実施レイヤー

「有効なKeycloak JWTが無ければ401」という**認証ゲートは、各サービス自身の`SecurityConfig`が担う**。
各サービスは明示的な`PUBLIC_PATHS`許可リストを持ち、それ以外は`anyRequest().authenticated()`
(WebFluxなら`anyExchange().authenticated()`)とする。gatewayは素通しのリバースプロキシに留まり、
Bearerトークンが提示されていれば検証するが、トークンが無いことを理由に拒否はしない。

決定の根拠・検討した代替案(gatewayでの一元的なdeny-by-default)・その却下理由は
[ADR-0008: 認証ゲートは各サービス自身の SecurityConfig で担い、gateway では実施しない](adr/0008-auth-gate-in-each-service-security-config.md)
を参照。

### ランタイム別の実施状況(2026-08-30時点、#772適用後)

| ランタイム | 認証ゲートの担い手 | 公開パス | 対応する統合テスト |
|---|---|---|---|
| gateway | 担わない(方針どおり。ADR-0008)。提示されたトークンの検証のみ | 全経路(`anyExchange().permitAll()`) | 該当なし(ゲートを担わないため) |
| legacy-api(**issue #583/#917で削除済み。`services/legacy-api`はリポジトリに存在しない**) | 自サービスの`SecurityConfig`(#566) | `/api/health`、`/api/auth/setup`、`/api/auth/setup-status`、`/actuator/**`、`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html` | 該当なし(サービス自体が削除済み。移設先の各行を参照) |
| platform | 自サービスの`SecurityConfig`(#705。**参照実装**) | `/actuator/**`、`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html`(`/api/internal/platform/**` は #742 でJWT必須へ移した) | `services/platform/src/test/java/com/letsblog/platform/integration/AuthorizationMatrixIntegrationTest.java`(**テストのテンプレート**) |
| identity | 自サービスの`SecurityConfig`(#772) | `/actuator/**`、`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html` | `services/identity/src/test/java/com/letsblog/identity/integration/AuthorizationMatrixIntegrationTest.java` |
| project | 自サービスの`SecurityConfig`(#772) | `/actuator/**`、`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html` | `services/project/src/test/java/com/letsblog/project/integration/AuthorizationMatrixIntegrationTest.java` |
| content | 自サービスの`SecurityConfig`(#772) | `/actuator/**`、`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html` | `services/content/src/test/java/com/letsblog/content/integration/AuthorizationMatrixIntegrationTest.java` |
| media | 自サービスの`SecurityConfig`(#772) | `/actuator/**`、`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html` | `services/media/src/test/java/com/letsblog/media/integration/AuthorizationMatrixIntegrationTest.java` |
| ai | 自サービスの`SecurityConfig`(#772) | `/actuator/**`、`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html` | `services/ai/src/test/java/com/letsblog/ai/integration/AuthorizationMatrixIntegrationTest.java` |
| analytics | 自サービスの`SecurityConfig`(#772) | `/actuator/**`、`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html` | `services/analytics/src/test/java/com/letsblog/analytics/integration/AuthorizationMatrixIntegrationTest.java` |
| publishing | 自サービスの`SecurityConfig`(#772) | `/actuator/**`、`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html` | `services/publishing/src/test/java/com/letsblog/publishing/integration/AuthorizationMatrixIntegrationTest.java` |
| log-writer | 自サービスの`SecurityConfig`(#772) | `/actuator/**`、`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html` | `services/log-writer/src/test/java/com/letsblog/logwriter/integration/AuthorizationMatrixIntegrationTest.java` |

**#772** で、残っていた8サービス(content / ai / analytics / media / identity / project /
publishing / log-writer)を ADR-0008 の形へ揃えた。同時に、これら8サービスの`SecurityConfig`の
Javadoc に残っていた「gatewayが実際のエンドユーザートラフィックの検証を担う想定」という
ADR-0008 と矛盾する記述も、挙動の変更と同じコミットで是正した。gatewayのみ、方針上そもそも
挙動を変えないため例外として #713 で Javadoc を是正済み。

identity-service / log-writer が従来から行っていた、各コントローラーによる
`CurrentActorService`/`AdminAuthorizationService`経由の手続き的チェック(JWTのsubクレームを読む)は
そのまま残る。これは「認証済みなら誰でも到達できるエンドポイントが残る」という別の問題で、
#772 が復元したのはその手前にある一律の認証ゲートである。
(その別の問題は #830 で解消した。後述の「認可チェックの網羅状況(issue #830)」を参照。)

各サービスのエンドポイント単位の一覧は、上表の「対応する統合テスト」列が指す
`AuthorizationMatrixIntegrationTest` の `allProtectedEndpoints()` が一次情報である
(gateway経由で公開している全エンドポイントと内部ブリッジを列挙し、「Authorizationヘッダーが
無ければ401」をパラメータ化テストで網羅する)。以降のエンドポイント単位のマトリクス(2層目の
認可チェックの有無)は、現時点では legacy-api(および legacy-api から移設された分の注記)のみを
対象とする。

#### #772 で認証必須化した結果、未認証では到達しなくなった既知の呼び出し

| 呼び出し元 | エンドポイント | 扱い |
|---|---|---|
| `apps/web/src/lib/errorLogger.ts`(#791 以前はブラウザから直接) | `POST /api/logs/errors` | legacy-api 時代も401だったため後退ではない。**#791 で是正済み**: ブラウザは同一オリジンのBFF `POST /client-errors`(`apps/web/src/app/client-errors/route.ts`)を呼び、そこから server-only の `apiClient` 経由でBearer付きで log-writer へ中継する。log-writerの`PUBLIC_PATHS`は増やしていない(未認証の書き込み経路を残さないため) |
| `scripts/provision-e2e-keycloak-users.sh` | `POST /api/users` | #772 で `letsblog-services` の Client Credentials を使うようにしたが、#796 で同エンドポイントが admin 限定になったため方式を変更した。サービスアカウントの `sub` に対応するローカル `users` 行が無く `CurrentActorService` が操作者を解決できないため、Client Credentials トークンでは `requireAdmin()` を通れない。現在は `letsblog-e2e` の password グラントで**実在する admin ユーザー**のトークンを取得する |

#### identity-service の `/api/users` の認可(#796・#798 適用後)

認証ゲート(#772)は「有効なJWTが無ければ401」までしか担わない。その先の**認可**は
`AdminAuthorizationService` によるコントローラ層の手続き的チェックが担う。

| エンドポイント | 認可 | 備考 |
|---|---|---|
| `GET /api/users` | `requireAdmin()` | #653 で追加 |
| `POST /api/users` | `requireAdmin()` | **#796 で追加**。`UserCreateRequest` が `role` を受け取るため、認可が無いと `role=admin` のアカウントを誰でも作れた(権限昇格) |
| `PATCH /api/users/{id}` | `requireAdmin()` + `requireNotSelfDemotion(id, role)` | **#796 で追加、#798 で自己降格ガードを追加**。扱うのは `role` / `password` で管理者が管理する項目。本人に許すと自分の `role` を admin へ書き換えられる。逆に admin が自分を `role="user"` へ降格すると admin 限定エンドポイントが全て閉じて復旧できなくなるため、**自分自身を admin 以外へ変更すること**も禁止した(パスワードのみの更新と admin→admin は通る)。**#1427 で「最後の有効な admin」の降格も拒否**(403) |
| `DELETE /api/users/{id}` | `requireAdminAndNotSelf(id, ...)` | **#796 で追加**。無効化が admin 限定なのに削除に認可が無い非対称を解消。あわせて自己削除も禁止(最後の admin が自分を消して誰も管理できなくなるのを防ぐ)。**#1162 で「最後の有効な admin」の削除も拒否**(403) |
| `POST /api/users/{id}/deactivate` | `requireAdminAndNotSelf(id, ...)` | **#798 で自己ガードを追加**。#796 は自己「削除」だけを禁止し「無効化」を放置していた。admin が自分を無効化するとログインできなくなり、他に admin がいなければ `reactivate` も `requireAdmin()` を要求するため誰も復旧できない。**#1162 で「最後の有効な admin」の無効化も拒否**(403) |
| `POST /api/users/{id}/reactivate` | `requireAdmin()` | 自己ガードは付けていない。#798 の時点では「無効化しても発行済みトークンが失効しないため厳密には自己 reactivate が可能」だったが、それは無効化全般のギャップ(下記)であり `reactivate` 固有ではないとして #816 に委ねた。**#816 で無効化ユーザーが操作者として解決されなくなったため、自己 reactivate は実際に不可能になった**(`requireAdmin()` の手前で 403)|
| `POST・DELETE /api/users/{userId}/roles/{roleName}` | 特権ロールは `requireAdmin()`、それ以外は `requirePermission(ROLE_MANAGE)` | **#798 で変更**。下記参照 |
| `POST /api/users/migrate-to-keycloak`・`/reconcile-keycloak` | `requireAdmin()` | 従来どおり |
| `GET /api/users/{id}`・`PUT /api/users/{id}`・`PATCH /{id}/preferences`・`PUT /{id}/github-token` | `requireSelfOrAdmin(id)` | 本人が変更してよいプロフィール項目。個人設定は `PATCH /api/identity/me/preferences`(#784)も使える |
| `POST /api/users/{id}/avatar`・`GET /api/users/{id}/avatar` | `requireSelfOrAdmin(id)` | **issue #1241 で追加**。プロフィール編集画面のアバター画像アップロード/配信。アバター画像も本人のプロフィール情報の一部として、上記と同じ認可に揃えている(`AvatarController.java`) |
| `GET /api/identity/me`・`/me/permissions`・`PATCH /me/preferences` | 自ユーザー限定(JWTの `sub` から解決) | クライアントから識別子を受け取らないため、ID の取り違えが構造的に起きない(#784) |

##### ロール付与・剥奪の認可(#798)

`POST・DELETE /api/users/{userId}/roles/{roleName}` は従来 `requirePermission(ROLE_MANAGE)` だけで
守られており、admin 判定ではなかった。既定シード(`V8__add_rbac_tables.sql`)で `ROLE_MANAGE` を
持つのは `ROLE_ADMIN` のみなので既定データでは実害が無いが、運用で非 admin ロールに
`ROLE_MANAGE` を付与すると、そのユーザーは `POST /api/users/{自分のid}/roles/ROLE_ADMIN` で
自分に特権ロールを付けられた。#796 が `PATCH /api/users/{id}` について塞いだのと同型の経路である。

**特権ロール**を「ロールを配れる権限(`ROLE_MANAGE` または `USER_ROLE_MANAGE`)を与えるロール」と
定義し、その付与・剥奪だけを `requireAdmin()` にした(`RoleService#isPrivilegedRole`)。
それ以外のロールは従来どおり `requirePermission(ROLE_MANAGE)` で、`ROLE_MANAGE` という権限が
無意味にならないようにしている。

剥奪も同じ扱いにしているのは対称性のため。付与が admin 限定なのに剥奪が `ROLE_MANAGE` のままだと、
`ROLE_MANAGE` 保有者が admin たちから特権ロールを剥がして回れてしまう。

**判定はロール名の文字列一致ではなく、DB から解決したロールの権限で行う**。MySQL の照合順序は
大文字小文字を区別しない(`lets_blog` はサーバー既定の `utf8mb4_0900_ai_ci`、テスト用
`lbs_identity_test` は `utf8mb4_unicode_ci`。どちらもアクセントと大小の差を無視する。
末尾空白の扱いだけは異なり、`utf8mb4_0900_ai_ci` は NO PAD なので無視しない)ため、
`findByRoleName("role_admin")` は `ROLE_ADMIN` の行に一致する。
`"ROLE_ADMIN".equals(roleName)` のような名前一致でガードすると、大小を変えただけの入力で
ガードだけをすり抜け、割り当て処理では同じ行に解決される、という迂回が成立する。

安全性は「正規化を網羅したから」ではなく**構造から**来ている。判定と割り当てが同じ
`findByRoleName(roleName)` を同じ入力文字列で呼ぶため、両者の解決結果は必ず一致する。
全角・Unicode 正規化・末尾空白といった照合順序の差異は、この構造の下では分岐点になりえない。

**「最後の有効な admin」の削除・無効化は拒否する(#1162)**。admin が2人以上いれば互いに削除・無効化でき、
それは正当な運用である。`DELETE /api/users/{id}` と `POST /api/users/{id}/deactivate` は、対象が
「有効(`enabled=true`)な `role=admin` の最後の1人」であれば 403(`最後の管理者は削除できません` /
`最後の管理者は無効化できません`)で拒否する。

**これは #798 の判断(「最後の admin か」は数えない)を覆す変更である。** #798 が数えなかった理由は、
数える設計にすると「他の admin が同時に自分を消す」レースで両者とも通る検査時-使用時(TOCTOU)の穴が
生まれる、というものだった。#1162 で利用者が「最後の admin の保護を実装する」と決定した(2026-09-08)ため、
その穴を実装で塞いだうえで数えるようにした。#798 の懸念そのものは撤回されておらず、塞ぎ方が要点である。

**TOCTOU の塞ぎ方**: `UserService#delete` / `#deactivate`(`@Transactional`)の中で、有効な admin の
全行を `SELECT ... FOR UPDATE`(`UserRepository#lockEnabledAdmins`、`ORDER BY id`)で取得して悲観ロックし、
ロック済みの集合の件数と対象の所属で判定してから、同じトランザクション内で変更する。ロックは変更のコミットまで
保持されるので、admin が互いを同時に消す/無効化するとき、後発は先発のコミットを待ち、コミット後の状態
(有効な admin が1人)で数え直して拒否される。数えてから変更するまでの間に割り込む余地が無い。
Keycloak 呼び出しはこの検査より後に置く(拒否時に外部を触らない)。検証は実 MySQL に対する
`LastAdminGuardIntegrationTest`(2人の admin が互いを同時に削除/無効化する競合を繰り返し、必ず1人残ること)。

自己削除・自己無効化の禁止(`requireAdminAndNotSelf`)は従来どおり残る。**#1427 で `PATCH` による降格も同じ判定の対象にした**:
`UserService#update` は `role` を admin 以外へ変更するとき、削除・無効化と同じ `requireNotLastEnabledAdmin`
(同じ `lockEnabledAdmins` の悲観ロック下、Keycloak 呼び出しより前)で、対象が最後の有効な admin なら
403(`最後の管理者は降格できません`)で拒否する。admin が2人以上なら互いに降格できる。

##### 無効化されたユーザーの発行済みトークン(#816 で一部解消)

`deactivate` は Keycloak 側とローカルの `users.enabled` を落とすが、**すでに発行済みの
アクセストークンは失効しない**(`infra/keycloak/realm-export.json` の `accessTokenLifespan: 300`)。
Keycloak が止めるのは新規のトークン発行だけで、既存トークンの署名も有効期限も変わらない。

#816 以前は identity-service の `CurrentActorService#resolveJwtActor` が `User.enabled` を
参照しなかったため、無効化直後のユーザーは最大5分間、admin 操作を含めて通常どおり API を
通せた。無効化された admin が自分自身を `reactivate` して復帰することもでき、
退職者や侵害されたアカウントを即時に締め出せなかった。

**#816 で `resolveJwtActor` が無効化ユーザーを操作者として解決しないようにした。**
JWT の検証(署名・有効期限・issuer)自体は通っている以上 401 ではなく、
「認証は済んでいるが操作者として扱わない」という扱いになる。

| サービス | 操作者の解決経路 | 対応 | 無効化ユーザーが受け取るステータス |
|---|---|---|---|
| identity | 自身の `users` テーブル | `resolveJwtActor` で `enabled` を検査 | **403** |
| legacy-api | 共有スキーマの `users` テーブルを自前参照(#786) | 同上。`User` に `enabled` の読み取り専用マッピングを追加 | **403** |
| platform | `GET /api/identity/me`(ただし `requireAuthenticated()` は JWT の `sub` だけを見ていた) | `isAuthenticated()` を操作者の解決可否による判定へ変更 | **403**(#829で502から是正) |
| 他7サービス(ai / analytics / content / log-writer / media / project / publishing) | `GET /api/identity/me` への同期呼び出し(`IdentityClient`) | `lookupProfile` が401/403を「操作者なし」へ分岐(#829) | **403**(#829で502から是正) |

###### 401/403(認証・認可の結果)とサービス障害(502)の区別

identity が返す 403 は、呼び出し側で `SyncServiceClientErrorException`
(`SyncServiceException` のサブクラス)に変換される。

**#829 以前**は、各サービスの `CurrentActorService#lookupProfile` がこれを一律に
`IdentityServiceUnavailableException` へ再変換し、`GlobalExceptionHandler` が
**502 Bad Gateway** にマップしていた。拒否はされる(fail-closed)ものの、
無効化ユーザー起因の 502 とサービス障害起因の 502 が区別できず、無効化ユーザーが
画面を開いたままにしているだけで `accessTokenLifespan`(既定300秒)の間、
各サービスが 502 と ERROR ログを出し続けて監視を誤爆させていた。

**#829 で、認証・認可の結果とサービス障害を分けた。**

| identity からの応答 | 扱い | 最終的なステータス |
|---|---|---|
| 401 / 403 | 「操作者なし」(`Optional.empty()`) | 呼び出し先の `requireAdmin()` 等が拒否し **403** |
| 上記以外の 4xx(404 等) | 例外のまま伝播 | **502** |
| 5xx・タイムアウト・通信断・サーキットオープン | 例外のまま伝播 | **502** |

5xx 以下を一緒に「操作者なし」へ縮退させていない点が重要である。そうすると
identity-service 障害時に権限チェックが素通りする方向の不具合になりうるため
(各サービスの `lookupProfile` の Javadoc に置かれた設計判断)。
`IdentityServiceUnavailableException` の ERROR ログは、これにより
**本当にサービスが応答しない場合にだけ**出るようになった。

判定は共通化してある。lbs-common の `IdentityClient#lookupProfile`(6サービスが利用)と、
独自クライアントを持つ project / publishing の `IdentityClient#lookupProfile`。

###### この対処が効く範囲(重要)

**actor を解決するエンドポイントに限る。** 本ドキュメント末尾の
「有効な JWT さえあれば到達できるエンドポイント」の節に挙げたものは
`CurrentActorService` を呼ばないため、**無効化ユーザーも `accessTokenLifespan` の間は
引き続き到達できる**(`PostController` の WordPress 投稿公開・削除、`SiteController`、
`DiagramController` の CRUD、`GenerationJobController` の作成・更新など)。

これらを塞ぐには認可チェックそのものを足す必要があり、#816 のスコープ外。

`reconcile-keycloak` で無効化された孤児ユーザーにも同じ判定が効く。

##### web の Server Action の認可(#824)

`"use server"` を付けた関数はブラウザから直接 POST できるエンドポイントになる。
**認可を書き忘れても動いてしまい、型検査もリントも警告しない。**

#824 の時点で認可呼び出しを持たない Server Action が7個あり、
「意図的に未認証であるべきもの」と「付け忘れ」が混在していた。

| Server Action | 判断 | 理由 |
|---|---|---|
| `setup/actions.ts: setupAction` | **意図的に未認証** | 初期セットアップはまだ誰もアカウントを持たない状態で実行する。保護はサーバー側(`UserService#setupInitialAdmin` が既存ユーザーがいれば拒否) |
| `sites/actions.ts: registerSiteAction` | `requireAdminSession()` | SSH 認証情報を保存しインフラを作る操作。同ファイルの削除・WP-CLI導入・静的コンテンツ生成・SSH鍵生成はすべて admin 限定 |
| `sites/actions.ts: createManagedWordPressSiteAction` | `requireAdminSession()` | 同上 |
| `sites/actions.ts: checkSiteConnectionAction` | `requireSession()` | `CheckConnectionButton` は `isAdmin` ガードの**外**で描画され、ログイン済みなら誰でも押せる想定。ただし外部サイトへ接続を試みるため未認証で通してはいけない |
| `image-gallery/actions.ts` の3つ | `requireSession()` | `/image-gallery` は `ADMIN_ONLY_PREFIXES` に含まれず、画面も `isAdmin` の出し分けをしていない。認可の粒度を画面に揃える |

**挙動の変更**: これまで非 admin でもサイトを登録できた。#824 以降は 403 になる。

###### 再発防止

`apps/web/src/__tests__/serverActionAuthorization.test.ts` が `src/app` 配下の
`actions.ts` を走査し、各 Server Action が
**認可呼び出しを持つか、JSDoc に「意図的に未認証」と理由を書いているか**のどちらかであることを
検証する。次に Server Action を足したとき、どちらも無ければ落ちる。

同じ手法の先例は `services/gateway` の `RouteControllerContractTest` と
`DownstreamHealthConfigContractTest`(#743)。

###### これは多層防御である

バックエンドが Bearer トークンで認証・認可するのが本来の関門。
各サービスの `SecurityConfig` は `anyRequest().authenticated()`(ADR-0008)なので、
**未認証はバックエンドでも弾かれる**。

ただし `GeneratedImageController` や `SiteController` の `register` /
`createManagedWordPress` / `adopt` / `list` / `testConnection` のように、
**認証は要求するがロール・所有者による絞り込みを持たない**エンドポイントがある(#830)。
その範囲では Server Action の認可が実質的に唯一の絞り込みになっている。

`SiteController` でも `getDetail` / `update` / `delete` / `installWpCli` / `reprovision` /
`generateSshKeyPair` は `requireAdmin()` を呼んでおり、欠けているのは一部のエンドポイントに限る。

##### 注意: admin には別軸が2つある

ここでの `requireAdmin()` は `users.role` カラムが `"admin"` であることを指す
(`CurrentActorService#isAdmin`)。RBAC の `ROLE_ADMIN`(`user_roles` / `roles` テーブル)とは
**別軸**で、`ROLE_ADMIN` を自分に付けても `requireAdmin()` のエンドポイントは開かない。
逆に、`ROLE_ADMIN` を持たない `users.role = "admin"` のユーザーは `requirePermission(...)` を
通れない。既定シードは admin ユーザーに `ROLE_ADMIN` も割り当てるため通常この差は表面化しない。

この2軸が並存していること自体は整理の余地があり、**#815** で追跡している。
#798 は次の非対称を1つ増やしている: `users.role = "admin"` だが `ROLE_ADMIN` を持たないユーザーは、
**特権ロールは付与できる**(`requireAdmin()` を通るため)のに、
**特権でないロールは付与できない**(`requirePermission(ROLE_MANAGE)` を通れないため)。
`users.role = "admin"` はこのコードベースの最上位権限なので昇格には当たらず、
自分に `ROLE_ADMIN` を付ければ自力で解消できるが、直感には反する。#815 で扱う。


Web 管理画面は `requireAdminSession()` で守られているが、gateway は認可判定を行わない
(ADR-0008)ため、アクセストークンを持つクライアントは API を直接叩ける。**クライアント側の
防御だけでは不十分**であり、サーバー側の認可が一次的な防御である。

---

## legacy-api のエンドポイント別マトリクス(#583/#917で削除済み。以下は解体完了直前の記録)

issue #568。**`services/legacy-api` はissue #583で解体され、#917でリポジトリから完全に削除された
(2026-08-31。以下のディレクトリ・コマンドはもう存在せず再実行できない)。**
本節は削除される直前に `services/legacy-api` に残っていた REST API エンドポイントの記録であり、
下記「以降の表に出てくる移設済みコントローラの現在の所有サービス」がその後の移設先の一覧として
今も有効である。個々のエンドポイントの現行の認可チェックは、移設先サービスの
`## XxxController` 節(本ファイル後半)を参照すること。

**実測値(2026-08-31 時点、develop、削除直前): 53エンドポイント / 11コントローラファイル**
(`HealthController` を含む)。

```bash
# 実行不能(services/legacy-api は削除済み)。削除前の実測手順として記録のみ残す。
ls services/legacy-api/src/main/java/com/letsblog/api/controller/*.java | wc -l
grep -rhoE '@(Get|Post|Put|Delete|Patch)Mapping' \
  services/legacy-api/src/main/java/com/letsblog/api/controller/*.java | wc -l
```

| コントローラ | エンドポイント数 |
|---|---|
| `ProjectApiKeyController` | 14 |
| `ProjectController` | 9 |
| `ProjectAiModelController` | 6 |
| `ContentBridgeController` | 6 |
| `ProjectUserBridgeController` | 5 |
| `AiBridgeController` | 4 |
| `AiController` | 3 |
| `AuthController` | 2 |
| `AnalyticsBridgeController` | 2 |
| `ProjectUserController` | 1 |
| `HealthController` | 1 |

### この数値の扱いについて(issue #733)

**この文書の数値は、Epic #551 のドメイン分割による移設に自動追随しない。** 以前この節は
「191件・33ファイル」→「174件・28ファイル」→「166件」と記述し、あわせて「このマトリクス自体は
各stageの移設時に更新した」と書いていたが、実際には #573 以降の多数の移設Issue
(#574 ai / #576 content / #577 project / #578 analytics / #693-#696 platform /
#707・#708・#709 publishing 等)に追随できておらず、実態と大きく乖離していた
(#733 起票時点の実測は67件で、その後さらに移設が進み現在は53件)。

エンドポイントを移設・追加する際は、上記コマンドで再計測してこの節を更新すること。

### 以降の表に出てくる移設済みコントローラの現在の所有サービス

以降のエンドポイント別マトリクスには、**legacy-api から既に他サービスへ移設されたコントローラの行が
多数含まれている**(認可の内容自体は移設後も同じため、記録として残している)。
行の「備考」欄に所有サービスを注記しているものもあるが、注記の無いものを含め、
2026-08-31 時点の実際の所有サービスは次のとおり。**いずれも legacy-api には存在しない。**

| コントローラ | 現在の所有サービス |
|---|---|
| `MetadataController` / `CustomTagController` / `CustomTagTemplateController` / `ProjectCustomTagController` / `ContentCacheController` | content |
| `PostController` | content と publishing の両方に同名クラスがある(記事本文の管理が content、公開処理が publishing) |
| `SiteController` / `SiteStaticContentController` / `SshKeyPairController` / `TagDesignSettingController` | project |
| `ArticlePlanController` | ai |
| `ProjectDashboardController` | analytics |
| `OperationLogController` / `OperationLogStatsController` / `FrontendErrorLogController` | log-writer |
| `InternalPlatformSettingsController` | platform |
| `DiagramController` / `GeneratedImageController` / `RenderController` / `MediaController` | media |
| `CmsMediaBridgeController` / `TaxonomyController` | publishing |
| `ArticlePreviewController` | content と publishing の両方に同名クラスがある |
| `GenerationJobController` | ai |
| `AppSettingController` / `SystemSettingController` / `DashboardController` / `BackupController` / `VscodeExtensionController` | platform |
| `AuditLogController` | log-writer |

各行の「未認証で401になるか」は、移設先サービスの `SecurityConfig` が担う。
**#772 で全サービスに認証ゲートが揃った**(冒頭の「認証ゲートの実施レイヤー」節を参照)。
本節が記録している2026-08-31時点(legacy-api削除直前)には既に#772が適用済みだった。

`CmsMediaBridgeController` は #573 で legacy-api に追加されたのち #709 で publishing-service へ
移設され、パスも `/api/internal/cms/**` から、他の内部ブリッジと同じ
`/api/internal/{owning-service}/**` の命名規則に合わせて `/api/internal/publishing/**` 配下へ
変更されている。legacy-api の統合テストの401チェック対象からも除外済み。

なお #566 で `AuthController` のログイン・2FA・パスワードリセット系8エンドポイントが撤去され、
現在 `AuthController` に残るのは公開パスの `setup` / `setup-status` の2件のみである。

当時対応していた統合テスト`services/legacy-api/src/test/java/com/letsblog/api/integration/AuthorizationMatrixIntegrationTest.java`は、
`services/legacy-api`自体の削除(#583/#917)に伴い存在しない。現行の対応する統合テストは、
移設先の各サービスの`AuthorizationMatrixIntegrationTest`(冒頭の「認証ゲートの実施レイヤー」節の表を参照)。

## 認可チェックの網羅状況(issue #830)

認証ゲート(#772、ADR-0008)は全サービスに入ったが、**その先の認可**(誰が何をしてよいか)は
まだ全エンドポイントに行き渡っていない。有効なJWTさえあれば到達できるエンドポイントが残っている。

### 実測(2026-08-31、develop)

全コントローラを走査し、認可呼び出し(`requireAdmin` / `requireSelfOrAdmin` /
`requireProjectMemberOrAdmin` / `requirePermission` 等)の有無を数えた。

**コントローラのメソッド本体だけでなく、委譲先のサービスが認可している場合も「認可あり」と数える。**
認可をサービス層に置く実装があるため(legacy-api の `ProjectApiKeyService` は全 public メソッドで
`requireProjectMemberOrAdmin` を呼ぶ)、コントローラだけを見ると**偽陽性**になる。
実際、この補正で **98件 → 56件**へ下がった(42件はサービス層で認可済みだった)。

| | 件数 |
|---|---|
| 総エンドポイント | 278 |
| **認可なし(内部ブリッジを除く)** | **0**(#830 の初回計測時は 56) |

サービス別の内訳:

| サービス | 認可なし | 内容 |
|---|---|---|
| **media** | **0** | #830 で解消 |
| **ai** | **0** | #830 で解消。下記「解消済み」参照 |
| **project** | **0** | #830 で解消 |
| **content** | **0** | #830 で解消 |
| **legacy-api** | **0** | #830 で解消 |
| **platform** | **0** | #830 で解消 |
| **publishing** | **0** | #830 で解消。下記「解消済み」参照 |
| **log-writer** | **0** | #830 で解消 |
| **analytics / identity / publishing / ai / platform / log-writer / legacy-api** | **0** | 全エンドポイントが認可済み、または理由付きで「認可不要」 |

内部ブリッジ(`/api/internal/**`)は対象外とした。サービス間呼び出し専用で gateway からは
到達せず、認可はトークンを転送する呼び出し元が担うため。

### 再発防止: ラチェット

個々について「認証のみでよいか、認可が必要か」を決めるのは**製品判断**を伴い、一度には片付かない。
一方でその間に新しい無認可エンドポイントが増え続けると差は開く一方になる。

そこで **`AuthorizationCoverageContract`**(`packages/lbs-common` の testFixtures)で
現状を許可リストとして固定し、**増えることだけを止める**。

- 許可リストに**無い**無認可エンドポイントが現れたら失敗する(新規の付け忘れを検知)
- 許可リストにあるのに**もう無認可でない**ものがあれば失敗する(解消したらリストから消させ、
  リストが実態から乖離しないようにする)
- 意図的に認証のみでよい場合は、そのメソッドのコメントに **`認可不要: <理由>`** と書けば
  許可リストに載せなくてよい

Spring コンテキストを起動しない静的解析なので、DBもコンテナも不要である。
先例は同パッケージの `AuthorizationMatrixContract`(認証ゲートの後退検知、#805)。

**この契約テストは「認可が正しいか」を判定しない。** 認可呼び出しが*書かれているか*だけを見る。
呼んでいる認可が適切かどうかはレビューの仕事である。

**全10サービスに導入済み**(各サービスの `AuthorizationCoverageTest`)。
DBもコンテナも要らないため、MySQL が未公開の環境でも実行できる(実測で確認済み)。

許可リストは `AuthorizationCoverageContract.currentUnauthorized("<service>")` で生成できる。
`analytics` と `identity` は許可リストが空(=全エンドポイントが認可済み)なので、
新たに無認可のエンドポイントを足すと**即座に失敗する**。

### 既知の要対応(優先度順)

**無し。** #830 で全エンドポイントの認可要否を決定し、実装へ反映した。

ラチェット(`AuthorizationCoverageTest`)の許可リストは**10サービスすべて空**で、
新たに認可チェックの無いエンドポイントが増えると即座に失敗する。

### 解消済み

**`publishing` の3件(#830)** — `PostController#publish` / `#delete`、`TaxonomyController#resolve`。

当初は「`ProjectServiceClient.SiteBridge` が `projectId` を持たないため掛けられない」と記録して
いたが、`ProjectServiceClient#findProjectIdBySiteId` でサイトIDから逆引きできるため前提が誤りだった。
3件とも `AdminAuthorizationService#requireProjectMemberOrAdminForSite` で、
サイトが属するプロジェクトのメンバー(または admin)に限定した。

どの環境にも紐付いていないサイトは `projectId` が null になりうる(#759)。判定に使える
メンバーシップが存在しないため、**その場合は admin のみを許可**する。未紐付けサイトを
投稿公開・削除の抜け道として残さないための判断。

**`project` の5件(#830)** — `SiteController#register` / `#createManagedWordPress` /
`#adoptManagedWordPress` / `#testConnection` と `ProjectController#get`。

前者4件は `requireAdmin()`。同じコントローラの `update` / `delete` / `getDetail` が既に admin 限定で、
サイト登録は CMS 認証情報の登録を、マネージド WordPress の作成・取り込みはコンテナの払い出しを伴う。
`testConnection` は保存済み認証情報で外部へ接続し admin 権限の有無まで返すため、`getDetail` と揃えた。

`ProjectController#get` は `requireProjectMemberOrAdmin(id)`。更新系が全て admin 限定である一方、
参照が「認証済みなら誰でも」では他人のプロジェクトの構成(GitHub リポジトリ・環境の紐付け)が読めた。

### 一覧系の絞り込み(#830)

`ProjectController#list` / `SiteController#list` / `content/PostController#list` / `#lookupBySlug` は
「認証済みなら誰でも全件を列挙できる」状態だった。admin 限定にすると VSCode 拡張のサイト選択や
Web の一覧画面が壊れるため、**操作者が見てよい範囲だけを返す絞り込み**を入れた。

判定材料の `project_users` は legacy-api に残っている(ADR-0004 によりクロススキーマ参照不可)。
行ごとに `isProjectMember` を呼ぶと N+1 になるので、**まとめて引く内部ブリッジを2本足した**。

| ブリッジ | 用途 |
|---|---|
| `GET /api/internal/project/users/{userId}/project-ids` | project-service が所属プロジェクトIDを引く |
| `GET /api/internal/content/users/{userId}/site-ids` | content-service がアクセス可能サイトIDを引く |

content-service は `project_users`(legacy-api)も `projects`(project-service)も持たないため、
両方を引ける legacy-api 側で解決して返している。

いずれも admin は絞り込まない(`Optional.empty()` を「制限なし」として扱う)。
#583 で `project_users` が project-service へ移った時点で、呼び出し側のクライアントとともに
向き先を変えることになる。

**`media` の5件(#830)** — `ComfyUiCheckpointController#install` / `#delete` と
`RenderController` の3件。いずれも**認可を足すのではなく、外部から到達できないことを確定させた**。

`ComfyUiCheckpointController` は元から gateway のルート表に無く、legacy-api の
`MediaComfyUiClient` が docker network 越しに直接呼ぶだけだった(クラスの Javadoc にもそう書かれており、
`RouteControllerContractTest` の `NON_GATEWAY_ROUTED_PATHS` にも載っている)。

`RenderController` の3件は **gateway の media ルートに `/api/render/**` が載っていた**ため、
有効な JWT があれば外部から直接叩けた。呼び出し元はいずれも content-service /
publishing-service の `MediaRenderClient` で、`app.media-service-uri` へコンテナ間で直接呼ぶ経路しか
持たない(web / extension からの利用は無いことをリポジトリ全体の検索で確認した)。
そこで **gateway のルート表から `/api/render/**` を外した**。

とくに `POST /api/render/penpot/design-file` は、サービスアカウント
(`PENPOT_SERVICE_EMAIL`)で共有 Penpot ワークスペースにファイルを作る。ルートに載っていた間は
認証済みユーザーなら誰でも無制限に作成できた。

5件とも各メソッドの Javadoc に `認可不要:` マーカーで理由を記録した。

**`ai` の10件(#830)** — 内訳は3種類。

*パスを内部側へ移して外部到達を断ったもの(3件)*

`GenerationJobController` の `create` / `update` と `InternalAiGenerationController#generate`。
いずれも Javadoc に「内部ブリッジ」と書かれ、実際の呼び出し元も legacy-api / project-service /
content-service / media-service のコンテナ間呼び出しだけだった。

にもかかわらず前者は `/api/generation-jobs/**`(一覧・詳細を Web が使うため gateway に載っている)に
同居し、後者は `/api/ai/internal/generate` という **`/api/internal/**` 規則から外れた命名**だった
(`/api/ai/**` は gateway に載っている)。結果として **有効な JWT さえあれば外部から任意のジョブを
作成・改変でき、内部生成ブリッジも直接叩けた**。

`/api/internal/ai/generation-jobs` と `/api/internal/ai/generate` へ移した。gateway は
`/api/internal/**` をルーティングしないため、外部からの到達経路が無くなる。
`create`/`update` は新設の `InternalGenerationJobController` へ分離した。

*認可を足したもの(1件)*

`AiController#tags` — `projectId` 指定時はそのプロジェクトの既存タグ(保存済みリソース)を読むため、
`requireProjectMemberOrAdmin` を掛けた。未指定時は読むものが無いので判定しない。

*「認可不要」と判断したもの(6件)*

`AiController` の `draft` / `ask` / `proofread` / `section` は、利用者自身の入力からの生成で
保存済みリソースに触れない。LLM のコストは利用量に比例するが、それは認可ではなく
レート制限 / クォータで扱う問題として本Issueのスコープ外とした。

`GenerationJobController` の `list` / `get` は、ログイン後の共通ダッシュボード
(`apps/web/src/app/page.tsx`)が表示するジョブ履歴。**ただし `generation_jobs` に所有者を表す列が無く、
「自分のジョブだけ」に絞ることが今のスキーマではできない。** 利用者ごとに絞るなら列の追加を伴うため、
ギャップとして記録するに留めた。

**`content` の4件 / `platform` の5件 / `log-writer` の2件 / `legacy-api` の5件(#830)**

*認可を足したもの(4件)*

- `platform/DashboardController#getContainerStatus` と `#streamContainerStatus` →
  `requireAdmin()`。**コンテナ名と稼働状況はインフラの構成情報**で、#816 のQAで
  「無効化された利用者に全コンテナ名と稼働状況が見え続ける」ことが確認されている。
  同じコントローラの `getServiceStatusDetail` が既に admin 限定なのとも揃う
- `legacy-api/AiController#image` と `#imageOptions` → `projectId` 指定時に
  `requireProjectMemberOrAdmin`。プロジェクト設定(既定サイズ・ネガティブプロンプト等)を
  読むため。同じクラスの `generateImagePrompt` が既にそうしていたのに揃えた

*「認可不要」と判断したもの(12件)*

| エンドポイント | 理由 |
|---|---|
| `content/MetadataController#postStatuses` | enum を列挙するだけ。保存済みデータに触れない |
| `content/MetadataController#roles` | ロール名と表示名のみ。権限一覧は含まない |
| `content/CustomTagController#validate` | HTML/CSS の記法検査。純粋な関数 |
| `content/ContentCacheController#resolve` | blogcard/amazon 用。記事を書く利用者が普通に使うので admin 限定にできず、メンバー限定にしても緩和にならない |
| `platform/DashboardController#getServiceStatus` / `#streamServiceStatus` | 共通ダッシュボードの「アプリが動いているか」の要約。詳細版は admin 限定 |
| `platform/VscodeExtensionController#download` | 拡張(.vsix)の配布。利用者固有のデータを含まない |
| `platform/PenpotPluginController#download` / `platform/McpServerController#download` | Penpotプラグイン / MCPサーバーのソースZip(+ setup.sh)の配布。利用者固有のデータを含まない(#1491) |
| `log-writer/FrontendErrorLogController#logError` | クライアントが自分のエラーを送る書き込み専用の窓口。読み取り側には認可あり |
| `log-writer/OperationLogController#record` | 同上 |
| `legacy-api/AuthController#setupStatus` / `#setup` | `PUBLIC_PATHS` の**認証前に叩かれる公開パス**。認可を掛けると初回セットアップが不可能になる |
| `legacy-api/HealthController#health` | 同上。監視・コンテナのヘルスチェック用 |

> **`ContentCacheController#resolve` について:** 認可の問題ではないが、**宛先アドレスの検証が無く
> 内部アドレスへの SSRF になる**ことがこの棚卸しで判明した。入力検証で対処すべき別種の問題なので
> **#902** として分けて起票し、`OutboundUrlGuard` で対処済み。ループバック・プライベート帯・
> リンクローカル(`169.254.169.254`)・IPv6 ユニークローカル・CGNAT 等へ**名前解決される**宛先を拒否し、
> Playwright の `page.route` で**リダイレクトを含む各リクエストの直前**にも同じ検査を通す。

**`media` の12件(#830)** — `DiagramController`(6) と `GeneratedImageController`(6)。

media-service に**プロジェクトメンバー判定の手段が無かった**ため、`Diagram` / `GeneratedImage` が
`projectId` を持っているにもかかわらず「有効な JWT さえあれば誰でも他人のダイアグラム・生成画像を
読み書き・削除できる」状態だった。

`LegacyApiBridgeClient`(ai / content / analytics / publishing が既に持っているものと同じ形)を
media にも追加し、`AdminAuthorizationService#requireProjectMemberOrAdmin` を実装した。
**legacy-api 側には新しいエンドポイントを足していない** — `ProjectUserBridgeController` が既に
公開している `/api/internal/project/projects/{projectId}/members/{userId}` を再利用している
(#583 が legacy-api を縮小しようとしているところへ、同一実装のメンバー判定を5本目として
増やしたくないため)。

- 個別リソース操作(`get` / `getSvg` / `update` / `delete` / `create` / `updateTags` /
  `getImageFile`)は、リソースの `projectId` でメンバー判定する
- どのプロジェクトにも紐付いていないリソース(`projectId` が null)は admin のみ。
  `projectId` を空で作ったリソースが抜け道にならないようにするため
- 一覧(`list`)は `projectId` 指定時はメンバー判定、**未指定時は admin 限定**。
  本来は「操作者が所属するプロジェクトの分だけ」返すべきだが、所属プロジェクトの一覧を引く手段が
  media-service に無い(内部ブリッジは `isProjectMember` だけ)。#583 の後に絞り込みへ置き換える

**`media/MediaController#upload`(#830)** — `site` キーで指定した CMS のメディアライブラリへ
直接ファイルをアップロードするため、**認証済みなら誰でも任意サイトへ書き込めた**。

media-service はサイトを所有していない(所有権は project-service、#577 stage2)ので
site→project の逆引きを自前でできなかった。publishing-service は `SiteService#resolveProjectId`
で既に解決できるため、同じ内部ブリッジ上に
`GET /api/internal/publishing/sites/{site}/project-id` を1本足し、
`requireProjectMemberOrAdminForResource` で塞いだ。
`legacy-api/AuthController` の2件は初回セットアップ導線で **`PUBLIC_PATHS` に含まれる公開パス**、
`HealthController#health` も同様。これらは「認可不要」が正しく、
#583 で legacy-api を解体する際に移設先で同じ扱いにする。

## admin判定の2つの軸(identity-service、issue #815)

identity-service には admin かどうかを決める仕組みが**2つ**ある。名前がどちらも「admin」なので
同一のものと誤解されやすいが、**別軸**である。

| 軸 | 実体 | 判定に使うもの | 主な利用 |
|---|---|---|---|
| **粗い軸** | `users.role` カラムが `"admin"` | `CurrentActorService#isAdmin()` | `AdminAuthorizationService#requireAdmin()` / `requireSelfOrAdmin()` / `requireAdminAndNotSelf()`。`/api/users` の大半、`/api/audit-logs`、`GET /api/logs/errors` など |
| **細かい軸** | RBAC(`roles` / `role_permissions` / `user_roles`) | `User#hasPermission(Permission)` | `PermissionAuthorizationService#requirePermission()` |

### 決定: `users.role = "admin"` は全 Permission を含意する

**#815 以前は両軸が完全に独立**しており、`users.role = "admin"` でも RBAC の `ROLE_ADMIN` を
持たなければ `requirePermission(...)` を通れなかった。

その状態で #798 が「ロールを配れる権限を与えるロール(特権ロール)」の付与・剥奪だけを
`requireAdmin()` に変更した結果、**直感に反する非対称**が生まれた。

> `users.role = "admin"` だが `ROLE_ADMIN` を持たないユーザーは、
> **特権ロールは付与できるのに、特権でないロールは付与できない**

#815 で `PermissionAuthorizationService#requirePermission()` の先頭に
「操作者が `users.role = "admin"` なら通す」を入れ、次のとおり関係を一意にした。

- **admin は全部できる**(RBACロールの保有状況を問わない)
- **RBAC は admin 以外へ個別に権限を配るための仕組み**

これにより上記の非対称は解消する。

### 2軸を維持した理由(採用しなかった案)

- **RBAC に寄せる**(`users.role` を廃止): 既存の `role='admin'` ユーザーへ `ROLE_ADMIN` を
  割り当てる移行と、`requireAdmin()` を使う多数のエンドポイントの書き換えが必要になる。
  `UserCreateRequest.role` / `UserUpdateRequest.role` の API 互換にも影響する
- **`users.role` に寄せる**(RBAC を撤去): `Permission` は19種あるが、実際に
  `requirePermission` で強制されているのは **`ROLE_MANAGE` の1種類のみ**
  (`USER_ROLE_MANAGE` は `RoleService#isPrivilegedRole` の特権判定に現れるだけ)。
  撤去は筋が通るが、`GET /api/roles` の廃止とテーブル削除を伴い影響が大きい。
  #786 で `lbs_identity` にこれらのテーブルを作ったばかりでもある

いずれも本Issueより広い変更になるため、**2軸を維持したうえで関係を定める**方針を採った。

### 未使用の Permission の扱い

19種のうち実際に強制されているのは `ROLE_MANAGE` のみで、残り
(`USER_CREATE` / `POST_PUBLISH` / `SITE_DELETE` / `AUDIT_LOG_VIEW` / `SYSTEM_CONFIG` 等)は
**どこからも参照されていない**。

`Permission` enum とシードは**残す**。細粒度認可を広げる際の受け皿として意図された設計であり、
消すと再導入時にマイグレーションが要る。ただし**現時点で強制されていない**ことを
ここに明記しておく。「`SITE_DELETE` を持たないロール」を作ってもサイト削除は防げない。

### Keycloak の realm ロール `admin` は `users.role` の**従**(issue #955)

軸をもう1つ増やしたわけではない。Keycloak の realm ロール `admin` は
**`users.role` から導出される値**であり、判定の根拠にはならない。

| | 実体 | 誰が書くか | 何に使われるか |
|---|---|---|---|
| **正** | `users.role`(`admin` / `user`) | identity-service | バックエンドAPIの認可(上表の粗い軸) |
| **従** | Keycloak realm ロール `admin` → JWT の `realm_access.roles` | identity-service が `users.role` に追随させる | **Web(Next.js)のセッションロール判定** |

Web はサーバー側で JWT の `realm_access.roles` からセッションのロールを決め
(`apps/web/src/lib/auth.ts`)、管理者専用パスをそれで塞ぐ(`apps/web/src/proxy.ts` の
`ADMIN_ONLY_PREFIXES`)。ローカルDBを引かないため、**identity-service が realm ロールを
同期しないと画面だけが非管理者として振る舞う**。#955 はまさにこれで、
初回セットアップで作った最初の管理者が `/users` にも `/admin/*` にも入れなかった
(APIは通るのに画面に入れない、という切り分けの難しい食い違いになる)。

#### 同期する経路

`users.role` が決まる/変わる経路すべてで `KeycloakAdminClient#grantRealmRole` /
`revokeRealmRole` を呼ぶ(`UserService`)。

| 経路 | 挙動 |
|---|---|
| `POST /api/auth/setup` | admin 作成時に付与。失敗したら Keycloak ユーザーごと削除して例外(ローカルには何も残らない) |
| `POST /api/users`(`role=admin`) | 同上 |
| `PATCH /api/users/{id}`(`role` 指定) | Keycloak を**先に**更新してからローカル保存。保存が失敗したら realm ロールを元へ戻す |
| `POST /api/users/migrate-to-keycloak` | 移行時に admin なら付与。失敗したら作った Keycloak ユーザーを消し、そのユーザーだけ移行失敗として集計する |
| `POST`/`DELETE /api/users/{userId}/roles/{roleName}` | `reconcileKeycloakAdminRole` で `users.role` へ冪等に整合させる(ベストエフォート) |

RBAC の `ROLE_ADMIN` は realm ロールを**駆動しない**。3つ目の軸を作らないための線引きであり、
`ROLE_ADMIN` を付けても剥がしても realm ロールは変わらない(`users.role` に対する整合だけを行う)。

#### ずれてしまった環境の回復

`PATCH /api/users/{id}` は **admin 性が変わらなくても**、`role` が指定されていれば realm ロールを
同期する。#955 の修正より前に作られた管理者は realm ロールを持たないので、「変わったときだけ
同期する」設計にすると画面から直す手段が無くなる(まさに #955 の背景。他に管理者が居なければ
`kcadm` でしか回復できない)。付与も剥奪も冪等なので、ずれていなければ確認で終わる。

**回復手順**: 別の管理者(または `kcadm`)から、対象ユーザーへ `PATCH /api/users/{id}` で
`role` を今と同じ値のまま送る。管理者が1人も画面へ入れない場合だけ、
`kcadm add-roles -r letsblog --uid <id> --rolename admin` で1人目を復旧させる。

#### 失敗を握りつぶさない

「ローカルDBだけが admin で Keycloak が追随しない」状態が #955 の不具合そのものなので、
`users.role` を**変える**経路では同期の失敗を必ず例外にする。作成系は Keycloak ユーザーを削除して
補償し(`compensateKeycloakUser` の先例に従う)、更新系は Keycloak → ローカルの順にして
`@Transactional` のロールバックに任せる。

唯一の例外が RBAC の `POST`/`DELETE /api/users/{userId}/roles/{roleName}` で、ここでの整合失敗は
警告ログに留めて成功を返す。この経路は `users.role` を**変えない**ので、失敗しても新たなずれは
生まれない(禁じているのはずれを作って黙ることであり、ここには作りようがない)。例外にすると、
admin 性と無関係な RBAC ロールの付け外しまで Keycloak の一時的な不調で 502 になり、
元々動いていた RBAC 管理を壊してしまう。

`keycloakSub` を持たない未移行ユーザーは Keycloak 側に対応するアカウントが無いためスキップする
(`updateUserProfile` / `delete` / `deactivate` と同じ扱い)。

#### 必要な Keycloak の権限

(#1590)バックアップ復元後の `clear-user-cache` / `clear-realm-cache` / `clear-keys-cache` は `manage-realm` を要求するため、
`realm-export.json` はサービスアカウントに `manage-realm` も付与する。構築済みの環境は realm の再インポートか
`kcadm.sh add-roles --uusername service-account-letsblog-services --cclientid realm-management --rolename manage-realm` が要る。
未付与でも復元自体は成功し、キャッシュ無効化の失敗が ERROR ログに残るだけである。
既知の限界: ロールの割当は Keycloak の DB に入っているため、`manage-realm` 付与前に取ったバックアップを復元すると
付与が失われ、キャッシュ無効化は 403 になる(ERROR ログのみ。Keycloak の再起動で解消する)。

`letsblog-services` のサービスアカウントが持つ `realm-management` のロールは
`manage-users` と `view-users`、および上記キャッシュ無効化専用の `manage-realm` である(`infra/keycloak/realm-export.json`)。
ロールマッピングは「ロールの表現(`id` + `name`)の配列」を要求するため id の解決が要るが、
素直な `GET /admin/realms/{realm}/roles/{name}` は **`view-realm` を要求し、実機で 403 になる**。

そこでユーザースコープの `GET .../users/{id}/role-mappings/realm`(割当済み)と
`.../role-mappings/realm/available`(割当可能)から id を引く。こちらは `view-users` で読めるため、
**ロールマッピングのために realm の権限設定を足さずに済む** — 既に構築済みの環境が realm の再インポートを迫られない。

## 現行の認可モデル(2層構造)

legacy-apiはまだ `@PreAuthorize` ベースの宣言的認可へ移行していない
(`SecurityConfig`のjavadoc参照。移行は認可マトリクス整備(#568、B10)のスコープであり、
本Issue(#566)では実施しない)。現行は以下の2層で認可を行っている。

1. **認証ゲート(→401)**: `com.letsblog.api.config.SecurityConfig`が、公開パスを除く
   全`/api/**`パスに対してKeycloak発行の有効なJWT(`Authorization: Bearer`ヘッダー)を要求する
   (issue #566で`ApiKeyAuthFilter`によるヘッダーベースのAPIキー認証を撤去し、Resource Server
   のJWT検証へ全面移行した)。JWTが無い、または不正・期限切れ・署名不正の場合、コントローラ
   メソッドや `@Valid` によるボディ検証に到達する前に即座に401を返す。
   例外として以下はJWTなしでも到達できる(`SecurityConfig.PUBLIC_PATHS`):
   - `GET /api/health`
   - `POST /api/auth/setup`
   - `GET /api/auth/setup-status`
   - Actuator (`/actuator/**`)・APIドキュメント (`/v3/api-docs/**`、`/swagger-ui/**`)

   上記以外の全エンドポイントは、リクエストボディやパスパラメータの妥当性に関わらず、
   有効なJWTが無ければ必ず401を返す。

   **platform-serviceへ移設されたエンドポイントについて(issue #705)**:
   `AppSettingController`・`BackupController`・`DashboardController`・`SystemSettingController`・
   `VscodeExtensionController`は#693/#694/#695/#696で`services/platform`へ移設された。移設先の
   `com.letsblog.platform.config.SecurityConfig`は当初、他の抽出サービスのテンプレート通り
   全経路`permitAll()`だったため、この認証ゲートが一時的に失われ、`GET /api/system/vscode-extension`等が
   Authorizationヘッダーなしでも200を返す後退が発生していた(gateway側も`anyExchange().permitAll()`で
   あり、どちらの層でも認証必須化が行われていなかった)。issue #705でplatform-serviceの`SecurityConfig`を
   legacy-apiと同じ形(公開パスを除き`anyRequest().authenticated()`)へ変更し、下表の「未認証: 401」を
   実態として復元した。platform-serviceの公開パスは Actuator (`/actuator/**`)と
   APIドキュメント (`/v3/api-docs/**`、`/swagger-ui/**`、`/swagger-ui.html`)のみ。対応する統合テストは
   `services/platform/src/test/java/com/letsblog/platform/integration/AuthorizationMatrixIntegrationTest.java`。

   **サービス間内部ブリッジ `/api/internal/platform/**` について(issue #742)**:
   #705の時点では公開パスに残していた。唯一の呼び出し元である legacy-api の
   `PlatformServiceClient` が Authorization ヘッダーを一切付与しない実装で、
   `authenticated()` にすると実行時に壊れたためである。

   しかしこれらのエンドポイント(`InternalPlatformSettingsController`)は
   **Brave Search APIキー・LLM APIキー・ChatGPTキーという実際のシークレットを返す**。
   gatewayのルート表に載っておらず外部からは到達できないが、内部ネットワークからは無防備だった。
   project-service / publishing-service の同種の内部ブリッジは既にJWT必須で、
   platform-service だけが一貫性を欠いていた。

   #742 で `PlatformServiceClient` を Client Credentials Grant
   (`ServiceAuthHeaders.clientCredentials`、#567)でトークンを付与するよう修正し、
   `/api/internal/platform/**` を `authenticated()` へ移した。
   呼び出し先はシステム全体で1つの値を解決するだけで特定ユーザーのデータではないため、
   呼び出し元ユーザーのトークンを転送する(`forwardedBearer`)必要はない。
   下流(`InternalPlatformSettingsController`)はユーザー単位の認可を一切行わないため、
   呼び出し元ユーザーの権限を運ぶ意味が無い。

   技術的には `forwardedBearer` も選択可能で、そちらなら Keycloak への新たな実行時依存は
   増えなかった。それでも `clientCredentials` を選んだのは、下流が必要としない権限を
   運ばない方が筋が通るのと、ADR-0005 の案B方向と整合するため。
   代償として **Keycloak 停止時にこれらの呼び出しが失敗するようになった**(#742 以前は
   トークンを取得しないため Keycloak の停止に影響されなかった)。
   `ServiceTokenUnavailableException` は `PlatformServiceClient` の各メソッドで捕捉して
   `IllegalStateException` に包み、platform-service 停止時と同じ 409 + 説明メッセージに揃えている。

   なお #796 で判明したとおり、Client Credentials のトークンは `requireAdmin()` を通れない
   (サービスアカウントの `sub` に対応するローカル `users` 行が無く `CurrentActorService` が
   操作者を解決できない)。`/api/internal/platform/**` は認証のみを要求し actor を解決しないため、
   この制約には当たらない。

2. **ロール/所有権ゲート(→403)**: コントローラメソッド(または委譲先のサービスメソッド)の
   先頭付近で `AdminAuthorizationService` の以下いずれかを呼ぶ場合がある。
   - `requireAdmin()` — 呼び出し元のactorがadminロール(`CurrentActorService`がJWTのsub
     クレームから解決したローカルUserのrole)であることを要求する。満たさなければ
     `ForbiddenException` を投げ、`GlobalExceptionHandler` が403へ変換する。
   - `requireProjectMemberOrAdmin(projectId)` — 呼び出し元がadmin、またはそのプロジェクトの
     メンバー(`ProjectUserRepository.findByProjectIdAndUserId`で判定)であることを要求する。
     別プロジェクトのメンバーであっても、対象プロジェクトのメンバーでなければ403。
   - `requireSelfOrAdmin(userId)` — `AdminAuthorizationService`に定義されているが、
     **現時点でどのコントローラからも呼ばれていない(未使用)**。
   - `PermissionAuthorizationService.requirePermission(Permission)` — 同様に定義されているが
     **現時点でどのコントローラからも呼ばれていない(未使用)**。

   上記チェックを直接コントローラで呼ばず、委譲先のサービスメソッド内で呼んでいるケースも
   複数ある(表の「認可チェック」列に「(service層)」と注記)。挙動としては同じくコントローラの
   処理が実行される前に例外が投げられる。

   **いずれのチェックも呼ばないエンドポイント**は、有効なJWTさえ持っていればロール・
   プロジェクト所属に関わらず到達できる。#733 の時点ではこれを既知のギャップとして
   末尾に列挙するに留めていたが、**#830 で全件の要否を決定し実装へ反映した**。
   現在は「認可チェックの網羅状況(issue #830)」節と
   `AuthorizationCoverageContract` の許可リスト(全10サービスで空)が一次情報である。

## 表の見方

- **現行: 未認証**: 有効なJWT(Authorization: Bearer)なし/不正の場合に実際に返るステータス。
  公開エンドポイントは「該当なし(公開エンドポイント)」。
- **現行: 権限不足**: ロール/所有権チェックがある場合に、それを満たさない認証済みactorが
  実際に返されるステータス。チェックが無いエンドポイントは「該当なし」。
- **現行: 権限あり**: チェックを満たす場合(またはチェックが無い場合の認証済みactor)に、
  後続のビジネスロジックへ到達すること(「認可OK」)を示す。具体的な2xxコードまでは主張しない。
- **あるべき(#591カットオーバー後)**: 認可チェックがある行は現状維持が期待値
  (未認証→401、権限不足→403、権限あり→通過)。認可チェックが無い行は、断定的な役割を
  書かず「要検討(本Issueの対象外)」とする。

---

## AiConnectionController (1エンドポイント、ベースパス `/api/projects/{id}/ai-connections`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/projects/{id}/ai-connections | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1499 で追加**。プロジェクトから見たAIプロバイダー4種の接続情報と利用可否を返す。非メンバーは403 |

## AiController (9エンドポイント、ベースパスなし)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/ai/draft | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | プロジェクト非依存のAI下書き生成。projectIdを取らないためプロジェクト単位の制御ができない |
| POST /api/ai/ask | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 同上 |
| POST /api/ai/tags | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 同上 |
| POST /api/ai/proofread | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 同上 |
| POST /api/ai/image | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 同上 |
| GET /api/ai/image-options | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | projectIdは任意パラメータだが未チェック |
| POST /api/ai/section | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 同上 |
| POST /api/projects/{projectId}/ai/generate-image-prompt | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | 唯一projectIdを取り、正しくチェックしている |
| POST /api/projects/{projectId}/ai/review-steps/{stepKey}/suggestions | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1213 で追加**。多段レビュー(#1210)のステップ単位の指摘生成。プロジェクト設定を読むため`generate-image-prompt`と同じ理由でプロジェクトメンバー限定 |

## AppSettingController (2エンドポイント、ベースパス `/api/system-settings/app-settings`)

platform-service所有(issue #693)。未認証401はplatform-serviceの`SecurityConfig`が担う(#705)。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/system-settings/app-settings | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `AppSettingService.getAllSettings()`内でrequireAdmin() |
| PUT /api/system-settings/app-settings | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `AppSettingService.updateSettings()`内でrequireAdmin() |

## ArticlePlanController (15エンドポイント、ベースパス `/api/projects/{projectId}/article-plan`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST .../chat | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../sessions | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../sessions/{sessionId} | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../sessions/by-issue/{issueNumber} | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../issues/{issueNumber}/description | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../issues | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST .../suggest-titles | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST .../accept | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST .../suggest-structure | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST .../issues/{issueNumber}/accept-structure | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST .../suggest-metadata | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../categories | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../categories/hierarchy | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET .../tags | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST .../issues/{issueNumber}/assign | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |

## ArticlePreviewController (2エンドポイント、ベースパス `/api/projects/{projectId}/preview`)

実装の所有サービスは分割済み。`POST .../render` はcontent-service(issue #576)、
`POST .../signed-url` はpublishing-service(issue #1561)が持つ。いずれも
requireProjectMemberOrAdmin をコントローラ側で呼ぶ点は同じ。旧プレビュー経路の
`GET .../theme-css`・`POST .../skeleton`・`DELETE .../preview-post` は issue #1564 で削除した
(削除後は404。認可の対象となるエンドポイントが無い)。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST .../render | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | content-service所有(#576) |
| POST .../signed-url | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | publishing-service所有(#1561) |

## ArticleReviewController (1エンドポイント、ベースパス `/api/projects/{projectId}/article-review`)

publishing-service所有(issue #1337、Epic #1333)。プロジェクトのGitHubリポジトリで開いているPull Requestを返す。
プロジェクトメンバー判定は `ArticlePreviewController` と同じ `requireProjectMemberOrAdmin` を使う。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET .../pull-requests | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | publishing-service所有(#1337) |

## AuditLogController (1エンドポイント、ベースパス `/api/audit-logs`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/audit-logs | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 統合テストで代表検証済み(b) |

## AuthController (2エンドポイント、ベースパス `/api/auth`)

issue #566でログイン(`POST /api/auth/login`)・2FA(`GET/POST /api/auth/totp/*`)・
パスワードリセット(`POST /api/auth/password-reset/*`)の計8エンドポイントはKeycloakへ
全面移行し撤去した。さらにissue #688で、ログインのKeycloak一本化(#564)以降ローカルDBにしか
アカウントを作らずログイン不能なユーザーを生むだけになっていたセルフサインアップ
(`AuthController.signup`。Web/拡張/SDK/OpenAPIのいずれからも呼び出し元は無かった)も撤去した。
残る2エンドポイントは、Keycloak上にまだアカウントが1つも存在しない状態からのWeb管理画面
初回セットアップ専用で、いずれも`SecurityConfig.PUBLIC_PATHS`により公開されている。

**issue #583でlegacy-apiが解体された際、この2エンドポイントは identity-service の
`AuthSetupController`(クラス名が変わった)へ移設された(`services/legacy-api`自体は
issue #583/#917で削除済み。issue #991で本節の`services/legacy-api/...`参照を現状のパスへ修正)。**

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/auth/setup-status | なし(公開) | 該当なし(公開エンドポイント) | 該当なし | 認可OK | 現状維持(公開エンドポイントとして必要) | PUBLIC_PATHS。`needsSetup` は**ローカル`users`テーブルの件数のみ**で判定する(`UserService#hasAnyUser`: `services/identity/src/main/java/com/letsblog/identity/service/UserService.java:155`)。Keycloak側は見ないため、ローカル`users`が空でKeycloakに同一メールのアカウントが残っている場合、`needsSetup=true` を返した直後の `POST /api/auth/setup` が Keycloak の409により502で失敗しうる |
| POST /api/auth/setup | なし(公開) | 該当なし(公開エンドポイント) | 該当なし | 認可OK | 現状維持(公開エンドポイントとして必要) | PUBLIC_PATHS。初期管理者セットアップ用。**Keycloak Admin REST API経由でKeycloak側にもアカウントを作る**ため、作成した資格情報でそのままKeycloakログインが可能(`UserService#setupInitialAdmin`: `services/identity/src/main/java/com/letsblog/identity/service/UserService.java:175`。183行目で`keycloakAdminClient.createUser`、185行目で`setPassword`を呼び、その後ローカル`users`行を`role=admin`+`ROLE_ADMIN`で作成する。issue #681で変更、issue #955でKeycloakのrealmロール`admin`付与も追加。#564時点の「ローカルDB直書きのみ」の記述はそれ以前の実装)。ユーザーが1人でも存在する場合は400で拒否される |

## BackupController (2エンドポイント、ベースパス `/api/backup`)

platform-service所有(issue #694)。未認証401はplatform-serviceの`SecurityConfig`が担う(#705)。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/backup/download | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `BackupService.createBackup()`内でrequireAdmin() |
| POST /api/backup/restore | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `BackupService.restoreBackup()`内でrequireAdmin() |

## ContentCacheController (1エンドポイント、ベースパス `/api/content-cache`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/content-cache | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | `[blogcard]`/`[amazon]`埋め込みタグ用の内部プロキシ/キャッシュ。渡されたURLをスクレイピングするのみ |

## CustomTagController (7エンドポイント、ベースパス `/api/custom-tags`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/custom-tags/generate | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagGenerationService.generate()`内 |
| POST /api/custom-tags/generate/jobs | requireAdmin(受理側 `CustomTagGenerationJobStarter`) | 401 | 403 | 認可OK | 現状維持 | 非同期ジョブとして受理(#1409)。生成結果は保存先へ書かない |
| POST /api/custom-tags/validate | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | ステートレスな検証のみ(DBへの副作用なし) |
| POST /api/custom-tags | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagService.create()`内 |
| GET /api/custom-tags | requireProjectMemberOrAdmin(projectId指定時のみ、service層) | 401 | 403(projectId指定時) | 認可OK | 現状維持 | `CustomTagService.list()`内。issue #1057で追加。projectId未指定(グローバルタグのみ)は従来通り認証済みなら誰でも参照可 |
| GET /api/custom-tags/css-bundle | requireProjectMemberOrAdmin(projectId指定時のみ、service層) | 401 | 403(projectId指定時) | 認可OK | 現状維持 | `CustomTagService.buildCssBundle()`内。issue #1057で追加。projectId未指定時は上記と同じ |
| PUT /api/custom-tags/{id} | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagService.update()`内 |
| DELETE /api/custom-tags/{id} | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagService.delete()`内 |

## CustomTagTemplateController (9エンドポイント、ベースパス `/api/custom-tag-templates`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/custom-tag-templates | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagTemplateService.create()`内 |
| GET /api/custom-tag-templates/{id} | requireProjectMemberOrAdmin(`isPublished=false`かつ`projectId`が非null時のみ、service層) | 401 | 403(該当時) | 認可OK | 現状維持 | `CustomTagTemplateService.getById()`内。issue #1220で追加。単一テンプレート参照で、公開済みなら`projectId`を問わず誰でも参照できる(退行なし)。未公開かつ`projectId`指定時はプロジェクトメンバー判定が必須(以前は無条件参照可能で、非メンバーがidを指定するだけで他プロジェクトの未公開テンプレートを読めた)。未公開でも`projectId`未指定(グローバル)は他メソッドと同じ既存の規約で対象外 |
| GET /api/custom-tag-templates | requireProjectMemberOrAdmin(`showAll=true`かつprojectId指定時のみ、service層) | 401 | 403(該当時) | 認可OK | 現状維持 | `CustomTagTemplateService.list()`内。issue #1057で追加。`showAll=true`は公開状態を問わず全件返すため、projectId指定時はプロジェクトメンバー判定が必須(以前は無条件参照可能で、非メンバーが他プロジェクトの未公開テンプレートを読めた)。`showAll=false`(既定、公開済みのみ)とsearch/categoryは元々`isPublished=true`のクエリで公開範囲が絞られておりこのIssueの対象外。projectId未指定(グローバル)も対象外 |
| GET /api/custom-tag-templates/my-templates | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 呼び出し元自身のテンプレートに限定される想定だが、明示チェックは無い |
| PUT /api/custom-tag-templates/{id} | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagTemplateService.update()`内 |
| POST /api/custom-tag-templates/{id}/publish | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagTemplateService.publish()`内 |
| POST /api/custom-tag-templates/{id}/unpublish | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagTemplateService.unpublish()`内 |
| POST /api/custom-tag-templates/{id}/clone | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagTemplateService.clone()`内 |
| POST /api/custom-tag-templates/{id}/apply | requireProjectMemberOrAdmin(対象プロジェクト。テンプレートが未公開かつプロジェクト所属ならそのプロジェクトも、service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagTemplateService.apply()`内。issue #1131で追加。テンプレートの HTML/CSS から対象プロジェクトの `custom_tags` 行を作る(`clone` はテンプレート間の複製で別物)。同名タグは409(上書きしない) |
| DELETE /api/custom-tag-templates/{id} | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `CustomTagTemplateService.delete()`内 |

## DashboardController (5エンドポイント、ベースパス `/api/dashboard`)

platform-service所有(issue #695)。未認証401はplatform-serviceの`SecurityConfig`が担う(#705)。
SSE配信の2エンドポイントもブラウザから直接ではなくWeb BFF(`apps/web/src/app/api/dashboard/*/stream/route.ts`)が
Authorizationヘッダーを付けて中継するため、認証必須化の影響を受けない。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/dashboard/service-status | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 接続先サービスの稼働状況(URL等は含まない概要) |
| GET /api/dashboard/service-status/stream | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 上記のSSE配信版 |
| GET /api/dashboard/service-status/detail | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 応答時間・エラー内容等の詳細診断情報(admin限定、コード上のコメントでも明記) |
| GET /api/dashboard/container-status | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | Dockerコンテナ稼働状況 |
| GET /api/dashboard/container-status/stream | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 上記のSSE配信版 |

## DiagramController (6エンドポイント、ベースパスなし)

media-service所有。draw.ioで作成したダイアグラムの一覧・詳細・バイナリ取得・作成・更新・削除
(issue #830で`requireProjectMemberOrAdmin`系を追加)。`projectId`を持たないリソースはadmin限定。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/diagrams | requireProjectMemberOrAdminForResource | 401 | 403 | 認可OK | 現状維持 | `DiagramController.java:33-43`。リクエストの`projectId`で判定 |
| GET /api/diagrams | requireProjectMemberOrAdmin(projectId指定時)/requireAdmin(未指定時) | 401 | 403 | 認可OK | 現状維持 | `DiagramController.java:45-61`。`projectId`未指定は全プロジェクト横断一覧になるためadmin限定 |
| GET /api/diagrams/{id} | requireProjectMemberOrAdminForResource | 401 | 403 | 認可OK | 現状維持 | `DiagramController.java:63-66,118-122`。`findAuthorized`が対象を読んでから`projectId`で判定するため、id=1のダイアグラムが存在しない場合は404が先に返る |
| GET /api/diagrams/{id}/svg | requireProjectMemberOrAdminForResource | 401 | 403 | 認可OK | 現状維持 | `DiagramController.java:68-75,118-122`。同上(存在しない場合は404が先) |
| PUT /api/diagrams/{id} | requireProjectMemberOrAdminForResource | 401 | 403 | 認可OK | 現状維持 | `DiagramController.java:77-84,118-122`。同上 |
| DELETE /api/diagrams/{id} | requireProjectMemberOrAdminForResource | 401 | 403 | 認可OK | 現状維持 | `DiagramController.java:86-90,118-122`。同上(存在しない場合は404が先) |

## FrontendErrorLogController (2エンドポイント、ベースパス `/api/logs`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/logs/errors | なし | 401 | 該当なし | 認可OK | 現状維持 | Web BFF(`POST /client-errors`)からのフロントエンドエラー記録。書き込みのみ。ブラウザからの直叩きは #791 で廃止した |
| GET /api/logs/errors | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |

### フロントエンドエラーログの経路(#791)

ブラウザは `POST /api/logs/errors` を直接叩かない。`apps/web/src/lib/errorLogger.ts` は同一オリジンの
`POST /client-errors` を呼び、Next.js の Route Handler が server-only の `apiClient` 経由で
Bearer を付けて log-writer へ中継する。

BFF を `/api/` の下に置いていないのは、nginx の `location /api/`
(`infra/nginx/conf.d/default.conf:68`)が NextAuth 用の正規表現 location を除き `/api/**` を
無条件に gateway へ転送するためで、`/api/**` に置いた Route Handler は到達しない。

**未認証時の挙動**: セッションが無い(または `session.error` が立っている)状態で発生した
エラーは**記録せずに破棄する**(BFF は 204 を返し、log-writer へ中継しない)。
`POST /api/logs/errors` を未認証で通すと、認証不要で無制限に書き込める経路ができて
スパム・容量枯渇の的になるため(ADR-0008)。ブラウザ側では error boundary が
`logErrorToConsole()` も呼ぶため、コンソールには常に残る。

この判定を BFF 自身が行えるよう、`apps/web/src/proxy.ts` は `/client-errors` を
**完全一致**で素通しする。素通ししないと proxy が先に `/login` へ307リダイレクトを返してしまい、
レスポンスを見ない fire-and-forget のビーコンに対して無意味なリダイレクトと
`needsInitialSetup()` の gateway 呼び出しが1件ずつ発生する。

matcher の否定先読み(`(?!api/auth|...)`)ではなく `proxy()` 内で弾いているのは、
先読みが前方一致になるため。`client-errors` を先読みに加えると
`/client-errors-foo` や `/client-errors/nested` のような「`client-errors` で始まる別のルート」
まで認証ゲートを外れてしまい、そこにページを足した時点で無言でゲートが消える。

## GeneratedImageController (7エンドポイント、ベースパスなし)

media-service所有。ComfyUIで生成した画像とパラメータの一覧・詳細・バイナリ取得
(issue #830で`requireProjectMemberOrAdmin`系を追加)。`projectId`を持たないリソースはadmin限定。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/generated-images | requireProjectMemberOrAdmin(projectId指定時)/requireAdmin(未指定時) | 401 | 403 | 認可OK | 現状維持 | `GeneratedImageController.java:67-91`。`projectId`未指定は全プロジェクト横断一覧になるためadmin限定。issue #1493で`folderId`/`unfiled`の絞り込み条件を追加したが、**認可は変えない**(絞り込みは認可の範囲内の画像をさらに狭めるだけ。一般ユーザーが見られるのは自分がメンバーのプロジェクトの画像のみ) |
| GET /api/generated-images/{id} | requireProjectMemberOrAdminForResource | 401 | 403 | 認可OK | 現状維持 | `GeneratedImageController.java:93-96,156-160`。`findAuthorized`が対象を読んでから`projectId`で判定するため、id=1の生成画像が存在しない場合は404が先に返る |
| POST /api/generated-images | requireProjectMemberOrAdminForResource | 401 | 403 | 認可OK | 現状維持 | `GeneratedImageController.java:103-113`。リクエストの`projectId`で判定 |
| PUT /api/generated-images/{id}/tags | requireProjectMemberOrAdminForResource | 401 | 403 | 認可OK | 現状維持 | `GeneratedImageController.java:116-122,156-160`。同上(存在しない場合は404が先) |
| GET /api/generated-images/{id}/file | requireProjectMemberOrAdminForResource | 401 | 403 | 認可OK | 現状維持 | `GeneratedImageController.java:124-132,156-160`。同上(存在しない場合は404が先) |
| DELETE /api/generated-images/{id} | requireProjectMemberOrAdminForResource | 401 | 403 | 認可OK | 現状維持 | `GeneratedImageController.java:134-140,156-160`。同上(存在しない場合は404が先) |
| POST /api/generated-images/bulk-delete | requireProjectMemberOrAdminForResource(idごと) | 401 | 403 | 認可OK | 現状維持 | issue #1492。`bulkDelete`が**全idを`findAuthorized`で読んで認可してから**削除ループに入る。権限の無いidが1つでもあれば403、存在しないidがあれば404で、権限のある画像も含めて1件も削除しない(ファイル削除は取り消せないため)。`projectId==null`の画像はadminのみ |
| POST /api/generated-images/upload | requireProjectMemberOrAdmin(projectId) | 401 | 403 | 認可OK | 現状維持 | issue #1599。手元のJPEG/PNGを切り抜き・拡縮せず元の解像度のまま(メタ情報除去・向き補正・形式維持、#1654)`provider=UPLOAD`で登録する(multipart、`projectId`はクエリ必須)。ファイル本体を読む前に判定する。形式・サイズ・中身の不正は400、multipart上限(20MB)超過は413。gatewayでは`upload-endpoint`バケットに分類する |
| PUT /api/generated-images/{id}/folder | requireAdmin | 401 | 403 | 認可OK | 現状維持 | issue #1493。画像をフォルダへ入れる/未分類へ戻す(`folderId`がnull)。フォルダは横断の共通ツリーで画像のプロジェクトに依存しないため、**画像の所属プロジェクトを問わずadminのみ**(利用者の判断 2026-10-01)。認可を先に行うので、権限の無い利用者には画像・フォルダの存在も漏らさない。存在しない画像・フォルダは404 |
| GET /api/generated-images/folders | requireAuthenticated | 401 | 該当なし(認証済みなら誰でも可) | 認可OK | 現状維持 | issue #1493。共通ツリー(id・name・parentId)を全利用者に返す。応答に画像の件数や情報を含めず、他プロジェクトの画像の存在を露出させない。操作者を解決できない(無効化)ユーザーは403。`{id}`付きの`GET /api/generated-images/{id}`とはリテラルパスが優先して解決される |
| POST /api/generated-images/folders | requireAdmin | 401 | 403 | 認可OK | 現状維持 | issue #1493。フォルダの作成(親は任意)。存在しない親は404、空の名前は400 |
| PUT /api/generated-images/folders/{id}/parent | requireAdmin | 401 | 403 | 認可OK | 現状維持 | issue #1493。親の変更。自分自身・自分の子孫を親に指定すると409(再帰CTEで子孫を引いて確認。自己参照FKだけでは循環を防げないため)で、状態は変わらない。存在しないフォルダ・親は404 |

## GenerationJobController (3エンドポイント、ベースパス `/api/generation-jobs`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/generation-jobs | なし(所有者による絞り込み) | 401 | 該当なし | 認可OK | 現状維持 | #1406: 呼び出し元が起こしたジョブ(`generation_jobs.owner_user_id`)だけを返す。管理者は全件(所有者不明の既存行を含む)。所有者不明の行は一般利用者には見えない |
| GET /api/generation-jobs/{id} | なし(所有者による絞り込み) | 401 | 該当なし | 認可OK | 現状維持 | #1406: 他人のジョブ・一般利用者から見た所有者不明の行は、存在しないものと同じ404(IDの存在を漏らさない)。管理者はどれも取得できる |
| PATCH /api/generation-jobs/{id} | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | #573 stage2で追加。media-service側の非同期ジョブランナーがBearerトークンを転送して呼ぶ内部向け更新API |

## GlobalTagDesignSettingController (3エンドポイント、ベースパス `/api/tag-design-settings`)

project-service所有(issue #763)。プロジェクトに紐付いていないサイト向けのグローバル既定タグ
デザイン。`TagDesignSettingController`(プロジェクト単位、`project_id`あり)とは別クラスで、
こちらは`project_id IS NULL`の1組を扱うため判定に使えるメンバーシップが無く、admin限定にしている。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/tag-design-settings | requireAdmin | 401 | 403 | 認可OK | 現状維持 | `GlobalTagDesignSettingController.java:54-58` |
| PUT /api/tag-design-settings/{tagType} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | `GlobalTagDesignSettingController.java:60-66` |
| POST /api/tag-design-settings/{tagType}/generate | requireAdmin | 401 | 403 | 認可OK | 現状維持 | `GlobalTagDesignSettingController.java:68-75` |
| POST /api/tag-design-settings/{tagType}/generate/jobs | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 非同期ジョブとして受理(#1409)。`generate`と同じ認可 |

## HealthController (1エンドポイント、ベースパスなし)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/health | なし(公開) | 該当なし(公開エンドポイント) | 該当なし | 認可OK | 現状維持(公開エンドポイントとして必要) | `SecurityConfig.PUBLIC_PATHS`で明示的に除外 |

## IdentityController (1エンドポイント、ベースパス `/api/identity`)

identity-service所有(issue #561)。`GET /api/identity/me`・`/me/permissions`・`PATCH /me/preferences`は
本節ではなく「identity-service の `/api/users` の認可」節の表(#784)に既に記載済みのため、
ここには含めない。issue #991時点でその節に無かった1エンドポイントのみを追加する。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/identity/users/{id}/permissions | requireSelfOrAdmin | 401 | 403 | 認可OK | 現状維持 | `IdentityController.java:76-80` |

## MediaController (1エンドポイント、ベースパスなし)

media-service所有(issue #573 stage3)。CMSのメディアライブラリへ直接アップロードするため、
サイトが属するプロジェクトのメンバー(またはadmin)に限定する(issue #830)。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/media/upload | requireProjectMemberOrAdminForResource | 401 | 403 | 認可OK | 現状維持 | `MediaController.java:41-46`。siteキーから`CmsBridgeClient.resolveProjectIdBySiteKey`でprojectIdを逆引きして判定 |

## MetadataController (2エンドポイント、ベースパス `/api/metadata`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/metadata/post-statuses | なし(意図的) | 401 | 該当なし | 認可OK | 現状維持(設計として認証済み全員に公開) | クラスjavadocに「特定のPermissionは要求せず、認証済みactorであれば参照できる」と明記。ギャップではなく設計 |
| GET /api/metadata/roles | なし(意図的) | 401 | 該当なし | 認可OK | 現状維持(設計として認証済み全員に公開) | 同上 |

## OperationLogController / OperationLogStatsController (6エンドポイント、ベースパス `/api/operation-logs`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/operation-logs | なし(自己スコープ) | 401 | 該当なし | 認可OK | 現状維持(設計として自己ログのみ) | ログイン中actor自身のログとして記録。`currentActorService`が`null`ならコントローラ内で自前401 |
| GET /api/operation-logs | なし(自己スコープ) | 401 | 該当なし | 認可OK | 現状維持(設計として自己ログのみ) | `requireActorId()`で自分のログのみ参照(admin/project権限チェックではなく自己判定) |
| GET /api/operation-logs/{operationId} | 本人、または`isAdmin()`で全利用者 | 401 | 該当なし(非adminは他人のoperationIdに空の一覧) | 認可OK | 本人、またはadminは全利用者 | issue #1471で変更。従来は「設計として自己ログのみ」だったが、adminが集計画面(下の2行)で見つけた他利用者の遅い操作を辿れるよう、adminには利用者を問わずその`operationId`の全行を返す。非adminは従来どおり自分の行だけ(他人のoperationIdには空の一覧、403ではない)。`OperationLogController#trace` |
| GET /api/operation-logs/unified | なし(自己スコープ) | 401 | 該当なし | 認可OK | 現状維持(設計として自己ログのみ) | `isAdmin()`はフィルタ条件緩和のためだけに使い、拒否には使わない |
| GET /api/operation-logs/stats/routes | requireAdmin | 401 | 403 | 認可OK | 現状維持 | issue #1471。期間(startDate/endDate)必須。method+正規化パス(数値ID・UUIDを`{id}`、クエリ除去)ごとの件数・p50・p95・最大(nearest-rank法)。`OperationLogStatsController` |
| GET /api/operation-logs/stats/operations | requireAdmin | 401 | 403 | 認可OK | 現状維持 | issue #1471。期間必須。operationIdごとの合計所要時間・呼び出し数・開始時刻・利用者ID。`OperationLogStatsController` |

## PostController (4エンドポイント、ベースパス `/api/posts`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/posts | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 全プロジェクト横断の投稿履歴一覧 |
| POST /api/posts/publish | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | WordPressへの新規投稿/更新。site識別子のみで対象を選べる |
| GET /api/posts/{site}/by-slug/{slug} | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | |
| DELETE /api/posts/{site}/{wpPostId} | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 投稿削除(WordPress上はゴミ箱移動)がプロジェクト所属確認なしに可能 |

## ProjectAiModelController (14エンドポイント、ベースパス `/api/projects/{id}/ai-models`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET .../llm/models | requireAdmin | 401 | 403 | 認可OK | 現状維持 | プロジェクト単位のパスだが、チェックはrequireProjectMemberOrAdminではなくrequireAdmin(=projectId未使用) |
| PUT .../llm/models/selection | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| GET .../llm/provider | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| PUT .../llm/provider/selection | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| GET .../image/provider | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| PUT .../image/provider/selection | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| GET .../comfyui/checkpoints | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| PUT .../comfyui/checkpoints/selection | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| POST .../comfyui/checkpoints/install | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| DELETE .../comfyui/checkpoints/{fileName} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 同上 |
| GET .../llm/review-steps | requireAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1211 で追加**。多段レビュー(#1210)の5ステップぶんの選択値・選択可能なprovider/model一覧(`ProjectLlmModelController.java`) |
| PUT .../llm/review-steps/{stepKey} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1211 で追加**。同上。provider/modelが空ならそのステップの上書きを解除する(`ProjectLlmModelController.java`) |
| GET .../connections | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1503 で追加**。プロジェクト単位のOllama / ComfyUI接続先の上書き値と解決結果(`ProjectConnectionController.java`)。他行のrequireAdminと違いプロジェクトメンバー判定 |
| PUT .../connections | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1503 で追加**。空文字で上書きを解除、http/https以外・空白/制御文字を含むURLは400で保存しない。**issue #1518 で宛先制限(拒否リスト)を追加**: クラウドメタデータ・リンクローカル(`169.254.0.0/16`・`fe80::/10`・`fd00:ec2::254`・`metadata.google.internal`)、loopback・未指定(`127.0.0.0/8`・`::1`・`0.0.0.0`・`::`・`localhost`・`*.localhost`)、docker-compose.ymlの内部サービス名・コンテナ名(Ollama / ComfyUI系を除く)を指すURLは、admin・メンバーを問わず400で保存しない。10進・16進・省略形・IPv4射影IPv6の別表記も正規化して判定する。ホスト名のDNS解決はしない。LANのプライベートアドレスとOllama / ComfyUIのサービス名・コンテナ名は許可 |

## ProjectApiKeyController (25エンドポイント、ベースパス `/api/projects/{projectId}/api-keys`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET .../github-token | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `ProjectApiKeyService`内で各メソッドがrequireProjectMemberOrAdmin() |
| PUT .../github-token | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| DELETE .../github-token | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| GET .../brave-search-api-key | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| PUT .../brave-search-api-key | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| DELETE .../brave-search-api-key | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| GET .../openai-api-key | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1506 で追加**。プロジェクト単位のChatGPT(OpenAI) APIキーの設定有無のみ(値は返さない)(`ProjectOpenAiApiKeyController.java`) |
| PUT .../openai-api-key | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1506 で追加**。CredentialCipherで暗号化して保存。空キーは400 |
| DELETE .../openai-api-key | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1506 で追加**。削除後はそのプロジェクトではChatGPTによる生成ができなくなる(システム設定・環境変数のキーは issue #1568 で廃止、フォールバックしない) |
| GET .../claude-api-key | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1507 で追加**。プロジェクト単位のClaude(Anthropic) APIキーの設定有無のみ(値は返さない)(`ProjectClaudeApiKeyController.java`) |
| PUT .../claude-api-key | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1507 で追加**。CredentialCipherで暗号化して保存。空キーは400 |
| DELETE .../claude-api-key | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1507 で追加**。削除後はそのプロジェクトではClaudeによる生成ができなくなる(システム設定・環境変数のキーは issue #1568 で廃止、フォールバックしない) |
| GET .../google-analytics | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| PUT .../google-analytics/client | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1231 で追加**。GA用OAuthクライアントID/シークレットの保存(旧`PUT .../google-analytics`のサービスアカウントJSON保存は廃止) |
| POST .../google-analytics/oauth-callback | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1231 で追加**。Next.js側OAuthコールバックからのサーバー間呼び出し(AdSenseと同じ) |
| GET .../google-analytics/properties | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1231 で追加**。Admin API `accountSummaries.list` 由来のGA4プロパティ一覧 |
| PUT .../google-analytics/property | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1231 で追加**。選択したプロパティIDの保存 |
| DELETE .../google-analytics | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | 連携解除(リフレッシュトークン・プロパティ・OAuthクライアントを破棄) |
| GET .../adsense | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| GET .../adsense/accounts | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1232 で追加**。AdSense Management API `accounts.list` 由来の、連携したGoogleアカウントが利用できるアカウント一覧(読み取り専用) |
| PUT .../adsense/account | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1232 で追加**。選択したパブリッシャーIDの保存 |
| PUT .../adsense | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| PUT .../adsense/client-secret | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| DELETE .../adsense | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | |
| POST .../adsense/oauth-callback | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | Next.js側OAuthコールバックからのサーバー間呼び出し。ブラウザ直叩き想定ではないが、チェック自体はある |

## ProjectContentSettingsController (2エンドポイント、ベースパス `/api/projects/{projectId}`)

content-service所有(issue #576、#913)。実装は`GET /content-settings`と`PUT /css-selector-prefix`の
計2エンドポイントを持つ。`PUT`行は移設前「## ProjectController」節に記載されていたが、#1049で
本節へ移設した。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/projects/{projectId}/content-settings | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | `ProjectContentSettingsController.java:43-47` |
| PUT /api/projects/{id}/css-selector-prefix | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |

## ProjectController (38エンドポイント、ベースパス `/api/projects`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/projects | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET /api/projects | accessibleProjectIds(所属で絞り込み。adminは全件) | 401 | 該当なし(他人のプロジェクトは一覧から除外) | 認可OK | 現状維持 | issue #830。`ProjectController#list`。操作者が所属するプロジェクトだけを返す |
| GET /api/projects/{id} | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | issue #830。`ProjectController#get`。非メンバーは403 |
| PUT /api/projects/{id} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| DELETE /api/projects/{id} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/environments | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| DELETE /api/projects/{id}/environments/{environment} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/projects/{id}/master-environment | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/projects/{id}/github-repository | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/environments/sync | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/apply | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/apply-all | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/upload | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/asset-images/{generatedImageId}/upload | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET /api/projects/{id}/bulk-management/categories/comparison | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET /api/projects/{id}/bulk-management/tags/comparison | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/categories/sync | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/categories/delete-all | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/categories/edit-sync | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/categories/sync-all | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/tags/sync | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/tags/delete-all | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/tags/edit-sync | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/tags/sync-all | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET /api/projects/{id}/bulk-management/plugins/comparison | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET /api/projects/{id}/bulk-management/themes/comparison | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/plugins/reconcile | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/themes/reconcile | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/plugins/delete-all | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/themes/delete-all | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET /api/projects/{id}/bulk-management/posts/comparison | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/posts/delete-all | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/bulk-management/posts/status-update | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| GET /api/projects/{id}/users | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/users | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/projects/{id}/users/{userId} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| DELETE /api/projects/{id}/users/{userId} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{id}/users/{userId}/sync | requireAdmin | 401 | 403 | 認可OK | 現状維持 | **issue #1242 で追加**。メンバー個別のユーザー情報再同期(`ProjectUserController.java`)。#1294 実装時に判明した、この節の他行と同じ認可判定の未掲載行 |

## ProjectCustomTagController (3エンドポイント、ベースパス `/api/projects/{projectId}/custom-tags`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/projects/{projectId}/custom-tags | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | 統合テスト(c)のプロジェクト間分離検証で使用 |
| GET /api/projects/{projectId}/custom-tags/css-bundle | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{projectId}/custom-tags/preview | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |

## ProjectDashboardController (2エンドポイント、ベースパス `/api/projects/{projectId}/dashboard`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET .../google-analytics | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `GoogleAnalyticsReportService.getReport()`内 |
| GET .../adsense | requireProjectMemberOrAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `AdSenseReportService.getReport()`内 |

## ProjectImageSettingsController (5エンドポイント、ベースパス `/api/projects/{id}`)

media-service所有(issue #583)。実装は`GET /image-settings`と4件の`PUT`(画像生成プロンプト/サイズ
既定値・記事画像リサイズ既定値・コンテンツフィルタ設定)の計5エンドポイントを持つ。`PUT`4件は
legacy-apiからの移設前「## ProjectController」節に記載されていたが、#1049で本節へ移設した。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/projects/{id}/image-settings | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | `ProjectImageSettingsController.java:50-58` |
| PUT /api/projects/{id}/image-generation-prompt-defaults | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/projects/{id}/image-generation-size-defaults | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/projects/{id}/article-image-resize-default | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/projects/{id}/image-content-filter-settings | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |

## ProjectMediaGarbageCollectionController (2エンドポイント、ベースパス `/api/projects/{id}/media-garbage-collection`)

media-service所有(issue #573 stage3)。プロジェクト画面の「ガベージコレクション」タブ向けAPI(issue #500)。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/projects/{id}/media-garbage-collection/scan | requireAdmin | 401 | 403 | 認可OK | 現状維持 | `ProjectMediaGarbageCollectionController.java:41-45` |
| POST /api/projects/{id}/media-garbage-collection/delete | requireAdmin | 401 | 403 | 認可OK | 現状維持 | `ProjectMediaGarbageCollectionController.java:47-56` |

## ProjectSnsController (4エンドポイント、ベースパス `/api/projects/{projectId}/sns/x`)

project-service所有(issue #1574)。プロジェクト設定画面の「SNS 告知」欄(公式 X アカウントの接続)向けAPI。
OAuth のトークンは本番サイトの letsblog プラグインへ送るだけで、アプリは保存せず、どの応答にも載せない。
クライアントの秘密と PKCE の検証子は認可の間だけメモリに持つ。コールバックは認可を始めた本人・同じプロジェクト
だけを受け付ける(`SnsXService#completeAuthorization`)。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/projects/{projectId}/sns/x | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | 接続状態・接続できない理由・告知履歴。本番サイトに届かないときは「取得できない」として返す |
| POST /api/projects/{projectId}/sns/x/authorize | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 認可の開始。X の認可画面の URL を返す |
| POST /api/projects/{projectId}/sns/x/callback | requireAdmin | 401 | 403 | 認可OK | 現状維持 | Next.js 側コールバックからのサーバー間呼び出し。アカウント名だけを返す |
| POST /api/projects/{projectId}/sns/x/test | requireAdmin | 401 | 403 | 認可OK | 現状維持 | テスト投稿 |

## ProjectSnsPvController (4エンドポイント、ベースパス `/api/projects/{projectId}/sns/pv`)

project-service所有(issue #1578)。プロジェクト設定画面の「SNS 告知」欄の PV 達成ルール向けAPI。ルールの正本はアプリ
(`project_pv_rules`)で、ルールの保存・削除・再送のたびに、GA4 の認証情報(analytics-service から復号済みで読む)と
ルール全件を本番サイトの letsblog プラグインへ wp-cli(`pv config set` / `pv rules set`)で送る。GA4 の認証情報は
どの応答にも載らず、アプリは保存しない。GA が未連携のプロジェクトではルールを追加できない(409)。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/projects/{projectId}/sns/pv | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | ルール・追加できない理由・本番サイトへの送信状態 |
| POST /api/projects/{projectId}/sns/pv/rules | requireAdmin | 401 | 403 | 認可OK | 現状維持 | ルールの追加。GA 未連携は409 |
| DELETE /api/projects/{projectId}/sns/pv/rules/{ruleId} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | ルールの削除。残りを本番サイトへ送り直す |
| POST /api/projects/{projectId}/sns/pv/resend | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 送信失敗からの再送 |

内部ブリッジ(gateway非経由、呼び出し元ユーザーのBearerトークンを転送):

| HTTPメソッド + パス | 認可チェック | 備考 |
| --- | --- | --- |
| POST /api/internal/project/projects/{projectId}/sns/pv/sync | requireProjectMemberOrAdmin | `ProjectPvInternalController`。analytics-service が GA のプロパティ選択の完了を知らせ、本番サイトへ GA4 の認証情報とルールを送らせる |
| GET /api/internal/analytics/projects/{projectId}/google-analytics/credentials | requireProjectMemberOrAdmin | analytics-service の `InternalAnalyticsProjectSettingsController`。復号済みの GA4 認証情報を project-service へ返す。秘密を返すため、他の内部ブリッジと違って呼び出し元ユーザーの権限を検査する |

## ProjectSnsThreadsController (5エンドポイント、ベースパス `/api/projects/{projectId}/sns/threads`)

project-service所有(issue #1579)。プロジェクト設定画面の「Threads」欄(公式 Threads アカウントの接続・切断)向けAPI。
`ProjectSnsController`(X)と同じ方式で、OAuth の長期トークンは本番サイトの letsblog プラグインへ送るだけで、
アプリは保存せず、どの応答にも載せない。アプリのシークレットは認可の間だけメモリに持つ。コールバックは認可を始めた本人・
同じプロジェクトだけを受け付ける(`SnsThreadsService#completeAuthorization`)。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/projects/{projectId}/sns/threads | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | 接続状態・接続できない理由・告知履歴。本番サイトに届かないときは「取得できない」として返す |
| POST /api/projects/{projectId}/sns/threads/authorize | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 認可の開始。Threads の認可画面の URL を返す |
| POST /api/projects/{projectId}/sns/threads/callback | requireAdmin | 401 | 403 | 認可OK | 現状維持 | Next.js 側コールバックからのサーバー間呼び出し。アカウント名だけを返す |
| POST /api/projects/{projectId}/sns/threads/test | requireAdmin | 401 | 403 | 認可OK | 現状維持 | テスト投稿 |
| DELETE /api/projects/{projectId}/sns/threads | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 切断。本番サイトのプラグインから Threads の設定を消す |

## ProjectSnsLinkedinController (5エンドポイント、ベースパス `/api/projects/{projectId}/sns/linkedin`)

project-service所有(issue #1581)。プロジェクト設定画面の「LinkedIn」欄(公式 LinkedIn アカウントの接続・切断)向けAPI。
`ProjectSnsController`(X)と同じ方式で、OAuth のアクセストークンは本番サイトの letsblog プラグインへ送るだけで、
アプリは保存せず、どの応答にも載せない。Client Secretは認可の間だけメモリに持つ。コールバックは認可を始めた本人・
同じプロジェクトだけを受け付ける(`SnsLinkedinService#completeAuthorization`)。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/projects/{projectId}/sns/linkedin | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | 接続状態・接続できない理由・告知履歴。本番サイトに届かないときは「取得できない」として返す |
| POST /api/projects/{projectId}/sns/linkedin/authorize | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 認可の開始。LinkedIn の認可画面の URL を返す |
| POST /api/projects/{projectId}/sns/linkedin/callback | requireAdmin | 401 | 403 | 認可OK | 現状維持 | Next.js 側コールバックからのサーバー間呼び出し。アカウント名だけを返す |
| POST /api/projects/{projectId}/sns/linkedin/test | requireAdmin | 401 | 403 | 認可OK | 現状維持 | テスト投稿 |
| DELETE /api/projects/{projectId}/sns/linkedin | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 切断。本番サイトのプラグインから LinkedIn の設定を消す |

## ProjectSnsFacebookController (7エンドポイント、ベースパス `/api/projects/{projectId}/sns/facebook`)

project-service所有(issue #1580)。プロジェクト設定画面の「Facebook」欄(公式 Facebook ページの接続・切断)向けAPI。
`ProjectSnsController`(X)と同じ方式で、OAuth のトークン(ユーザー・ページ)は本番サイトの letsblog プラグインへ送るだけで、
アプリは保存せず、どの応答にも載せない。個人アカウントには投稿しないため、認可のあとに投稿先のページを選ぶ
(`/pages` で選べるページの ID と名前だけを返し、`/page` で選んだページのトークンだけを本番サイトへ送る)。
ページのトークンは選ぶまでの間だけメモリに持つ(`FacebookPageSelectionStore`、期限10分)。コールバック・一覧・選択は、
認可を始めた本人・同じプロジェクトだけを受け付ける(`SnsFacebookService`)。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/projects/{projectId}/sns/facebook | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | 接続状態・接続できない理由・告知履歴。本番サイトに届かないときは「取得できない」として返す |
| POST /api/projects/{projectId}/sns/facebook/authorize | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 認可の開始。Facebook の認可画面の URL を返す |
| POST /api/projects/{projectId}/sns/facebook/callback | requireAdmin | 401 | 403 | 認可OK | 現状維持 | Next.js 側コールバックからのサーバー間呼び出し。選べるページの一覧(ID と名前)だけを返す |
| GET /api/projects/{projectId}/sns/facebook/pages | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 認可のあとに選べるページの一覧(state が必要)。トークンは返さない |
| POST /api/projects/{projectId}/sns/facebook/page | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 投稿先のページを選ぶ。そのページのトークンだけを本番サイトへ送り、ページ名だけを返す |
| POST /api/projects/{projectId}/sns/facebook/test | requireAdmin | 401 | 403 | 認可OK | 現状維持 | テスト投稿 |
| DELETE /api/projects/{projectId}/sns/facebook | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 切断。本番サイトのプラグインから Facebook の設定を消す |

## ProjectUserController (1エンドポイント、ベースパス `/api/project-users`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/project-users | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 全プロジェクト横断のproject-userペア一覧。統合テストで代表検証済み(b) |

## RoleController (1エンドポイント、ベースパス `/api/roles`)

identity-service所有(RBAC、issue #653系)。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/roles | requirePermission(ROLE_MANAGE) | 401 | 403 | 認可OK | 現状維持 | `RoleController.java:25-29` |

## SiteController (11エンドポイント、ベースパス `/api/sites`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/sites | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | Swagger `@ApiResponse`は401のみ列挙(403は無い)。実装と整合 |
| POST /api/sites/managed-wordpress | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | Swagger `@ApiResponse`は401のみ列挙。実装と整合するが、マネージドサイトの新規作成という重い操作にadmin/所属チェックが無い |
| POST /api/sites/managed-wordpress/adopt | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 同上 |
| GET /api/sites | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | 全サイト一覧 |
| GET /api/sites/{id} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | Swagger `@ApiResponse`が403を明記しており実装と整合 |
| POST /api/sites/ssh-keypair | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/sites/{id} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/sites/{id}/test-connection | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | Swagger `@ApiResponse`は401のみ列挙(403は無い)。実装と整合。テスト(`testConnection_admin権限不問で呼べる`)でも意図的と明記 |
| DELETE /api/sites/{id} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/sites/{id}/install-wp-cli | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/sites/{id}/reprovision | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |

## SiteStaticContentController (2エンドポイント、ベースパス `/api/sites/{siteId}/static-content`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/sites/{siteId}/static-content | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 統合テストで代表検証済み(b) |
| POST /api/sites/{siteId}/static-content/generate | requireAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/sites/{siteId}/static-content/generate/jobs | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 非同期ジョブとして受理(#1409)。`generate`と同じ認可 |
| PUT /api/sites/{siteId}/static-content/{contentType} | requireAdmin | 401 | 403 | 認可OK | 現状維持 | 生成結果を確認した利用者の「保存」(#1409)。既存の`static_content`への書き込み |

## SshKeyPairController (3エンドポイント、ベースパス `/api/ssh-key-pairs`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/ssh-key-pairs | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `SshKeyPairService.list()`内 |
| POST /api/ssh-key-pairs | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `SshKeyPairService.generate()`内 |
| DELETE /api/ssh-key-pairs/{id} | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `SshKeyPairService.delete()`内 |

## SystemSettingController (4エンドポイント、ベースパス `/api/system-settings`)

platform-service所有(issue #693)。未認証401はplatform-serviceの`SecurityConfig`が担う(#705)。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/system-settings/brave-search-api-key | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | `SystemSettingService.getBraveSearchApiKeyStatus()`にrequireAdmin()が無い(設定値そのものは返さず、設定済みか否か/設定元のみ) |
| PUT /api/system-settings/brave-search-api-key | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `SystemSettingService.setBraveSearchApiKey()`内 |
| DELETE /api/system-settings/brave-search-api-key | requireAdmin(service層) | 401 | 403 | 認可OK | 現状維持 | `SystemSettingService.clearBraveSearchApiKey()`内 |
| GET /api/system-settings/site-admin-path | requireAuthenticated(service層) | 401 | 該当なし | 認可OK | 現状維持 | `AppSettingService.getSiteAdminPath()`内(#1079)。非adminのサイト一覧が必要とする秘匿でない値のため、adminは要求しない |

## TagDesignSettingController (3エンドポイント、ベースパス `/api/projects/{projectId}/tag-design-settings`)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/projects/{projectId}/tag-design-settings | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| PUT /api/projects/{projectId}/tag-design-settings/{tagType} | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{projectId}/tag-design-settings/{tagType}/generate | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | |
| POST /api/projects/{projectId}/tag-design-settings/{tagType}/generate/jobs | requireProjectMemberOrAdmin | 401 | 403 | 認可OK | 現状維持 | 非同期ジョブとして受理(#1409)。`generate`と同じ認可 |

## TaxonomyController (1エンドポイント、ベースパスなし)

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| POST /api/taxonomy/resolve | なし | 401 | 該当なし | 認可OK | 要検討(本Issueの対象外) | site識別子を渡せば任意サイトのカテゴリ/タグ解決が可能 |

## VscodeExtensionController (1エンドポイント、ベースパス `/api/system/vscode-extension`)

platform-service所有(issue #696)。未認証401はplatform-serviceの`SecurityConfig`が担う(#705)。
移設直後は同SecurityConfigが全経路permitAllだったためAuthorizationヘッダーなしでも200で.vsixが
取得できていた(#705の後退)。ロールチェックが無く「ログイン済みなら誰でも取得可能」である点は
issue #705でも変更しておらず、下記「既知のギャップ」に残っていた。
#830 で `VscodeExtensionController` に `認可不要: <理由>` を明記し、
「ログイン済みなら誰でも取得可能」が意図的な判断であることを記録した。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/system/vscode-extension | なし(#830 で「認可不要」と決定) | 401 | 該当なし | 認可OK | 現状維持 | VSCode拡張機能(.vsix)のビルド・ダウンロード。認証済みなら誰でも取得可能。#830 で意図的な判断として `認可不要:` コメントに記録済み |

## PenpotPluginController / McpServerController (各1エンドポイント、ベースパス `/api/system/penpot-plugin` / `/api/system/mcp-server`)

platform-service所有(issue #1491)。ソース一式 + `setup.sh` + README のZipを返す(サーバー側ではビルドしない)。
未認証401は`SecurityConfig`が担い、`VscodeExtensionController`と同じく「ログイン済みなら誰でも取得可能」を
意図的な判断として各メソッドの`認可不要:`コメントに記録している。

| HTTPメソッド + パス | 認可チェック | 未認証 | 権限不足 | 権限あり | あるべき | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| GET /api/system/penpot-plugin | なし(「認可不要」) | 401 | 該当なし | 認可OK | 現状維持 | Penpotプラグインのソース一式Zip。`node_modules`とビルド生成物は含めない |
| GET /api/system/mcp-server | なし(「認可不要」) | 401 | 該当なし | 認可OK | 現状維持 | MCPサーバーのソース一式Zip。`node_modules`・`.env`・ログは含めない |

---

## 既知のギャップ(#830 で解消済み。履歴として残す)

> **2026-08-31 更新(#830)。** かつてこの節は「認可チェックが無いエンドポイント」を
> 列挙していた(下記「解消前の一覧」)。#830 で**その全件について認可の要否を決定し実装へ
> 反映した**ため、列挙は現在の実態を表していない。
>
> **現在の一次情報はこの節ではない。** 上の「[認可チェックの網羅状況(issue #830)]
> (#認可チェックの網羅状況issue-830)」節と、そこで説明している
> `AuthorizationCoverageContract`(`packages/lbs-common` の testFixtures)の許可リストが
> 一次情報である。許可リストは**全10サービスで空**であり、認可チェックを持たない
> エンドポイントが新たに増えると各サービスの `AuthorizationCoverageTest` が失敗する。
>
> ここに手で書いた一覧を再び置かないこと。**この一覧が腐ったことが、機械検査へ
> 移した理由そのもの**である。

### 認可を付けなかったエンドポイントの調べ方

「認証済みなら誰でもよい」と判断したものは、許可リストではなく**そのハンドラの Javadoc に
`認可不要: <理由>` と書く**規約になっている。現在の全件はリポジトリから直接引ける:

```bash
grep -rn "認可不要:" services/*/src/main/java --include=*.java
```

理由は概ね次のいずれかに分類される。

| 分類 | 例 |
| --- | --- |
| 利用者自身の入力からの生成で、保存済みリソースに触れない | `AiController#draft` / `#ask` / `#tags` / `#proofread` |
| 渡された値を検査・変換して返すだけの純粋な関数 | `CustomTagController#validate`、`MetadataController` |
| ログイン後の共通ダッシュボードが出す要約(所有者軸が無い) | `DashboardController#getServiceStatus`、`GenerationJobController#list` |
| クライアントが自分の記録を送る書き込み専用の窓口 | `OperationLogController`、`FrontendErrorLogController` |
| `SecurityConfig` の `PUBLIC_PATHS`(未認証で到達する公開パス) | `HealthController`、`AuthController#setup` / `#setupStatus` |
| gateway のルート表に無く外部から到達できない(呼び出し元が認可済み) | `RenderController`、`ComfyUiCheckpointController` |

### 注記: `requireAuthenticated()` は認可ではない

`AuthorizationCoverageContract` は `requireAuthenticated` も認可呼び出しとして数える。
厳密には認証の再確認であって認可ではないため、**これだけを持つエンドポイントは
「認証済みなら誰でも」と同義**である。現在の該当は2件で、いずれも意図的:

- `SystemSettingService#getBraveSearchApiKeyStatus`(`GET /api/system-settings/brave-search-api-key`)
  — 設定済みか否かと設定元だけを返す読み取り専用で、値は返さない。admin 限定にしない判断は
  #693 のレビューで決めたもの(legacy-api 版も admin 以外の認証済み利用者から到達できた)。
- `AppSettingService#getSiteAdminPath`(`GET /api/system-settings/site-admin-path`)
  — 管理画面パスのグローバル既定値(#1079)。非 admin にも開かれるサイト一覧のリンク生成に必要な
  秘匿でない値のため admin を要求しない。保存時の検証で絶対 URL・`//`・スキーム付きを拒否している。

新たに `requireAuthenticated` だけのエンドポイントを足す場合は、ここに理由を追記すること。

### 解消前の一覧(2026-08-31 以前。**現状ではない**)

<details>
<summary>#830 着手前に「有効なJWTさえあれば到達できる」と記録されていたエンドポイント</summary>

- `AiController`: `POST /api/ai/draft`, `POST /api/ai/ask`, `POST /api/ai/tags`,
  `POST /api/ai/proofread`, `POST /api/ai/image`, `GET /api/ai/image-options`,
  `POST /api/ai/section`(`generateImagePrompt`のみプロジェクト単位チェックあり)
- `ContentCacheController`: `GET /api/content-cache`
- `CustomTagController`: `POST /api/custom-tags/validate`, `GET /api/custom-tags`,
  `GET /api/custom-tags/css-bundle`
- `CustomTagTemplateController`: `GET /api/custom-tag-templates/{id}`,
  `GET /api/custom-tag-templates`, `GET /api/custom-tag-templates/my-templates`
- `DashboardController`: `GET /api/dashboard/service-status`,
  `GET /api/dashboard/service-status/stream`, `GET /api/dashboard/container-status`,
  `GET /api/dashboard/container-status/stream`
- `FrontendErrorLogController`: `POST /api/logs/errors`
- `GenerationJobController`: `GET /api/generation-jobs`, `GET /api/generation-jobs/{id}`,
  `PATCH /api/generation-jobs/{id}`(#573 stage2で追加), `POST /api/generation-jobs`(#573 stage3で追加)
- `OperationLogController`: 全4エンドポイント(自己スコープ設計。ただし`GET /{operationId}`のみ#1471でadminに全利用者を許可)。`OperationLogStatsController`(#1471): admin限定の集計2エンドポイント
- `PostController`: 全4エンドポイント。WordPressへの投稿公開・削除を含む、影響の大きい操作
- `ProjectController`: `GET /api/projects`(一覧), `GET /api/projects/{id}`(詳細)
- `SiteController`: `POST /api/sites`, `POST /api/sites/managed-wordpress`,
  `POST /api/sites/managed-wordpress/adopt`, `GET /api/sites`,
  `POST /api/sites/{id}/test-connection`
- `SystemSettingController`: `GET /api/system-settings/brave-search-api-key`, `GET /api/system-settings/site-admin-path`
- `TaxonomyController`: `POST /api/taxonomy/resolve`
- `VscodeExtensionController`: `GET /api/system/vscode-extension`

</details>

### 未使用の認可プリミティブ

- `AdminAuthorizationService.requireSelfOrAdmin(Long userId)` — 定義されているが、
  現時点でどのコントローラからも呼ばれていない(未使用)。
- `PermissionAuthorizationService.requirePermission(Permission)` — 同様に未使用。
