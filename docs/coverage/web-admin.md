# apps/web 管理系の画面の分岐カバレッジ(#1833)

Epic #1711 の子 Issue #1833 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`apps/web` の管理系の画面
(`src/app/admin`・`src/app/users`・`src/app/operation-logs`・`src/app/connect`)に適用した結果。

対象は上の 4 ディレクトリの本番コード 49 ファイル(`.ts` 22・`.tsx` 27)。プロダクションコードは変更していない
(この Issue の差分は `apps/web` のテストコード —— `**/__tests__/**` —— と、本ファイルだけ。
`git diff origin/develop --name-only` で `__tests__/` の外に出るパスは `docs/coverage/web-admin.md` だけ)。
`jest.config.ts`・`jest.setup.ts` も変えていない(#1832 が足したものをそのまま使う)。

この Issue の受け入れ基準はテスト資産の状態なので、Gherkin の受け入れシナリオは書かない(製品の振る舞いではなく、
web UI から到達できない。CLAUDE.md → Where the tests live の例外)。検証は jest で行う。足したテストは、
プロダクションコードがすでに存在するので、足した時点で通る(RED の対象になる未実装の振る舞いは無い)。代わりに、
足す前の `coverage-final.json` で該当の分岐が未カバーだったことを、着手時の計測値が示している。

## 計測値(着手時・完了時)

数え方は jest の `branches`(v8 プロバイダー)。`coverage/coverage-final.json` の `branchMap` の各分岐を 1 と数え、
`b` の実行回数が 1 以上なら covered とする。`||` / `&&` / `??` / 三項 / `if` / `switch` / 既定値の分岐は
v8 の出力ではそれぞれ別の分岐になるので、C1 と C2 を兼ねる。ファイル別の値は、下の「再現手順」の
スクリプトが出す(`jest --coverage` の表の `% Branch` と同じ値)。

計測コマンド(着手時・完了時とも同じ):

```bash
cd apps/web && npm run test:coverage
```

### 着手時

コミット `a4778548`(develop 起点、変更なし)。テスト 268 スイート・2,653 件、失敗 0。

測定の注意: v8 は、**どのテストからも読み込まれていないファイル**を実際の分岐を数えず「未実行の 1 分岐」
(`(empty-report)`)として出し、**一度も実行されなかった関数**の内側の分岐も数えない(関数ごと 1 つの未実行ブロック)。
そのため着手時の total は実際の分岐数より少なく、完了時に total が増えているのは、これらが実行されて実際の分岐が
数えられるようになったため。着手時に `(empty-report)` だったのは `admin/backup/BackupPanel.tsx`・
`admin/backup/actions.ts`・`admin/backup/page.tsx`・`admin/roles/actions.ts`・`admin/ssh-keys/actions.ts`・
`connect/adsense/start/route.ts`・`users/UserForm.tsx` の 7 件。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `src/app/admin/backup/BackupPanel.tsx` | 0 | 1 | 0.0% |
| `src/app/admin/backup/actions.ts` | 0 | 1 | 0.0% |
| `src/app/admin/backup/download/route.ts` | 5 | 5 | 100.0% |
| `src/app/admin/backup/page.tsx` | 0 | 1 | 0.0% |
| `src/app/admin/roles/RoleAssignmentPanel.tsx` | 22 | 23 | 95.7% |
| `src/app/admin/roles/actions.ts` | 0 | 1 | 0.0% |
| `src/app/admin/roles/page.tsx` | 7 | 7 | 100.0% |
| `src/app/admin/ssh-keys/SshKeyPairsPanel.tsx` | 25 | 26 | 96.2% |
| `src/app/admin/ssh-keys/actions.ts` | 0 | 1 | 0.0% |
| `src/app/admin/ssh-keys/page.tsx` | 2 | 2 | 100.0% |
| `src/app/admin/system-settings/AppSettingsPanel.tsx` | 26 | 27 | 96.3% |
| `src/app/admin/system-settings/ExtensionsImportPanel.tsx` | 11 | 11 | 100.0% |
| `src/app/admin/system-settings/PluginRegistrationsPanel.tsx` | 27 | 27 | 100.0% |
| `src/app/admin/system-settings/actions.ts` | 8 | 8 | 100.0% |
| `src/app/admin/system-settings/extensionsImportActions.ts` | 17 | 17 | 100.0% |
| `src/app/admin/system-settings/page.tsx` | 5 | 5 | 100.0% |
| `src/app/admin/system-settings/pluginRegistrationActions.ts` | 20 | 20 | 100.0% |
| `src/app/admin/tag-design/page.tsx` | 5 | 5 | 100.0% |
| `src/app/connect/adsense/callback/route.ts` | 20 | 22 | 90.9% |
| `src/app/connect/adsense/start/route.ts` | 0 | 1 | 0.0% |
| `src/app/connect/facebook/callback/route.ts` | 16 | 16 | 100.0% |
| `src/app/connect/google-analytics/callback/route.ts` | 20 | 22 | 90.9% |
| `src/app/connect/google-analytics/start/route.ts` | 7 | 7 | 100.0% |
| `src/app/connect/hatena/callback/route.ts` | 17 | 18 | 94.4% |
| `src/app/connect/linkedin/callback/route.ts` | 16 | 17 | 94.1% |
| `src/app/connect/threads/callback/route.ts` | 16 | 17 | 94.1% |
| `src/app/connect/x/callback/route.ts` | 16 | 17 | 94.1% |
| `src/app/operation-logs/BrowserTimeZoneField.tsx` | 4 | 4 | 100.0% |
| `src/app/operation-logs/OperationLogTimeZoneLabel.tsx` | 2 | 2 | 100.0% |
| `src/app/operation-logs/UnifiedLogRow.tsx` | 14 | 15 | 93.3% |
| `src/app/operation-logs/actions.ts` | 2 | 2 | 100.0% |
| `src/app/operation-logs/operationGroups.ts` | 14 | 15 | 93.3% |
| `src/app/operation-logs/page.tsx` | 28 | 29 | 96.6% |
| `src/app/operation-logs/slow/page.tsx` | 53 | 53 | 100.0% |
| `src/app/operation-logs/slow/slowStats.ts` | 10 | 10 | 100.0% |
| `src/app/operation-logs/useViewerTimeZone.ts` | 8 | 8 | 100.0% |
| `src/app/users/DeleteUserButton.tsx` | 14 | 14 | 100.0% |
| `src/app/users/UserForm.tsx` | 0 | 1 | 0.0% |
| `src/app/users/UserRow.tsx` | 4 | 4 | 100.0% |
| `src/app/users/[id]/edit/AvatarUploadField.tsx` | 21 | 23 | 91.3% |
| `src/app/users/[id]/edit/CustomLinksEditor.tsx` | 1 | 1 | 100.0% |
| `src/app/users/[id]/edit/DisplayNameSelect.tsx` | 4 | 11 | 36.4% |
| `src/app/users/[id]/edit/PersonalPreferencesForm.tsx` | 3 | 4 | 75.0% |
| `src/app/users/[id]/edit/UserProfileForm.tsx` | 7 | 9 | 77.8% |
| `src/app/users/[id]/edit/actions.ts` | 31 | 36 | 86.1% |
| `src/app/users/[id]/edit/page.tsx` | 2 | 8 | 25.0% |
| `src/app/users/actions.ts` | 11 | 11 | 100.0% |
| `src/app/users/error.tsx` | 4 | 5 | 80.0% |
| `src/app/users/page.tsx` | 11 | 11 | 100.0% |
| **合計(49 ファイル)** | **556** | **601** | **92.5%** |

### 完了時

コミット `b3b82ede`(本 Issue のブランチ `test/1833-web-admin-coverage`、テストを足した最後のコミット。本ファイルを
足すコミットは `docs/` だけを変えるので、計測値は変わらない)。テスト 278 スイート・2,796 件、失敗 0・skipped 0。
`npm run test:coverage` は終了コード 0(`jest.config.ts` のリポジトリ全体の下限を含む)。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `src/app/admin/backup/BackupPanel.tsx` | 5 | 5 | 100.0% |
| `src/app/admin/backup/actions.ts` | 11 | 11 | 100.0% |
| `src/app/admin/backup/download/route.ts` | 5 | 5 | 100.0% |
| `src/app/admin/backup/page.tsx` | 2 | 2 | 100.0% |
| `src/app/admin/roles/RoleAssignmentPanel.tsx` | 24 | 24 | 100.0% |
| `src/app/admin/roles/actions.ts` | 6 | 6 | 100.0% |
| `src/app/admin/roles/page.tsx` | 8 | 8 | 100.0% |
| `src/app/admin/ssh-keys/SshKeyPairsPanel.tsx` | 31 | 31 | 100.0% |
| `src/app/admin/ssh-keys/actions.ts` | 15 | 15 | 100.0% |
| `src/app/admin/ssh-keys/page.tsx` | 2 | 2 | 100.0% |
| `src/app/admin/system-settings/AppSettingsPanel.tsx` | 28 | 28 | 100.0% |
| `src/app/admin/system-settings/ExtensionsImportPanel.tsx` | 12 | 12 | 100.0% |
| `src/app/admin/system-settings/PluginRegistrationsPanel.tsx` | 27 | 27 | 100.0% |
| `src/app/admin/system-settings/actions.ts` | 9 | 9 | 100.0% |
| `src/app/admin/system-settings/extensionsImportActions.ts` | 22 | 22 | 100.0% |
| `src/app/admin/system-settings/page.tsx` | 6 | 6 | 100.0% |
| `src/app/admin/system-settings/pluginRegistrationActions.ts` | 25 | 25 | 100.0% |
| `src/app/admin/tag-design/page.tsx` | 7 | 7 | 100.0% |
| `src/app/connect/adsense/callback/route.ts` | 23 | 24 | 95.8% |
| `src/app/connect/adsense/start/route.ts` | 7 | 7 | 100.0% |
| `src/app/connect/facebook/callback/route.ts` | 18 | 18 | 100.0% |
| `src/app/connect/google-analytics/callback/route.ts` | 23 | 24 | 95.8% |
| `src/app/connect/google-analytics/start/route.ts` | 7 | 7 | 100.0% |
| `src/app/connect/hatena/callback/route.ts` | 21 | 22 | 95.5% |
| `src/app/connect/linkedin/callback/route.ts` | 18 | 19 | 94.7% |
| `src/app/connect/threads/callback/route.ts` | 18 | 19 | 94.7% |
| `src/app/connect/x/callback/route.ts` | 18 | 19 | 94.7% |
| `src/app/operation-logs/BrowserTimeZoneField.tsx` | 4 | 4 | 100.0% |
| `src/app/operation-logs/OperationLogTimeZoneLabel.tsx` | 2 | 2 | 100.0% |
| `src/app/operation-logs/UnifiedLogRow.tsx` | 14 | 15 | 93.3% |
| `src/app/operation-logs/actions.ts` | 4 | 4 | 100.0% |
| `src/app/operation-logs/operationGroups.ts` | 15 | 15 | 100.0% |
| `src/app/operation-logs/page.tsx` | 32 | 32 | 100.0% |
| `src/app/operation-logs/slow/page.tsx` | 53 | 53 | 100.0% |
| `src/app/operation-logs/slow/slowStats.ts` | 10 | 10 | 100.0% |
| `src/app/operation-logs/useViewerTimeZone.ts` | 8 | 8 | 100.0% |
| `src/app/users/DeleteUserButton.tsx` | 14 | 14 | 100.0% |
| `src/app/users/UserForm.tsx` | 5 | 5 | 100.0% |
| `src/app/users/UserRow.tsx` | 4 | 4 | 100.0% |
| `src/app/users/[id]/edit/AvatarUploadField.tsx` | 22 | 24 | 91.7% |
| `src/app/users/[id]/edit/CustomLinksEditor.tsx` | 10 | 10 | 100.0% |
| `src/app/users/[id]/edit/DisplayNameSelect.tsx` | 12 | 12 | 100.0% |
| `src/app/users/[id]/edit/PersonalPreferencesForm.tsx` | 5 | 5 | 100.0% |
| `src/app/users/[id]/edit/UserProfileForm.tsx` | 23 | 23 | 100.0% |
| `src/app/users/[id]/edit/actions.ts` | 48 | 48 | 100.0% |
| `src/app/users/[id]/edit/page.tsx` | 20 | 20 | 100.0% |
| `src/app/users/actions.ts` | 27 | 27 | 100.0% |
| `src/app/users/error.tsx` | 5 | 5 | 100.0% |
| `src/app/users/page.tsx` | 11 | 11 | 100.0% |
| **合計(49 ファイル)** | **746** | **755** | **98.8%** |

未カバーは 9 分岐(下の「テストから到達できない分岐」に全件を載せる)。それ以外の分岐はすべて通っている。

## モックの境界

境界の考え方は Issue 本文の既定案(Q1・Q2。Epic #1711 で利用者が確定済み)のとおり。

- **外部との通信**: HTTP は `fetch` の層で模す。自前の通信ラッパー(`@/lib/apiClient`)・Server Action(`actions.ts`)・
  `@/lib/session`・`@/lib/auth`・コンポーネント・`react`・`next/link` は差し替えない。
- **実行基盤の代替(Q2)**: jest の上に実体が無い Next.js のサーバー実行時(`next/headers` など)・`next-auth*`・
  `server-only` は代替する。
- **ブラウザ / JS ランタイムの機能**: jsdom に無い・壊せない機能は、その機能が欠けた(または結果を観測できる)環境を
  再現するために差し替える。自前のモジュールではない。

自前モジュールを差し替えていた既存テスト(`@/lib/apiClient`・`@/lib/session`・`@/lib/auth`・`@/lib/formatDate`・
`@/components/ViewerDateTime`・`@/components/Breadcrumb`・`../actions`・`../extensionsImportActions`・
`../pluginRegistrationActions`・`../UserForm`・`../DeleteUserButton`・`../AvatarUploadField`・`../SshKeyPairsPanel`・
`../RoleAssignmentPanel`・`../PluginRegistrationsPanel`・`../AppSettingsPanel`・`../ExtensionsImportPanel`・
`../UnifiedLogRow`・`../OperationLogTimeZoneLabel`・`../BrowserTimeZoneField`・`TagDesignSettingsPanel`・`react`・
`next/link`)は、すべて実物を使う形に書き直した。書き直しで、アサーションは減らしていない。バックエンドの呼び出しを
`toHaveBeenCalledWith(引数)` で確かめていた箇所は、同じ内容を HTTP の URL・本文で確かめる形にした(エラーの文言は
`apiClient` が組み立てる実物の `APIエラー (ステータス): 本文` になる)。

共通の足場は 2 つ。
`src/lib/__tests__/testUtils/serverRuntime.ts`(#1832 が足したもの。ここでは変更していない)の `installFetch()` は
`"<METHOD> <パス>"` ごとに gateway の応答を返し、登録の無い通信は例外にする。
`src/lib/__tests__/testUtils/webAdminRuntime.ts`(本 Issue で足した)は、

- `installFetchByPath()` —— 検索文字列が呼び出しごとに変わる一覧 API 用に、パスだけで照合し、検索文字列をテストの側で読めるようにした `installFetch` の変種。差し替えるのは `global.fetch` だけ。
- `ADMIN_SESSION` / `USER_SESSION` —— `next-auth` の `getServerSession` が返す値。
- `queryOf()` / `calledRequests()` —— 呼び出された URL を読む補助。
- `attachFile()` —— `<input type="file">` に利用者がファイルを選んだ状態を作る。jsdom は `fireEvent.change(input, { target: { files } })` では
  フォームの FormData にファイルを載せないため、jsdom の内部の選択済みファイル一覧へ直接足す。何も差し替えず、実装も変えない
  (`required` の検証と FormData が実際のブラウザと同じ状態になる)。

バックエンド(gateway の向こう側)の振る舞いを `jest.fn()` で書いているテストがある(`mockComplete`・`mockStatus`・
`listUsers`・`deleteUser`・`getMyProfile` など)。これは gateway の向こうのバックエンドの代役であって、自前の TypeScript
モジュールの差し替えではない。呼び出しは `fetch` の層を通って HTTP の URL・本文として届く。

### 外部との通信

| 差し替えているもの | 方法 | 使うテスト | どの外部通信か |
| --- | --- | --- | --- |
| `global.fetch`(gateway の `/api/**`・`/api/operation-logs` の記録・ブラウザからの `/client-errors`) | `installFetch()` / `installFetchByPath()` または `global.fetch = fetchMock` の代入 | 4 ディレクトリの 44 テストファイル(Server Action・Route Handler・Server Component のページ・クライアントコンポーネントの各テスト。`users/__tests__/error.test.tsx` は `global.fetch` を直接代入) | HTTP |
| gateway の向こうのバックエンドの代役 `jest.fn()`(`mockComplete`・`mockStatus`・`assignRole`・`removeRole`・`listUsers`・`listProjects`・`listAllProjectUsers`・`deleteUser`・`createUser`・`getMyProfile`・`listSshKeyPairs`・`getOperationTrace`・`getRouteStats`・`getOperationStats`・`listUnifiedOperationLogs`・`listAppSettings`・`listPluginRegistrations` など) | `jest.fn()` を `installFetch` / `installFetchByPath` のルートに載せる | 上の各テスト | HTTP(サーバー側の応答) |

### Next.js の実行基盤・next-auth の代替

| 差し替えているもの | 方法 | 使うテスト | どの実行基盤の代替か |
| --- | --- | --- | --- |
| `server-only` | `jest.mock("server-only", () => ({}))` | `fetch` を通すサーバー側のコードを読み込むテスト(46 件) | サーバー専用であることを示す印。import するだけで例外を投げ、jest 上には実体が無い |
| `next/headers`(`cookies()` / `headers()`) | `nextHeadersModule`(`serverRuntime.ts`) | 同上(46 件) | リクエスト文脈(Cookie・ヘッダー)。jest 上にリクエストは無い |
| `next/server` の `after`(node 環境のテストでは `NextRequest` / `NextResponse` などは `jest.requireActual` の実物のまま。jsdom のテストでは `Request` が無く実物を読めないため `after` だけの代替) | `nextServerModule`(`serverRuntime.ts`) | 同上(46 件) | リクエスト後処理のスケジューラ。リクエスト文脈の外で呼ぶと例外になる |
| `next-auth/jwt`(`getToken`) | `nextAuthJwtModule`(`serverRuntime.ts`) | 同上(46 件) | セッション Cookie の復号。Next.js のリクエスト文脈に依存する |
| `next-auth`(`getServerSession`) | `nextAuthModule`(`serverRuntime.ts`)。値は `resetServerRuntime(ADMIN_SESSION / USER_SESSION / null)` で与える | 同上(46 件) | サーバー側のセッション取得。リクエスト文脈に依存する(`@/lib/session` の `requireSession` / `requireAdminSession` / `getViewerProfile` は実物) |
| `next/navigation`(`redirect`。一部のテストは `notFound`・`usePathname` も) | `jest.mock("next/navigation", ...)`。`redirect` は `NEXT_REDIRECT:<パス>` を投げて処理を打ち切る(実物と同じく、呼び出しの先へ進まない) | 同上(46 件)。`users/__tests__/UserRow.test.tsx` は投げる値を切り替えられる(`mockRedirectThrows`)。Error でない値が投げられた場合の経路を作るため | App Router のルーティング文脈 |
| `next/cache`(`revalidatePath`) | `jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }))`(呼び出しを検証するテストは呼び出しを観測する) | Server Action とそれを呼ぶクライアントコンポーネントのテスト(25 件) | サーバーのキャッシュの再検証。Next.js のリクエスト文脈が無いと使えない |

### ブラウザ / JS ランタイムの機能

| 差し替えているもの | 方法 | 使うテスト | 何を再現するか |
| --- | --- | --- | --- |
| `window.confirm` | `window.confirm = jest.fn().mockReturnValue(...)` | `admin/backup/__tests__/BackupPanel.test.tsx`・`admin/ssh-keys/__tests__/SshKeyPairsPanel.test.tsx`・`users/__tests__/UserRow.test.tsx` | ブラウザの確認ダイアログ(承認・キャンセルを選ぶ)。jsdom は未実装 |
| `navigator.clipboard` | `Object.assign(navigator, { clipboard: { writeText: jest.fn() } })` | `operation-logs/__tests__/UnifiedLogRow.test.tsx` | クリップボードへの書き込み(コピーしたテキストを観測する)。jsdom は未実装 |
| `HTMLCanvasElement.prototype.getContext` / `toBlob` | 代入 | `users/[id]/edit/__tests__/AvatarUploadField.test.tsx` | canvas の 2D 描画と PNG 化。jsdom は canvas を実装していない(取得失敗・`toBlob` が null の端末も再現する) |
| `URL.createObjectURL` | 代入(`afterAll` で戻す) | 同上 | 選んだファイルの URL。jsdom は未実装 |
| `<img>` の `naturalWidth` / `naturalHeight` | `Object.defineProperty` | 同上 | 画像のデコード結果。jsdom は画像をデコードしない |
| `Intl.supportedValuesOf` が無い・例外を投げる | `Object.defineProperty(Intl, ...)`(`afterEach` で戻す) | `users/[id]/edit/__tests__/editPagePersonalPreferences.test.tsx` | タイムゾーン一覧を返せない JS 実行環境 |
| `Intl.DateTimeFormat.prototype.resolvedOptions`(ブラウザのタイムゾーン) | `jest.spyOn(...).mockReturnValue / mockImplementation` | `operation-logs/__tests__/OperationLogTimeZoneLabel.test.tsx`・`UnifiedLogRow.test.tsx`・`useViewerTimeZone.test.tsx` | 閲覧者のブラウザのタイムゾーン(テストは UTC 固定なので、別のタイムゾーンのブラウザを再現する) |
| `console.error` | `jest.spyOn(console, "error").mockImplementation(...)` | `admin/roles/__tests__/rolesPageFetchFailure.test.tsx`・`admin/ssh-keys/__tests__/page.test.tsx`・`sshKeysPageFetchFailure.test.tsx`・`admin/system-settings/__tests__/systemSettingsPageFetchFailure.test.tsx`・`users/__tests__/usersPageFetchFailure.test.tsx`・`error.test.tsx`・`operation-logs/__tests__/OperationLogTimeZoneLabel.test.tsx` | 標準エラー出力。取得失敗を記録することを仕様として検証する箇所は呼び出しを観測する(出力を捨てるだけのテストもある) |

実装の差し替えではないが、`fireEvent.submit(form)`(ブラウザの `required` の検証を通り抜けた送信を直接再現する)と
`attachFile()`(上記)も、テストが jsdom の振る舞いに合わせる箇所として使っている。

### 「表に無いものを差し替えているテスト」が 0 件であることの確認

次の出力は、すべて上の表のどれかに当たる(`jest.mock` の対象は `server-only`・`next/server`・`next/headers`・`next-auth/jwt`・
`next-auth`・`next/cache`・`next/navigation` の 7 種類だけで、`@/lib/*`・`../actions`・コンポーネント・`react`・`next/link` を
指すものは 0 件)。

```bash
cd apps/web
grep -rnE "jest\.(mock|doMock|spyOn|replaceProperty)\(|(global|globalThis|window)\.[A-Za-z.]+ *= |Object\.defineProperty\(|Object\.assign\(navigator|HTMLCanvasElement\.prototype\.[a-zA-Z]+ *=|console\.(error|warn|log) *=" \
  src/app/admin src/app/users src/app/operation-logs src/app/connect | grep -E "\.test\.|__tests__"

# jest.mock の対象のモジュール名だけを数える(7 種類だけが出ることを確かめる)
grep -rhoE "jest\.mock\([\"'][^\"']+[\"']" src/app/admin src/app/users src/app/operation-logs src/app/connect | sort | uniq -c
```

## テストから到達できない分岐

完了時の `coverage-final.json` で、対象の本番コード 49 ファイルに未カバーの分岐は **9 件**(3 種類)。いずれも、
プロダクションコードを変えずには、その分岐に入る入力や状態を作れない。行は `branchMap` の `locations[i].start.line`。

| # | ファイル | 関数 | 行 | 分岐 | 到達できない具体的な理由 |
| ---: | --- | --- | ---: | --- | --- |
| 1 | `src/app/connect/adsense/callback/route.ts` | `GET` 内の `redirectToSettings` | 30 | `message ?? "unknown_error"` の右辺 | `redirectToSettings` は `GET` の中で宣言された関数で、外から呼べない。`"error"` を渡す呼び出しは 3 箇所だけで、どれも必ず文字列を渡す(`oauthError` は直前の `if (oauthError)` で真と確かめた値、固定の `"invalid_state"`、`catch` の `err instanceof Error ? err.message : String(err)`。`String(...)` は常に文字列で、`Error` の `message` は空文字でも `null` / `undefined` にならず、`??` は空文字では右辺へ進まない)。`message` が `undefined` のまま `"error"` で呼ばれる経路が存在しない。`"connected"` の呼び出しは `else` 側に入らない。 |
| 2 | `src/app/connect/google-analytics/callback/route.ts` | 同上 | 30 | 同上 | 同上(呼び出し 3 箇所の引数が上と同じ構造) |
| 3 | `src/app/connect/hatena/callback/route.ts` | 同上 | 38 | 同上 | 同上(呼び出しの引数: `oauthError`・固定の `"invalid_state"`・`catch` の文字列) |
| 4 | `src/app/connect/linkedin/callback/route.ts` | 同上 | 33 | 同上 | 同上 |
| 5 | `src/app/connect/threads/callback/route.ts` | 同上 | 33 | 同上 | 同上 |
| 6 | `src/app/connect/x/callback/route.ts` | 同上 | 31 | 同上 | 同上 |
| 7 | `src/app/operation-logs/UnifiedLogRow.tsx` | `UnifiedLogRow` 内の `handleCopy` | 39 | `if (!entry.operationId) return;` の `return` | `handleCopy` を呼べる唯一の入口は「コピー」ボタンの `onClick` で、ボタンは `entry.sourceType === "OPERATION" && entry.operationId` が真のときにだけ描画される(同じ `entry` を閉包している)。`operationId` が偽の `entry` ではボタンが DOM に無く、`handleCopy` を呼ぶ手段が無い。`handleCopy` は export されておらずコンポーネントの内側の関数なので、直接も呼べない。 |
| 8 | `src/app/users/[id]/edit/AvatarUploadField.tsx` | `AvatarUploadField` 内の `handleImageLoad` | 76 | `if (!img) { return; }` の `return` | `handleImageLoad` は切り抜き用の `<img ref={imgRef}>` の `onLoad` にだけ結ばれている。React は `ref` を commit で設定し、`load` イベントはその後の要素に対して発火するので、`onLoad` が呼ばれた時点で `imgRef.current` は必ずその要素である。`imgRef.current` が `null` の状態で `onLoad` が呼ばれる経路が無い(要素が外れると React はハンドラも外す)。 |
| 9 | `src/app/users/[id]/edit/AvatarUploadField.tsx` | `AvatarUploadField` 内の `handleConfirm` | 89 | `if (!crop \|\| !imgRef.current) { return; }` の `return` | `handleConfirm` は「この内容でアバターとして保存」ボタンの `onClick` にだけ結ばれ、そのボタンは `imageUrl` が真のときにだけ描画され、`disabled={pending \|\| !crop}`。`crop` が `null` の間は無効で、無効なボタンはクリックしても `onClick` が呼ばれない。`crop` が真のときは切り抜き画像 `<img ref={imgRef}>` も描画されているので `imgRef.current` も真。保存が終わると `setCrop(null)` と `setImageUrl(null)` が同時に行われ、ボタンごと消える。2 つの条件のどちらかが真のまま `return` に入る状態を作れない。 |

最初は届かないと見えた分岐は、プロダクションコードを変えずに通した。主なもの:

| ファイル | 関数 | 行 | 通した方法 |
| --- | --- | ---: | --- |
| `src/app/admin/backup/BackupPanel.tsx` | `BackupPanel` | 全体 | `attachFile()` で本物のファイル選択を作って送信し、完了・拒否・キャンセル・送信中を確かめる |
| `src/app/users/[id]/edit/page.tsx` | `getTimezoneOptions` | 29・32 | `Intl.supportedValuesOf` が無い・例外を投げる JS 実行環境を再現(上の「ブラウザ / JS ランタイムの機能」の表) |
| `src/app/connect/adsense/callback/route.ts` ほか 2 件 | `GET` | 48 | `fetch` 自体が `Error` でない値(文字列)で失敗する場合を再現 |
| `src/app/users/error.tsx` | `UsersError` | 16・38 | StrictMode の二重 effect で同じ `error` を 1 件だけ送る分岐と、`message` が空の場合の既定文言 |
| `src/app/admin/ssh-keys/SshKeyPairsPanel.tsx` | `handleGenerate` | 58 | 生成の応答が空(本文なし)で `keyPair` が `undefined` になる場合(`result.keyPair ?? null`) |

### 計測の対象外

`apps/web/jest.config.ts` の `collectCoverageFrom` は `src/**/*.d.ts`・`src/**/*.stories.*`・`src/**/index.*` を計測から外している。
対象の 4 ディレクトリにこれに当たるファイルは **0 件**(下のコマンドの出力が空)。したがって、計測対象外の分岐も無い。

```bash
cd apps/web && find src/app/admin src/app/users src/app/operation-logs src/app/connect \( -name "*.d.ts" -o -name "*.stories.*" -o -name "index.*" \)
```

`collectCoverageFrom` は `src/**` を拾うので、テストの足場 `src/lib/__tests__/testUtils/webAdminRuntime.ts` もレポートに載るが、
本番コードではないので対象に含めない。

## 再現手順

完了時の数字・「未カバー 9 件」は次で再現できる。

```bash
cd apps/web && npm run test:coverage
```

`extract.py`(リポジトリの外に置き、`apps/web` で実行する。`rows` はファイル別の covered / total / 率と合計、
`unc` は未カバーの分岐を 1 行ずつ出し、最後の行がその件数):

```python
import json,re,sys,os
d=json.load(open('coverage/coverage-final.json'))
root=os.getcwd()+'/'
pat=re.compile(r'^src/app/(admin|users|operation-logs|connect)/')
mode=sys.argv[1]
rows=[];unc=[]
for f,v in sorted(d.items()):
    r=f.replace(root,'')
    if not pat.match(r) or '__tests__' in r or r.endswith(('.test.ts','.test.tsx')): continue
    tot=cov=0
    for k,locs in v['branchMap'].items():
        for i,c in enumerate(v['b'][k]):
            tot+=1
            if c>0: cov+=1
            else:
                line=locs['locations'][i]['start'].get('line') or locs['loc']['start']['line']
                fn='(module)'
                for m in v['fnMap'].values():
                    if m['loc']['start']['line']<=line<=m['loc']['end']['line']: fn=m['name']
                unc.append((r,fn,line,locs['type'],i))
    rows.append((r,cov,tot))
if mode=='rows':
    T=C=0
    for r,c,t in rows:
        T+=t;C+=c
        print(f"| `{r}` | {c} | {t} | {'100.0' if t==0 else f'{100*c/t:.1f}'}% |")
    print(f"TOTAL {len(rows)} files {C} {T} {100*C/T:.1f}%")
else:
    for u in unc: print(u)
    print(len(unc))
```

実行: `cd apps/web && python3 -I /path/to/extract.py rows`(ファイル別)/ `python3 -I /path/to/extract.py unc`
(未カバーの分岐。完了時は `(ファイル, 関数, 行, 種類, 分岐の番号)` が 9 行と、最後に `9`)。出力の
(ファイル, 行)が、上の「テストから到達できない分岐」の表の # 1〜9 と一致することを確かめる(1 件も欠けず、余りも無い)。
着手時の値は、同じスクリプトを develop の `a4778548` で `npm run test:coverage` を実行した直後に走らせたもの。

差分にプロダクションコードが無いことの確認:

```bash
git diff origin/develop --name-only | grep -v "__tests__/"   # docs/coverage/web-admin.md だけが出る
```
