# 受け入れ基準カタログ(機能インベントリ / トレーサビリティ表)

実装済みの全機能を一意なIDで列挙し、各機能に「利用者から見た受け入れ基準」と
「対応する `.feature` シナリオ」を紐付けた台帳。issue #927(AT-1)で作成。

この表の目的は**カバレッジの穴を可視化すること**である。
受け入れテストが1件も無い機能は、この表に `未着手` として現れる。
AT-3〜AT-19(#929〜#945)の各Issueが、自分の担当領域の行を `検証済` に変えていく。

- 受け入れテストの書き方・タグ規約・実行方法は [ACCEPTANCE_TESTING.md](ACCEPTANCE_TESTING.md)
- 実行環境の前提は [e2e-testing.md](e2e-testing.md)

---

## 1. 表の読み方

| 列 | 意味 |
| --- | --- |
| 機能ID | `AC-<領域略号>-<連番>`。**一度付けたら変更しない**。行を廃止するときは削除せず `対象外` にする |
| 機能 | 利用者が認識する単位。画面名・コマンド名・APIの束 |
| 利用者から見た価値 | なぜこの機能があるのか。ここが書けない行は機能の切り方が間違っている |
| 受け入れ基準(要約) | 利用者から観測できるふるまい。`.feature` のシナリオの元になる |
| 対応シナリオ | `apps/web/e2e/features/...` のファイルパスとシナリオ名。未実装なら `—` |
| 状態 | `未着手` / `実装中` / `既存spec` / `検証済` / `対象外(理由)` |

状態の意味:

- `未着手` — 受け入れテストが1件も無い
- `実装中` — `.feature` を書いたが通っていない(`test.skip` で止まっている場合を含む)
- `既存spec` — 移行前の Playwright spec が担保している。`.feature` への移行待ち。
  `既存spec(部分)` は、その行の受け入れ基準の一部しか見ていないことを示す
  (例: フォームに到達するだけで送信結果を見ていない)。
  **spec が存在することと、通っていることは別である。** #949 では
  `custom-tag-generation.spec.ts` の6テストが `beforeAll` の403で1件も実行されておらず、
  この表で `既存spec` としていた6行が実際には未検証だった。状態を書くときは
  「その spec が今この環境で通ること」を確かめること
- `検証済` — そのシナリオが `npm run test:at` で実際に通っている
- `対象外(理由)` — 受け入れテストを書かないと決めた。**理由を必ず書く**

### 領域略号

| 略号 | 領域 | 担当Issue |
| --- | --- | --- |
| `SET` | 初回セットアップ | AT-3 (#929) |
| `AUTH` | 認証・セッション | AT-3 (#929) |
| `USR` | ユーザー・ロール・権限・プロジェクトメンバー | AT-4 (#930) |
| `PRJ` | プロジェクト・環境 | AT-5 (#931) |
| `SITE` | サイト管理 | AT-5 (#931) |
| `POST` | 執筆から公開までのジャーニー | AT-6 (#932) |
| `BULK` | 一括管理・環境間比較 | AT-7 (#933) |
| `AI` | AI執筆支援 | AT-8 (#934) |
| `PLAN` | 記事プラン・GitHub Issue連携 | AT-9 (#935) |
| `IMG` | 画像生成・ギャラリー・画像設定・メディアGC | AT-10 (#936) |
| `DIAG` | ダイアグラムとレンダリング | AT-11 (#937) |
| `TAG` | カスタムタグ・テンプレート・コンテンツ設定 | AT-12 (#938) |
| `ANA` | Analytics(GA / AdSense) | AT-13 (#939) |
| `SYS` | システム設定・バックアップ・拡張配布・ダッシュボード | AT-14 (#940) |
| `LOG` | ログと非同期経路 | AT-15 (#941) |
| `EXT` | VSCode拡張 | AT-16 (#942) |
| `XC` | 横断的品質(認可・ルーティング・レート制限・相関ID・縮退) | AT-17 (#943) |
| `UX` | 横断的品質(i18n・アクセシビリティ・レスポンシブ) | AT-18 (#944) |
| `STUB` | 外部依存スタブ(受け入れテストの土台) | AT-2 (#928) |
| `INT` | サービス間契約(`/api/internal/**`) | §4 |

---

## 2. 機能インベントリ

### 2.1 初回セットアップ / 認証 — `SET` / `AUTH`

画面: `/setup`, `/login`
API: identity `AuthSetupController`, `IdentityController` / Keycloak Device Code

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-SET-001 | 初回セルフサインアップ (`/setup`) | 誰も居ないシステムに最初の管理者を作れる | ユーザー0人なら `/` は `/setup` へ誘導される。作成した管理者はそのアカウントでログインでき、管理者専用ページへ入れる(自動ログインはしない。#564) | `features/auth/setup.feature` › ユーザーが1人も居ないとトップページは初回セットアップへ誘導する / 初回セットアップで作った最初のユーザーは管理者としてログインできる | 検証済(#1280で管理者パスワードが空のまま送信され続けるシナリオ側の欠陥を修正し、ログイン後の `/users` 遷移ステップも #1078 と同種の `goto`/`waitForURL` 競合に強くした。0人リセット→3回連続実行で毎回全3シナリオが通ることを確認済み) |
| AC-SET-002 | セットアップ済みの再実行拒否 | 他人が後から管理者を勝手に作れない | ユーザーが1人以上居ると未認証の `POST /api/auth/setup` は拒否され、ユーザーも作られない | `features/auth/setup-guard.feature` › ユーザーが居る状態では初回セットアップは拒否される | 検証済 |
| AC-SET-003 | セットアップ状態の照会 | 画面が「まだ初期設定が必要か」を判定できる | `GET /api/auth/setup-status` が未認証で到達でき、ユーザーの有無を返す | `features/auth/setup.feature` / `setup-guard.feature` の全シナリオが前提として検証 | 検証済 |
| AC-AUTH-001 | Keycloakホスト型ログイン画面への誘導 | パスワードをアプリに預けずに認証できる | `/login` は Keycloak のログイン画面へリダイレクトする | `features/auth/login.feature` › ログイン画面にアクセスするとKeycloakのホスト型ログイン画面へリダイレクトされる | 検証済 |
| AC-AUTH-002 | ログイン成功 | 自分のアカウントで作業を始められる | 正しい資格情報でセッションが確立し、ログアウトボタンが出る | `features/auth/login.feature` › 正しい資格情報でログインするとセッションが確立する | 検証済 |
| AC-AUTH-003 | ログイン失敗 | 誤った資格情報で入れない | 誤ったパスワードでは Keycloak 側にエラーが出てコールバックへ進まない | `features/auth/login.feature` › 誤ったパスワードではログインできない | 検証済 |
| AC-AUTH-004 | ログアウト | 共用端末でセッションを断てる | ログアウト後、保護ページはログイン画面へ戻される | `features/auth/login.feature` › ログアウトするとセッションが破棄され、保護ページはログイン画面へ戻される | 検証済 |
| AC-AUTH-005 | Device Code フロー(拡張のログイン) | エディタからブラウザ経由で安全にログインできる | デバイス認可要求→承認→トークン取得が成功し、未承認なら `authorization_pending`、無効なデバイスコードは拒否される | `features/auth/device-code.feature` › 全4シナリオ | 検証済(§4.1 参照) |
| AC-AUTH-006 | 無効化ユーザーのトークン失効 | 退職者の発行済みトークンが使えなくなる | `POST /api/users/{id}/deactivate` 後、既発行アクセストークンでの要求が拒否され、新規トークンも取得できない(#816) | `features/auth/token-lifecycle.feature` › 無効化したユーザーの発行済みアクセストークンは拒否される | 検証済(`@destructive`) |
| AC-AUTH-007 | 認証ゲート(ADR-0008) | gateway を迂回しても保護が効く | 全9サービスへ直接アクセスすると、JWT無し・他レルム・改竄署名のいずれも401。有効なJWTなら401にならない | `features/auth/auth-gate.feature` › 全4シナリオ | 検証済(`@api`) |
| AC-AUTH-008 | セッション期限切れ | 期限切れで自動的に締め出される | 復号できないセッションでは保護ページに入れず、ログイン画面へ戻される | `features/auth/token-lifecycle.feature` › 不正なトークンではWebの保護ページに入れずログイン画面へ戻される | 検証済(§4.1 参照) |
| AC-AUTH-009 | 自分のプロフィール・権限の取得 | 画面が自分の権限に応じた出し分けをできる | `GET /api/identity/me` と `/me/permissions` が本人の情報と権限集合を返す | `features/auth/permissions.feature` › 権限APIの内容と画面の出し分けが一致する | 検証済 |
| AC-AUTH-010 | 表示設定(preferences)の保存 | 言語・表示設定が次回も維持される | `PATCH /api/identity/me/preferences` の内容が再ログイン後も反映される | — | 未着手 |

### 2.2 ユーザー・ロール・権限 — `USR`

画面: `/users`, `/users/[id]/edit`, `/admin/roles`
API: identity `UserController`, `RoleController`, `ProjectUserController` / content `MetadataController`

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-USR-001 | ユーザー一覧 (`/users`) | 誰がシステムを使えるか把握できる | 管理者は一覧を閲覧でき、非管理者はアクセスを拒否される | `features/auth/permissions.feature` › 非管理者は管理者専用ページへアクセスすると拒否される / 管理者は管理者専用ページへアクセスできる | 検証済 |
| AC-USR-002 | ユーザー作成 | 新しいメンバーを迎え入れられる | `POST /api/users` でユーザーが作成され、一覧と Keycloak の双方に現れる | `features/identity/user-management.feature` › 管理者が新しいメンバーを登録でき、一覧とKeycloakの双方に現れる | 検証済 |
| AC-USR-003 | ユーザー編集 (`/users/[id]/edit`) | 氏名・メール・所属を直せる | `PUT/PATCH /api/users/{id}` の変更が一覧へ反映される | `features/identity/user-management.feature` › 管理者がメンバーの表示名を編集できる / › 管理者がメンバーのメールアドレスを編集でき、一覧とKeycloakの双方に反映される / › 管理者が既に使われているメールアドレスへは変更できない | 部分的に検証。表示名は検証済。メールアドレス編集は#1192で実装済みだがGherkin 2件は未実行(検証用のAT環境が無いホストで作成)のため、AT環境での実行結果をもって検証済とする |
| AC-USR-004 | ユーザー削除 | 不要なアカウントを消せる | `DELETE /api/users/{id}` 後、そのユーザーではログインできない | `features/identity/user-management.feature` › 管理者が一覧から不要なメンバーを削除でき、Keycloak側とも整合する | 検証済 |
| AC-USR-005 | 無効化 / 再有効化 | 退職者を消さずに止められる | `deactivate` でログイン・API利用が拒否され、`reactivate` で戻る | `features/identity/user-deactivation.feature` › 管理者がアカウントを無効化でき、無効化されたアカウントではログインできない / 無効化したアカウントを再有効化でき、再びログインできる | 検証済 |
| AC-USR-006 | ロール付与 / 剥奪 | 権限を後から変えられる | `POST/DELETE /api/users/{userId}/roles/{roleName}` の結果が `/me/permissions` に反映される | `features/identity/roles-and-permissions.feature` › ロールを付与/剥奪すると対象ユーザーのGET /api/identity/me/permissionsの内容が変わる / 権限(users.roleの付与/剥奪)の変化がUIのメニュー出し分けに反映される | 検証済 |
| AC-USR-007 | ロール一覧 (`/admin/roles`) | どんな権限セットがあるか分かる | `GET /api/roles` の内容が画面に表示される | `features/identity/roles-and-permissions.feature` › /admin/rolesに表示されるロールごとの権限一覧はGET /api/rolesの内容と一致する | 検証済 |
| AC-USR-008 | プロジェクトメンバー管理 | プロジェクト単位でアクセスを絞れる | `/api/projects/{id}/users` の追加・変更・削除が、そのユーザーの見えるプロジェクトに反映される | `features/identity/project-members.feature` › 管理者がプロジェクトにメンバーを追加でき、そのメンバーがプロジェクトを閲覧できる / プロジェクトメンバーの役割を変更できる / メンバーから外された利用者は、そのプロジェクトへアクセスできない | 検証済 |
| AC-USR-009 | 自分の参加プロジェクト一覧 | 自分に関係するプロジェクトだけ見える | 非管理者の `GET /api/projects` は自分が所属するプロジェクトだけを返し、管理者は全件を返す(`accessibleProjectIds()` による絞り込み、#830)。`GET /api/project-users` は管理画面(`/users`)向けの管理者専用・全件返却(`requireAdmin`、全プロジェクト横断の割当一覧)であり、「自分の所属だけ」を返す仕様ではない(#1195 で確定。`docs/AUTHORIZATION_MATRIX.md` の `GET /api/project-users` 行の「現状維持」と一致) | `GET /api/project-users` は `AuthorizationMatrixIntegrationTest` で認証なし(401)のみ検証しており、非管理者の403は未検証。共有シナリオ(`features/identity/project-members.feature` › メンバーから外された利用者は、そのプロジェクトへアクセスできない、AC-USR-008 と共有)が検証するのは除外後の `GET /api/projects/{id}` の403であり、`GET /api/projects` 一覧の絞り込みと `GET /api/project-users` の非管理者403を検証するGherkin・統合テストは未作成 | 未着手。仕様確定(#1195)。一覧絞り込みの受け入れテストは未着手 |
| AC-USR-010 | GitHubトークンの登録 | 記事プランのIssue連携が使える | `PUT /api/users/{id}/github-token` 後、Issue連携が成功する | — | 未着手 |
| AC-USR-011 | メタデータ(投稿ステータス/ロール)の取得 | 画面の選択肢がサーバー定義と一致する | `GET /api/metadata/post-statuses` と `/roles` が画面の選択肢と一致する | `features/publishing/publish-status.feature` › 投稿ステータスの選択肢はAPIとUIで一致する(issue #1172 / AT-6-2、親issue #932の親シナリオ7。投稿ステータス側のみ。プロジェクト一括管理画面「ポスト/ページ」タブのステータス変更セレクトと比較する)。**対象外**: ロール(`/roles`)側は #1163(AT-6-7)が担当、未検証のまま | 検証済(`@slow`。投稿ステータス側のみ) |
| AC-USR-014 | 本人設定(タイムゾーン/ロケール)の保存 | 自分の表示設定を保てる。タイムゾーンは任意の上書きで、未設定なら閲覧者のブラウザのタイムゾーンに従う(#1259) | `PATCH /api/identity/me/preferences` で保存した内容が、再ログイン後も `GET /api/identity/me` に反映される(#784 の退行検知)。タイムゾーンに `null` を送ると未設定(ブラウザのタイムゾーンに従う)に戻せる(#1259) | `features/identity/user-management.feature` › 利用者が自分のタイムゾーン/ロケールを保存でき、再ログイン後も保持される / `features/identity/timezone-override.feature` (#1259) | 検証済 |
| AC-USR-015 | ユーザー操作の認可 | 権限の無い利用者にアカウントを改変されない | 一般ユーザーは `POST/PATCH/DELETE /api/users` を実行できず(403)、実行後も一覧・対象の状態が変化していない(#796 の退行検知) | `features/identity/user-authorization.feature` › 一般ユーザーは新しいメンバーを登録できず、ユーザー一覧は変化しない / 一般ユーザーは他人のロールも削除も操作できず、対象の状態は変化しない | 検証済 |
| AC-USR-016 | 自己権限昇格・自己締め出しの防止 | 権限を持つ利用者でも自分を特別扱いできない | ROLE_MANAGE保有者(非admin)は自分自身へ特権ロールを付与できず(403)、管理者は自分自身を無効化できず(403)、いずれも実行後に権限・アカウント状態が変化していない(#798 の退行検知) | `features/identity/self-guard.feature` › ROLE_MANAGE保有者は自分自身に特権ロールを付与できず、権限は変化しない / 管理者は自分自身を無効化できず、アカウント状態は変化しない | 検証済 |
| AC-USR-017 | 最後の管理者の保護 | 有効な管理者が0人にならない | 有効な管理者が2人以上いるときは互いに削除・無効化できる。最後の1人の削除・無効化は拒否され(403)、2つの削除/無効化要求が同時に来ても最後の1人は残る(#798 の「数えない」判断を覆し、悲観ロックで TOCTOU を塞ぐ #1162)。「唯一の管理者が対象」の状態と同時実行はWeb/APIから決定的に作れないため、その2点は実MySQLの統合テストで固定する | `features/identity/last-admin-guard.feature` › 管理者が2人いるとき、一方は他方を削除できる / 管理者が2人いるとき、一方は他方を無効化できる(回帰)。拒否と同時実行: `services/identity/src/test/java/com/letsblog/identity/integration/LastAdminGuardIntegrationTest.java` | 検証済 |

### 2.3 プロジェクト・環境・サイト — `PRJ` / `SITE`

画面: `/projects`, `/projects/[id]`, `/projects/[id]/settings/*`, `/sites`, `/sites/[id]/edit`, `/admin/ssh-keys`
API: project `ProjectController`, `SiteController`, `SshKeyPairController`, `SiteStaticContentController`, `ProjectGithubTokenController`

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-PRJ-001 | プロジェクト一覧 (`/projects`) | 管理対象のブログを一覧できる | 一覧に自分が参加するプロジェクトが表示される。**対象外**: 検索・フィルタ入力欄は`/projects`に未実装(`ProjectsTable.tsx`は定義されているが`page.tsx`から未使用のdead code、issue #645で確認・#1165で再確認)のため、シナリオでは対象としない。実装されたら本行とシナリオを更新すること | `features/project/project-management.feature` › プロジェクトを作成すると一覧に現れ、詳細を開ける | 検証済 |
| AC-PRJ-002 | プロジェクト作成 | 新しいブログの管理を始められる | フォームから作成でき、一覧と詳細に反映される | `features/project/project-management.feature` › プロジェクトを作成すると一覧に現れ、詳細を開ける | 検証済 |
| AC-PRJ-003 | プロジェクト詳細 (`/projects/[id]`) | 設定・サイト・記事をまとめて見られる | 作成時に入力した値が詳細画面に表示される。プロジェクト設定(画像生成のデフォルトサイズ・CSSセレクタ接頭辞)を保存すると、再取得後も反映されている(#913 の再発検知) | `features/project/project-management.feature` › プロジェクトを作成すると一覧に現れ、詳細を開ける / プロジェクト設定を保存すると、再読込後も表示される(#913の退行検知) | 検証済 |
| AC-PRJ-004 | プロジェクト編集・削除 | 不要になったら消せる | 詳細画面の`DeleteProjectButton.tsx`(確認ダイアログ含む)経由で削除すると一覧に反映され、紐付いていたサイトは削除されずに残る(`ProjectService#deleteProject`はプロジェクト行のみ削除する現行仕様どおり)。**対象外**: 一覧行の削除ボタンは`/projects`に未実装(`ProjectsTable.tsx`は定義されているが`page.tsx`から未使用のdead code、issue #645で確認・#1165で再確認)。実装されたら本行とシナリオを更新すること | `features/project/project-management.feature` › プロジェクトを削除すると一覧から消え、紐付くサイトは残る | 検証済 |
| AC-PRJ-005 | 環境の追加・削除 | 本番と検証を分けて運用できる | 詳細画面「概要」タブの`EnvironmentSlot.tsx`からサイトを紐付ける/切り離すと、`POST/DELETE /api/projects/{id}/environments`経由で画面とAPI取得結果の両方に反映される | `features/project/environments.feature` › 環境にサイトを追加・削除でき、マスター環境を切り替えられる | 検証済 |
| AC-PRJ-006 | マスタ環境の指定 | どの環境を正とするか決められる | 詳細画面「概要」タブの`MasterEnvironmentSelector.tsx`からマスター環境を切り替えると、`PUT /api/projects/{id}/master-environment`経由でAPI取得結果に反映される | `features/project/environments.feature` › 環境にサイトを追加・削除でき、マスター環境を切り替えられる | 検証済 |
| AC-PRJ-007 | 環境間の同期 | 検証環境を本番に揃えられる | `POST /api/projects/{id}/environments/sync` 後、差分が解消する | — | 未着手 |
| AC-PRJ-008 | GitHubリポジトリの紐付け | 記事プランをIssueと連携できる | 詳細画面「設定」タブの`ProjectGithubRepositoryForm.tsx`からリポジトリ(owner/repo形式)を設定すると、`PUT /api/projects/{id}/github-repository`経由でAPI取得結果に反映される。**対象外**: 設定したリポジトリからのIssue一覧取得(記事計画のGitHub Issue連携)はAT-9の対象であり本issue(#1166)の対象外 | `features/project/environments.feature` › GitHubリポジトリとトークンを設定でき、トークンは平文で再表示されない | 検証済(リポジトリ設定のみ) |
| AC-PRJ-009 | GitHubトークン(プロジェクト) | プロジェクト単位で連携先を分けられる | 詳細画面「設定」タブの`ProjectApiKeysForm.tsx`からトークンを設定・削除でき、`PUT/DELETE /api/projects/{projectId}/api-keys/github-token`の結果(設定済みか否かの真偽値のみ)がAPI取得結果に反映される。`ProjectGithubTokenController`の応答は`configured`の真偽値のみを返し暗号化前のトークン本体を含まないこと、および画面の入力欄が`type="password"`で送信後も値を保持しない(平文で再表示されない)ことを確認する | `features/project/environments.feature` › GitHubリポジトリとトークンを設定でき、トークンは平文で再表示されない | 検証済 |
| AC-SITE-001 | サイト一覧 (`/sites`) | 公開先を一覧できる | 一覧に登録済みサイトと接続状態が表示される | `features/project/site-provisioning.feature` › サイト一覧ページとサイト作成フォームが表示される / プロビジョニング済みサイトの接続状態表示と検索絞り込みができる | 検証済 |
| AC-SITE-002 | サイト検索 | 多数のサイトから目的の1件を探せる | 検索語で一覧が絞り込まれる | `features/project/site-provisioning.feature` › プロビジョニング済みサイトの接続状態表示と検索絞り込みができる | 検証済(`@slow`) |
| AC-SITE-003 | 既存WordPressの登録 | 手持ちのブログを繋げられる | `POST /api/sites` で登録でき、一覧に現れる。資格情報が誤っている場合は登録自体は完了しつつ、疎通確認が失敗したことと理由(`failureReason`)が画面に表示される(issue #1168 / AT-5-4、親issue #931の親シナリオ8) | `features/project/site-registration.feature` › 資格情報が誤っているサイトの疎通確認は失敗し、原因が利用者に分かるメッセージが出る | 検証済 |
| AC-SITE-004 | ManagedWordPress の新規構築 | WordPressを自分で用意しなくてよい | `POST /api/sites/managed-wordpress` でサイトが構築され、公開URLが応答する | `features/project/site-provisioning.feature` › ManagedWordPressを新規プロビジョニングすると、サイトが作成され疎通確認が成功する / 1つのプロジェクトが2環境(=2サイト)を持てる / プロビジョニング済みサイトの識別子をat-main段階のシナリオから参照できる | 検証済(`@slow`) |
| AC-SITE-005 | ManagedWordPress の引き取り | 既存のコンテナを管理下に置ける | `POST /api/sites/managed-wordpress/adopt` 後、通常のサイトとして操作できる(issue #1169 / AT-5-5、親issue #931の親シナリオ7) | `features/project/site-adoption.feature` › 既存のWordPressをadoptで取り込むと、通常のサイトとして操作できる | 検証済(`@slow`) |
| AC-SITE-006 | 接続確認 | 公開前に繋がるか確かめられる | `POST /api/sites/{id}/test-connection` の結果が画面の接続状態に反映される | `features/project/site-provisioning.feature` › ManagedWordPressを新規プロビジョニングすると、サイトが作成され疎通確認が成功する | 検証済(`@slow`) |
| AC-SITE-007 | サイト編集・削除 (`/sites/[id]/edit`) | 認証情報やURLを直せる | `PUT/DELETE /api/sites/{id}` の結果が画面とAPIの両方に反映される。プロジェクトに紐付いていないサイトへの疎通確認・編集・削除も500にならず正常に完了する(issue #1168 / AT-5-4。#759の退行検知、親issue #931の親シナリオ9・10・16) | `features/project/site-registration.feature` › サイト情報を編集して保存できる / 登録を解除したサイトが一覧から消える / プロジェクトに紐付いていないサイトへの操作が500ではなく利用者に分かる結果になる(#759の退行検知) | 検証済 |
| AC-SITE-008 | WP-CLI の導入 | 一括管理機能が使えるようになる | `POST /api/sites/{id}/install-wp-cli` 後、一括管理の操作が成功する(issue #1169 / AT-5-5、親issue #931の親シナリオ14) | `services/publishing/src/test/java/com/letsblog/publishing/cms/ssh/WordPressSshOperationsTest.java` › `installWpCli_*`(`SshCommandExecutor`をモックしたサービスレベルテスト。導入成功後に`applyPluginTheme`が成功することも同テストで確認) | 検証済(サービスレベル)。ローカルの`wordpress`コンテナはSSHサーバを持たず、`install-wp-cli`はSSHトランスポートのサイトにしか対応しない(managed/AGENTサイトは常にエラーになる設計。理由はDockerイメージへのwp-cliビルド時導入)ため、Web UI/E2Eからは届かない基準としてサービスレベルテストで表現した(`CLAUDE.md`のTest-First Implementationが認める例外)。サービスレベルが恒久的な正式の検証範囲であり、実SSHサーバを使ったE2Eは足さない(利用者の決定 2026-10-01、issue #1197。理由は`docs/ACCEPTANCE_TESTING.md`の「11. JUnit のテストと重複したら、どちらを正とするか」内「`install-wp-cli` の SSH 経路はサービスレベルテストが正式な検証範囲」を参照) |
| AC-SITE-009 | 再プロビジョニング | 壊れたサイトを作り直せる | `POST /api/sites/{id}/reprovision` 後、サイトが再び応答し、既存の投稿データを失わない(issue #1169 / AT-5-5、親issue #931の親シナリオ15) | `features/project/site-adoption.feature` › 再プロビジョニングしても既存の投稿データは失われない | 検証済(`@slow` `@destructive`) |
| AC-SITE-010 | SSH鍵ペア管理 (`/admin/ssh-keys`) | 公開先へ鍵で安全に接続できる | `GET/POST/DELETE /api/ssh-key-pairs` の結果が一覧に反映され、サイト登録から選択できる(issue #1170 / AT-5-6、親issue #931の親シナリオ12) | `features/project/ssh-key-pairs.feature` › SSH鍵ペアを生成すると公開鍵が取得でき、秘密鍵は一覧に出てこない / SSH鍵ペアを削除すると一覧から消える(現行の無条件削除挙動) | 検証済(生成・秘密鍵の非開示・削除のみ。削除時の「使用中(サイトから参照中)かどうか」チェックは`SshKeyPairService.java`のコメント(issue #577 stage 3のTODO)の通り現行実装に存在しないため、issue #1170ではその追加を対象外とし、無条件削除の現行挙動をそのまま固定した) |
| AC-SITE-011 | 静的コンテンツの生成 | サイト共通のCSS等を配布できる | `POST /api/sites/{siteId}/static-content/generate` 後、`GET` が生成物を返す | — | 未着手 |

### 2.4 執筆から公開まで — `POST`

画面: `/posts`, `/projects/[id]/posts`, `/projects/[id]/dashboard`
API: content `PostController` / publishing `PostController`, `ArticlePreviewController`, `TaxonomyController` / content `ArticlePreviewController`

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-POST-001 | 主要ジャーニー(サイト登録→公開→履歴) | 記事を書いて公開するという中心的な価値が成立する | サイトを登録して記事を公開すると投稿履歴に表示される | `features/publishing/publish-lifecycle.feature` › 記事を新規公開すると、WordPress側に記事が作成され投稿履歴に表示される(issue #1171 / AT-6-1、親issue #932の親シナリオ1。`e2e/main-scenario.spec.ts`から移行し同specは削除) | 検証済(`@slow`) |
| AC-POST-002 | 投稿履歴一覧 (`/posts`) | 何をいつ公開したか追える | `GET /api/posts` の内容が一覧に表示される | `features/publishing/publish-lifecycle.feature` › 記事を新規公開すると、WordPress側に記事が作成され投稿履歴に表示される(issue #1171 / AT-6-1)。**対象外**: 複数件の一覧表示・絞り込みは未検証(このシナリオは新規公開した1件が`/posts`に現れることのみ確認する) | 検証済(新規公開時の1件表示のみ) |
| AC-POST-003 | プロジェクト別の記事一覧 (`/projects/[id]/posts`) | プロジェクト単位で記事を管理できる | 該当プロジェクトの記事だけが表示される | — | 未着手 |
| AC-POST-004 | 記事の公開 | 書いた記事が実際にブログに載る | `POST /api/posts/publish` 後、公開先URLで記事が閲覧できる | `features/publishing/publish-lifecycle.feature` › 公開した記事はWordPress上で本文・タイトル・アイキャッチが送信内容と一致する(issue #1171 / AT-6-1、親issue #932の親シナリオ2。WordPress側の実体をwp-cliで直接確認し、公開先URLへのHTTPアクセスまでは行わない) | 検証済(`@slow`) |
| AC-POST-005 | 記事の更新 | 公開後に直せる | 同じ記事を再公開すると、公開先の本文が更新され重複投稿されない | `features/publishing/publish-lifecycle.feature` › 同じ記事を更新して再公開すると、新規作成されずに既存記事が更新される(issue #1171 / AT-6-1、親issue #932の親シナリオ3) | 検証済(`@slow`) |
| AC-POST-006 | 予約公開 | 書いた日と公開日を分けられる | 予約した記事は指定時刻まで公開されず、時刻到来後に公開される | `features/publishing/publish-status.feature` › 予約投稿すると、指定日時が設定された状態でWordPressに登録される(issue #1172 / AT-6-2、親issue #932の親シナリオ6。本番サイトへ`publishScheduledAt`付きで投稿し、WordPress側がfutureステータスかつ指定日時(`post_date_gmt`)で登録されることをwp-cliで確認する)。**対象外**: 指定時刻到来後の自動公開そのもの(WordPress標準のcronに委ねる)は未検証 | 検証済(`@slow`。future登録までを検証) |
| AC-POST-007 | 記事の削除 | 誤って出した記事を取り下げられる | `DELETE /api/posts/{site}/{wpPostId}` 後、公開先で記事が閲覧できなくなる | `features/publishing/publish-status.feature` › 公開済み記事を取り下げると、WordPress側からも消え、投稿履歴の状態が更新される(issue #1172 / AT-6-2、親issue #932の親シナリオ4。取り下げ後のWordPress側のpost_statusがtrashになること、および`GET /api/posts/{site}/by-slug/{slug}`が返す投稿履歴の状態もtrashに更新されることをそれぞれ確認する) | 検証済(`@slow`) |
| AC-POST-008 | スラッグによる記事取得 | 公開済み記事を一意に特定できる | `GET /api/posts/{site}/by-slug/{slug}` が該当記事を返す | `features/publishing/publish-lifecycle.feature` › 同じ記事を更新して再公開すると、新規作成されずに既存記事が更新される(issue #1171 / AT-6-1。VSCode拡張と同じ経路で`GET /api/posts/{site}/by-slug/{slug}`から既存投稿のwpPostIdを解決してから再公開する) | 検証済(`@slow`) |
| AC-POST-009 | プレビュー(実サイトの署名付きURL) | 公開前に実際の見た目を確認できる | `POST /api/projects/{projectId}/preview/signed-url` が期限付きの署名付きURLを返し、開くと実テーマで表示される。投稿(`wp_posts`)は作られない。旧プレビュー経路(`/skeleton`・`/theme-css`・`/preview-post`)は #1564 で削除し、404になる | `features/publishing/preview-signed-url.feature` › URL を開くと実テーマでタイトルと本文が表示される / 発行と表示の前後で wp_posts の行数が変わらない(issue #1561)。削除した旧経路が存在しないことは `features/publishing/preview.feature`(issue #1564) | 検証済 |
| AC-POST-010 | プレビュー用テーマCSSの取得 | (廃止) | `GET /api/projects/{projectId}/preview/theme-css` は #1564 で削除した | `features/publishing/preview.feature` › 旧プレビュー経路の「GET」「theme-css」は存在せず、サイトに投稿も作られない | 対象外(プラグイン必須化に伴い旧プレビュー経路ごと削除。実サイトの署名付きURL(AC-POST-009)に置き換わった。#1564) |
| AC-POST-011 | プレビュー投稿の後始末 | (廃止) | プレビュー用の一時投稿は作らなくなったため後始末も無い。`DELETE /api/projects/{projectId}/preview/preview-post` は #1564 で削除した | `features/publishing/preview.feature` › 旧プレビュー経路の「DELETE」「preview-post」は存在せず、サイトに投稿も作られない | 対象外(一時投稿そのものを作らなくなったため。#1564) |
| AC-POST-012 | カテゴリ・タグの解決 | 記事に付けた分類が公開先で正しく紐付く | `POST /api/taxonomy/resolve` が既存分類に解決し、無ければ作成する | `features/publishing/taxonomy.feature` › 既にあるカテゴリ・タグは大文字小文字を無視した完全一致で解決される / 存在しないカテゴリ・タグを指定すると新規作成される | 検証済 |
| AC-POST-013 | プロジェクトダッシュボード (`/projects/[id]/dashboard`) | プロジェクトの状況を一目で掴める | 記事数・公開状況・Analytics サマリが表示される | — | 未着手 |
| AC-POST-014 | 複数サイトへの同時公開と一部失敗時の挙動 | 2サイトに出せる。片方が落ちても成功した側は出て、失敗が握り潰されない | 2サイトへ公開すると両方に記事が作成される。片方のサイトが到達不能なとき、成功した側は公開され、失敗した側はエラー応答として利用者に示される(全体が巻き戻ることも、失敗が握り潰されることもない) | `features/publishing/multi-site-publish.feature` › 2つのサイトを選んで公開すると、両方のサイトに記事が作成される / 片方のサイトが到達不能なとき、成功した側は公開され失敗した側は失敗として示される(issue #1173 / AT-6-3、親issue #932の親シナリオ8・9。`POST /api/posts/publish`はサイトごとの単発呼び出しであり、公開APIはサイトを跨いだトランザクションを持たない) | 検証済(`@slow`) |
| AC-POST-015 | 記事の著者マッピングと著者の自動プロビジョニング | 公開した本人がWordPress側の投稿者として正しく現れる | 公開者に対応するWordPress著者(`user_site_authors`のキャッシュ、無ければメール検索)がpost_authorに設定される。対応する著者が存在しない場合は`POST /api/projects/{id}/users`経由(内部的に`POST /api/internal/publishing/sites/{siteKey}/authors`、AC-INT-018)で自動作成される(issue #1176 / AT-6-6、親issue #932の親シナリオ14・15) | `features/publishing/authors.feature` › 記事の著者マッピングに従い、WordPress側の投稿者が期待通りになる / WordPress側に対応する著者が存在しない場合、自動プロビジョニングされる | 検証済 |

### 2.5 一括管理・環境間比較 — `BULK`

API: publishing `BulkManagementController`(23エンドポイント)

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-BULK-001 | カテゴリの環境間比較 | どの環境に何が無いか分かる | `GET /bulk-management/categories/comparison` が環境ごとの差分を返す | `apps/web/e2e/features/bulk/category-comparison.feature`(マスターにのみ存在=対象環境に不足 / 両環境に存在し内容が異なる=差異あり / 対象環境にのみ存在=余剰。#1177) | 検証済 |
| AC-BULK-002 | タグの環境間比較 | 同上(タグ) | `GET /bulk-management/tags/comparison` が差分を返す | `apps/web/e2e/features/bulk/resource-comparison.feature` › タグの比較が、不足・差異・余剰を示す(タグ: マスターにのみ存在=対象環境に不足 / 両環境で説明文が異なる=差異 / 対象環境にのみ存在=余剰。#1178) | 検証済(`@slow`) |
| AC-BULK-003 | 記事の環境間比較 | 同上(記事) | `GET /bulk-management/posts/comparison` が差分を返す | `apps/web/e2e/features/bulk/resource-comparison.feature` › 投稿の比較が、環境間の差分を示す(投稿: マスターにのみ存在=対象環境に存在しない / 両環境に存在し公開状態が異なる。#1178) | 検証済(`@slow`) |
| AC-BULK-004 | プラグインの環境間比較 | 環境間の構成差を把握できる | `GET /bulk-management/plugins/comparison` が差分を返す | `apps/web/e2e/features/bulk/resource-comparison.feature` › プラグインの比較が、環境間の構成差を示す(プラグイン: マスターにのみ有効=対象環境に未インストール / 有効状態が異なる。#1178) | 検証済(`@slow`) |
| AC-BULK-005 | テーマの環境間比較 | 同上(テーマ) | `GET /bulk-management/themes/comparison` が差分を返す | `apps/web/e2e/features/bulk/resource-comparison.feature` › テーマの比較が、環境間の構成差を示す(テーマ: マスターにのみ存在・対象環境にのみ存在=それぞれ他方が未インストール。#1178) | 検証済(`@slow`) |
| AC-BULK-006 | カテゴリの同期 | 環境を手作業で揃えなくてよい | `sync` / `sync-all` / `edit-sync` 後、比較の差分が解消する | — | 未着手 |
| AC-BULK-007 | タグの同期 | 同上(タグ) | 同上 | — | 未着手 |
| AC-BULK-008 | プラグイン/テーマの調整 | 環境の構成を揃えられる | `plugins/reconcile` `themes/reconcile` 後、比較の差分が解消する | — | 未着手(`@slow`) |
| AC-BULK-009 | 一括削除 | 検証環境を作り直せる | `categories/delete-all` `tags/delete-all` `posts/delete-all` `plugins/delete-all` `themes/delete-all` の実行後、対象が空になる | — | 未着手(`@destructive`) |
| AC-BULK-010 | 記事ステータスの一括変更 | 大量の記事をまとめて下書きに戻せる | `posts/status-update` 後、対象記事のステータスが変わる | `apps/web/e2e/features/bulk/status-update-and-authorization.feature` › 投稿のステータスが一括で変更される(公開の2件が両環境で下書きになり、対象外の投稿は公開のまま。WordPress の実状態を wp-cli で確認)/ › 一般ユーザーは一括削除系を実行できず、対象環境の状態も変わらない(カテゴリ・タグ・投稿・プラグイン・テーマの `delete-all` が403、管理者トークンの比較結果と wp-cli で状態不変を確認。`@api`。#1182) | 検証済(2026-10-01、`--no-deps` で2シナリオとも成功。#1182)(`@slow`) |
| AC-BULK-011 | 一括適用 | 変更を全環境へ一度に流せる | `bulk-management/apply` / `apply-all` が対象環境へ反映される | — | 未着手 |
| AC-BULK-012 | ファイルアップロード | 手元のファイルを公開先へ送れる | `bulk-management/upload` 後、公開先にファイルが存在する | — | 未着手 |
| AC-BULK-013 | 生成画像のアップロード | ギャラリーの画像を記事素材にできる | `POST /api/projects/{id}/asset-images/{generatedImageId}/upload` 後、公開先メディアに現れる | — | 未着手 |
| AC-BULK-014 | 一括削除の確認 | 取り消せない削除を、どの環境から消えるのかを知った上で承認できる | カテゴリ・タグ・プラグイン・テーマ・投稿の行削除の確認に、その項目が存在する環境の数と環境名が表示され、存在しない環境は含まれない(比較表の行データから求め、API・サーバの変更なし) | `apps/web/e2e/features/bulk/bulk-delete-confirmation.feature` › 5シナリオ(カテゴリ・タグ・プラグイン・投稿の2環境、およびテスト環境にだけあるカテゴリ)。単体: `apps/web/src/app/projects/[id]/__tests__/{Term,PluginTheme,Post}ComparisonTable.test.tsx`(#1181) | 実装済み(受け入れテストの実行結果は #1181 のレポート参照) |

### 2.6 AI執筆支援 — `AI`

API: ai `AiController`, `ProjectLlmModelController`, `ProjectBraveSearchApiKeyController`, `GenerationJobController`
画面: プロジェクト詳細のAI設定 / 拡張のコマンド群

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-AI-001 | 下書き生成 (`/api/ai/draft`) | 白紙から書き始めなくてよい | テーマを与えると記事の下書きが返る | `ai/generation.feature` › 下書き生成に見出しと要望を渡すと、スタブの決定的な下書きがそのまま返る(`@api`) | 実装済み(`@stub` `@api`) |
| AC-AI-002 | 質問 (`/api/ai/ask`) | 執筆中の疑問をその場で解ける | 質問に対する回答が返る | `ai/generation.feature` › セクション生成に追加の指示を続けると、直前の生成を踏まえた壁打ちの再生成を依頼できる(`@api`)。`/api/ai/ask`(AiAskRequest)は history を持たないため、対象を history/message を持つ `/api/ai/section` の壁打ち再生成に合わせて検証する(issue #1146。feature内のコメント参照) | 実装済み(`@stub` `@api`) |
| AC-AI-003 | タグ提案 (`/api/ai/tags`) | 分類を考える手間が減る | 本文からタグ候補が返る | `ai/tag-and-proofread.feature` › タグ提案が、本文に基づくカテゴリ候補とタグ候補として返る(`@api`) | 実装済み(`@stub` `@api`、issue #1004) |
| AC-AI-004 | 校正 (`/api/ai/proofread`) | 誤字や言い回しを直せる | 本文に対する指摘が返る | `ai/tag-and-proofread.feature` › 校正チェックが、本文中の該当箇所を指す指摘として返る(`@api`) | 実装済み(`@stub` `@api`、issue #1004) |
| AC-AI-005 | セクション生成 (`/api/ai/section`) | 見出し単位で書き足せる | 見出しを与えるとその節の本文が返る | `ai/generation.feature` › セクション生成が、指定した見出し配下の本文として返る(`@api`) | 実装済み(`@stub` `@api`) |
| AC-AI-006 | Web検索付き質問 | 最新情報を踏まえた回答が得られる | Brave Search APIキー設定時、検索結果を根拠にした回答が返る。未設定・呼び出し失敗時はフェイルオープンし、検索結果なしで回答が返る(エラーにしない) | `ai/web-search.feature` › プロジェクトのBrave Search APIキーが設定されていると、壁打ちの回答がWeb検索結果を踏まえて生成される(`@api`)。同 › Brave Search呼び出しが失敗すると、壁打ちの回答はWeb検索結果なしでフェイルオープンする(`@api`) | 実装済み(`@stub` `@api`) |
| AC-AI-007 | 画像プロンプト生成 | 記事に合う画像を頼みやすい | `POST /api/projects/{projectId}/ai/generate-image-prompt` が本文に沿ったプロンプトを返す | `ai/generation.feature` › 画像プロンプト生成が、記事内容に基づくプロンプト文字列を返す(`@api`) | 実装済み(`@stub` `@api`) |
| AC-AI-008 | LLMプロバイダの選択 | 用途に応じてAIを切り替えられる | `PUT /ai-models/llm/provider/selection` の選択が、画像プロンプト生成に加えて執筆支援5機能(`/api/ai/draft`・`ask`・`section`・`tags`・`proofread`。本文の任意`projectId`で指す)の以後の生成にも使われる。優先順位はリクエストの`provider`指定 → プロジェクトの選択 → システム既定。`projectId`未指定はシステム既定へフォールバックする(issue #1495) | `ai/model-selection.feature` › プロバイダーを切り替えると、以後の生成が切り替え先へ向かう(`@api`)。同 › プロジェクトで選択したプロバイダーが執筆支援の<機能>でも使われる / リクエストのプロバイダー指定はプロジェクトの選択より優先される(<機能>) / projectIdを伴わない呼び出しはシステム既定で動きプロジェクトの選択の影響を受けない(<機能>)(いずれも下書き・Ask AI・セクション生成・タグ提案・校正チェックの5例、`@api`)。LLMスタブが受け取ったリクエストのmodelまで検査する(issue #1148、#1495) | 実装済み(`@stub` `@api`) |
| AC-AI-009 | LLMモデルの選択 | 精度とコストを選べる | `PUT /ai-models/llm/models/selection` の選択が、画像プロンプト生成に加えて執筆支援5機能(`/api/ai/draft`・`ask`・`section`・`tags`・`proofread`。本文の任意`projectId`で指す)の以後の生成にも使われる。`projectId`未指定はシステム既定モデルへフォールバックする(issue #1495) | `ai/model-selection.feature` › 利用可能なLLMモデル一覧が取得でき、選択したモデルが以後の生成要求に反映される(`@api`)。同 › 選択したモデルが執筆支援の<機能>にも反映される(下書き・Ask AI・セクション生成・タグ提案・校正チェックの5例、`@api`)。LLMスタブが受け取ったリクエストのmodelまで検査する(issue #1148、#1495) | 実装済み(`@stub` `@api`) |
| AC-AI-010 | Brave Search APIキー管理 | 検索機能を自分の鍵で使える | `GET/PUT/DELETE /api-keys/brave-search-api-key` の結果が検索付き質問の可否に反映される。保存後のキーは平文で再表示されない | `ai/web-search.feature` › プロジェクトのBrave Search APIキーを保存・削除でき、保存後のキーは平文で再表示されない(`@api`) | 実装済み(`@stub` `@api`) |
| AC-AI-011 | 生成ジョブの照会 | 長い生成の進捗を追える | `GET /api/generation-jobs` と `/{id}` が状態(実行中/完了/失敗)を返す。AI執筆支援(draft/ask/section)は同期呼び出しで非同期ジョブ経路を持たないため、実ジョブを作る唯一の到達可能な経路(メディアガベージコレクション削除)を題材に検証する(issue #1151のDecision Record) | `ai/generation-job.feature` › 起動直後の生成ジョブは、ID照会でも一覧でも実行中として現れる / 削除できるメディアが1件も無いジョブは、最終的に失敗として理由付きで返る(`@api`)。完了状態は`media/media-garbage-collection.feature`で既に確認済み | 実装済み(`@api`、issue #1151) |
| AC-AI-012 | LLMのレート制限 | 429でも生の例外ログではなく次の行動が分かる | LLMが429を返したとき、`AiServiceException`経由でHTTP 502・`error`にレート制限と分かる文言が返る | `ai/resilience.feature` › LLMが429を返したとき、利用者にレート制限と分かるメッセージが出る(`@api`) | 実装済み(`@stub` `@api`、issue #1149) |
| AC-AI-013 | LLMのタイムアウト/接続断 | 生成が固まらず再試行できる | LLMがタイムアウト/接続断したとき、速やかに失敗が返り、直後の通常リクエストは成功する | `ai/resilience.feature` › LLMがタイムアウトしたとき、UIが固まらず速やかに失敗が返り再試行できる(`@api`) | 実装済み(`@stub` `@api`、issue #1149) |
| AC-AI-014 | LLM接続設定の不備 | 生成前に設定不備と分かる | プロバイダのAPIキーが未設定/不正なとき、LLM呼び出し前に設定不備の文言で失敗する | `ai/resilience.feature` › LLM接続設定が未設定/不正なとき、生成前に設定不備と分かるメッセージが出る(`@api`) | 実装済み(`@stub` `@api`、issue #1149) |
| AC-AI-015 | LLM設定(`/ai-models/llm/**`)の非メンバー拒否 | 他人のプロジェクトのLLM設定を書き換えられない | 一般利用者は、対象プロジェクトのメンバーか否かに関わらず`requireAdmin()`により403で拒否される(現状の実装。是正は本Issueの対象外) | `ai/authorization.feature` › 一般利用者はプロジェクトのメンバーでなくてもLLM設定を読み書きできない(`@api`) | 実装済み(`@api`、issue #1150) |
| AC-AI-016 | Brave Search APIキーの非メンバー拒否 | 他人のプロジェクトのBrave Search APIキーを読み書きできない | `requireProjectMemberOrAdmin(projectId)`により、メンバーでないプロジェクトへのGET/PUT/DELETEが403で拒否される。自分のプロジェクトでは読み書きできる | `ai/authorization.feature` › 自分がメンバーでないプロジェクトのBrave Search APIキーは読み書きできない(`@api`) | 実装済み(`@api`、issue #1150) |
| AC-AI-017 | レビューステップ単位の指摘生成(`/api/projects/{projectId}/ai/review-steps/{stepKey}/suggestions`、`JAPANESE`/`PROOFREADING`) | 多段レビュー(#1210)のステップごとに、そのステップの観点だけの指摘が得られる | ステップキー・本文・projectIdを渡すと、そのステップキーと本文中に実在する該当箇所を持つ指摘一覧が返る。同じ本文・同じステップへの呼び出しは指摘の識別子(ステップキー+引用+指摘内容から導出、本文中の位置を含まない)が一致し、指摘箇所より前方への加筆があっても識別子は変わらない | `ai/review-step-suggestions.feature` › 「JAPANESE」/「PROOFREADING」ステップの指摘が、そのステップキーと本文中に実在する該当箇所を持って返る / 同じ本文・同じステップへの2回の呼び出しは指摘の識別子が一致する / 指摘箇所より前方に文字を挿入しても、同じ指摘の識別子は変わらない(`@api`) | 実装済み(`@stub` `@api`、issue #1213) |
| AC-AI-018 | レビューステップ別のAIプロバイダー/モデル設定(プロジェクト詳細画面「AIモデル管理」カード・LLMタブの`ReviewStepSettingsPanel.tsx`、`/ai-models/llm/review-steps`、issue #1211のAPI) | 多段レビュー(#1210)のステップごとに使うプロバイダー/モデルを画面から設定できる | LLMタブに5つのレビューステップの行が#1211のAPIが返す順序(日本語チェック→校正チェック→校閲→読者視点でのチェック→文体チェック)で表示され、未設定のステップは「(プロジェクト既定を使用)」と表示される。provider/modelを選んで保存すると、表示が保存済みの値へ更新され、再読込後も残る。**対象外**(#1223へ切り出し): 設定を空へ戻す操作、保存失敗時のエラー表示 | `ai/review-step-model-settings.feature` › LLMタブに5つのレビューステップの行が#1211のAPIの順序・表示名で表示され、未設定は既定表示になる / ステップのproviderとmodelを選んで保存すると、リロード後もその値が表示される | 実装済み(issue #1212)。ステップ定義の解決(bddgen)は確認済みだが、実行環境にPlaywrightブラウザの起動に必要な共有ライブラリが無く(#1194)、実際のブラウザ実行による確認はできていない |
| AC-AI-019 | 校閲ステップ(`FACT_CHECK`、Web検索を伴う事実確認。`/api/projects/{projectId}/ai/review-steps/FACT_CHECK/suggestions`) | 記事中の事実主張がWeb検索で裏取りされ、裏付けが取れない主張が出典つきで指摘される。Brave Searchが使えなくても他のステップ・ワークフローは失敗しない | `FACT_CHECK`・本文・projectIdを渡すと、ステップキーと本文中に実在する該当箇所を持つ指摘が返り、各指摘は判断の根拠にした出典(タイトルとURL)を含む。Brave Searchを利用できない(APIキー未設定・キーが拒否された・検索呼び出しが失敗した・検索結果が得られない)ときはHTTPエラーにせず、応答の`skipped=true`と`skipReason`で校閲を実行しなかったことと理由を返し、指摘0件(`skipped=false`)と区別できる。`skipped`/`skipReason`/`sources`は校閲だけが返し、他のステップ(AC-AI-017)の応答にはキーが現れない。方式は2回のLLM呼び出し(事実主張の抽出 → 主張ごとのWeb検索(最大3件) → 検索結果を踏まえた判定) | `ai/review-step-fact-check.feature` › 「FACT_CHECK」ステップの指摘が、ステップキーと本文中に実在する該当箇所を持って返る / 校閲の指摘は、判断の根拠となった出典のタイトルとURLを含む / プロジェクトのBrave Search APIキーがBrave Searchに拒否されると、校閲はエラーにならずスキップされる / Web検索が失敗すると、校閲はスキップされ、問題なしとは区別できる形で返る / 校閲以外のステップの応答には、スキップ情報も出典も含まれない(`@api`)。**APIキー未設定**のスキップは、受け入れテスト環境がシステム全体のキーを持ちフォールバックで検索に成功するため、Gherkinでは再現せず`AiAssistServiceTest`の`factCheck_APIキー未設定でWeb検索が使えないとき…`で検証 | 実装済み(`@stub` `@api`、issue #1214) |
| AC-AI-020 | 読者視点でのチェック・文体チェックのステップ(`READER_PERSPECTIVE` / `STYLE`。`/api/projects/{projectId}/ai/review-steps/{stepKey}/suggestions`) | 多段レビュー(#1210)の残る2ステップも、AC-AI-017と同じ契約で指摘が得られる | `READER_PERSPECTIVE`(想定読者にとっての前提知識の飛躍・説明不足)/`STYLE`(文末表現の統一・一文の長さ・受動態の多用・トーンの一貫性)・本文・projectIdを渡すと、そのステップキーと本文中に実在する該当箇所を持つ指摘一覧が返る。同じ本文への2回の呼び出しで各指摘の識別子が一致する。応答の形・識別子の導出規則・`originalText`の実在チェックはAC-AI-017と同一で、`JAPANESE`/`PROOFREADING`の応答は変わらない | `ai/review-step-reader-style.feature` › 「READER_PERSPECTIVE」/「STYLE」ステップの指摘が、そのステップキーと本文中に実在する該当箇所を持って返る / 各ステップは同じ本文への2回の呼び出しで指摘の識別子が一致する(`@api`)。既存2ステップの不変は`ai/review-step-suggestions.feature`がそのまま通ることで確認 | 実装済み(`@stub` `@api`、issue #1221) |

### 2.7 記事プランとGitHub Issue連携 — `PLAN`

画面: `/projects/[id]/plan`
API: ai `ArticlePlanController`(15エンドポイント)
シナリオ: `apps/web/e2e/features/article-plan/`(#935 / AT-9。全15シナリオ、`@stub` `@plan`)

**GitHub は実サービスを叩かない。** `infra/e2e-stubs/github` のスタブへ向ける
(#928 / AT-2。`docker-compose.e2e-stubs.yml` が ai-service の `GITHUB_API_BASE_URL` を
差し替える)。検証専用リポジトリを用意しない判断は 2026-09-01 に #935 で確定した。
実 GitHub へ向けると、テストのたびに本リポジトリの Issue が作られ担当者が書き換わるためである。

**3行は `@api` で検証する。** AC-PLAN-005 / 012 と AC-PLAN-011 の導線は Web 管理画面に無く、
VSCode拡張(`AC-EXT-*`)と拡張向けAPIが使う。UI から到達できない基準を UI シナリオに
仕立てても、確かめているのはテスト側の作り物になる。

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-PLAN-001 | プラン対話 (`/article-plan/chat`) | 何を書くかをAIと詰められる | 対話が継続し、セッションとして保存される | `article-plan/planning-session.feature` › 企画チャットを開始すると新規セッションが作られ壁打ち一覧に現れる / 同じセッションで発言を続けると直前までの文脈が保持される / LLMが失敗してもセッションは壊れず再試行できる | 検証済(`@stub`。#1282で2026-09-15に再実測、`--project=at-main --no-deps --workers=1`を2回連続実行し毎回通過を確認。#1140が2026-09-07に検出した失敗はスタブコンテナ未起動という環境前提の問題で、シナリオ・製品側の欠陥ではなかった) |
| AC-PLAN-002 | セッション一覧・復元 | 中断した検討を再開できる | `GET /sessions` と `/sessions/{id}` で過去の対話を復元できる | `article-plan/planning-session.feature` › 保存済みセッションを開き直すと過去のやり取りが復元される | 検証済(#1282で2026-09-15に再実測、2回連続通過を確認。#1140の失敗は環境前提(スタブ未起動)によるもの) |
| AC-PLAN-003 | タイトル提案 | 見出しを考える手間が減る | `POST /suggest-titles` が複数の候補を返す | `article-plan/suggestions.feature` › タイトル案が複数返り、選んだものをIssueとして受理できる | 検証済(`@stub`) |
| AC-PLAN-004 | 構成提案 | 記事の骨組みを得られる | `POST /suggest-structure` が見出し構成を返す | `article-plan/suggestions.feature` › 構成案が見出し階層として返る | 検証済(`@stub`) |
| AC-PLAN-005 | メタデータ提案 | 分類とdescriptionを埋められる | `POST /suggest-metadata` がカテゴリ・タグ・説明文を返す | `article-plan/suggestions.feature` › メタデータ提案のカテゴリは公開先の既存カテゴリだけになる(`@api`) | 検証済(`@stub` `@api`。Web に導線が無い) |
| AC-PLAN-006 | プランの確定 | 決めた内容を記事へ引き継げる | `POST /accept` 後、その内容で執筆を開始できる | `article-plan/suggestions.feature` › タイトル案が複数返り、選んだものをIssueとして受理できる | 検証済(部分。Issue化までを見る。執筆への引き継ぎは AT-6 / #932) |
| AC-PLAN-007 | GitHub Issue一覧の取得 | 書くネタをIssueで管理できる | `GET /issues` が紐付けたリポジトリのIssueを返す | `article-plan/github-issues.feature` › GitHubトークンが設定されたプロジェクトでIssue一覧を取得できる / GitHubトークン未設定のプロジェクトでは設定不備と分かるエラーになる / 無効なGitHubトークンでは認証に失敗したと分かるエラーになる | 検証済(`@stub`。#1282で2026-09-15に再実測、2回連続通過を確認。#1140の失敗は環境前提(スタブ未起動)によるもの) |
| AC-PLAN-008 | Issue本文の取得 | Issueの内容からプランを起こせる | `GET /issues/{issueNumber}/description` が本文を返す | `article-plan/github-issues.feature` › Issue本文を企画セッションの入力にできる | 検証済(`@stub`。#1282で2026-09-15に再実測、2回連続通過を確認。#1140の失敗は環境前提(スタブ未起動)によるもの) |
| AC-PLAN-009 | Issueに紐づくセッション | Issueと検討履歴が対応する | `GET /sessions/by-issue/{issueNumber}` が該当セッションを返す | `article-plan/github-issues.feature` › Issue番号から壁打ちセッションを引き当てられる | 検証済(`@stub`。#1282で2026-09-15に再実測、2回連続通過を確認。#1140の失敗は環境前提(スタブ未起動)によるもの) |
| AC-PLAN-010 | Issueへの構成反映 | 決めた構成をIssueに残せる | `POST /issues/{issueNumber}/accept-structure` 後、Issueに構成が書かれる | `article-plan/github-issues.feature` › 構成案をIssueへ反映するとGitHub側のIssueが更新される | 検証済(`@stub`。#1282で2026-09-15に再実測、2回連続通過を確認。#1140の失敗は環境前提(スタブ未起動)によるもの) |
| AC-PLAN-011 | Issueの担当割当 | 誰が書くか決められる | `POST /issues/{issueNumber}/assign` 後、Issueの担当者が変わる | `article-plan/github-issues.feature` › Issueを担当者へ割り当てられる(`@api`) | 検証済(`@stub` `@api`。Web に導線が無い。#1282で2026-09-15に再実測、2回連続通過を確認。#1140の失敗は環境前提(スタブ未起動)によるもの) |
| AC-PLAN-012 | 既存カテゴリ・タグの参照 | 公開先にある分類を再利用できる | `GET /categories` `/categories/hierarchy` `/tags` が公開先の既存分類を返す | `article-plan/suggestions.feature` › 既存カテゴリを親子構造として、既存タグを一覧として取得できる(`@api`) | 検証済(`@api`。Web に導線が無い) |

### 2.8 画像生成・ギャラリー・メディア — `IMG`

画面: `/image-gallery`
API: media `ImageGenerationController`, `GeneratedImageController`, `MediaController`, `ProjectImageModelController`, `ProjectImageSettingsController`, `ProjectMediaGarbageCollectionController`, `ComfyUiCheckpointController`

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-IMG-001 | 画像生成 (`POST /api/ai/image`) | 記事の挿絵を自分で用意しなくてよい | プロンプトから画像が生成され、ギャラリーに現れる | `features/media/image-generation.feature` › プロンプトを指定してComfyUIで画像を生成すると、生成画像の一覧に現れる / 生成に使ったプロンプト・サイズ・チェックポイントが生成画像の詳細に残る、`features/media/image-generation-chatgpt.feature` › 画像生成に失敗したときは理由が示され、壊れた画像レコードは残らない | 検証済(`@slow` は GPU 必須。`@stub` 経路は GPU 非搭載でも通る) |
| AC-IMG-002 | 生成オプションの取得 | 選べる設定が画面に出る | `GET /api/ai/image-options` の内容が生成フォームの選択肢と一致する | `features/media/image-settings.feature` › 画像生成のデフォルトプロンプトを保存すると、次の生成の既定値になる(既定値の部分のみ) | 部分的に検証 |
| AC-IMG-003 | ギャラリー一覧 (`/image-gallery`) | 生成済み画像を探せる | 生成済み画像が一覧に表示される | `features/media/image-gallery.feature` › 保存済みの生成画像がギャラリーに一覧表示される | 検証済(#1284で実測、2026-09-15) |
| AC-IMG-004 | 画像詳細 | どのプロンプトで作ったか分かる | 詳細モーダルに生成パラメータが表示される | `features/media/image-gallery.feature` › 詳細モーダルに生成パラメータが表示される、`features/media/generated-image-seed.feature` › ComfyUIで生成した画像の詳細には、生成に使われたseedとバッチ内位置が表示される / ChatGPTで生成した画像の詳細にはseedが表示されず、再現できないと分かる | 検証済(#1284で実測、2026-09-15。サムネイルのクリックがハイドレーション完了前に取りこぼされる欠陥(#1283と同種)を修正) |
| AC-IMG-005 | 画像削除 | 不要な画像を消せる | `DELETE /api/generated-images/{id}` 後、一覧から消える | `features/media/image-gallery.feature` › 画像を削除するとギャラリーから消え、ファイル実体も取得できなくなる | 検証済(#1284で実測、2026-09-15) |
| AC-IMG-006 | 画像タグ編集 | 画像を分類して探しやすくできる | `PUT /api/generated-images/{id}/tags` の結果が一覧の絞り込みに反映される | `features/media/image-gallery.feature` › 画像にタグを付けて保存でき、そのタグで絞り込める | 検証済(#1284で実測、2026-09-15) |
| AC-IMG-007 | 画像ファイルの取得 | 生成画像を記事に貼れる | `GET /api/generated-images/{id}/file` が画像バイト列を返す | `features/media/image-gallery.feature` › 保存済みの生成画像がギャラリーに一覧表示される(一覧の `img` が同エンドポイントを参照する)/ 画像を削除するとギャラリーから消え、ファイル実体も取得できなくなる | 検証済(#1284で実測、2026-09-15) |
| AC-IMG-008 | 画像アップロード | 手元の画像も使える | `POST /api/media/upload` した画像がギャラリーに現れる | —(`/image-gallery` にファイルアップロードのUIは無い。#645 で確認済み。移行元 spec が持っていた「未実装であることの確認」は受け入れ基準ではないので #936 では移していない) | 未着手 |
| AC-IMG-009 | 画像プロバイダの選択 | ComfyUI と外部APIを切り替えられる | `PUT /ai-models/image/provider/selection` の選択が以後の生成に使われる | `features/media/image-generation-chatgpt.feature` › 画像生成AIをChatGPTに切り替えると、ChatGPTの経路で生成される | 検証済(`@stub`。#1285で実測、2026-09-15) |
| AC-IMG-010 | ComfyUI チェックポイント管理 | 使うモデルを選べる | チェックポイントの一覧・選択・導入・削除の結果が生成に反映される | `features/media/comfyui-checkpoints.feature` › 利用可能なチェックポイントの一覧が取得でき、選んだものが保存される / チェックポイントを導入すると、導入後の一覧に現れる / 選択中のチェックポイントは削除できず、選択していないものは削除できる | 検証済(`@slow`。GPU 必須) |
| AC-IMG-011 | 画像生成の既定設定 | 毎回同じ設定を入れ直さなくてよい | プロンプト既定値・サイズ既定値・リサイズ既定値の保存内容が生成フォームに反映される(#913 の再発検知) | `features/media/image-settings.feature` › 画像生成のデフォルトプロンプトを保存すると、次の生成の既定値になる / デフォルトサイズと記事内画像のリサイズ幅は、保存後にページを開き直しても表示される | 検証済(#1285で実測、2026-09-15。2件目のシナリオでタブ切替がハイドレーション完了前クリックに弱い欠陥(#1283と同種)と、数値キャプチャを文字列APIへそのまま渡していた欠陥を修正) |
| AC-IMG-012 | 画像コンテンツフィルタ設定 | 不適切な生成を抑止できる | `PUT /image-content-filter-settings` の設定が生成結果に反映される | `features/media/image-settings.feature` › コンテンツフィルタに抵触するプロンプトは、生成を始めずに拒否される | 検証済(#1285で実測、2026-09-15) |
| AC-IMG-013 | メディアのガベージコレクション | 使われていない画像で容量を食わない | `GET /media-garbage-collection/scan` が未使用を列挙し、`POST /delete` で削除される | `features/media/media-garbage-collection.feature` › どの記事からも参照されていないメディアをスキャンで検出できる / ガベージコレクションを実行すると、選んだ未参照の画像だけが削除される / 記事から参照されている画像は、ガベージコレクションを実行しても残っている | 検証済(`@destructive`) |
| AC-IMG-014 | ギャラリーの複数選択と一括削除 (`POST /api/generated-images/bulk-delete`) | 同一プロンプトの試行を大量に生成した後片付けを1回の操作で済ませられる | チェックで複数選択し件数を確認して一括削除でき、選んだ画像だけが消える(ファイル実体も取得不能)。キャンセルすれば1枚も消えず、権限の無い画像を含む要求は403で拒否され1枚も削除されない | `features/media/image-gallery.feature` › 複数の画像にチェックを入れると選択件数が表示され、全選択と全選択解除で表示中の全画像を切り替えられる / 一括削除の確認に選択件数が表示され、キャンセルすると画像は1枚も削除されない / 一括削除を承諾すると選択した画像だけが消え、選択しなかった画像は残りファイル実体も取得できなくなる / 権限の無い画像のidを含む一括削除は403で拒否され、その要求に含まれた画像は1枚も削除されない | 実装中(#1492。受け入れシナリオは未実測。単体テストは通過) |
| AC-IMG-015 | ギャラリーの入れ子フォルダ (`POST /api/generated-images/folders`、`PUT /api/generated-images/folders/{id}/parent`、`PUT /api/generated-images/{id}/folder`、`GET /api/generated-images?folderId=…&unfiled=…`) | 増え続ける生成画像を目的別に片付け、フォルダごとに探せる | 管理者が既存のフォルダを親にしてフォルダを作成でき、ギャラリーのツリーに階層のまま現れる。自分自身・自分の子孫を親に指定する要求は409で拒否され循環は生じない。画像をフォルダへ入れる/未分類へ戻すことができ、開き直しても所在が保たれる。フォルダを選ぶとそのフォルダと子孫の画像だけ、「未分類」を選ぶとどのフォルダにも属さない画像だけが表示される(タグ絞り込み・ページングと併用可)。admin でない利用者の作成・親の変更・所属変更は403で拒否され状態は変わらず、フォルダ一覧の取得は成功する(閲覧は全員・変更は admin のみ)。フォルダの改名・削除は #1494 | `features/media/image-gallery.feature` › 管理者が既存のフォルダを親にしてフォルダを作成すると、ギャラリーのツリーに階層のまま現れる / 自分自身や自分の子孫を親に指定する要求は拒否され、階層に循環は生じない / 画像をフォルダへ入れて未分類へ戻せ、ギャラリーを開き直しても所在が保たれる / フォルダを選ぶとそのフォルダと子孫フォルダの画像だけが表示され、未分類を選ぶとどのフォルダにも属さない画像だけが表示される / 一般利用者はフォルダの作成・親の変更・画像の所属変更が403で拒否され状態は変わらないが、フォルダ一覧は取得できる | 実装中(#1493。受け入れシナリオは未実測。単体・統合テストは通過) |

### 2.9 ダイアグラムとレンダリング — `DIAG`

API: media `DiagramController`, `RenderController` / 拡張のダイアグラムコマンド

受け入れシナリオは `apps/web/e2e/features/diagram/` の5ファイル(#937 / AT-11)。
**全て `@api` である。** Web のダイアグラムギャラリーは #662 で削除済みで、図のUIは
VSCode 拡張だけにあるため、この節の受け入れ基準に対応する画面がそもそも存在しない
(docs/ACCEPTANCE_TESTING.md §4 の例外を明示的に使っている)。
`/api/render/**` は gateway のルート表に載っていない(#830)ので、lbs-net 上の踏み台
コンテナから media-service を直接呼ぶ(`e2e/support/services.ts` の
`postJsonToServiceDirectly`)。

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-DIAG-001 | ダイアグラムの作成・編集・削除 | 図を記事に添えられる | `POST/PUT/DELETE /api/diagrams` の結果が一覧に反映される | `features/diagram/diagram-storage.feature` › drawio で描いた図を保存すると、あとから同じ内容を取り出して編集を続けられる / ダイアグラムを作成すると一覧に現れ、SVGを取得できる / ダイアグラムを削除すると一覧から消え、SVGは404になる、`features/diagram/diagram-authorization.feature` › 未認証では図の作成・取得・削除ができない / 他プロジェクトのダイアグラムは取得も削除もできない | 検証済 |
| AC-DIAG-002 | ダイアグラム一覧・詳細 | 作った図を再利用できる | `GET /api/diagrams` と `/{id}` が保存内容を返す | `features/diagram/diagram-storage.feature` › drawio で描いた図を保存すると、あとから同じ内容を取り出して編集を続けられる / ダイアグラムを作成すると一覧に現れ、SVGを取得できる | 検証済 |
| AC-DIAG-003 | SVG取得 | 図が記事に埋め込める | `GET /api/diagrams/{id}/svg` が描画済みSVGを返す | `features/diagram/diagram-storage.feature` › ダイアグラムを作成すると一覧に現れ、SVGを取得できる / ソースを更新すると、取り直したSVGは更新後の内容になる(キャッシュが残らないこと)/ ダイアグラムを削除すると一覧から消え、SVGは404になる | 検証済 |
| AC-DIAG-004 | PlantUML レンダリング | テキストからUMLを描ける | `POST /api/render/plantuml` が図を返し、不正な記法はエラーになる | `features/diagram/plantuml-rendering.feature` › 妥当なPlantUMLソースを渡すと、ノード名を含む図がPNGとして返る / 不正な構文では、理由の分かるエラーが返り、生の500にはならない / 巨大な入力にはサイズ上限のエラーが返り、待たされ続けない | 検証済(返る画像は SVG ではなく PNG。中身は埋め込みメタデータと寸法で検証している) |
| AC-DIAG-005 | recharts レンダリング | データからグラフを描ける | `POST /api/render/recharts` が図を返す | `features/diagram/recharts-rendering.feature` › チャート定義を渡すと、元データが反映されたSVGが返る / 系列の指定を欠いた定義ではエラーが返る | 検証済 |
| AC-DIAG-006 | Penpot デザインファイル連携 | デザインを記事素材にできる | `POST /api/render/penpot/design-file` がデザインを取り込む | `features/diagram/penpot-unavailable.feature` › Penpot が起動していないとき、デザインファイルの要求は理由の分かるエラーになる | 部分的に検証(`@destructive`。Penpot は任意サービスなので **未起動時の振る舞い**を受け入れ基準にしている。成功経路は共有 Penpot に消せないファイルを残すため叩かない) |
| AC-DIAG-007 | draw.io 編集 | 図をGUIで描ける | 拡張の `addNewDiagram` / `editDiagram` で draw.io が開き、保存内容が `GET /api/diagrams/{id}` に反映される | `features/diagram/diagram-storage.feature` › drawio で描いた図を保存すると、あとから同じ内容を取り出して編集を続けられる(サービス側の契約)、`ext:diagrams/diagrams.feature` › ダイアグラムを作成し編集して一覧から参照できる(拡張のコマンド)、手動: チェックリスト §7 | 検証済(draw.io のエディタ操作は手動) |

### 2.10 カスタムタグ・テンプレート・コンテンツ設定 — `TAG`

画面: `/custom-tag-templates`, `/projects/[id]/tags`, `/admin/tag-design`
API: content `CustomTagController`, `CustomTagTemplateController`, `ProjectCustomTagController`, `ProjectContentSettingsController`, `ContentCacheController` / project `TagDesignSettingController`, `GlobalTagDesignSettingController`

受け入れシナリオは `apps/web/e2e/features/custom-tag/` の6ファイル(#938 / AT-12)。
状態欄について:

- **`実装中` の行はシナリオが書かれているが、この開発ホストでは実行できていない。**
  Playwright のブラウザが OS の共有ライブラリを欠いていて起動しないため(#1045)。
  ブラウザを起動できる環境で `npm run test:at` を通した時点で `検証済` へ変える。

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-TAG-001 | カスタムタグ生成 | 記事に使う装飾を自分で作れる | プロンプトからタグが生成され自動保存される | `e2e/features/custom-tag/generation.feature` › プロンプトからタグを生成すると、検証を通過した内容が自動保存される || 検証済(#1283で実測、2026-09-15)、`@stub` |
| AC-TAG-002 | タグ名のバリデーション | 使えない名前で作らずに済む | パターンに一致しないタグ名では生成が開始されない | `e2e/features/custom-tag/generation.feature` › 命名規則に反するタグ名では生成が開始されない || 検証済(#1283で実測、2026-09-15) |
| AC-TAG-003 | 生成物のセキュリティ検証 | 危険なHTMLが公開先へ入らない | script タグ・イベントハンドラ・`javascript:`・CSS `behavior` を含む生成は拒否され、生成後の検証結果(成功/警告/エラー)が画面に示される | `e2e/features/custom-tag/generation.feature` › 危険なHTMLを含むカスタムタグは検証で拒否される / CSSのインジェクションを含むカスタムタグは検証で拒否される / プロンプトからタグを生成すると、検証を通過した内容が自動保存される(検証結果の表示) || 検証済(`@api` の2シナリオ、画面のシナリオも#1283で実測、2026-09-15) |
| AC-TAG-004 | 生成失敗時のエラー表示 | 失敗に気付ける | 生成に失敗するとエラーメッセージが表示される | `e2e/features/custom-tag/generation.feature` › 生成に失敗したときエラーが表示される || 検証済(#1283で実測、2026-09-15)、`@stub` |
| AC-TAG-005 | タグの一覧・編集・削除 | 作ったタグを保守できる | `GET/PUT/DELETE /api/custom-tags` の結果が一覧に反映される。使用中のタグも削除でき、記事側はショートコードが未展開のまま残る | `e2e/features/custom-tag/management.feature` › 既存タグの内容を編集して保存でき、再読込後も反映されている / タグを削除すると、一覧から消える / 記事で使用中のタグを削除しても削除は成功し、記事にはショートコードが残る || 検証済(`@api` の1シナリオ、画面のシナリオも#1283で実測、2026-09-15) |
| AC-TAG-006 | タグ検証API | 貼る前に安全か確かめられる | `POST /api/custom-tags/validate` が `isValid` と理由を返す | `e2e/features/custom-tag/generation.feature` › 危険なHTMLを含むカスタムタグは検証で拒否される、`e2e/features/custom-tag/performance.feature` › カスタムタグの検証APIが所定の時間内に応答する | 検証済 |
| AC-TAG-007 | CSSバンドルの取得 | 公開先でタグの見た目が再現される | `GET /api/projects/{id}/custom-tags/css-bundle` がタグ定義に対応するCSSを返し、画面から取得できる | `e2e/features/custom-tag/preview-and-css.feature` › プロジェクトの統合CSSに、そのプロジェクトのタグのCSSが含まれる || 検証済(#1283で実測、2026-09-15) |
| AC-TAG-008 | テンプレートギャラリー (`/custom-tag-templates`) | 他人の作ったタグを再利用できる | ギャラリーの詳細パネルで「プロジェクトで使う」を押すと、対象プロジェクトの `custom_tags` 一覧に、テンプレートのHTML/CSSを持つタグが現れる(`POST /api/custom-tag-templates/{id}/apply`。「複製を作成」= `clone` はテンプレート間の複製で、`custom_tags` は作らない — #1131) | `e2e/features/custom-tag/templates.feature` › テンプレートをプロジェクトで使うと、そのプロジェクトのカスタムタグ一覧にタグが現れる / テンプレートを複製すると、同じプロジェクトに独立したテンプレートの複製が作られる || 実装中(画面のシナリオ。#1045 によりこの開発ホストではブラウザを起動できず未実行。バックエンドは `CustomTagTemplateApplyIntegrationTest` で検証済) |
| AC-TAG-018 | テンプレートの作成 | 画面からテンプレートを作れる | ギャラリーの「新しいテンプレート」で名前・HTML(必須)・説明・カテゴリー・スコープ・CSS を入力して作成すると、未公開のテンプレートとして一覧(「未公開を含める」)に現れ、詳細パネルに入力した内容が表示される(`createCustomTagTemplateAction`) | `e2e/features/custom-tag/templates.feature` › 「新しいテンプレート」から作成すると、未公開のテンプレートとして一覧に現れ、入力した内容が詳細に表示される | 実装中(#1550) |
| AC-TAG-019 | テンプレートの編集 | 作ったテンプレートを直せる | 詳細パネルでテンプレート名・説明・カテゴリー・HTML・CSS を変えて「保存」すると、再読み込み後も変更後の内容が表示される。スコープは変更できない(`updateCustomTagTemplateAction`) | `e2e/features/custom-tag/templates.feature` › 詳細パネルで名前とHTMLを変えて保存すると、再読み込み後も変更後の内容が表示される | 実装中(#1550) |
| AC-TAG-020 | テンプレートの削除 | 不要なテンプレートを消せる | 詳細パネルの「削除」で確認ダイアログを承諾すると一覧から消え、キャンセルすると残る(`deleteCustomTagTemplateAction`) | `e2e/features/custom-tag/templates.feature` › 詳細パネルで削除の確認を承諾すると、テンプレートが一覧から消える / 詳細パネルで削除の確認をキャンセルすると、テンプレートは一覧に残る | 実装中(#1550) |
| AC-TAG-021 | 作成・保存の入力検証とエラー表示 | 失敗に気付け、入力を失わない | 名前かHTMLが空のときは作成・保存の要求を送らない。Server Action がエラーを返したときは理由を画面に表示し、入力した内容を残す | `e2e/features/custom-tag/templates.feature` › 名前かHTMLが空のままでは作成の要求が送られず、入力した内容も残る / 保存がエラーになると、その理由が画面に表示され、入力した内容は残る | 実装中(#1550) |
| AC-TAG-016 | プロジェクトで使ったタグ | テンプレート由来のタグを記事で使える | `[tagname]` を本文に書いて描画すると、テンプレートのHTMLで展開される | `e2e/features/custom-tag/templates.feature` › プロジェクトで使ったタグは、記事で [タグ名] として使え、テンプレートのHTMLで描画される | 実装中(#1045 によりブラウザ未実行。同じ挙動を `CustomTagTemplateApplyIntegrationTest` が検証) |
| AC-TAG-017 | テンプレート適用の名前衝突 | 既存のタグを黙って上書きしない | 対象プロジェクトに同名のタグがあると 409 と「既に登録されています」の理由で拒否され、既存のタグはそのまま残る | `e2e/features/custom-tag/templates.feature` › 同じ名前のタグが既にあるプロジェクトには、テンプレートを適用できない(`@api`) | 実装中(`CustomTagTemplateApplyIntegrationTest` / `CustomTagTemplateServiceTest` で検証済) |
| AC-TAG-009 | テンプレートの公開・非公開 | 共有範囲を選べる | `publish` / `unpublish` の結果が他利用者から見える一覧に反映される | `e2e/features/custom-tag/templates.feature` › タグをテンプレートとして公開すると、他の利用者からも見えるようになる / 公開をやめると、他の利用者から見えなくなる | 検証済(#1128で実測、2026-09-26。ギャラリーの詳細パネルで切り替え、結果は一般利用者の一覧APIで確認) |
| AC-TAG-010 | テンプレート削除の認可 | 他人のテンプレートを消されない | 非adminユーザーはテンプレートを削除できない | `e2e/features/custom-tag/templates.feature` › 非adminは他人のテンプレートを削除できない | 検証済 |
| AC-TAG-011 | 自分のテンプレート一覧 | 自作を管理できる | ギャラリーの「自分が作ったものだけ」で `GET /api/custom-tag-templates/my-templates` の作成分だけが一覧に出る | `e2e/features/custom-tag/templates.feature` › ギャラリーで自分が作ったものだけに絞り込むと、自分のテンプレートだけが一覧に出る / 他の利用者の自分のテンプレート一覧には、自分が作ったものは出ない(`@api`。一般利用者はギャラリーを開けない) | 検証済(#1128で実測、2026-09-26) |
| AC-TAG-012 | プロジェクト別タグとプレビュー | プロジェクトごとの見た目を確認できる | 保存前のHTML/CSSでも、実際の投稿と同じ描画結果をプレビューで確認できる | `e2e/features/custom-tag/preview-and-css.feature` › タグのプレビューで、保存前に描画結果を確認できる || 検証済(#1283で実測、2026-09-15) |
| AC-TAG-013 | CSSセレクタ接頭辞の設定 | 公開先の既存CSSと衝突しない | `PUT /api/projects/{projectId}/css-selector-prefix` の設定が統合CSSのセレクタへ反映され、他プロジェクトと衝突しない(タグ画面の「CSSセレクタ接頭辞」フォームから保存) | `e2e/features/custom-tag/preview-and-css.feature` › CSSセレクタ接頭辞を変えると、統合CSSのセレクタが接頭辞付きになり他プロジェクトと衝突しない | 検証済(#1128で実測、2026-09-26) |
| AC-TAG-014 | タグデザイン設定(プロジェクト/全体) | タグの見た目を一括で決められる | `/admin/tag-design`(グローバル)と `/projects/{id}/tags` の「組み込みタグのデザイン」タブ(プロジェクト個別)で保存でき、再読み込み後も保存値が表示される。両者は完全に独立している(グローバル設定は未紐付けサイトの解決にだけ使われ、未保存のプロジェクトは標準プリセットを使い、保存済みのプロジェクト設定は後からのグローバル変更で変わらない。2026-10-02 の決定)。公開先の見た目への反映(#861 の再発検知)は内部ブリッジ `/api/internal/project/tag-design/{tagType}` のサービス側テストが持つ | `e2e/features/platform/tag-design-settings.feature` › 管理者がグローバルのタグデザインを保存すると、再読み込み後も保存した値が表示される / グローバル設定を保存しても、未保存の新規プロジェクトにはグローバルの値ではなく標準の値が表示される / プロジェクトで保存した値は再読み込み後も表示され、後からグローバル設定を変えても変わらない | 実装中(保存と独立性は #1155 の3シナリオが通過。AI生成のシナリオは #1586 で扱う) |
| AC-TAG-015 | コンテンツキャッシュ | 外部URLの情報をカード表示できる | `GET /api/content-cache` が取得結果を返してキャッシュし、内部アドレスへの取得は拒否される(#902 SSRF の再発検知) | `e2e/features/custom-tag/content-cache.feature` › 外部URLのコンテンツを取得してキャッシュできる / 内部アドレスのコンテンツ取得はSSRF対策で拒否される | 検証済 |

### 2.11 Analytics — `ANA`

画面: `/projects/[id]/settings/google-analytics`, `/projects/[id]/settings/adsense`, `/projects/[id]/dashboard`
API: analytics `ProjectAnalyticsApiKeyController`, `ProjectDashboardController`

受け入れシナリオは `apps/web/e2e/features/analytics/` の4ファイル・14シナリオ(#939 / AT-13)。
**実 Google は叩かない。** 向き先は `docker-compose.e2e-stubs.yml` が `ga-stub` / `adsense-stub`
へ差し替える(#928 / AT-2)ため、全シナリオが `@stub` である。状態欄について3点:

- **`@api` と書いてある行は、ブラウザ経路がそもそも成立しない。** OAuth の起点
  (`/connect/adsense/start`)は accounts.google.com へリダイレクトし、同意画面は
  スタブ化の対象外(#928 が置き換えたのはトークン交換とレポートAPIだけ)なので、
  認可コードをブラウザから得る手段が無い。`state` 照合だけは Next.js の Route Handler にあり、
  `apps/web/src/app/connect/adsense/__tests__/callback-route.test.ts` が担当する。
- **`実装中` の行はシナリオが書かれているが、この開発ホストでは実行できていない。**
  Playwright のブラウザが OS の共有ライブラリを欠いていて起動しないため(#1045)。
  ブラウザを起動できる環境で `npm run test:at` を通した時点で `検証済` へ変える。
  AT-12(§2.10)と同じ扱いである。
- **異常系の文言は #939 で直した。** 401 / 429 のとき、従来はステータスとGoogleの応答本文が
  そのまま画面に出るだけで「再認証すればよい」「待てばよい」が伝わらなかった
  (`GoogleApiFailureMessage`、コミット 9eb0eb87)。

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-ANA-001 | GA のOAuth連携・プロパティ選択・解除(#1231) | 自分の計測データを見られる | Googleアカウントとの連携(`analytics.readonly`のみ)後、アクセスできるGA4プロパティの一覧から選んで保存でき、`GET/DELETE /api-keys/google-analytics` の結果がダッシュボードの表示可否に反映される。保存したクライアントシークレット・リフレッシュトークンは画面にもAPI応答にも再表示されない | `features/analytics/credentials.feature` › Google Analytics のOAuthクライアントを保存すると、Googleアカウントとの連携を始めるリンクが現れる / Google Analytics の認可コードをコールバックで受け取ると、リフレッシュトークンが保存され連携済みになる / 連携済みのプロジェクトでは、アクセスできるGA4プロパティが一覧に出て、選んで保存できる / 登録済みの資格情報は、画面にもAPI応答にも平文で再表示されない / Google Analytics の連携を解除すると、未設定状態に戻る / Google Analytics の不正な認可コードは拒否され、連携済みにならない、`features/analytics/analytics-authorization.feature` › 未認証では資格情報の読み書きができない / 自分がメンバーでないプロジェクトの資格情報は読み書きできない | 検証済(`@api` の認可2シナリオ)/ 実装中(画面の3シナリオ。#1045 によりこの開発ホストではブラウザを起動できず未実行) |
| AC-ANA-002 | GA ダッシュボード | アクセス状況を把握できる | `GET /dashboard/google-analytics` の内容が画面に表示される。未設定なら「未設定」と分かり、失効・レート制限・タイムアウトは理由が分かる形で示され画面が壊れない | `features/analytics/dashboard-report.feature` › GA資格情報が設定されたプロジェクトのダッシュボードに、スタブが返す指標が表示される / 資格情報が未設定のプロジェクトでは、ダッシュボードが未設定と分かる表示になる、`features/analytics/report-failures.feature` › 資格情報が失効していると、再認証が必要と分かるメッセージが出る / 外部APIがレート制限を返したとき、利用者に分かる形で示され画面が壊れない / 外部APIがタイムアウトしても、ダッシュボードの他のパネルは表示され続ける | 検証済(`@stub` の画面3シナリオ。#1281で `at-main` 実行を確認、2026-09-15)。文言そのものは `GoogleAnalyticsClientTest` も検証済 |
| AC-ANA-003 | AdSense 認証情報の登録・削除、パブリッシャーIDの自動取得(#1232) | 収益を見られる。パブリッシャーIDを調べて手入力しなくてよい | `GET/PUT/DELETE /api-keys/adsense` の結果がダッシュボードの表示可否に反映される。パブリッシャーIDは任意入力で、連携したGoogleアカウントの利用可能アカウントが1件なら自動保存され(素の`pub-…`)、複数件なら一覧から選べ、取得に失敗しても手入力で復旧できる。クライアントシークレットとリフレッシュトークンは再表示されない | `features/analytics/credentials.feature` › AdSense のパブリッシャーIDとOAuthクライアントを登録できる / AdSense のパブリッシャーIDを空のままOAuthクライアントだけを保存でき、連携リンクが現れる / AdSense のアカウントが1件なら、認可コードのコールバックでパブリッシャーIDが自動で保存される / 自動で取得したパブリッシャーIDが設定画面に表示され、ダッシュボードのウィジェットが収益レポートを表示する / AdSense のアカウントが複数あるときは、一覧から選んで保存できる / AdSense のアカウント一覧を取得できなくても、リフレッシュトークンは残り、手入力で連携を完了できる / 登録済みの資格情報は、画面にもAPI応答にも平文で再表示されない、`features/analytics/analytics-authorization.feature` › 全2シナリオ(`adsense` 系4エンドポイントを含む) | 検証済(`@api` の認可2シナリオ)/ 実装中(画面の2シナリオ。#1045 によりこの開発ホストではブラウザを起動できず未実行) |
| AC-ANA-004 | AdSense クライアントシークレット | OAuth連携を設定できる | `PUT /api-keys/adsense/client-secret` 後、Googleとの連携を開始できる。保存した値は再表示されない | `features/analytics/credentials.feature` › AdSense のパブリッシャーIDとOAuthクライアントを登録できる(設定済み表示と連携リンクの出現)/ 登録済みの資格情報は、画面にもAPI応答にも平文で再表示されない | 実装中(画面のシナリオ。#1045 によりこの開発ホストではブラウザを起動できず未実行) |
| AC-ANA-005 | AdSense OAuth コールバック | 認可を完了できる | `POST /api-keys/adsense/oauth-callback` 後にレポートが取得でき、不正な認可コード・不正な `state` は拒否される | `features/analytics/credentials.feature` › AdSense の認可コードをコールバックで受け取ると、リフレッシュトークンが保存される / 不正な認可コードや不正なstateのコールバックは拒否される、単体 `src/app/connect/adsense/__tests__/callback-route.test.ts`(cookie と `state` の照合) | 検証済(`@stub` `@api`。Google の同意画面はスタブ化の対象外でブラウザ経路が成立しない) |
| AC-ANA-006 | AdSense ダッシュボード | 収益状況を把握できる | `GET /dashboard/adsense` の内容が画面に表示される。未設定なら「未設定」と分かり、他パネルの障害に巻き込まれない | `features/analytics/dashboard-report.feature` › AdSense資格情報が設定されたプロジェクトのダッシュボードに、収益レポートが表示される / 資格情報が未設定のプロジェクトでは、ダッシュボードが未設定と分かる表示になる、`features/analytics/report-failures.feature` › 外部APIがタイムアウトしても、ダッシュボードの他のパネルは表示され続ける | 検証済(`@stub` の画面シナリオ。#1281で `at-main` 実行を確認、2026-09-15)。レポートの取得自体は `credentials.feature` › 保存されたリフレッシュトークンでAdSenseのレポートを取得できる が `@api` で検証済 |

### 2.12 システム設定・バックアップ・拡張配布・ダッシュボード — `SYS`

画面: `/`(ダッシュボード), `/admin/system-settings`, `/admin/backup`
API: platform `SystemSettingController`, `AppSettingController`, `BackupController`, `DashboardController`, `VscodeExtensionController`

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-SYS-001 | ダッシュボード(`/`)のサービス状態 | 何が動いていて何が落ちているか分かる | `GET /api/dashboard/service-status` の内容がパネルに表示される | `features/platform/dashboard-status.feature` › サービス状態パネルが認証エラーにならず、実際のサービス状態を表示する(#876の退行検知) | 検証済(issue #1154。この開発環境はComfyUIコンテナ自体が起動していないため「全サービスNORMAL」ではなく「認証エラーにならず1件以上表示される」で判定する。ただしe2e環境にはComfyUIのスタブが用意されており本来ERRORになるはずのサービスは無いため、表示されている行のいずれもエラー表示になっていないことまで併せて確認する。`STATUS_LABEL[service.status]`を強制的に`エラー`表示へ書き換えて実測しRedを確認した後、元に戻して再びGreenを確認済み) |
| AC-SYS-002 | コンテナ状態パネル | コンテナ単位の異常に気付ける | `GET /api/dashboard/container-status` の内容が表示される(#876 の再発検知)。他プロジェクトのコンテナが混ざらない(#803)。正常終了したワンショットジョブをエラー扱いしない(#725) | `features/platform/dashboard-status.feature` › コンテナ状態パネルに、本プロジェクトのコンテナだけが表示される(#803の退行検知) / 正常終了したワンショットコンテナがERROR扱いされない(#725の退行検知) | 検証済(issue #1154。`@destructive`。使い捨てコンテナをdocker runで実際に起動し検証。`ContainerStatusService`の`belongsToThisProject`と`isCompletedOneShotJob`の判定を一時的に無効化して実測し、両シナリオがそれぞれ正しい理由でRedになることを確認した後、元に戻して再びGreenを確認済み) |
| AC-SYS-003 | 状態のストリーミング更新 | 手動リロードなしで最新が見える | `/service-status/stream` `/container-status/stream` が更新を push する | `features/platform/dashboard-status.feature` › サービス状態はSSE(/service-status/stream)で継続的に更新される / コンテナ状態はSSE(/container-status/stream)で継続的に更新される | 検証済(issue #1154。両ストリームとも、担当する各Broadcaster(`ConnectedServiceStatusBroadcaster` / `ContainerStatusBroadcaster`)の配信間隔を一時的に999,000,000msへ変更し、25秒の待機内で2件目のイベントが届かず失敗する(`Expected: >= 2, Received: 1`)ことをそれぞれ実測してRedを確認した後、元に戻して再びGreenを確認済み) |
| AC-SYS-004 | サービス状態の詳細 | 障害の原因に辿り着ける | `GET /service-status/detail` が個別サービスの詳細を返す | — | 未着手(#940 / #1154のスコープでは着手せず) |
| AC-SYS-005 | Brave Search APIキー(システム全体) | 全プロジェクト共通で検索を使える | `GET/PUT/DELETE /api/system-settings/brave-search-api-key` の設定が検索付き質問に反映される | `features/platform/system-settings.feature` › Brave Search APIキーを保存・削除でき、保存後は平文で再表示されない | 検証済(`@api`。この設定にはWeb画面が無いためAPIレベルで検証。「削除後は未設定になる」ではなく「DB由来ではなくなる」で判定する。この環境は`BRAVE_SEARCH_API_KEY`環境変数を設定済みのため、削除後も環境変数へフォールバックし`configured`はtrueのままになる) |
| AC-SYS-006 | アプリ設定 (`/admin/system-settings`) | 全体の挙動を調整できる | `GET/PUT /api/system-settings/app-settings` の設定が保存され画面に反映される。一般ユーザーは変更できない。LLM接続設定(`llm_provider`/`llm_ollama_base_url`)の変更はDB側が環境変数より優先される | `features/platform/system-settings.feature` › システム設定を保存すると、再読込後も反映されている / 一般ユーザーはシステム設定を変更できない / LLM接続設定を変更すると、以後のAI生成が新しい向き先へ行く(DB側優先の確認、`@destructive`) | 検証済(非管理者の拒否はAPIレベル403で検証。ページアクセス自体の拒否は`features/auth/permissions.feature`の`ADMIN_ONLY_PREFIXES`検証で既にカバーされている。LLM接続設定の検証は、システム全体既定を使う`/api/ai/tags`をDB設定変更の前後で呼び、到達不能なURLへ変更した直後は失敗し元に戻すと再び成功することで確認する。システム全体のLLM既定を書き換えるため`@destructive`) |
| AC-SYS-007 | バックアップのダウンロード | 環境を失っても復旧できる | `GET /api/backup/download` がリストア可能なアーカイブを返す。一般ユーザーは作成・ダウンロードできない | `features/platform/backup.feature` › バックアップを作成でき、ダウンロードしたアーカイブに全スキーマのダンプが含まれる / 一般ユーザーはバックアップの作成・ダウンロードができない | 検証済(issue #1156。親issue #940のシナリオ13・15を引き取る。`@destructive`。全スキーマのダンプ含有確認は、`BackupService#createBackup()`でMySQLスキーマ`lbs_log`のダンプ書き込みを一時的にスキップし「スキーマ lbs_log のダンプが含まれていません」で失敗することを実測してRedを確認した後、元に戻して再びGreenを確認済み。一般ユーザー拒否の確認は、同メソッドの`adminAuthorizationService.requireAdmin()`呼び出しを一時的にコメントアウトし、一般ユーザーでも200が返って`toBe(403)`が`Received: 200`で失敗することを実測してRedを確認した後、元に戻して再びGreenを確認済み。バックアップからのリストア(シナリオ14)は#1141/#1142の環境不整合により別issueへ分離しておりAC-SYS-008が対象) |
| AC-SYS-008 | バックアップからのリストア | 実際に復旧できる | `POST /api/backup/restore` 後、バックアップ時点のデータが復元される。1つのMySQLスキーマのダンプが不正なアーカイブでは、失敗したスキーマ名を画面に示して止まり、以降の手順(Keycloak PostgreSQL)は実行されず、自動ロールバックもしない | `features/platform/backup-restore.feature` › 正しいアーカイブで復元すると、変更・削除した代表データがバックアップ時点に戻り、後から追加したものは消える / 1つのMySQLスキーマのダンプが不正なアーカイブで復元すると、失敗したスキーマを示して止まり、ロールバックされず、正しいアーカイブで復元し直せる | 検証済(issue #1157。2026-10-02 に実スタックで2シナリオとも通過。復元後の Keycloak ユーザーは、Keycloak API がキャッシュ値を返す(#1590)ため Keycloak PostgreSQL を直接読んで確認する。`@destructive` `@slow`。代表データ(project-service のプロジェクト、content-service のカスタムタグ、Keycloak のテスト用ユーザー)で確認する。共有スタックのDB全体を上書きするため、実行はスタックを占有できるときに `npm run test:at -- --project=at-destructive --no-deps --grep バックアップからのリストア` で行う) |
| AC-SYS-009 | VSCode拡張の配布 | 拡張をサーバーから入手できる | `GET /api/system/vscode-extension` が `.vsix` を返す。ビルド物に`coverage/`や`src/`を含まない(#773の退行検知) | `features/platform/vscode-extension.feature` › ダウンロードした.vsixは妥当なVSIX(zip)である / ビルドされた.vsixにcoverage/やsrc/が含まれない(#773の退行検知) | 検証済(issue #1153。この開発ホストはfirefox/webkitの共有ライブラリが未導入で`npx playwright test`のglobalSetupがブラウザ起動確認自体で落ちるため、`npm run test:at:fast`はそのままでは動かない。`E2E_SKIP_BROWSER_CHECK=1`で起動確認を回避し、`--project=at-main --no-deps`(環境は既にシード済みのため)、`E2E_WORKERS=1`で実行し2件とも成功を確認した。ワーカー数を1にしたのは、並行実行するとビルド出力パスの競合で500になる既知の別バグ(#1190、このIssueのACの対象外)を踏むため。退行検知シナリオ(#773)はRedを実証済み: `.vscodeignore` の `coverage/**`/`src/**` 除外を一時的にコメントアウトし `npm run test:coverage`(`apps/extension`)でcoverage/を生成した状態で実行すると`extension/src/*.ts`と`extension/coverage/**`が同梱されて失敗することを確認し、除外を戻すと再び成功することを確認した。zip解析は当初python3をシェルアウトしていたが、Review指摘によりNode標準の`Buffer`だけでzipのセントラルディレクトリを読む実装に置き換えた) |
| AC-SYS-011 | ComfyUI の演算デバイス(GPU / CPU)の切り替え(#1399) | 管理者が画面から GPU と CPU を選んで切り替えられ、GPU の無いホストでは CPU に固定される | 両構成(`lbs-comfyui` / `lbs-comfyui-cpu`)があるホストで、適用すると「適用中」の後に「完了」が表示され、選んだ構成だけが `running` になる。GPU 構成が無いホストは「CPU(固定)」と表示され、GPU は選べず、API へ直接送った GPU への適用も拒否されてコンテナは操作されない。選んだ構成が上限時間内に成功しないと失敗の理由が表示され、元の構成へ戻る。管理者以外には欄が表示されず、API は 403 | `features/platform/compute-device.feature` › 両構成があるホストでGPUからCPUへ切り替えると、適用中の後に完了が表示される / GPU構成が無いホストでは「CPU(固定)」と表示され、GPUは選べず理由が表示される / GPU構成が無いホストでGPUへの適用をAPIへ直接送っても拒否され、どのコンテナも操作されない / 選んだ構成が上限時間内に稼働しないと、失敗の理由が表示され元の構成に戻る / 一般ユーザーには演算デバイス欄が表示されない / 一般ユーザーは演算デバイスの参照APIも適用APIも403になる、`scripts/test_docker_socket_proxy.py`(AC5。proxy の書き込み許可) | 実装中(#1399。受け入れシナリオは Docker Engine API スタブ `infra/e2e-stubs/docker-engine` へ向けて書いたが、共有の受け入れ環境の platform / web を作り直して実行する工程は未実施で、シナリオは未実行。単体テストは通過。**docker-socket-proxy の書き込み許可は #1587 の結論待ち**: `POST: 0` + `ALLOW_START` / `ALLOW_STOP` では start / stop が 403 になることを実機のイメージで確認したため、`docker-compose.yml` は変更していない) |

### 2.13 ログと非同期経路 — `LOG`

画面: `/operation-logs`
API: log-writer `AuditLogController`, `OperationLogController`, `FrontendErrorLogController` / ai `GenerationJobController` / RabbitMQ

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-LOG-001 | 操作ログ一覧 (`/operation-logs`) | 誰が何をしたか追える | 操作を行うと `GET /api/operation-logs` に記録が現れる(#825 の再発検知: 常に空にならない) | `features/logging/operation-log.feature` › 画面から業務操作を行うと、その操作が操作ログに記録され操作ログ画面に現れる | 検証済(記録は非同期のため、固定 sleep ではなくポーリングで待つ。操作ログを書くのは Web の BFF だけなので、操作は必ず画面から行う) |
| AC-LOG-002 | 操作ログの詳細 | 個別操作の内訳を見られる | `GET /api/operation-logs/{operationId}` が該当操作の詳細を返す | `features/logging/operation-log.feature` › operationIdで1つの操作に紐づく一連のログを取得できる | 検証済 |
| AC-LOG-003 | 統合ログ | 操作と監査を突き合わせられる | `GET /api/operation-logs/unified` が両者を時系列で返す | `features/logging/operation-log.feature` › 統合ビューで、複数サービスにまたがる1操作のログが時系列で並ぶ | 検証済(操作ログの発行元は log-writer、監査ログの発行元は project-service。この2件が揃うことを「複数サービスにまたがる」の判定にしている) |
| AC-LOG-004 | 監査ログ | 権限変更等を追跡できる | 権限変更後、`GET /api/audit-logs` に記録が現れる。記録は利用者から改変・削除できない | `features/logging/audit-log.feature` › プロジェクトメンバーのロール付与・変更・剥奪が監査ログに記録される / 監査ログは利用者から改変・削除できない | 検証済(`@api`)。ただし**ユーザーの無効化・role 変更・一括削除は監査ログを1件も残さない**ため対象にできない(#1137)。記録が無いことを期待値として固定しない |
| AC-LOG-005 | フロントエンドエラーログ | 画面側の異常を検知できる | `POST /api/logs/errors` した内容が `GET /api/logs/errors` で読める。送信は認証済みの経路で行われ、認証ゲートが有効でも欠落しない | `features/logging/frontend-error-log.feature` › 画面でクライアント側エラーが起きると、そのエラーが読み取りAPIで取得できる / エラーログの送信は認証済みの経路で行われ、認証ゲートが有効でも欠落しない | 検証済(#791 の退行検知。`errorLogger.ts` の送信先を `/client-errors` から `/api/logs/errors` へ戻すと2シナリオとも落ちることを実測で確認済み) |
| AC-LOG-006 | 非同期生成ジョブの完了通知 | 長い処理の完了に気付ける | 生成ジョブがキュー経由で完了状態に遷移し、画面に反映される | — | 未着手(`@slow`) |
| AC-LOG-007 | 操作者の解決 | ログに「誰が」が正しく残る | ログの操作者が実際のログインユーザーと一致する(#906 / #916 の再発検知) | `features/logging/operation-log.feature` › 操作ログに、誰が・いつ・何に対して・結果はどうだったかが記録されている、`features/logging/audit-log.feature` › 監査ログの各件には操作者・日時・対象・操作種別が揃っている、`features/logging/frontend-error-log.feature` › そのエラーログには発生画面のURLと操作者が記録されている | 検証済(`userId` と JWT の `sub` の両方を、実際にログインしたアカウントのものと突き合わせる) |
| AC-LOG-008 | ログ経路の障害耐性 | ログのために業務操作が失敗しない | RabbitMQ または log-writer が停止していても、業務操作は成功し画面は壊れない | `features/logging/async-path.feature` › RabbitMQが停止していても業務操作は成功する / log-writerが停止していても画面からの業務操作は成功する | 検証済(`@slow` `@destructive`。実際にコンテナを停止する) |
| AC-LOG-009 | 停止中に発生したログの扱い | ログが残るのか消えるのかが決まっている | 操作ログとフロントエンドエラーログは同期DB書き込みへフォールバックして**残る**。監査ログは発行元が `lbs_log` へ書けない(ADR-0004)ため**失われる**。取りこぼしを後から再送する仕組み(outbox)は無い | `features/logging/async-path.feature` › RabbitMQ停止中のログは、定義どおり操作ログとエラーログが残り監査ログが失われる | 検証済(`@slow` `@destructive`)。#941 で仕様を確定させた。確定の過程で、操作ログのフォールバックが `created_at` を設定せず 500 になる欠陥(記録は失われ、BFF が握り潰すので誰も気付かない)を発見し #941 で修正した |
| AC-LOG-010 | ログの閲覧と認可 | 見たいログに辿り着け、他人のログは見えない | `/operation-logs` で種別とキーワードで絞り込める。監査ログは日時と利用者で絞り込める。一般ユーザーは他人の操作ログ・監査ログ・エラーログを読めない | `features/logging/log-viewing.feature` › 操作ログ画面で種別とキーワードによる絞り込みができる / 監査ログを日時と利用者で絞り込める / 一般ユーザーは他人の操作ログと監査ログを閲覧できない | 検証済。ただし `/operation-logs` の画面に**日時の絞り込みが無い**(#1138)。利用者の絞り込みは、操作ログAPIが常に閲覧者本人に固定されるため画面に持たせる意味が無く、対象外 |

### 2.14 VSCode拡張 — `EXT`

`apps/extension/package.json` の `contributes.commands` 全24コマンド。
検証方針は AT-16(#942)に従い、**APIレベル + 単体テストの二層**とする(UI操作は対象外)。

- Layer 1(APIレベル): `apps/extension/e2e/features/**`。拡張自身の `apiClient` / `httpClient` を
  通して実スタックへ接続する。実行方法は [apps/extension/e2e/README.md](../apps/extension/e2e/README.md)。
- Layer 2(単体): `apps/extension/src/__tests__/**`。サーバー越しに観測できない部分
  (リクエストの中身、ローカルのファイル生成、パネルの状態遷移)を担当する。
- UI操作(コマンドパレット・エディタへの挿入・Webview・キーバインド)は自動化せず、
  [apps/extension/MANUAL_ACCEPTANCE_CHECKLIST.md](../apps/extension/MANUAL_ACCEPTANCE_CHECKLIST.md)
  に列挙して人が確認する。**どのコマンドも、この3つのいずれかに必ず対応付ける**。

下表の「対応シナリオ」は `apps/extension/e2e/features/` 配下を `ext:` を頭に付けて示す
(`apps/web/e2e/features/` を指す他節と混ざらないようにするため)。単体テストは
`apps/extension/src/__tests__/` 配下のファイル名で示す。

| 機能ID | コマンド | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-EXT-001 | `letsBlog.login` | エディタから離れずに認証できる | Device Code フローが完了しトークンが保存される | `ext:auth/login.feature` › デバイスコードで承認するとアクセストークンが保存され再ログインなしでAPIを呼べる / 期限切れのアクセストークンはリフレッシュトークンで自動的に更新される、`ext:auth/connection.feature` の2シナリオ(TLS検証・誤ったURL)、手動: チェックリスト §2 | 検証済(UI表示は手動) |
| AC-EXT-002 | `letsBlog.selectProject` | 作業対象を切り替えられる | プロジェクト一覧から選択でき、以後の操作が対象に向く | `ext:projects/selection.feature` › プロジェクト一覧から選んだプロジェクトはワークスペースに記憶される、手動: チェックリスト §3 | 検証済(クイックピックは手動) |
| AC-EXT-003 | `letsBlog.selectSite` | 公開先を切り替えられる | サイト一覧から選択でき、以後の公開が対象に向く | `ext:projects/selection.feature` › 選択したプロジェクトに紐付くサイトを一覧できる、手動: チェックリスト §3 | 検証済(クイックピックは手動) |
| AC-EXT-004 | `letsBlog.createArticle` | AI支援付きで記事を起こせる | 新規記事ファイルが生成され、AIの下書きが入る | `ext:articles/authoring.feature` › AIありの記事作成では見出しを含む構成案が提案される、単体 `articleScaffold.test.ts`、手動: チェックリスト §4 | 検証済(パネル操作は手動) |
| AC-EXT-005 | `letsBlog.createArticleWithoutAi` | AIを使わず記事を起こせる | テンプレートだけの記事ファイルが生成される | 単体 `articleScaffold.test.ts`(生成物・上書き確認の3分岐・日本語front matter)、手動: チェックリスト §4 | 検証済(単体。サーバーを介さずLayer 1の対象外) |
| AC-EXT-006 | `letsBlog.publish` | エディタから公開できる | 編集中の記事が公開され、公開先URLで読める | `ext:articles/publish.feature` › front matter付きのMarkdownを公開するとWordPress投稿が作成される / 公開済みの記事を再度公開すると同じWordPress投稿が更新される、単体 `apiClientRequests.test.ts`(multipartの組み立て) | 検証済(`@slow`) |
| AC-EXT-007 | `letsBlog.schedulePublication` | 予約公開できる | 指定時刻が設定され、時刻まで公開されない | `ext:articles/publish.feature` › 本番サイトへは公開予定日時を指定して予約投稿できる / 予約日時を変更して再公開しても予約状態が維持される(#1003 で解消。provision-agentの`/wp-cli/post`が`publishScheduledAt`を読んでおらず、日時なしで即時公開されていた) | 検証済(`@slow`) |
| AC-EXT-008 | `letsBlog.deletePost` | 公開済み記事を取り下げられる | 公開先から記事が消える | `ext:articles/deletion.feature` › 公開した記事を削除すると公開先から読めなくなる / アイキャッチ画像付きの記事を削除すると、未参照になったメディアも削除できる(#1001 で解消。`wp post delete` へ渡していた存在しない `--yes` が原因で常に502だった) | 検証済(`@slow`) |
| AC-EXT-009 | `letsBlog.askAi` | 執筆中に下書き/校正/要約を頼める | 選択範囲に対する応答がエディタへ挿入される | `ext:ai/assist.feature` › askAiは選んだモードでAIへ依頼する(draft / proofread / summarize の3例) | 検証済(`@stub`) |
| AC-EXT-010 | `letsBlog.askAiSearch` | Web検索を踏まえた回答を得られる | 検索結果を根拠にした応答が返る | `ext:ai/assist.feature` › askAiSearchはWeb検索の結果を根拠として返す | 検証済(`@stub`) |
| AC-EXT-011 | `letsBlog.suggestTags` | タグを考えなくてよい | 本文からタグ候補が提示される | —(#1004 でブロック。LLMスタブがJSONを要求するプロンプトへ散文で応答するため候補が常に空。単体 `apiClientRequests.test.ts` がリクエスト形式のみ担保) | 未着手(#1004) |
| AC-EXT-012 | `letsBlog.proofreadNow` | 公開前に記事を複数の観点で順にレビューできる | 日本語チェック→校正チェック→校閲→読者視点でのチェック→文体チェックの5ステップが順に実行され、指摘がステップ別の色のアンダーラインと指摘チェックリスト(未対応/修正済み/スキップ)で示される。未対応の指摘が残っている間は Publish がブロックされ、プレビューでは警告される | `ext:ai/review-steps.feature` › レビュー5ステップを定義された順に、ステップキーとプロジェクトIDを指定して依頼する(#1215)、単体 `apiClientRequests.test.ts`(ステップ別の依頼先)/ `proofreadLogic.test.ts` / `proofreadDiagnostics.test.ts` / `reviewChecklistLogic.test.ts` / `reviewChecklistStore.test.ts` / `reviewChecklistPanel.test.ts` / `reviewChecklistWebview.test.ts`、手動: チェックリスト §5(色分け・中断・失敗・スキップ)/ §5.1・§5.1.1・§5.2(指摘チェックリスト)。Publish直前のレビューと未対応の指摘による投稿ブロック(#1217): `ext:articles/publish-review.feature`、単体 `publishReviewLogic.test.ts`、手動: チェックリスト §6.1。プレビュー直前のレビュー(#1226): `ext:articles/preview-review.feature`、単体 `previewReviewLogic.test.ts`、手動: チェックリスト §6.2 | 検証済(部分。5ステップの依頼とPublish/プレビュー連携は自動。色分け・チェックリストの操作・実プロバイダーでの指摘内容は手動。Epic #1210) |
| AC-EXT-013 | `letsBlog.generateSection` | 節単位で書き足せる | 見出しに対応する本文が挿入される | `ext:ai/assist.feature` › generateSectionは見出しのコンテキストを含めて依頼する、単体 `apiClientRequests.test.ts` › セクション生成は見出しと直前の文脈・記事タイトルを含めて送る | 検証済(`@stub`) |
| AC-EXT-014 | `letsBlog.switchAiProvider` | 用途に応じてAIを変えられる | 切り替えたプロバイダが以後の生成に使われる | `ext:ai/assist.feature` › AIプロバイダーを切り替えると以降のリクエストへ反映される、単体 `apiClientRequests.test.ts` | 検証済(`@stub`) |
| AC-EXT-015 | `letsBlog.planArticle` | 何を書くか詰められる | プラン対話が開始し、結果を記事へ引き継げる | `ext:articles/authoring.feature` › AIありの記事作成では見出しを含む構成案が提案される(`/article-plan/suggest-structure`)、手動: チェックリスト §4 | 検証済(部分。壁打ちパネルの対話は手動) |
| AC-EXT-016 | `letsBlog.generateImage` | 挿絵を作れる | 生成画像が記事へ挿入され、ギャラリーにも現れる | —(#998 でブロック。media-service に `KEYCLOAK_SERVICES_CLIENT_SECRET` が渡っておらず画像生成APIが失敗する) | 未着手(#998) |
| AC-EXT-017 | `letsBlog.imageGallery` | 既存の画像を再利用できる | ギャラリーが開き、選んだ画像が記事へ挿入される | `ext:media/gallery.feature` › プロジェクトの生成画像を一覧できる、手動: チェックリスト §7 | 検証済(挿入操作は手動) |
| AC-EXT-018 | `letsBlog.previewArticle` | 公開前の見た目を確認できる | 公開先テーマ相当のプレビューが開く | `ext:articles/authoring.feature` › 記事のプレビューHTMLを取得できる、単体 `previewPanel.test.ts`、手動: チェックリスト §8 | 検証済(見た目は手動) |
| AC-EXT-019 | `letsBlog.previewDevTools` | プレビューの不具合を調べられる | DevTools が開く | 手動: チェックリスト §8 | 対象外(開発者向けデバッグ機能で、利用者から見た受け入れ基準を持たない) |
| AC-EXT-020 | `letsBlog.addNewDiagram` | 図を新規作成できる | draw.io が開き、保存すると図が登録される | `ext:diagrams/diagrams.feature` › ダイアグラムを作成し編集して一覧から参照できる(作成とSVG取得)、単体 `diagramEditorPanel.test.ts`、手動: チェックリスト §7 | 検証済(draw.io操作は手動) |
| AC-EXT-021 | `letsBlog.editDiagram` | 図を修正できる | 既存の図が draw.io で開き、保存内容が反映される | `ext:diagrams/diagrams.feature`(更新して一覧へ反映)、手動: チェックリスト §7 | 検証済(draw.io操作は手動) |
| AC-EXT-022 | `letsBlog.diagramGallery` | 作った図を再利用できる | 図の一覧から選択して記事へ挿入できる | `ext:diagrams/diagrams.feature`(一覧・削除)、手動: チェックリスト §7 | 検証済(挿入操作は手動) |
| AC-EXT-023 | `letsBlog.pasteSmartCard` | リンクを見栄えよく貼れる | URLがブログカード/Amazonカードとして挿入される | 単体 `urlPaste.test.ts`(カード記法の生成)、`features/cross-cutting/gateway-routing.feature`(カード情報の先読み `/api/content-cache` の疎通。#1002 で解消)、手動: チェックリスト §9 | 検証済(部分。カード記法自体はURL種別のみで即時組み立てられ取得情報を含まないため、記法への反映はAC-EXT-024側で検証。カード記法の見た目確認は手動。#1071) |
| AC-EXT-024 | `letsBlog.pasteAsLink` | リンクを簡潔に貼れる | URLがタイトル付きリンクとして挿入される | 単体 `urlPaste.test.ts`(リンク記法の生成)、`features/cross-cutting/gateway-routing.feature`(タイトル解決 `/api/content-cache` の疎通。#1002 で解消)、`ext:articles/url-paste.feature` › pasteAsLinkはカード情報の先読みに成功するとタイトル付きリンクを組み立てる / 失敗するとURLのみへフォールバックする(#1071) | 検証済 |

### 2.15 横断的品質(認可・ルーティング・レート制限・相関ID・縮退) — `XC`

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-XC-001 | エンドポイント認可 | 権限の無い操作ができない | [AUTHORIZATION_MATRIX.md](AUTHORIZATION_MATRIX.md) の全行が、想定ロールでのみ成功し他は403(#830 の再発検知) | `features/cross-cutting/authorization-matrix.feature` › 認可マトリクスが未認証401としている全エンドポイントは、認証なしでは拒否される / 認可マトリクスが権限不足403としている全エンドポイントは、権限の無い利用者を拒否する / 権限の無い利用者は投稿の公開も削除もできない | 検証済(`@api`) |
| AC-XC-002 | Server Action の認可 | 画面経由でも権限が効く | 認可を要する Server Action が未認可では拒否される(#824 の再発検知) | — | 未着手 |
| AC-XC-003 | gateway ルーティング | 追加したAPIが正しいサービスへ届く | 全公開ルートが意図したサービスへ到達する(#861 の再発検知) | `features/cross-cutting/gateway-routing.feature` › 全公開エンドポイントがgateway経由で担当サービスまで到達する / gatewayを迂回した直接アクセスは、gatewayが付けるヘッダを偽装しても拒否される | 検証済(`@api`。到達先が**どのサービスか**は `RouteControllerContractTest` が正。[ACCEPTANCE_TESTING.md §11](ACCEPTANCE_TESTING.md)) |
| AC-XC-004 | レート制限 | 過剰な要求でシステムが倒れない | 制限超過時に429が返り、通常利用は影響を受けない | `features/cross-cutting/rate-limit.feature` › 短時間に上限を超えて要求すると429と再試行までの時間が返る / あるクライアントが上限に達しても、別のクライアントの要求は通る / 制限の時間枠が明けると再び受理される | 検証済(`@api`。時間枠の回復は `@slow`) |
| AC-XC-005 | 相関IDの伝播 | 障害を横断的に追跡できる | 1リクエストの相関IDが全サービスのログで一致する | `features/cross-cutting/correlation-id.feature` › クライアントが送った相関IDが下流サービスのログに現れる / クライアントが相関IDを送らないとgatewayが採番して応答ヘッダで返す / 1つの操作のログをgatewayと下流サービスで同じ相関IDから追える / 下流サービスがリクエスト1件ごとに所要時間つきの1行をログへ出す / 1つの操作のログをgatewayと「project-service・publishing-service」で同じ相関IDから追える | 検証済(`@api`。project / publishing も #992 で対象に含めた。下流サービスのリクエスト1件ごとの所要時間ログ(method・path・status・duration_ms・相関ID を1行)も #1470 で同じ feature に追加) |
| AC-XC-006 | 下流障害時の縮退 | 一部が落ちても画面が壊れない | 下流サービス(content / ai / media / log-writer)が停止していても、画面は壊れず業務操作は続けられ、復旧後は追加の操作なしに元へ戻る | `features/cross-cutting/service-degradation.feature` › ダッシュボードの状態APIが落ちてもページは壊れず、直近の表示を維持する / content-serviceが停止していても投稿履歴ページは空状態で表示される / ai-serviceが停止していてもAI以外の機能は使える / media-serviceが停止していても記事の公開はできる / log-writerが停止していても業務操作は成功する / 停止したサービスが復旧すると追加の操作なしに機能が戻る | 検証済(`@destructive`) |
| AC-XC-007 | 障害の種別判別 | 認可拒否と本当の障害を区別できる | identity の401/403がサービス障害(502)として扱われない(#829 の再発検知) | — | 未着手(`@api`) |
| AC-XC-008 | CSRF保護 | 外部サイトから操作されない | 保護対象フォームにCSRFトークンが含まれる | `e2e/security.spec.ts` › CSRF保護確認: トークンが含まれていることを確認 | 既存spec |
| AC-XC-009 | SQLインジェクション対策 | 不正な入力でデータが壊れない | 特殊文字を含むクエリが安全に処理される | `e2e/security.spec.ts` › SQLインジェクション対策: 特殊文字を含むクエリが安全に処理されること | 既存spec |
| AC-XC-010 | 入力サニタイズ | 危険な入力が保存されない | ユーザー入力がサニタイズされて保存・表示される | `e2e/security.spec.ts` › 入力サニタイズ: ユーザー入力が正しくサニタイズされること | 既存spec |
| AC-XC-011 | 認可表の網羅性 | 認可の一次情報が実態とずれない | 実装から抽出した公開エンドポイント集合と [AUTHORIZATION_MATRIX.md](AUTHORIZATION_MATRIX.md) の差分が空である(#731 の陳腐化の再発防止) | `features/cross-cutting/authorization-matrix.feature` › 公開エンドポイントはすべて認可マトリクスに載っている | 実装中(`@api`。シナリオは通っていない。認可表に無い公開エンドポイントが22件あるため。#991) |

### 2.16 横断的品質(i18n・a11y・レスポンシブ) — `UX`

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
issue #944(AT-18)で、旧 `e2e/accessibility.spec.ts`(Playwright直書き、ホーム画面と
Keycloakログイン画面の2画面のみ)を `e2e/features/ui-quality/` の4ファイル・16シナリオへ
全面移行し、対象を `docs/ACCEPTANCE_CRITERIA.md` §6 が数える全24ページへ広げた。
`Identify accessibility violations for review`(違反を記録するだけで合否を決めない調査用
テスト)は移行対象から外し、合否を決めるものだけを引き継いだ。

| AC-UX-001 | アクセシビリティ(アプリ画面) | 支援技術で操作できる | 全24ページで axe の critical・serious 相当の違反が出ない | `features/ui-quality/accessibility.feature` › 全ページでaxeによる重大な違反が無い | 検証済 |
| AC-UX-002 | アクセシビリティ(ログイン画面) | ログインから支援技術で使える | Keycloak ログイン画面で critical・serious 相当の違反が出ない | `features/ui-quality/accessibility.feature` › Keycloakのログイン画面も同じ基準を満たす | 検証済 |
| AC-UX-003 | キーボード操作 | マウス無しで操作できる | ログイン→プロジェクト作成→保存がキーボードのみで完了し、フォーカスが視認できる | `features/ui-quality/accessibility.feature` › 主要な操作がキーボードのみで完了できる / フォーカスインジケータが視認できる | 検証済 |
| AC-UX-004 | 見出し階層・代替テキスト・リンク文言 | 読み上げで内容が分かる | このアプリ自身がレンダリングする全ページで見出し階層が妥当、画像にalt、リンクに説明的な文言がある | `features/ui-quality/accessibility.feature` › 全ページで見出し階層が妥当である / 画像に代替テキストがある / リンクテキストが内容を説明している | 検証済(`/login` `/setup` はKeycloakのホスト型画面へリダイレクトするため見出し階層の対象外。当該ページ自身のaxe検査はAC-UX-002が行う) |
| AC-UX-005 | フォームのラベルとARIA | 入力欄の意味が伝わる | フォーム要素にラベル/ARIA属性がある | `features/ui-quality/accessibility.feature` › フォーム要素にラベルとARIA属性が付いている | 検証済 |
| AC-UX-006 | 色コントラスト | 弱視でも読める | 全24ページで axe の color-contrast(serious)が0件 | `features/ui-quality/accessibility.feature` › 全ページでaxeによる重大な違反が無い(AC-UX-001と同一シナリオ。axeのcolor-contrastルールはseriousとして検出される) | 検証済(発見した違反は本Issueで是正: `EnvironmentSlot` 等のバッジ・`UnifiedLogRow` のKeycloak Subバッジの配色) |
| AC-UX-007 | レスポンシブ(モバイル・タブレット) | スマートフォン・タブレットでも操作できる | 375px/768pxで全24ページが横スクロールを起こさず、375pxでナビゲーション・フォーム送信が成立する | `features/ui-quality/responsive.feature` › モバイル幅(375px)で全ページが横スクロールを起こさない / モバイル幅で主要な操作(ナビゲーション・フォーム送信)ができる / タブレット幅(768px)で崩れない | 検証済(発見した横スクロールは本Issueで是正: `HeaderNav` のデスクトップナビ切替を`sm:`→`lg:`へ、`ProjectSectionNav`に`overflow-x-auto`を追加、`UnifiedLogRow`に`flex-wrap`を追加) |
| AC-UX-008 | 言語表示(i18n) | 日本語で一貫して読める | 画面の文言が言語設定に従い、未翻訳のキーが露出しない。翻訳ファイル間でキーの過不足が無い。日時が利用者のタイムゾーンに従う(操作ログ画面は個人設定TZ、未設定なら閲覧者のブラウザTZで表示し、表示中のTZを画面に明示する。ハイドレーションエラーを出さない。コピーしたトレース文字列の日時はUTC・`Z`付きISO-8601) | `features/ui-quality/internationalization.feature` › 対応する全ロケールで、翻訳キーの欠落が無い(issue #718の退行を検出できることを同シナリオ内で証明) / ロケールを切り替えると画面のラベルが切り替わり、再読込後も保持される / 日付・時刻が利用者のタイムゾーン設定に従って表示される / 操作ログの日時がブラウザTZ(Pacific/Auckland・America/New_York)で表示され表示中のTZが明示される / 個人設定TZ(Asia/Tokyo)がブラウザTZより優先される / 操作ログの「コピー」の日時がUTC(Z付きISO-8601)になる(issue #1260) / 翻訳ファイル間でキーの過不足が無い | 検証済(メインナビゲーション自体は`t()`を経由しない既知のギャップがあり、別issue #1145で追跡。本シナリオは実際に`t()`を経由する要素の範囲で検証) |

クロスブラウザ(Firefox / WebKit での認証フロー・主要画面のレンダリング)は、新しい機能IDを
起こさず(§6集計の機械的な取り直しは#1133のスコープ外)、`features/ui-quality/cross-browser.feature`
› 認証フローと主要画面がレンダリングされる として AC-UX-001 の追加観点に位置付ける。
`playwright.config.ts` に `at-cross-browser-firefox` / `at-cross-browser-webkit` を追加し、
`@stage:cross-browser` のシナリオをそれぞれのブラウザで実行する(chromiumでは`at-main`でも
実行される)。開発ホストにFirefox/WebKitの起動に必要なOS共有ライブラリが無い場合は
`docs/e2e-testing.md` §3.3の手順(`sudo npx playwright install-deps`)が必要。

### 2.17 外部依存スタブ — `STUB`

製品機能ではなく**受け入れテストの土台**。ここが壊れると、スタブに乗る AT-8 / AT-9 /
AT-10 / AT-13 のシナリオが理由の分からない形で落ちるため、土台自体に受け入れ基準を置く。

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-STUB-001 | スタブの決定性 | シナリオが応答の中身をアサートできる | 全6スタブが同じ入力に3回とも同一の応答を返す | `features/stubs/external-stubs.feature` › 同じ入力に対して常に同じ応答を返す | 検証済 |
| AC-STUB-002 | エラー注入 | 実サービスでは再現できない異常系を検証できる | 401 / 429 / 500 / タイムアウトを注入でき、注入していないリクエストは正常に戻る。429 には `Retry-After` が付く | `features/stubs/external-stubs.feature` › 認証失敗を注入できる / レート制限を注入できる / サーバーエラーを注入できる / タイムアウトを注入できる | 検証済 |

「利用者」はここでは**受け入れテストを書く実装者**である。この2行だけは製品の利用者を指さない。

---

## 3. サービス間契約(`/api/internal/**`) — `INT`

`/api/internal/**` は**利用者から直接呼ばれない**。gateway は公開せず、サービス間の同期呼び出し
だけが使う([SYNC_SERVICE_CALLS.md](SYNC_SERVICE_CALLS.md))。したがって「利用者から見た価値」を
持たず、受け入れテストの対象ではない。**契約テスト(サービス側)の担当**である。

ここに列挙するのは、受け入れテストが失敗したときに「どのサービス間契約を疑うか」を引くため。
`.feature` は書かない。

| 機能ID | 契約 | 呼び元 → 呼び先 | 支える利用者機能 |
| --- | --- | --- | --- |
| AC-INT-001 | `/api/internal/ai/generate` | 各サービス → ai | AC-AI-001〜007 |
| AC-INT-002 | `/api/internal/ai/generation-jobs` | 各サービス → ai | AC-AI-011, AC-LOG-006 |
| AC-INT-003 | `/api/internal/ai/projects/{id}/brave-search-api-key` | ai内部 | AC-AI-006, AC-AI-010 |
| AC-INT-004 | `/api/internal/ai/projects/{id}/existing-categories`, `/existing-tags` | ai → publishing | AC-PLAN-012 |
| AC-INT-005 | `/api/internal/analytics/projects/{id}/google-analytics`, `/adsense` | 各サービス → analytics | AC-ANA-001〜006 |
| AC-INT-006 | `/api/internal/content/posts` 系 | publishing → content | AC-POST-004〜008 |
| AC-INT-007 | `/api/internal/content/preview-skeleton/**`(#1564 で削除) | publishing → content | (旧 AC-POST-009) |
| AC-INT-008 | `/api/internal/content/projects/{id}/content-settings` | 各サービス → content | AC-TAG-013 |
| AC-INT-009 | `/api/internal/content/render/**` | publishing → content | AC-POST-004, AC-IMG-001 |
| AC-INT-010 | `/api/internal/identity/**` | 各サービス → identity | AC-USR-008, AC-LOG-007 |
| AC-INT-011 | `/api/internal/media/projects/{id}/article-image-long-edge-px` | publishing → media | AC-IMG-011 |
| AC-INT-012 | `/api/internal/platform/**` | 各サービス → platform | AC-SYS-005, AC-AI-008 |
| AC-INT-013 | `/api/internal/project/projects/**`, `/sites/**` | 各サービス → project | AC-PRJ-*, AC-SITE-* |
| AC-INT-014 | `/api/internal/project/sites/{siteKey}/credentials` | publishing → project | AC-POST-004 |
| AC-INT-015 | `/api/internal/project/tag-design/{tagType}` | content → project | AC-TAG-014 |
| AC-INT-016 | `/api/internal/project/cms/**` | project → publishing | AC-SITE-004〜009 |
| AC-INT-017 | `/api/internal/publishing/sites/{site}/media`, `/projects/{id}/media-scan` | media → publishing | AC-IMG-013, AC-BULK-013 |
| AC-INT-018 | `/api/internal/publishing/sites/{siteKey}/authors` | identity → publishing | AC-USR-008 |

---

## 4. 対象外

受け入れテストを書かないと決めた機能。**理由を必ず書く**。
「面倒だから」は理由にならない。ここに移すのは、利用者から見た受け入れ基準が定義できないか、
別の種類のテストが担当する場合だけ。

**AC-PERF-001 / AC-PERF-003 は #938 (AT-12) で対象外から外した。** #915 は
「受け入れ基準ではなく性能の閾値検証だから spec に残す」と判断していたが、#938 の受け入れ基準が
この2つを `.feature` のシナリオとして要求したため、そちらが新しい判断になる。移行先は
`e2e/features/custom-tag/performance.feature`(検証APIの応答時間 / タグ画面のページロード時間)で、
移行元の `apps/web/e2e/performance.spec.ts` は削除した。
**両シナリオとも外部LLMを呼ばない**ので、スタブ構成でも実LLM構成でも同じものを測る。

**#1476 で応答時間予算を画面・操作の全体へ広げた。** 対象一覧・分類・計測点は §10(AC-PERF-004)。
代表例は `e2e/features/response-budget/click-response-budget.feature`。

| 機能ID | 機能 | 対象外の理由 |
| --- | --- | --- |
| AC-PERF-002 | AI生成の応答時間 | 外部LLMの応答時間に依存し、受け入れ可否の判定に使えない。移行前の `performance.spec.ts` › `Ollamaレスポンス時間が10秒以内であること` は #938 で移行先を持たせずに削除した — 閾値10秒は当時の Ollama の実測に由来し、受け入れテストがLLMをスタブへ向ける現在の構成では**スタブの往復時間**を測ることにしかならないため。生成が成立すること自体は `e2e/features/custom-tag/generation.feature` が確かめる |
| AC-EXT-019 | `letsBlog.previewDevTools` | 開発者向けのデバッグ機能。利用者から見た受け入れ基準を持たない |
| AC-USR-012 | `POST /api/users/migrate-to-keycloak` | #566 のKeycloak移行時にのみ使う一度きりの移行操作。恒常的な利用者機能ではない |
| AC-USR-013 | `POST /api/users/reconcile-keycloak` | 同上(移行後の突き合わせ用の運用操作) |
| AC-SYS-010 | phpMyAdmin / RabbitMQ 管理UI / Penpot 管理UI | 本システムが提供する機能ではなく、同梱している第三者ツールの画面。それぞれの提供元が品質を担保する |
| AC-INT-001〜018 | `/api/internal/**` | 利用者から直接呼ばれないサービス間契約。契約テストの担当(§3) |

### 4.1 実装が仕様に届いていないため受け入れ基準を狭めた行

「実装の都合に合わせて基準を下げた」箇所は、下げたと分かる形で残す。
黙って基準を書き換えると、後から見たときに「もともとそれだけを求めていた」ように見えてしまう。

| 機能ID | 元の受け入れ基準 | 実装したもの | 理由 |
| --- | --- | --- | --- |
| AC-AUTH-005 | 期限切れのデバイスコードで `expired_token` が返る | 存在しないデバイスコードが拒否されることを検証 | 真に期限切れにするには Keycloak realm の `oauth2DeviceCodeLifespan`(既定600秒)を縮める必要がある。共有レルムの設定変更になるため見送った。拡張(`deviceAuth.ts`)が見ているのは「待ち続けずに失敗と分かること」なので、検証の目的は満たしている |
| AC-AUTH-008 | 期限切れアクセストークンで401 | 復号できないセッションで保護ページに入れないことを検証 | 有効な署名を持つ期限切れトークンは、realm のアクセストークン寿命(既定5分)を待つか設定を変えないと作れない。署名が不正なトークンの拒否は AC-AUTH-007 が別途検証している |

どちらも Keycloak の realm 設定を一時的に変更すれば検証できる。必要になった時点で、
設定変更と復元を含む `@slow` シナリオとして足すこと。

---

## 5. AT Issue と機能IDの対応

各 Issue が `検証済` にする責任を負う行。

| Issue | 領域 | 機能ID |
| --- | --- | --- |
| AT-0 (#926) | 受け入れテスト基盤 | (基盤のみ。AC-AUTH-001 をサンプルとして実装済み) |
| AT-1 (#927) | 本カタログ | (本ドキュメント) |
| AT-2 (#928) | 外部依存スタブ | `@stub` が付く全行の前提。土台自体の検証は `features/stubs/external-stubs.feature`(AC-STUB-001〜002) |
| AT-3 (#929) | 初回セットアップ・認証・セッション | AC-SET-001〜003, AC-AUTH-001〜010 |
| AT-4 (#930) | ユーザー・ロール・権限・メンバー | AC-USR-001〜011 |
| AT-5 (#931) | プロジェクト・環境・サイト | AC-PRJ-001〜009, AC-SITE-001〜011 |
| AT-6 (#932) | 執筆から公開までのジャーニー | AC-POST-001〜013 |
| AT-7 (#933) | 一括管理・環境間比較 | AC-BULK-001〜013 |
| AT-8 (#934) | AI執筆支援 | AC-AI-001〜014 |
| AT-9 (#935) | 記事プランとGitHub Issue連携 | AC-PLAN-001〜012 |
| AT-10 (#936) | 画像生成・ギャラリー・メディアGC | AC-IMG-001〜013(014 は #1492、015 は #1493 で追加。ギャラリーの一括削除・入れ子フォルダ) |
| AT-11 (#937) | ダイアグラムとレンダリング | AC-DIAG-001〜007 |
| AT-12 (#938) | カスタムタグ・テンプレート・コンテンツ設定 | AC-TAG-001〜021 |
| AT-13 (#939) | Analytics | AC-ANA-001〜006 |
| AT-14 (#940) | システム設定・バックアップ・拡張配布・ダッシュボード | AC-SYS-001〜009、AC-SYS-011(#1399) |
| AT-15 (#941) | ログと非同期経路 | AC-LOG-001〜010(008〜010 は #941 で追加。ログ経路の障害耐性・停止中のログの扱い・閲覧と認可) |
| AT-16 (#942) | VSCode拡張 | AC-EXT-001〜024(AC-EXT-019 は対象外) |
| AT-17 (#943) | 認可・ルーティング・レート制限・相関ID・縮退 | AC-XC-001〜010 |
| AT-18 (#944) | i18n・アクセシビリティ・レスポンシブ | AC-UX-001〜008 |
| AT-19 (#945) | クリーンスレート初期化と実行順序 | (実行基盤。全行の前提) |

---

## 6. 集計

§2 に列挙した機能ID: **223**。うち3件(AC-EXT-019、AC-POST-010・011)は対象外としたので、
受け入れテストの対象は **220**。

| 状態 | 件数 |
| --- | --- |
| `検証済` | 159 |
| `部分的に検証` | 3 |
| `実装中` | 13 |
| `実装済み` | 21 |
| `既存spec` / `既存spec(部分)` | 3 |
| `未着手` | 21 |
| `対象外`(§2 に行を持つもの) | 3 |
| **§2 合計** | **223** |

`実装済み` は §1 の状態の定義に無い。`AC-AI-*` の全20行がこの語を使っており、§1 の
どの状態に当たるかは各行を担当する Issue の判断である。ここでは行の値どおりに数える。

`検証済` のうち2件は §4.1 のとおり受け入れ基準を狭めてある。
`@fail`(不具合が直るまで失敗が期待値)のシナリオは無い(#955 の修正で最後の1件が外れた)。
`AC-EXT-011` / `016` は実装側・スタブ側の不具合(#1004 / #998)のため自動化できず
`未着手` のままにしてある——バグを期待値として固定しないため。

| 区分 | 件数 |
| --- | --- |
| サービス間契約 `AC-INT-*`(§3、受け入れテスト対象外) | 18 |
| §4 の対象外(`AC-INT-*` の一括行を除く) | 5 |

領域別の内訳:

| 領域 | 件数 | | 領域 | 件数 | | 領域 | 件数 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `SET` | 3 | | `AI` | 20 | | `SYS` | 10 |
| `AUTH` | 10 | | `PLAN` | 12 | | `LOG` | 10 |
| `USR` | 15 | | `IMG` | 15 | | `EXT` | 24 |
| `PRJ` | 9 | | `DIAG` | 7 | | `XC` | 11 |
| `SITE` | 11 | | `TAG` | 21 | | `UX` | 8 |
| `POST` | 15 | | `ANA` | 6 | | `STUB` | 2 |
| `BULK` | 14 | | | | | | |

**受け入れテストが1件も無い領域**: `BULK`(13 機能ID、全行 `未着手`)。
`AI` は全行が `実装済み` で、`検証済` の行は無い。

> この節の件数は `scripts/test_acceptance_criteria_summary.py` が §2 の表から数え直して
> 突き合わせる(#1133)。§2 の状態を変えたら、この節の件数も同じ変更で直すこと。
> 一致しないと `python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'` が失敗する。

入力ソースの網羅状況:

| ソース | 件数 | カバー状況 |
| --- | --- | --- |
| Web管理画面 (`apps/web/src/app/**/page.tsx`) | 24 ページ | 全ページが1つ以上の機能IDに対応(§7) |
| VSCode拡張 (`apps/extension/package.json`) | 24 コマンド | AC-EXT-001〜024 で1対1。全24コマンドが Layer 1 シナリオ / 単体テスト / 手動チェックリストのいずれかへ対応付け済み(§2.14) |
| 公開APIコントローラ | 48 クラス | 全クラスが1つ以上の機能IDに対応 |
| 内部ブリッジコントローラ | 18 クラス | AC-INT-001〜018(受け入れテスト対象外) |

> #927 の Background は Web を「23 ページ」としていたが、`find apps/web/src/app -name page.tsx`
> の実測は 24 ページ(2026-09-01)。本カタログは実測に合わせた。

---

## 7. Web画面と機能IDの対応

| 画面 | 機能ID |
| --- | --- |
| `/` | AC-SYS-001〜004 |
| `/login` | AC-AUTH-001〜004 |
| `/setup` | AC-SET-001〜003 |
| `/users` | AC-USR-001, AC-USR-002, AC-USR-005, AC-USR-006 |
| `/users/[id]/edit` | AC-USR-003, AC-USR-004, AC-USR-010 |
| `/admin/roles` | AC-USR-007 |
| `/admin/ssh-keys` | AC-SITE-010 |
| `/admin/system-settings` | AC-SYS-005, AC-SYS-006, AC-SYS-011 |
| `/admin/backup` | AC-SYS-007, AC-SYS-008 |
| `/admin/tag-design` | AC-TAG-014 |
| `/projects` | AC-PRJ-001, AC-PRJ-002 |
| `/projects/[id]` | AC-PRJ-003〜009, AC-AI-008〜010, AC-IMG-009〜012 |
| `/projects/[id]/dashboard` | AC-POST-013, AC-ANA-002, AC-ANA-006 |
| `/projects/[id]/plan` | AC-PLAN-001〜012 |
| `/projects/[id]/posts` | AC-POST-003 |
| `/projects/[id]/tags` | AC-TAG-001〜007, AC-TAG-012, AC-TAG-013 |
| `/projects/[id]/settings/google-analytics` | AC-ANA-001, AC-ANA-002 |
| `/projects/[id]/settings/adsense` | AC-ANA-003〜006 |
| `/posts` | AC-POST-002, AC-POST-004〜008 |
| `/sites` | AC-SITE-001〜006 |
| `/sites/[id]/edit` | AC-SITE-007〜009, AC-SITE-011 |
| `/custom-tag-templates` | AC-TAG-008〜011, AC-TAG-018〜021 |
| `/image-gallery` | AC-IMG-003〜008, AC-IMG-014, AC-IMG-015 |
| `/operation-logs` | AC-LOG-001〜005, AC-LOG-010 |

---

## 8. 保守のしかた

1. **機能を追加したら、この表に行を足す。** 行が無い機能は誰も検証していないのと同じ。
2. **機能IDは再利用しない。** 廃止した機能の行は削除せず `対象外(廃止: #<Issue>)` にする。
3. **`.feature` を実装したら、対応シナリオ列とその行の状態を同じPRで更新する。**
   ドキュメントの更新を別PRに残すと、必ず乖離する。
4. **調査中に実装とドキュメントの乖離を見つけたら、直さずIssueを起票する**
   (`.claude/CLAUDE.md` → Scope Control)。
5. **§10 の対象一覧に行を足したら、同じ変更で予算のシナリオ(`@budget-page:` / `@budget-action:`)を足す。**
   一覧とシナリオの不一致は `scripts/check-response-budget-coverage.py` が検出する(§10.7)。

## 9. 参考

- [ACCEPTANCE_TESTING.md](ACCEPTANCE_TESTING.md) — 記述形式・タグ規約・実行方法
- [e2e-testing.md](e2e-testing.md) — 実行環境の前提
- [AUTHORIZATION_MATRIX.md](AUTHORIZATION_MATRIX.md) — AC-XC-001 の入力
- [SYNC_SERVICE_CALLS.md](SYNC_SERVICE_CALLS.md) — §3 の入力
- Epic #925 / AT-1 #927

---

## 10. 応答時間予算(AC-PERF-004) — 3秒予算の対象一覧

利用者の要望(2026-09-29): 「すべてのクリックに対する応答を3秒以内にする」(issue #1476)。
`AC-PERF-001` / `AC-PERF-003`(`e2e/features/custom-tag/performance.feature`)が先行して実在する
ハードゲートで、本節はそれを**画面・操作の全体へ広げるための対象一覧**である。
シナリオを全対象へ広げる作業は #1477、3秒を構造的に満たせない操作の非同期化は #1478 が担う。

- 範囲: **`app/**/page.tsx` の全27ページの初回表示**と、**`export async function *Action` の全120件**。
  「クリック」単位の粒度は Gherkin 上に存在しない(「クリック」を含むステップは5件)ため、
  クリックではなく「ページの初回表示」と「Server Action」を単位にした。
- 一覧は `find apps/web/src/app -name page.tsx` と
  `grep -rn "export async function [A-Za-z]*Action" apps/web/src/app` から作った。ページ・Action を
  足したら、この節に行を足す(§8 の 1)。コードと一覧の一致は `scripts/check-budget-target-list.py` が検査し、
  どちらかにしか無いページ・Action、見出しの件数のずれがあると落ちる(#1544。`python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'` の実リポジトリ照合テストも同じ検査をする)。

### 10.1 分類

| 分類 | 意味 |
| --- | --- |
| 予算対象 | 3秒(または表の閾値)を超えたらシナリオが落ちる。ハードゲート(`AC-PERF-001/003` と同じ) |
| 非同期ハンドオフ待ち(#1478) | 構造的に3秒を満たせない。受付応答まで返す非同期ハンドオフを #1478 が実装するまで、予算を課さない |
| 予算対象外 | 予算を課さない。**理由を必ず添える**(`AC-PERF-002` と同じ) |

利用者の判断(2026-09-29)による6操作の扱い:

| 操作 | 分類 | 根拠 |
| --- | --- | --- |
| サイト自動構築 | 非同期ハンドオフ待ち(#1478) | Web画面から起こす長時間の書き込み。gateway 60秒で現に応答が失われる |
| 環境間同期 | 非同期ハンドオフ待ち(#1478) | 同上 |
| 一括管理の環境間比較 | 予算対象 | 読み取りで、ページの初回描画を止めている形。#1474(並列化)+ #1475(`loading.tsx`)で3秒を狙う。**達成できなかった場合に非同期化を再判断する** |
| 記事プレビュー | 予算対象外 | VSCode拡張専用の経路(web からの呼び出し0件)で、対象範囲の外。高速化は #1473 / #1369 が担う |
| 実WordPressへの記事公開 | 予算対象外 | 同じくVSCode拡張専用。拡張側の進捗表示で対応する |
| WordPressへの大容量アップロード | 予算対象外 | wp-admin 自身の「メディアの新規追加」画面での操作で、当社のコードを通らない。`AC-SYS-010`(第三者ツールの画面)と同じ分類 |

### 10.2 計測点(共通ステップ `e2e/support/responseBudget.ts` / `responseBudget.steps.ts`)

| 記号 | 計測点 | 使う場面 |
| --- | --- | --- |
| ① 画面の初回表示 | **2回目の `page.goto` の開始から完了まで。** 1回目の遷移は Next.js(devモード)のルートコンパイルを含むので捨てる。受け入れテスト環境の web コンテナが `next dev`(`npm run dev`)で動く(`docker-compose.yml:126`)ことが前提。本番ビルドで動かす構成にしたら、このウォームアップの要否を見直すこと | 全ページ |
| ② Server Action の往復 | **`next-action` ヘッダを持つ POST のリクエスト送出からレスポンス本文の受信完了まで。** 複数回送られたら最も遅い1往復 | 全 Server Action |
| ③ API の応答 | 要求の送出から全応答の受信完了まで(`AC-PERF-001`) | `@api` シナリオ |

### 10.3 `retryClick` 経由の操作の計測点(②)

`retryClick`(`clickUntilVisible` 等)は、ハイドレーション前の空振りを撃ち直すため、クリックから期待結果までの
経過時間に「再試行回数 x 可視待ちタイムアウト(既定3秒)」が混入し、応答時間の指標にならない。
だからといって予算の対象から外さず、**計測点を別に取る**: `page.on('request')` /
`'requestfinished'` で **サーバへ実際に送られた Server Action の POST だけ**を捉え、その往復を測る。
空振りしたクリックはサーバへ何も送らないので、再試行分は構造的に含まれない。
「最後のクリックから期待結果まで」を採らなかったのは、最後のクリックがどれかを知る手段が無く、
Reactの再描画時間まで含めてしまうため。`measureServerActionRoundTrip` が単体テスト
(`apps/web/e2e/responseBudget.test.ts`)で「trigger に6秒の空振りがあっても往復400msと測る」ことを確かめている。

**注意(プログレッシブエンハンスメント)**: ハイドレーション前にフォームが送信されると、`next-action` ヘッダを持たない通常のフルページ POST になり、この計測では数えない(Server Action の往復として捕捉されない)。その場合、`measureServerActionRoundTrip` は往復を計測できずに失敗する(サイレントに通らない)。ハイドレーション前の送信は `retryClick` の撃ち直しで次のクリックに委ねる。

### 10.4 画面の初回表示(全27ページ)

計測点: **ウォームアップ後の2回目の `page.goto` の完了まで**(§10.2 の①)。閾値 3,000ms は全行共通。

| 操作 | 計測点 | 閾値 | 閾値の根拠 | 分類 |
| --- | --- | --- | --- | --- |
| `/` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/admin/backup` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/admin/roles` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/admin/ssh-keys` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/admin/system-settings` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/admin/tag-design` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/custom-tag-templates` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/image-gallery` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/login` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値。未ログインで到達でき、遷移先(Keycloak / `/login`)へのリダイレクトまでを含む | 予算対象 |
| `/login/error` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値。未ログインで到達できる静的なエラーページ(通信断でログインを開始できなかったときの行き先、#1392) | 予算対象 |
| `/operation-logs` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/operation-logs/slow` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値。遅い操作・経路の集計を表示する診断画面 | 予算対象 |
| `/posts` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/projects` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/projects/[id]` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値。初期表示で環境間比較(実測 11,012ms)を待つため現状は超過。#1474(並列化)+ #1475(`loading.tsx`)で達成する。**達成できなければ非同期化を再判断する** | 予算対象 |
| `/projects/[id]/article-review` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値。プロジェクトと、GitHub リポジトリを設定済みならレビュー待ち Pull Request を読むだけの画面(#1340)。**既定どおり予算対象**(#1544) | 予算対象 |
| `/projects/[id]/dashboard` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/projects/[id]/plan` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/projects/[id]/posts` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/projects/[id]/settings/adsense` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/projects/[id]/settings/google-analytics` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/projects/[id]/tags` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/setup` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値。未ログインで到達でき、遷移先(Keycloak / `/login`)へのリダイレクトまでを含む | 予算対象 |
| `/sites` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/sites/[id]/edit` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/users` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |
| `/users/[id]/edit` の初回表示 | ① 2回目の `page.goto` 完了まで | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。既存の AC-PERF-003(タグ画面)と同じ値 | 予算対象 |

### 10.5 Server Action(全121件)

計測点: **Server Action の POST の往復**(§10.2 の②)。`retryClick` 系を通る操作かどうかに関わらず同じ計測点を使う。

| 操作(Server Action) | 計測点 | 閾値 | 閾値の根拠 | 分類 |
| --- | --- | --- | --- | --- |
| `app/admin/backup/actions.ts`<br>`restoreBackupAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。外部システム(WordPress / SSH / 複数環境)への往復を含み**超過しうる**。#1477 の実測で超過した場合は再判断する | 予算対象 |
| `app/admin/roles/actions.ts`<br>`assignRoleAction`<br>`removeRoleAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作 | 予算対象 |
| `app/admin/ssh-keys/actions.ts`<br>`createSshKeyPairAction`<br>`deleteSshKeyPairAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作 | 予算対象 |
| `app/admin/system-settings/actions.ts`<br>`updateAppSettingsAction`<br>`applyComputeDeviceAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作。`applyComputeDeviceAction` は適用を**受け付けた時点で返る**(platform-service が裏でコンテナの停止・起動・成功判定を進め、進行は別に取得する。#1399)ので、切り替えの完了までの時間(最大180秒)は含まない | 予算対象 |
| `app/custom-tag-templates/actions.ts`<br>`createCustomTagTemplateAction`<br>`updateCustomTagTemplateAction`<br>`publishCustomTagTemplateAction`<br>`unpublishCustomTagTemplateAction`<br>`cloneCustomTagTemplateAction`<br>`applyCustomTagTemplateAction`<br>`deleteCustomTagTemplateAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作 | 予算対象 |
| `app/custom-tags/actions.ts`<br>`generateCustomTagAction` | — | — | カスタムタグのAI生成を非同期ジョブとして要求する受付だけの操作になった(#1409。生成と同時に保存はせず、結果は処理キューの「結果を見る」から確認して `upsertProjectCustomTagAction` で保存する)。生成そのものは外部LLM / 画像生成の応答時間に依存する。AC-PERF-002 と同じく、受け入れテストがスタブへ向く構成では**スタブの往復時間**しか測れず、受け入れ可否の判定に使えない。生成の成立は `ai/`・`custom-tag/generation.feature` 等が確かめる。長時間の生成は #1404 のキューが担う(画像生成の同期経路 `generateProjectImagesAction` は #1408 で非同期ジョブ `requestProjectImageJobAction` に置き換わり、削除済み)。**この分類は利用者の判断表(6操作)には無かったが、利用者が承認(2026-10-01)した** | 予算対象外 |
| `app/custom-tags/actions.ts`<br>`validateCustomTagAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作 | 予算対象 |
| `app/image-gallery/actions.ts`<br>`getGeneratedImageAction`<br>`fetchGalleryImagesPageAction`<br>`deleteGeneratedImageAction`<br>`bulkDeleteGeneratedImagesAction`<br>`updateGeneratedImageTagsAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作 | 予算対象 |
| `app/image-gallery/actions.ts`<br>`createGeneratedImageFolderAction`<br>`setGeneratedImageFolderAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。画像のフォルダ作成・割り当て。データの読み書きが gateway を1〜数往復するだけの操作(利用者が予算対象と決定、2026-10-06、#1623) | 予算対象 |
| `app/infoRailActions.ts`<br>`fetchQueueJobsAction`<br>`fetchRecentOperationLogsAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。全画面の情報表示レールが処理キュー・操作ログを gateway から読むだけの操作(#1407)。**既定どおり予算対象**(#1544) | 予算対象 |
| `app/login/actions.ts`<br>`startNoJsLoginAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作 | 予算対象 |
| `app/operation-logs/actions.ts`<br>`copyOperationTraceAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作 | 予算対象 |
| `app/projects/[id]/actions.ts`<br>`bindEnvironmentAction`<br>`unbindEnvironmentAction`<br>`updateProjectNameAction`<br>`fetchProjectConnectionsAction`<br>`updateProjectConnectionAction`<br>`setClaudeApiKeyAction`<br>`clearClaudeApiKeyAction`<br>`setOpenAiApiKeyAction`<br>`clearOpenAiApiKeyAction`<br>`updateMasterEnvironmentAction`<br>`updateProjectGithubRepositoryAction`<br>`updateImageGenerationPromptDefaultsAction`<br>`updateImageGenerationSizeDefaultsAction`<br>`updateImageContentFilterSettingsAction`<br>`updateArticleImageResizeDefaultAction`<br>`setProjectGithubTokenAction`<br>`clearProjectGithubTokenAction`<br>`setProjectBraveSearchApiKeyAction`<br>`clearProjectBraveSearchApiKeyAction`<br>`setProjectGoogleAnalyticsClientAction`<br>`selectProjectGoogleAnalyticsPropertyAction`<br>`clearProjectGoogleAnalyticsCredentialsAction`<br>`setProjectAdSenseSettingsAction`<br>`selectProjectAdSenseAccountAction`<br>`clearProjectAdSenseCredentialsAction`<br>`addProjectUserAction`<br>`updateProjectUserRoleAction`<br>`removeProjectUserAction`<br>`syncProjectUserAction`<br>`fetchLlmModelsAction`<br>`selectLlmModelAction`<br>`fetchLlmProviderAction`<br>`selectLlmProviderAction`<br>`fetchReviewStepSettingsAction`<br>`updateReviewStepSettingAction`<br>`fetchImageProviderAction`<br>`selectImageProviderAction`<br>`fetchComfyUiCheckpointsAction`<br>`selectComfyUiCheckpointAction`<br>`deleteComfyUiCheckpointAction`(受け入れシナリオから到達できない: 画面の削除ボタンは ComfyUI が返す一覧の選択中でないチェックポイントに押せ、押すと media サービスが ComfyUI と共有するモデル領域のそのファイルを実際に削除する。安全に消せる検証用のファイルを一覧へ出す手段が無い。ComfyUI スタブの一覧は固定の2件で導入したファイルは現れず、実機で導入から削除まで通せるのは @requires-gpu の comfyui-checkpoints.feature だけで予算検証の対象にできず、一覧の固定の名前を消すと実機では本物のモデルを消してしまう。根拠は ComfyUiCheckpointStorageService.deleteCheckpoint と infra/e2e-stubs/comfyui/server.js の CHECKPOINTS)<br>`fetchGenerationJobAction`<br>`fetchMediaGarbageScanAction`<br>`fetchImageGenerationOptionsAction`<br>`fetchGeneratedImagesAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作 | 予算対象 |
| `app/projects/[id]/actions.ts`<br>`syncEnvironmentAction`(環境間同期) | ② Server Action POST の往復(ハンドオフ後は受付応答まで) | 3,000ms(ハンドオフ後の受付応答に対して) | Web画面から起こす長時間の書き込み。gateway 60秒で現に応答が失われる。#1478 が非同期ハンドオフを実装するまで予算を課さない | 非同期ハンドオフ待ち(#1478) |
| `app/projects/[id]/actions.ts`<br>`fetchTermComparisonAction`<br>`deleteMediaGarbageAction`<br>`fetchPostComparisonAction`<br>`fetchPostStatusesAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。外部システム(WordPress / SSH / 複数環境)への往復を含み**超過しうる**。#1477 の実測で超過した場合は再判断する | 予算対象 |
| `app/projects/[id]/actions.ts`<br>`applyToEnvironmentAction`<br>`applyToAllEnvironmentsAction`<br>`syncAllTermsToMasterAction`<br>`runBulkOperationUploadAction`<br>`fetchStatusComparisonAction`<br>`reconcileStateAction`<br>`syncTermToMasterAction`<br>`editTermAndSyncAction`<br>`deleteTermEverywhereAction`<br>`deleteSlugEverywhereAction`<br>`deletePostEverywhereAction`<br>`updatePostStatusEverywhereAction`<br>`uploadProjectAssetImageAction` | ② Server Action POST の往復(ハンドオフ後は受付応答まで) | 3,000ms(ハンドオフ後の受付応答に対して) | 利用者の判断(2026-10-01)で再分類。複数環境への逐次の WordPress REST 呼び出しを含み、#1477 の実測で3秒を超えた(#1552)。構造的に3秒に収まらないため、サイト自動構築・環境間同期と同じく #1478 が非同期ハンドオフを実装するまで予算を課さない | 非同期ハンドオフ待ち(#1478) |
| `app/projects/[id]/actions.ts`<br>`fetchAiConnectionsAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。**既定どおり予算対象**(#1544)。ただし ai-service の疎通確認を呼び、`AiConnectionService` はプロバイダーごとに最大3秒のタイムアウトを持つため、到達できないプロバイダーがあると**構造的に3秒を超えうる**。受け入れ環境で超過が観測され分類の変更(予算対象外 / 非同期ハンドオフ待ち)が要るときは、利用者の判断を仰ぐ(分類の例外は利用者が決める) | 予算対象 |
| `app/projects/[id]/actions.ts`<br>`installComfyUiCheckpointAction`(ComfyUI チェックポイントの導入) | ② Server Action POST の往復(ハンドオフ後は受付応答まで) | 3,000ms(ハンドオフ後の受付応答に対して) | Web画面から起こす長時間の書き込み。数GBのモデルを外部から取得し、`media/comfyui-checkpoints.feature` は `@timeout:600000`。サイト自動構築と同じ形で、利用者の判断表(6操作)には無かったが、利用者が承認(2026-10-01)した。#1478 が非同期ハンドオフを実装するまで予算を課さない | 非同期ハンドオフ待ち(#1478) |
| `app/projects/[id]/actions.ts`<br>`generateImagePromptAction` | — | — | 外部LLM / 画像生成の応答時間に依存する。AC-PERF-002 と同じく、受け入れテストがスタブへ向く構成では**スタブの往復時間**しか測れず、受け入れ可否の判定に使えない。生成の成立は `ai/`・`custom-tag/generation.feature` 等が確かめる。長時間の生成は #1404 のキューが担う。**この分類は利用者の判断表(6操作)には無かったが、利用者が承認(2026-10-01)した** | 予算対象外 |
| `app/projects/[id]/actions.ts`<br>`requestProjectImageJobAction`<br>`fetchImageJobResultAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。画像生成を非同期ジョブとして**受け付けるまで**の操作と、完了したジョブの結果の画像IDを読む操作(#1408)。生成そのものは #1404 のキューが担い、これらは受付と読み取りだけ(利用者が予算対象と決定、2026-10-06、#1623) | 予算対象 |
| `app/projects/[id]/actions.ts`<br>`uploadGeneratedImageAction` | — | — | 生成画像のアップロード。ファイルの大きさに応じて時間が延びるため、3秒の予算では測れない。**利用者が予算対象外と決定した(2026-10-06、#1623)** | 予算対象外 |
| `app/projects/[id]/custom-tags/actions.ts`<br>`updateProjectCssSelectorPrefixAction`<br>`upsertProjectCustomTagAction`<br>`deleteProjectCustomTagAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作 | 予算対象 |
| `app/projects/[id]/tag-design/actions.ts`<br>`saveTagDesignSettingAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作 | 予算対象 |
| `app/projects/[id]/tag-design/actions.ts`<br>`generateTagDesignAction` | — | — | タグデザインのAI生成を非同期ジョブとして要求する受付だけの操作になった(#1409。結果は処理キューの「結果を見る」から確認して `saveTagDesignSettingAction` で保存する)。生成そのものは外部LLM / 画像生成の応答時間に依存する。AC-PERF-002 と同じく、受け入れテストがスタブへ向く構成では**スタブの往復時間**しか測れず、受け入れ可否の判定に使えない。生成の成立は `ai/`・`custom-tag/generation.feature` 等が確かめる。長時間の生成は #1404 のキューが担う。**この分類は利用者の判断表(6操作)には無かったが、利用者が承認(2026-10-01)した** | 予算対象外 |
| `app/projects/actions.ts`<br>`createProjectAction`<br>`deleteProjectAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作 | 予算対象 |
| `app/setup/actions.ts`<br>`setupAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作 | 予算対象 |
| `app/sites/[id]/edit/actions.ts`<br>`updateSiteAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作 | 予算対象 |
| `app/sites/actions.ts`<br>`getLetsblogPluginStatusAction`<br>`getLetsblogSyncAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。サイト編集画面が letsblog プラグインの導入状態と同期状態を**読んで表示する**だけの操作(利用者が予算対象と決定、2026-10-06、#1623)。受け入れ環境では到達できない SSH 先のため、導入状態の取得は失敗として返る往復も測る | 予算対象 |
| `app/sites/actions.ts`<br>`installLetsblogPluginAction`<br>`resyncLetsblogAction` | — | — | 外部の WordPress サイトへの letsblog プラグインの導入(外部システムへの書き込みで、時間が読めない)と、再同期(対象量と外部システムの応答に依存する)。**利用者が予算対象外と決定した(2026-10-06、#1623)** | 予算対象外 |
| `app/sites/actions.ts`<br>`registerSiteAction`<br>`generateSshKeyPairAction`<br>`deleteSiteAction`<br>`checkSiteConnectionAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作 | 予算対象 |
| `app/sites/actions.ts`<br>`createManagedWordPressSiteAction`(サイト自動構築) | ② Server Action POST の往復(ハンドオフ後は受付応答まで) | 3,000ms(ハンドオフ後の受付応答に対して) | Web画面から起こす長時間の書き込み。gateway 60秒で現に応答が失われる。#1478 が非同期ハンドオフを実装するまで予算を課さない | 非同期ハンドオフ待ち(#1478) |
| `app/sites/actions.ts`<br>`installWpCliAction`<br>`generateStaticContentAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。外部システム(WordPress / SSH / 複数環境)への往復を含み**超過しうる**。#1477 の実測で超過した場合は再判断する | 予算対象 |
| `app/sites/actions.ts`<br>`saveStaticContentAction` | — | — | 静的コンテンツの生成結果を確認した利用者の「保存」(#1409。既存の `static_content` への書き込み)。生成は `generateStaticContentAction` が非同期ジョブとして受け付ける。受け入れシナリオ(`@budget-action`)が未整備のため、予算対象に加える判断は #1661 で行う。 | 予算対象外 |
| `app/users/[id]/edit/actions.ts`<br>`updateUserProfileAction`<br>`uploadAvatarAction`<br>`updatePreferencesAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作 | 予算対象 |
| `app/users/actions.ts`<br>`createUserAction`<br>`deleteUserAction` | ② Server Action POST の往復 | 3,000ms | 利用者要望「すべてのクリックに対する応答を3秒以内」。データの読み書きが gateway を1〜数往復するだけの操作 | 予算対象 |

### 10.6 一覧に載せない操作(予算対象外)

Server Action でも画面でもないため、上の表には行を持たないが、利用者の判断(2026-09-29)で分類を確定した操作:
記事プレビュー(旧 `/api/preview/skeleton`。現在は署名付きURLの発行 `/signed-url`)・実WordPressへの記事公開(`/api/posts/publish`、実測 4,188ms)は
VSCode拡張専用で**予算対象外**、WordPressへの大容量アップロードは wp-admin 自身の画面での操作で**予算対象外**
(理由は §10.1 の表)。一括管理の環境間比較は §10.4 の `/projects/[id]` と、§10.5 の
`fetch*ComparisonAction` の**予算対象**として扱う。

### 10.7 一覧とシナリオの照合(#1477)

一覧(§10.4 / §10.5)の「予算対象」の全行にシナリオがあることは、人の目ではなく
`scripts/check-response-budget-coverage.py` が検証する。シナリオは、直前のタグで検証対象を宣言する。

| タグ | 意味 | 値 |
| --- | --- | --- |
| `@budget-page:<経路>` | §10.4 の画面の初回表示を検証する | 一覧の表記のまま。例 `/projects/[id]/plan` |
| `@budget-action:<関数名>` | §10.5 の Server Action を検証する | 例 `updateProjectNameAction` |

- 宣言はシナリオ(シナリオアウトライン)の直前にだけ置く。1つのシナリオが複数を宣言してよい。
  機能・背景・`例:` の直前に置いたタグは数えず、置き間違いとして失敗にする。
- 失敗する不一致は3つ: **予算対象の行にシナリオが無い** / **一覧に無いものを宣言している** /
  **予算対象でない行(予算対象外・非同期ハンドオフ待ち)を宣言している**。
- 既定は画面と Server Action の両方を見る。`--pages-only` / `--actions-only` は、片方ずつ段階的に
  緑にする間だけの絞り込みで、最終的な検証は既定で行う。
- **受け入れシナリオから到達できない Server Action** は、§10.5 の名前の直後に
  `(受け入れシナリオから到達できない: <理由>)` と注記する(分類は「予算対象」のまま)。照合はその行に
  シナリオを要求しない代わりに、**理由が空** / **予算対象でない行への注記** / **注記があるのにシナリオがある**
  (到達できるようになったのに注記が残っている)を失敗にする。黙って飛ばせない形にするための仕組みで、
  理由に `|`・`<br>` は使えず、理由の末尾を `)` で終えてはならない(注記の閉じ括弧と区別できないため)。到達できるようになったら注記を外してシナリオを置く。
- 一覧の解析は `scripts/response_budget_list.py` に切り出してあり、コードと一覧の照合(#1544)も
  同じ解析を使える。単体テストは `scripts/test_check_response_budget_coverage.py`
  (`python3 -m unittest discover -s scripts -t scripts -p 'test_*.py'`)。
- 宣言の置き場所: 画面は `e2e/features/response-budget/page-first-display.feature`(`/projects` と `/users` は
  `click-response-budget.feature`)。書式の説明は `page-first-display.feature` の冒頭にもある。
  `custom-tag/performance.feature` の「タグ画面のページロード」(AC-PERF-003)は独立した先行ゲートで、
  宣言を付けていない(§10.4 の `/projects/[id]/tags` は `page-first-display.feature` が持つ)。

### 10.8 再試行で通れば合格とする例外(#1554)

利用者の決定(2026-10-02): **3秒予算のシナリオは、再試行で通れば合格とみなす。** ホストのメモリ逼迫や
並行する受け入れテストで、一度だけ偶発的に3秒を超えることがあるため。

- **範囲**: `@response-budget` を持つ feature だけ。ほかのシナリオの再試行・flaky の扱い(ゼロ許容)は変えない。
- **再試行の回数**: 最大2回(feature 単位の `@retries:2`)。閾値(3000ms)・判定・手順は変えない。
  最初の試行と2回の再試行のすべてで超えた場合は、これまでどおり失敗(`unexpected`)になる。
- **付け忘れの検出**: `scripts/check-response-budget-coverage.py` が、`@response-budget` の feature に
  `@retries:2` が無い・ほかの feature やシナリオ単位に `@retries:` がある、を失敗にする。
- **記録先**: リリース検証(`scripts/release-verify-tag.py`)の実行ログと要約、タグの注釈に、再試行で通った
  予算シナリオの feature のパス・シナリオ名・失敗した試行の計測値(ms)を全件出す(0件なら0件)。
  `docs/ACCEPTANCE_TESTING.md` の「ゼロ許容」も参照。
