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
| AC-SET-001 | 初回セルフサインアップ (`/setup`) | 誰も居ないシステムに最初の管理者を作れる | ユーザー0人なら `/` は `/setup` へ誘導される。作成した管理者はそのアカウントでログインでき、管理者専用ページへ入れる(自動ログインはしない。#564) | `features/auth/setup.feature` › ユーザーが1人も居ないとトップページは初回セットアップへ誘導する / 初回セットアップで作った最初のユーザーは管理者としてログインできる | 検証済 |
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
| AC-USR-002 | ユーザー作成 | 新しいメンバーを迎え入れられる | `POST /api/users` でユーザーが作成され、一覧と Keycloak の双方に現れる | — | 未着手 |
| AC-USR-003 | ユーザー編集 (`/users/[id]/edit`) | 氏名・メール・所属を直せる | `PUT/PATCH /api/users/{id}` の変更が一覧へ反映される | — | 未着手 |
| AC-USR-004 | ユーザー削除 | 不要なアカウントを消せる | `DELETE /api/users/{id}` 後、そのユーザーではログインできない | — | 未着手 |
| AC-USR-005 | 無効化 / 再有効化 | 退職者を消さずに止められる | `deactivate` でログイン・API利用が拒否され、`reactivate` で戻る | — | 未着手 |
| AC-USR-006 | ロール付与 / 剥奪 | 権限を後から変えられる | `POST/DELETE /api/users/{userId}/roles/{roleName}` の結果が `/me/permissions` に反映される | — | 未着手 |
| AC-USR-007 | ロール一覧 (`/admin/roles`) | どんな権限セットがあるか分かる | `GET /api/roles` の内容が画面に表示される | — | 未着手 |
| AC-USR-008 | プロジェクトメンバー管理 | プロジェクト単位でアクセスを絞れる | `/api/projects/{id}/users` の追加・変更・削除が、そのユーザーの見えるプロジェクトに反映される | — | 未着手 |
| AC-USR-009 | 自分の参加プロジェクト一覧 | 自分に関係するプロジェクトだけ見える | `GET /api/project-users` が自分の所属だけを返す | — | 未着手 |
| AC-USR-010 | GitHubトークンの登録 | 記事プランのIssue連携が使える | `PUT /api/users/{id}/github-token` 後、Issue連携が成功する | — | 未着手 |
| AC-USR-011 | メタデータ(投稿ステータス/ロール)の取得 | 画面の選択肢がサーバー定義と一致する | `GET /api/metadata/post-statuses` と `/roles` が画面の選択肢と一致する | — | 未着手 |

### 2.3 プロジェクト・環境・サイト — `PRJ` / `SITE`

画面: `/projects`, `/projects/[id]`, `/projects/[id]/settings/*`, `/sites`, `/sites/[id]/edit`, `/admin/ssh-keys`
API: project `ProjectController`, `SiteController`, `SshKeyPairController`, `SiteStaticContentController`, `ProjectGithubTokenController`

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-PRJ-001 | プロジェクト一覧 (`/projects`) | 管理対象のブログを一覧できる | 一覧に自分が参加するプロジェクトが表示される | `e2e/post-creation.spec.ts` › Navigate to projects page and view project list / Project list displays project information / Project search/filter input is not implemented yet | 既存spec |
| AC-PRJ-002 | プロジェクト作成 | 新しいブログの管理を始められる | フォームから作成でき、一覧と詳細に反映される | `e2e/post-creation.spec.ts` › Project creation form is accessible / Create a new project with basic information | 既存spec |
| AC-PRJ-003 | プロジェクト詳細 (`/projects/[id]`) | 設定・サイト・記事をまとめて見られる | 作成時に入力した値が詳細画面に表示される(#913 の再発検知) | `e2e/post-creation.spec.ts` › View project details | 既存spec |
| AC-PRJ-004 | プロジェクト編集・削除 | 不要になったら消せる | `PUT/DELETE /api/projects/{id}` の結果が一覧に反映される | `e2e/post-creation.spec.ts` › Project row delete button is not implemented yet(UI未実装の確認のみ) | 既存spec(部分) |
| AC-PRJ-005 | 環境の追加・削除 | 本番と検証を分けて運用できる | `POST/DELETE /api/projects/{id}/environments` の結果が環境選択に現れる | — | 未着手 |
| AC-PRJ-006 | マスタ環境の指定 | どの環境を正とするか決められる | `PUT /api/projects/{id}/master-environment` の指定が比較・同期の基準になる | — | 未着手 |
| AC-PRJ-007 | 環境間の同期 | 検証環境を本番に揃えられる | `POST /api/projects/{id}/environments/sync` 後、差分が解消する | — | 未着手 |
| AC-PRJ-008 | GitHubリポジトリの紐付け | 記事プランをIssueと連携できる | `PUT /api/projects/{id}/github-repository` 後、Issue一覧が取得できる | — | 未着手 |
| AC-PRJ-009 | GitHubトークン(プロジェクト) | プロジェクト単位で連携先を分けられる | `PUT/DELETE /api/projects/{projectId}/api-keys/github-token` の結果が連携の成否に反映される | — | 未着手 |
| AC-SITE-001 | サイト一覧 (`/sites`) | 公開先を一覧できる | 一覧に登録済みサイトと接続状態が表示される | `e2e/site-registration.spec.ts` › Navigate to sites page and view site list / Sites page displays connection status controls for the fixture site | 既存spec |
| AC-SITE-002 | サイト検索 | 多数のサイトから目的の1件を探せる | 検索語で一覧が絞り込まれる | `e2e/site-registration.spec.ts` › Search filters the site list down to the fixture site | 既存spec |
| AC-SITE-003 | 既存WordPressの登録 | 手持ちのブログを繋げられる | `POST /api/sites` で登録でき、一覧に現れる | `e2e/site-registration.spec.ts` › Site creation form is accessible(フォーム到達のみ) | 既存spec(部分) |
| AC-SITE-004 | ManagedWordPress の新規構築 | WordPressを自分で用意しなくてよい | `POST /api/sites/managed-wordpress` でサイトが構築され、公開URLが応答する | — | 未着手(`@slow`) |
| AC-SITE-005 | ManagedWordPress の引き取り | 既存のコンテナを管理下に置ける | `POST /api/sites/managed-wordpress/adopt` 後、通常のサイトとして操作できる | — | 未着手 |
| AC-SITE-006 | 接続確認 | 公開前に繋がるか確かめられる | `POST /api/sites/{id}/test-connection` の結果が画面の接続状態に反映される | `e2e/site-registration.spec.ts` › Test site connection for the fixture site | 既存spec |
| AC-SITE-007 | サイト編集・削除 (`/sites/[id]/edit`) | 認証情報やURLを直せる | `PUT/DELETE /api/sites/{id}` の結果が一覧に反映される | — | 未着手 |
| AC-SITE-008 | WP-CLI の導入 | 一括管理機能が使えるようになる | `POST /api/sites/{id}/install-wp-cli` 後、一括管理の操作が成功する | — | 未着手(`@slow`) |
| AC-SITE-009 | 再プロビジョニング | 壊れたサイトを作り直せる | `POST /api/sites/{id}/reprovision` 後、サイトが再び応答する | — | 未着手(`@slow` `@destructive`) |
| AC-SITE-010 | SSH鍵ペア管理 (`/admin/ssh-keys`) | 公開先へ鍵で安全に接続できる | `GET/POST/DELETE /api/ssh-key-pairs` の結果が一覧に反映され、サイト登録から選択できる | — | 未着手 |
| AC-SITE-011 | 静的コンテンツの生成 | サイト共通のCSS等を配布できる | `POST /api/sites/{siteId}/static-content/generate` 後、`GET` が生成物を返す | — | 未着手 |

### 2.4 執筆から公開まで — `POST`

画面: `/posts`, `/projects/[id]/posts`, `/projects/[id]/dashboard`
API: content `PostController` / publishing `PostController`, `ArticlePreviewController`, `TaxonomyController` / content `ArticlePreviewController`

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-POST-001 | 主要ジャーニー(サイト登録→公開→履歴) | 記事を書いて公開するという中心的な価値が成立する | サイトを登録して記事を公開すると投稿履歴に表示される | `e2e/main-scenario.spec.ts` › サイトを登録して記事を公開すると投稿履歴に表示される | 既存spec |
| AC-POST-002 | 投稿履歴一覧 (`/posts`) | 何をいつ公開したか追える | `GET /api/posts` の内容が一覧に表示される | — | 未着手 |
| AC-POST-003 | プロジェクト別の記事一覧 (`/projects/[id]/posts`) | プロジェクト単位で記事を管理できる | 該当プロジェクトの記事だけが表示される | — | 未着手 |
| AC-POST-004 | 記事の公開 | 書いた記事が実際にブログに載る | `POST /api/posts/publish` 後、公開先URLで記事が閲覧できる | — | 未着手(`@slow`) |
| AC-POST-005 | 記事の更新 | 公開後に直せる | 同じ記事を再公開すると、公開先の本文が更新され重複投稿されない | — | 未着手(`@slow`) |
| AC-POST-006 | 予約公開 | 書いた日と公開日を分けられる | 予約した記事は指定時刻まで公開されず、時刻到来後に公開される | — | 未着手 |
| AC-POST-007 | 記事の削除 | 誤って出した記事を取り下げられる | `DELETE /api/posts/{site}/{wpPostId}` 後、公開先で記事が閲覧できなくなる | — | 未着手(`@destructive`) |
| AC-POST-008 | スラッグによる記事取得 | 公開済み記事を一意に特定できる | `GET /api/posts/{site}/by-slug/{slug}` が該当記事を返す | — | 未着手 |
| AC-POST-009 | プレビュー(骨組み+差し込み) | 公開前に実際の見た目を確認できる | `POST /api/projects/{projectId}/preview/skeleton` と `/render` で、公開先テーマ相当の見た目が得られる | — | 未着手 |
| AC-POST-010 | プレビュー用テーマCSSの取得 | プレビューが公開先の見た目と一致する | `GET /api/projects/{projectId}/preview/theme-css` が公開先のCSSを返す | — | 未着手 |
| AC-POST-011 | プレビュー投稿の後始末 | プレビューの残骸が公開先に残らない | `DELETE /api/projects/{projectId}/preview/preview-post` 後、公開先にプレビュー記事が残らない | — | 未着手 |
| AC-POST-012 | カテゴリ・タグの解決 | 記事に付けた分類が公開先で正しく紐付く | `POST /api/taxonomy/resolve` が既存分類に解決し、無ければ作成する | — | 未着手 |
| AC-POST-013 | プロジェクトダッシュボード (`/projects/[id]/dashboard`) | プロジェクトの状況を一目で掴める | 記事数・公開状況・Analytics サマリが表示される | — | 未着手 |

### 2.5 一括管理・環境間比較 — `BULK`

API: publishing `BulkManagementController`(23エンドポイント)

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-BULK-001 | カテゴリの環境間比較 | どの環境に何が無いか分かる | `GET /bulk-management/categories/comparison` が環境ごとの差分を返す | — | 未着手 |
| AC-BULK-002 | タグの環境間比較 | 同上(タグ) | `GET /bulk-management/tags/comparison` が差分を返す | — | 未着手 |
| AC-BULK-003 | 記事の環境間比較 | 同上(記事) | `GET /bulk-management/posts/comparison` が差分を返す | — | 未着手 |
| AC-BULK-004 | プラグインの環境間比較 | 環境間の構成差を把握できる | `GET /bulk-management/plugins/comparison` が差分を返す | — | 未着手 |
| AC-BULK-005 | テーマの環境間比較 | 同上(テーマ) | `GET /bulk-management/themes/comparison` が差分を返す | — | 未着手 |
| AC-BULK-006 | カテゴリの同期 | 環境を手作業で揃えなくてよい | `sync` / `sync-all` / `edit-sync` 後、比較の差分が解消する | — | 未着手 |
| AC-BULK-007 | タグの同期 | 同上(タグ) | 同上 | — | 未着手 |
| AC-BULK-008 | プラグイン/テーマの調整 | 環境の構成を揃えられる | `plugins/reconcile` `themes/reconcile` 後、比較の差分が解消する | — | 未着手(`@slow`) |
| AC-BULK-009 | 一括削除 | 検証環境を作り直せる | `categories/delete-all` `tags/delete-all` `posts/delete-all` `plugins/delete-all` `themes/delete-all` の実行後、対象が空になる | — | 未着手(`@destructive`) |
| AC-BULK-010 | 記事ステータスの一括変更 | 大量の記事をまとめて下書きに戻せる | `posts/status-update` 後、対象記事のステータスが変わる | — | 未着手 |
| AC-BULK-011 | 一括適用 | 変更を全環境へ一度に流せる | `bulk-management/apply` / `apply-all` が対象環境へ反映される | — | 未着手 |
| AC-BULK-012 | ファイルアップロード | 手元のファイルを公開先へ送れる | `bulk-management/upload` 後、公開先にファイルが存在する | — | 未着手 |
| AC-BULK-013 | 生成画像のアップロード | ギャラリーの画像を記事素材にできる | `POST /api/projects/{id}/asset-images/{generatedImageId}/upload` 後、公開先メディアに現れる | — | 未着手 |

### 2.6 AI執筆支援 — `AI`

API: ai `AiController`, `ProjectLlmModelController`, `ProjectBraveSearchApiKeyController`, `GenerationJobController`
画面: プロジェクト詳細のAI設定 / 拡張のコマンド群

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-AI-001 | 下書き生成 (`/api/ai/draft`) | 白紙から書き始めなくてよい | テーマを与えると記事の下書きが返る | — | 未着手(`@stub`) |
| AC-AI-002 | 質問 (`/api/ai/ask`) | 執筆中の疑問をその場で解ける | 質問に対する回答が返る | — | 未着手(`@stub`) |
| AC-AI-003 | タグ提案 (`/api/ai/tags`) | 分類を考える手間が減る | 本文からタグ候補が返る | — | 未着手(`@stub`) |
| AC-AI-004 | 校正 (`/api/ai/proofread`) | 誤字や言い回しを直せる | 本文に対する指摘が返る | — | 未着手(`@stub`) |
| AC-AI-005 | セクション生成 (`/api/ai/section`) | 見出し単位で書き足せる | 見出しを与えるとその節の本文が返る | — | 未着手(`@stub`) |
| AC-AI-006 | Web検索付き質問 | 最新情報を踏まえた回答が得られる | Brave Search APIキー設定時、検索結果を根拠にした回答が返る | — | 未着手(`@stub`) |
| AC-AI-007 | 画像プロンプト生成 | 記事に合う画像を頼みやすい | `POST /api/projects/{projectId}/ai/generate-image-prompt` が本文に沿ったプロンプトを返す | — | 未着手(`@stub`) |
| AC-AI-008 | LLMプロバイダの選択 | 用途に応じてAIを切り替えられる | `PUT /ai-models/llm/provider/selection` の選択が以後の生成に使われる | — | 未着手 |
| AC-AI-009 | LLMモデルの選択 | 精度とコストを選べる | `PUT /ai-models/llm/models/selection` の選択が以後の生成に使われる | — | 未着手 |
| AC-AI-010 | Brave Search APIキー管理 | 検索機能を自分の鍵で使える | `GET/PUT/DELETE /api-keys/brave-search-api-key` の結果が検索付き質問の可否に反映される | — | 未着手 |
| AC-AI-011 | 生成ジョブの照会 | 長い生成の進捗を追える | `GET /api/generation-jobs` と `/{id}` が状態(実行中/完了/失敗)を返す | — | 未着手 |

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
| AC-PLAN-001 | プラン対話 (`/article-plan/chat`) | 何を書くかをAIと詰められる | 対話が継続し、セッションとして保存される | `article-plan/planning-session.feature` › 企画チャットを開始すると新規セッションが作られ壁打ち一覧に現れる / 同じセッションで発言を続けると直前までの文脈が保持される / LLMが失敗してもセッションは壊れず再試行できる | 検証済(`@stub`) |
| AC-PLAN-002 | セッション一覧・復元 | 中断した検討を再開できる | `GET /sessions` と `/sessions/{id}` で過去の対話を復元できる | `article-plan/planning-session.feature` › 保存済みセッションを開き直すと過去のやり取りが復元される | 検証済 |
| AC-PLAN-003 | タイトル提案 | 見出しを考える手間が減る | `POST /suggest-titles` が複数の候補を返す | `article-plan/suggestions.feature` › タイトル案が複数返り、選んだものをIssueとして受理できる | 検証済(`@stub`) |
| AC-PLAN-004 | 構成提案 | 記事の骨組みを得られる | `POST /suggest-structure` が見出し構成を返す | `article-plan/suggestions.feature` › 構成案が見出し階層として返る | 検証済(`@stub`) |
| AC-PLAN-005 | メタデータ提案 | 分類とdescriptionを埋められる | `POST /suggest-metadata` がカテゴリ・タグ・説明文を返す | `article-plan/suggestions.feature` › メタデータ提案のカテゴリは公開先の既存カテゴリだけになる(`@api`) | 検証済(`@stub` `@api`。Web に導線が無い) |
| AC-PLAN-006 | プランの確定 | 決めた内容を記事へ引き継げる | `POST /accept` 後、その内容で執筆を開始できる | `article-plan/suggestions.feature` › タイトル案が複数返り、選んだものをIssueとして受理できる | 検証済(部分。Issue化までを見る。執筆への引き継ぎは AT-6 / #932) |
| AC-PLAN-007 | GitHub Issue一覧の取得 | 書くネタをIssueで管理できる | `GET /issues` が紐付けたリポジトリのIssueを返す | `article-plan/github-issues.feature` › GitHubトークンが設定されたプロジェクトでIssue一覧を取得できる / GitHubトークン未設定のプロジェクトでは設定不備と分かるエラーになる / 無効なGitHubトークンでは認証に失敗したと分かるエラーになる | 検証済(`@stub`) |
| AC-PLAN-008 | Issue本文の取得 | Issueの内容からプランを起こせる | `GET /issues/{issueNumber}/description` が本文を返す | `article-plan/github-issues.feature` › Issue本文を企画セッションの入力にできる | 検証済(`@stub`) |
| AC-PLAN-009 | Issueに紐づくセッション | Issueと検討履歴が対応する | `GET /sessions/by-issue/{issueNumber}` が該当セッションを返す | `article-plan/github-issues.feature` › Issue番号から壁打ちセッションを引き当てられる | 検証済(`@stub`) |
| AC-PLAN-010 | Issueへの構成反映 | 決めた構成をIssueに残せる | `POST /issues/{issueNumber}/accept-structure` 後、Issueに構成が書かれる | `article-plan/github-issues.feature` › 構成案をIssueへ反映するとGitHub側のIssueが更新される | 検証済(`@stub`) |
| AC-PLAN-011 | Issueの担当割当 | 誰が書くか決められる | `POST /issues/{issueNumber}/assign` 後、Issueの担当者が変わる | `article-plan/github-issues.feature` › Issueを担当者へ割り当てられる(`@api`) | 検証済(`@stub` `@api`。Web に導線が無い) |
| AC-PLAN-012 | 既存カテゴリ・タグの参照 | 公開先にある分類を再利用できる | `GET /categories` `/categories/hierarchy` `/tags` が公開先の既存分類を返す | `article-plan/suggestions.feature` › 既存カテゴリを親子構造として、既存タグを一覧として取得できる(`@api`) | 検証済(`@api`。Web に導線が無い) |

### 2.8 画像生成・ギャラリー・メディア — `IMG`

画面: `/image-gallery`
API: media `ImageGenerationController`, `GeneratedImageController`, `MediaController`, `ProjectImageModelController`, `ProjectImageSettingsController`, `ProjectMediaGarbageCollectionController`, `ComfyUiCheckpointController`

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-IMG-001 | 画像生成 (`POST /api/ai/image`) | 記事の挿絵を自分で用意しなくてよい | プロンプトから画像が生成され、ギャラリーに現れる | — | 未着手(`@slow` `@stub`) |
| AC-IMG-002 | 生成オプションの取得 | 選べる設定が画面に出る | `GET /api/ai/image-options` の内容が生成フォームの選択肢と一致する | — | 未着手 |
| AC-IMG-003 | ギャラリー一覧 (`/image-gallery`) | 生成済み画像を探せる | 生成済み画像が一覧に表示される | `e2e/image-upload.spec.ts` › Navigate to image gallery page / Image gallery displays the fixture generated image | 既存spec |
| AC-IMG-004 | 画像詳細 | どのプロンプトで作ったか分かる | 詳細モーダルに生成パラメータが表示される | `e2e/image-upload.spec.ts` › Image detail modal opens and shows generation parameters | 既存spec |
| AC-IMG-005 | 画像削除 | 不要な画像を消せる | `DELETE /api/generated-images/{id}` 後、一覧から消える | `e2e/image-upload.spec.ts` › Image deletion removes the fixture image from the gallery | 既存spec |
| AC-IMG-006 | 画像タグ編集 | 画像を分類して探しやすくできる | `PUT /api/generated-images/{id}/tags` の結果が一覧の絞り込みに反映される | `e2e/image-upload.spec.ts` › Search/filter input is not implemented on the image gallery page(UI未実装の確認のみ) | 既存spec(部分) |
| AC-IMG-007 | 画像ファイルの取得 | 生成画像を記事に貼れる | `GET /api/generated-images/{id}/file` が画像バイト列を返す | — | 未着手 |
| AC-IMG-008 | 画像アップロード | 手元の画像も使える | `POST /api/media/upload` した画像がギャラリーに現れる | `e2e/image-upload.spec.ts` › Local file upload input is not implemented on the image gallery page(UI未実装の確認のみ) | 既存spec(部分) |
| AC-IMG-009 | 画像プロバイダの選択 | ComfyUI と外部APIを切り替えられる | `PUT /ai-models/image/provider/selection` の選択が以後の生成に使われる | — | 未着手 |
| AC-IMG-010 | ComfyUI チェックポイント管理 | 使うモデルを選べる | チェックポイントの一覧・選択・導入・削除の結果が生成に反映される | — | 未着手(`@slow`) |
| AC-IMG-011 | 画像生成の既定設定 | 毎回同じ設定を入れ直さなくてよい | プロンプト既定値・サイズ既定値・リサイズ既定値の保存内容が生成フォームに反映される(#913 の再発検知) | — | 未着手 |
| AC-IMG-012 | 画像コンテンツフィルタ設定 | 不適切な生成を抑止できる | `PUT /image-content-filter-settings` の設定が生成結果に反映される | — | 未着手 |
| AC-IMG-013 | メディアのガベージコレクション | 使われていない画像で容量を食わない | `GET /media-garbage-collection/scan` が未使用を列挙し、`POST /delete` で削除される | — | 未着手(`@destructive`) |

### 2.9 ダイアグラムとレンダリング — `DIAG`

API: media `DiagramController`, `RenderController` / 拡張のダイアグラムコマンド

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-DIAG-001 | ダイアグラムの作成・編集・削除 | 図を記事に添えられる | `POST/PUT/DELETE /api/diagrams` の結果が一覧に反映される | — | 未着手 |
| AC-DIAG-002 | ダイアグラム一覧・詳細 | 作った図を再利用できる | `GET /api/diagrams` と `/{id}` が保存内容を返す | — | 未着手 |
| AC-DIAG-003 | SVG取得 | 図が記事に埋め込める | `GET /api/diagrams/{id}/svg` が描画済みSVGを返す | — | 未着手 |
| AC-DIAG-004 | PlantUML レンダリング | テキストからUMLを描ける | `POST /api/render/plantuml` が図を返し、不正な記法はエラーになる | — | 未着手 |
| AC-DIAG-005 | recharts レンダリング | データからグラフを描ける | `POST /api/render/recharts` が図を返す | — | 未着手 |
| AC-DIAG-006 | Penpot デザインファイル連携 | デザインを記事素材にできる | `POST /api/render/penpot/design-file` がデザインを取り込む | — | 未着手 |
| AC-DIAG-007 | draw.io 編集 | 図をGUIで描ける | 拡張の `addNewDiagram` / `editDiagram` で draw.io が開き、保存内容が `GET /api/diagrams/{id}` に反映される | — | 未着手 |

### 2.10 カスタムタグ・テンプレート・コンテンツ設定 — `TAG`

画面: `/custom-tag-templates`, `/projects/[id]/tags`, `/admin/tag-design`
API: content `CustomTagController`, `CustomTagTemplateController`, `ProjectCustomTagController`, `ProjectContentSettingsController`, `ContentCacheController` / project `TagDesignSettingController`, `GlobalTagDesignSettingController`

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-TAG-001 | カスタムタグ生成 | 記事に使う装飾を自分で作れる | プロンプトからタグが生成され自動保存される | `e2e/custom-tag-generation.spec.ts` › 正常系: プロンプト入力からタグ生成・自動保存までの完全フロー | 既存spec(`@stub`) |
| AC-TAG-002 | タグ名のバリデーション | 使えない名前で作らずに済む | パターンに一致しないタグ名では生成が開始されない | `e2e/custom-tag-generation.spec.ts` › バリデーション: パターンに一致しないタグ名では生成が開始されない | 既存spec |
| AC-TAG-003 | 生成物のセキュリティ検証 | 危険なHTMLが公開先へ入らない | script タグ・イベントハンドラ・`javascript:`・CSS `behavior` を含む生成は拒否され、生成後の検証結果(成功/警告/エラー)が画面に示される | `e2e/security.spec.ts` › XSS脆弱性チェック: scriptタグの検出 / XSS脆弱性チェック: イベントハンドラの検出 / XSS脆弱性チェック: JavaScriptプロトコルの検出 / CSS インジェクション検出: behavior プロパティ、`e2e/custom-tag-generation.spec.ts` › セキュリティ検証: 不正なHTMLを要求した場合は拒否されるか検証結果が示される | 既存spec |
| AC-TAG-004 | 生成失敗時のエラー表示 | 失敗に気付ける | 生成に失敗するとエラーメッセージが表示される | `e2e/custom-tag-generation.spec.ts` › エラーハンドリング: 生成に失敗した場合はエラーメッセージが表示される | 既存spec |
| AC-TAG-005 | タグの一覧・編集・削除 | 作ったタグを保守できる | `GET/PUT/DELETE /api/custom-tags` の結果が一覧に反映される | — | 未着手 |
| AC-TAG-006 | タグ検証API | 貼る前に安全か確かめられる | `POST /api/custom-tags/validate` が `isValid` と理由を返す | (検証は AC-TAG-003 の spec 群が担保。応答時間は AC-PERF-001) | 既存spec(部分) |
| AC-TAG-007 | CSSバンドルの取得 | 公開先でタグの見た目が再現される | `GET /api/custom-tags/css-bundle` がタグ定義に対応するCSSを返す | — | 未着手 |
| AC-TAG-008 | テンプレートギャラリー (`/custom-tag-templates`) | 他人の作ったタグを再利用できる | 検索・詳細表示・クローンができる | `e2e/custom-tag-generation.spec.ts` › テンプレート検索・詳細表示・クローンフロー | 既存spec |
| AC-TAG-009 | テンプレートの公開・非公開 | 共有範囲を選べる | `publish` / `unpublish` の結果がギャラリーの見え方に反映される | — | 未着手 |
| AC-TAG-010 | テンプレート削除の認可 | 他人のテンプレートを消されない | 非adminユーザーはテンプレートを削除できない | `e2e/security.spec.ts` › 認可テスト: 非adminユーザーはテンプレートを削除できないこと | 既存spec |
| AC-TAG-011 | 自分のテンプレート一覧 | 自作を管理できる | `GET /api/custom-tag-templates/my-templates` が自分の作成分を返す | — | 未着手 |
| AC-TAG-012 | プロジェクト別タグとプレビュー | プロジェクトごとの見た目を確認できる | `GET /projects/{id}/custom-tags` と `POST /preview` が期待の描画を返す | — | 未着手 |
| AC-TAG-013 | CSSセレクタ接頭辞の設定 | 公開先の既存CSSと衝突しない | `PUT /projects/{projectId}/css-selector-prefix` の設定が保存され、詳細画面に表示される(#913) | — | 未着手 |
| AC-TAG-014 | タグデザイン設定(プロジェクト/全体) | タグの見た目を一括で決められる | `/projects/{id}/tag-design-settings` と `/api/tag-design-settings` の保存・生成結果が公開先の見た目に反映される(#861 の再発検知) | — | 未着手 |
| AC-TAG-015 | コンテンツキャッシュ | 外部URLの情報をカード表示できる | `GET /api/content-cache` が取得結果を返し、内部アドレスへの取得は拒否される(#902 SSRF の再発検知) | — | 未着手 |

### 2.11 Analytics — `ANA`

画面: `/projects/[id]/settings/google-analytics`, `/projects/[id]/settings/adsense`, `/projects/[id]/dashboard`
API: analytics `ProjectAnalyticsApiKeyController`, `ProjectDashboardController`

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-ANA-001 | GA 認証情報の登録・削除 | 自分の計測データを見られる | `GET/PUT/DELETE /api-keys/google-analytics` の結果がダッシュボードの表示可否に反映される | — | 未着手 |
| AC-ANA-002 | GA ダッシュボード | アクセス状況を把握できる | `GET /dashboard/google-analytics` の内容が画面に表示される | — | 未着手(`@stub`) |
| AC-ANA-003 | AdSense 認証情報の登録・削除 | 収益を見られる | `GET/PUT/DELETE /api-keys/adsense` の結果がダッシュボードの表示可否に反映される | — | 未着手 |
| AC-ANA-004 | AdSense クライアントシークレット | OAuth連携を設定できる | `PUT /api-keys/adsense/client-secret` 後、OAuth開始できる | — | 未着手 |
| AC-ANA-005 | AdSense OAuth コールバック | 認可を完了できる | `POST /api-keys/adsense/oauth-callback` 後、ダッシュボードにデータが出る | — | 未着手(`@stub`) |
| AC-ANA-006 | AdSense ダッシュボード | 収益状況を把握できる | `GET /dashboard/adsense` の内容が画面に表示される | — | 未着手(`@stub`) |

### 2.12 システム設定・バックアップ・拡張配布・ダッシュボード — `SYS`

画面: `/`(ダッシュボード), `/admin/system-settings`, `/admin/backup`
API: platform `SystemSettingController`, `AppSettingController`, `BackupController`, `DashboardController`, `VscodeExtensionController`

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-SYS-001 | ダッシュボード(`/`)のサービス状態 | 何が動いていて何が落ちているか分かる | `GET /api/dashboard/service-status` の内容がパネルに表示される | — | 未着手 |
| AC-SYS-002 | コンテナ状態パネル | コンテナ単位の異常に気付ける | `GET /api/dashboard/container-status` の内容が表示される(#876 の再発検知) | — | 未着手 |
| AC-SYS-003 | 状態のストリーミング更新 | 手動リロードなしで最新が見える | `/service-status/stream` `/container-status/stream` が更新を push する | — | 未着手 |
| AC-SYS-004 | サービス状態の詳細 | 障害の原因に辿り着ける | `GET /service-status/detail` が個別サービスの詳細を返す | — | 未着手 |
| AC-SYS-005 | Brave Search APIキー(システム全体) | 全プロジェクト共通で検索を使える | `GET/PUT/DELETE /api/system-settings/brave-search-api-key` の設定が検索付き質問に反映される | — | 未着手 |
| AC-SYS-006 | アプリ設定 (`/admin/system-settings`) | 全体の挙動を調整できる | `GET/PUT /api/system-settings/app-settings` の設定が保存され画面に反映される | — | 未着手 |
| AC-SYS-007 | バックアップのダウンロード | 環境を失っても復旧できる | `GET /api/backup/download` がリストア可能なアーカイブを返す | — | 未着手(`@slow`) |
| AC-SYS-008 | バックアップからのリストア | 実際に復旧できる | `POST /api/backup/restore` 後、バックアップ時点のデータが復元される | — | 未着手(`@slow` `@destructive`) |
| AC-SYS-009 | VSCode拡張の配布 | 拡張をサーバーから入手できる | `GET /api/system/vscode-extension` が `.vsix` を返す | — | 未着手 |

### 2.13 ログと非同期経路 — `LOG`

画面: `/operation-logs`
API: log-writer `AuditLogController`, `OperationLogController`, `FrontendErrorLogController` / ai `GenerationJobController` / RabbitMQ

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-LOG-001 | 操作ログ一覧 (`/operation-logs`) | 誰が何をしたか追える | 操作を行うと `GET /api/operation-logs` に記録が現れる(#825 の再発検知: 常に空にならない) | — | 未着手 |
| AC-LOG-002 | 操作ログの詳細 | 個別操作の内訳を見られる | `GET /api/operation-logs/{operationId}` が該当操作の詳細を返す | — | 未着手 |
| AC-LOG-003 | 統合ログ | 操作と監査を突き合わせられる | `GET /api/operation-logs/unified` が両者を時系列で返す | — | 未着手 |
| AC-LOG-004 | 監査ログ | 権限変更等を追跡できる | 権限変更後、`GET /api/audit-logs` に記録が現れる | — | 未着手 |
| AC-LOG-005 | フロントエンドエラーログ | 画面側の異常を検知できる | `POST /api/logs/errors` した内容が `GET /api/logs/errors` で読める | — | 未着手 |
| AC-LOG-006 | 非同期生成ジョブの完了通知 | 長い処理の完了に気付ける | 生成ジョブがキュー経由で完了状態に遷移し、画面に反映される | — | 未着手(`@slow`) |
| AC-LOG-007 | 操作者の解決 | ログに「誰が」が正しく残る | ログの操作者が実際のログインユーザーと一致する(#906 / #916 の再発検知) | — | 未着手 |

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
| AC-EXT-007 | `letsBlog.schedulePublication` | 予約公開できる | 指定時刻が設定され、時刻まで公開されない | —(#1003 でブロック。エージェント経路が `publishScheduledAt` を無視して即時公開するため、シナリオを置くとバグを期待値に固定してしまう) | 未着手(#1003) |
| AC-EXT-008 | `letsBlog.deletePost` | 公開済み記事を取り下げられる | 公開先から記事が消える | —(#1001 でブロック。`wp post delete` へ存在しない `--yes` を渡しており削除が常に502) | 未着手(#1001) |
| AC-EXT-009 | `letsBlog.askAi` | 執筆中に下書き/校正/要約を頼める | 選択範囲に対する応答がエディタへ挿入される | `ext:ai/assist.feature` › askAiは選んだモードでAIへ依頼する(draft / proofread / summarize の3例) | 検証済(`@stub`) |
| AC-EXT-010 | `letsBlog.askAiSearch` | Web検索を踏まえた回答を得られる | 検索結果を根拠にした応答が返る | `ext:ai/assist.feature` › askAiSearchはWeb検索の結果を根拠として返す | 検証済(`@stub`) |
| AC-EXT-011 | `letsBlog.suggestTags` | タグを考えなくてよい | 本文からタグ候補が提示される | —(#1004 でブロック。LLMスタブがJSONを要求するプロンプトへ散文で応答するため候補が常に空。単体 `apiClientRequests.test.ts` がリクエスト形式のみ担保) | 未着手(#1004) |
| AC-EXT-012 | `letsBlog.proofreadNow` | その場で校正できる | 校正指摘が提示される | —(#1004 でブロック。LLMスタブが校正指摘のJSONを返さないため指摘が常に空)。単体 `apiClientRequests.test.ts` › 校正チェックは本文とプロバイダーだけを送る(送信内容のみ担保)、手動: チェックリスト §5 | 未着手(#1004。送信内容は単体で検証済) |
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
| AC-EXT-023 | `letsBlog.pasteSmartCard` | リンクを見栄えよく貼れる | URLがブログカード/Amazonカードとして挿入される | 単体 `urlPaste.test.ts`(カード記法の生成)、手動: チェックリスト §9。カード情報の先読み(`/api/content-cache`)は #1002 でブロック | 検証済(部分。先読みは #1002) |
| AC-EXT-024 | `letsBlog.pasteAsLink` | リンクを簡潔に貼れる | URLがタイトル付きリンクとして挿入される | 単体 `urlPaste.test.ts`(リンク記法の生成)、手動: チェックリスト §9。タイトル解決(`/api/content-cache`)は #1002 でブロックのため、現状はURLのみの貼り付けへフォールバックする | 検証済(部分。先読みは #1002) |

### 2.15 横断的品質(認可・ルーティング・レート制限・相関ID・縮退) — `XC`

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-XC-001 | エンドポイント認可 | 権限の無い操作ができない | [AUTHORIZATION_MATRIX.md](AUTHORIZATION_MATRIX.md) の全行が、想定ロールでのみ成功し他は403(#830 の再発検知) | `features/cross-cutting/authorization-matrix.feature` › 認可マトリクスが未認証401としている全エンドポイントは、認証なしでは拒否される / 認可マトリクスが権限不足403としている全エンドポイントは、権限の無い利用者を拒否する / 権限の無い利用者は投稿の公開も削除もできない | 検証済(`@api`) |
| AC-XC-002 | Server Action の認可 | 画面経由でも権限が効く | 認可を要する Server Action が未認可では拒否される(#824 の再発検知) | — | 未着手 |
| AC-XC-003 | gateway ルーティング | 追加したAPIが正しいサービスへ届く | 全公開ルートが意図したサービスへ到達する(#861 の再発検知) | `features/cross-cutting/gateway-routing.feature` › 全公開エンドポイントがgateway経由で担当サービスまで到達する / gatewayを迂回した直接アクセスは、gatewayが付けるヘッダを偽装しても拒否される | 検証済(`@api`。到達先が**どのサービスか**は `RouteControllerContractTest` が正。[ACCEPTANCE_TESTING.md §11](ACCEPTANCE_TESTING.md)) |
| AC-XC-004 | レート制限 | 過剰な要求でシステムが倒れない | 制限超過時に429が返り、通常利用は影響を受けない | `features/cross-cutting/rate-limit.feature` › 短時間に上限を超えて要求すると429と再試行までの時間が返る / あるクライアントが上限に達しても、別のクライアントの要求は通る / 制限の時間枠が明けると再び受理される | 検証済(`@api`。時間枠の回復は `@slow`) |
| AC-XC-005 | 相関IDの伝播 | 障害を横断的に追跡できる | 1リクエストの相関IDが全サービスのログで一致する | `features/cross-cutting/correlation-id.feature` › クライアントが送った相関IDが下流サービスのログに現れる / クライアントが相関IDを送らないとgatewayが採番して応答ヘッダで返す / 1つの操作のログをgatewayと下流サービスで同じ相関IDから追える | 検証済(`@api`。ただし project / publishing はログにIDを出しておらず対象にできない。#992) |
| AC-XC-006 | 下流障害時の縮退 | 一部が落ちても画面が壊れない | 下流サービス(content / ai / media / log-writer)が停止していても、画面は壊れず業務操作は続けられ、復旧後は追加の操作なしに元へ戻る | `features/cross-cutting/service-degradation.feature` › ダッシュボードの状態APIが落ちてもページは壊れず、直近の表示を維持する / content-serviceが停止していても投稿履歴ページは空状態で表示される / ai-serviceが停止していてもAI以外の機能は使える / media-serviceが停止していても記事の公開はできる / log-writerが停止していても業務操作は成功する / 停止したサービスが復旧すると追加の操作なしに機能が戻る | 検証済(`@destructive`) |
| AC-XC-007 | 障害の種別判別 | 認可拒否と本当の障害を区別できる | identity の401/403がサービス障害(502)として扱われない(#829 の再発検知) | — | 未着手(`@api`) |
| AC-XC-008 | CSRF保護 | 外部サイトから操作されない | 保護対象フォームにCSRFトークンが含まれる | `e2e/security.spec.ts` › CSRF保護確認: トークンが含まれていることを確認 | 既存spec |
| AC-XC-009 | SQLインジェクション対策 | 不正な入力でデータが壊れない | 特殊文字を含むクエリが安全に処理される | `e2e/security.spec.ts` › SQLインジェクション対策: 特殊文字を含むクエリが安全に処理されること | 既存spec |
| AC-XC-010 | 入力サニタイズ | 危険な入力が保存されない | ユーザー入力がサニタイズされて保存・表示される | `e2e/security.spec.ts` › 入力サニタイズ: ユーザー入力が正しくサニタイズされること | 既存spec |
| AC-XC-011 | 認可表の網羅性 | 認可の一次情報が実態とずれない | 実装から抽出した公開エンドポイント集合と [AUTHORIZATION_MATRIX.md](AUTHORIZATION_MATRIX.md) の差分が空である(#731 の陳腐化の再発防止) | `features/cross-cutting/authorization-matrix.feature` › 公開エンドポイントはすべて認可マトリクスに載っている | 実装中(`@api`。シナリオは通っていない。認可表に無い公開エンドポイントが22件あるため。#991) |

### 2.16 横断的品質(i18n・a11y・レスポンシブ) — `UX`

| 機能ID | 機能 | 利用者から見た価値 | 受け入れ基準(要約) | 対応シナリオ | 状態 |
| --- | --- | --- | --- | --- | --- |
| AC-UX-001 | アクセシビリティ(アプリ画面) | 支援技術で操作できる | 主要画面で axe の重大な違反が出ない | `e2e/accessibility.spec.ts` › Home page should not have accessibility violations / Identify accessibility violations for review | 既存spec |
| AC-UX-002 | アクセシビリティ(ログイン画面) | ログインから支援技術で使える | Keycloak ログイン画面で重大な違反が出ない | `e2e/accessibility.spec.ts` › Login page should not have accessibility violations | 既存spec |
| AC-UX-003 | キーボード操作 | マウス無しで操作できる | ナビゲーションがキーボードで辿れ、フォーカスが視認できる | `e2e/accessibility.spec.ts` › Navigation should be keyboard accessible / Focus indicators should be visible | 既存spec |
| AC-UX-004 | 見出し階層・代替テキスト・リンク文言 | 読み上げで内容が分かる | 見出し階層が妥当、画像にalt、リンクに説明的な文言がある | `e2e/accessibility.spec.ts` › Page should have valid heading hierarchy / Images should have alt text / Links should have descriptive text | 既存spec |
| AC-UX-005 | フォームのラベルとARIA | 入力欄の意味が伝わる | フォーム要素にラベル/ARIA属性がある | `e2e/accessibility.spec.ts` › Forms should have proper labels and ARIA attributes | 既存spec |
| AC-UX-006 | 色コントラスト | 弱視でも読める | 主要テキストのコントラスト比が基準を満たす | `e2e/accessibility.spec.ts` › Color contrast should be sufficient (manual check)(自動判定していない) | 既存spec(部分) |
| AC-UX-007 | レスポンシブ(モバイル) | スマートフォンでも操作できる | モバイルビューポートで主要画面の操作が成立する | `e2e/custom-tag-generation.spec.ts` › レスポンシブテスト: モバイルビューポートでも生成フォームを操作できる、`e2e/image-upload.spec.ts` › Responsive layout on mobile | 既存spec(部分) |
| AC-UX-008 | 言語表示(i18n) | 日本語で一貫して読める | 画面の文言が言語設定に従い、未翻訳のキーが露出しない | — | 未着手 |

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
| AC-INT-007 | `/api/internal/content/preview-skeleton/**` | publishing → content | AC-POST-009 |
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

| 機能ID | 機能 | 対象外の理由 |
| --- | --- | --- |
| AC-PERF-001 | カスタムタグ検証APIの応答時間 | 受け入れ基準ではなく性能の閾値検証。#915 の判断で `apps/web/e2e/performance.spec.ts` に残す。対応: `APIレスポンス時間が2秒以内であること` / `複数リクエストの並列処理パフォーマンス` |
| AC-PERF-002 | AI生成の応答時間 | 同上。外部LLMの応答時間に依存し、受け入れ可否の判定に使えない。対応: `performance.spec.ts` › `Ollamaレスポンス時間が10秒以内であること` |
| AC-PERF-003 | タグ画面のページロード時間 | 同上。対応: `performance.spec.ts` › `UIレンダリング性能: タグ画面のページロード時間` |
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
| AT-8 (#934) | AI執筆支援 | AC-AI-001〜011 |
| AT-9 (#935) | 記事プランとGitHub Issue連携 | AC-PLAN-001〜012 |
| AT-10 (#936) | 画像生成・ギャラリー・メディアGC | AC-IMG-001〜013 |
| AT-11 (#937) | ダイアグラムとレンダリング | AC-DIAG-001〜007 |
| AT-12 (#938) | カスタムタグ・テンプレート・コンテンツ設定 | AC-TAG-001〜015 |
| AT-13 (#939) | Analytics | AC-ANA-001〜006 |
| AT-14 (#940) | システム設定・バックアップ・拡張配布・ダッシュボード | AC-SYS-001〜009 |
| AT-15 (#941) | ログと非同期経路 | AC-LOG-001〜007 |
| AT-16 (#942) | VSCode拡張 | AC-EXT-001〜024(AC-EXT-019 は対象外) |
| AT-17 (#943) | 認可・ルーティング・レート制限・相関ID・縮退 | AC-XC-001〜010 |
| AT-18 (#944) | i18n・アクセシビリティ・レスポンシブ | AC-UX-001〜008 |
| AT-19 (#945) | クリーンスレート初期化と実行順序 | (実行基盤。全行の前提) |

---

## 6. 集計

§2 に列挙した機能ID: **194**。うち1件(AC-EXT-019)は §4 で対象外としたので、
受け入れテストの対象は **193**。

| 状態 | 件数 |
| --- | --- |
| `検証済` | 33 |
| `既存spec` / `既存spec(部分)` | 31 |
| `未着手` | 129 |
| `対象外`(§2 に行を持つもの) | 1 |
| **§2 合計** | **194** |

`検証済` のうち 18 件は VSCode拡張(`AC-EXT-*`)で、#942(AT-16)により
APIレベルの受け入れテスト・単体テスト・手動チェックリストのいずれかへ対応付けた。
`AC-EXT-007` / `008` / `011` / `012` / `016` は実装側・スタブ側の不具合(#1003 / #1001 / #1004 / #998)のため自動化できず
`未着手` のままにしてある——バグを期待値として固定しないため。

`検証済` のうち2件は §4.1 のとおり受け入れ基準を狭めてある。
`@fail`(不具合が直るまで失敗が期待値)のシナリオは無い(#955 の修正で最後の1件が外れた)。

| 区分 | 件数 |
| --- | --- |
| サービス間契約 `AC-INT-*`(§3、受け入れテスト対象外) | 18 |
| §4 の対象外(`AC-INT-*` の一括行を除く) | 7 |

領域別の内訳:

| 領域 | 件数 | | 領域 | 件数 | | 領域 | 件数 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `SET` | 3 | | `AI` | 11 | | `SYS` | 9 |
| `AUTH` | 10 | | `PLAN` | 12 | | `LOG` | 7 |
| `USR` | 11 | | `IMG` | 13 | | `EXT` | 24 |
| `PRJ` | 9 | | `DIAG` | 7 | | `XC` | 10 |
| `SITE` | 11 | | `TAG` | 15 | | `UX` | 8 |
| `POST` | 13 | | `ANA` | 6 | | `STUB` | 2 |
| `BULK` | 13 | | | | | | |

**受け入れテストが1件も無い領域**: `BULK` `AI` `DIAG` `ANA` `SYS` `LOG`
(6領域 / 59 機能ID)。これが issue #927 が可視化しようとした穴である。
`SET`(初回セットアップ)は #929(AT-3)で、`EXT`(VSCode拡張)は #942(AT-16)で、
`PLAN`(記事プランとGitHub Issue連携)は #935(AT-9)で埋めた。
なお `AI` / `DIAG` の一部は拡張側(`AC-EXT-*`)から同じサーバー契約を検証しているが、
Web管理画面としての受け入れ基準は AT-8 / AT-11 の担当のままである。

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
| `/admin/system-settings` | AC-SYS-005, AC-SYS-006 |
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
| `/custom-tag-templates` | AC-TAG-008〜011 |
| `/image-gallery` | AC-IMG-003〜008 |
| `/operation-logs` | AC-LOG-001〜005 |

---

## 8. 保守のしかた

1. **機能を追加したら、この表に行を足す。** 行が無い機能は誰も検証していないのと同じ。
2. **機能IDは再利用しない。** 廃止した機能の行は削除せず `対象外(廃止: #<Issue>)` にする。
3. **`.feature` を実装したら、対応シナリオ列とその行の状態を同じPRで更新する。**
   ドキュメントの更新を別PRに残すと、必ず乖離する。
4. **調査中に実装とドキュメントの乖離を見つけたら、直さずIssueを起票する**
   (`.claude/CLAUDE.md` → Scope Control)。

## 9. 参考

- [ACCEPTANCE_TESTING.md](ACCEPTANCE_TESTING.md) — 記述形式・タグ規約・実行方法
- [e2e-testing.md](e2e-testing.md) — 実行環境の前提
- [AUTHORIZATION_MATRIX.md](AUTHORIZATION_MATRIX.md) — AC-XC-001 の入力
- [SYNC_SERVICE_CALLS.md](SYNC_SERVICE_CALLS.md) — §3 の入力
- Epic #925 / AT-1 #927
