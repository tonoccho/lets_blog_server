# apps/web 共通部分の分岐カバレッジ(#1832)

Epic #1711 の子 Issue #1832 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`apps/web` の共通部分に適用した結果。

対象は `src/lib/`・`src/components/`・`src/types/`・`src/proxy.ts`・`src/app/` 直下のファイル・
`src/app/{api,client-errors,downloads,login,setup}/`・`next.config.ts` の本番コード 57 ファイル。
プロダクションコードは変更していない(この Issue の差分は `apps/web` のテストコードとテスト設定
`jest.config.ts` / `jest.setup.ts`、および本ファイルだけ)。

この Issue の受け入れ基準はテスト資産の状態なので、Gherkin の受け入れシナリオは書かない(製品の振る舞いでは
なく、web UI から到達できない。CLAUDE.md → Where the tests live の例外)。検証は jest で行う。足したテストは、
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

分岐を持たないファイル(型定義・定数のみ)は `0 / 0` で、率は 100.0% と書く。

### 着手時

コミット `561f519c`(develop 起点、変更なし)。テスト 262 スイート・2,573 件、失敗 0。

測定の注意: 着手時に **どのテストからも読み込まれていないファイル**(`src/app/api/**/route.ts` 5 件、
`src/app/setup/actions.ts`・`page.tsx`、`src/lib/useCustomTagValidation.ts`)は、v8 が実際の分岐を数えず
「未実行の 1 分岐」(`(empty-report)`)として出す。そのため着手時の total は実際の分岐数より少なく、
完了時に total が増えているのは、これらが読み込まれて実際の分岐が数えられるようになったため。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `next.config.ts` | 1 | 1 | 100.0% |
| `src/app/ConnectedServiceStatusPanel.tsx` | 23 | 27 | 85.2% |
| `src/app/ContainerStatusPanel.tsx` | 16 | 20 | 80.0% |
| `src/app/DownloadMenu.tsx` | 33 | 33 | 100.0% |
| `src/app/Footer.tsx` | 1 | 1 | 100.0% |
| `src/app/I18nProvider.tsx` | 13 | 17 | 76.5% |
| `src/app/InfoRail.tsx` | 34 | 34 | 100.0% |
| `src/app/InfoRailOperationLogs.tsx` | 12 | 13 | 92.3% |
| `src/app/InfoRailQueue.tsx` | 27 | 27 | 100.0% |
| `src/app/LanguageSwitcher.tsx` | 4 | 4 | 100.0% |
| `src/app/LogoutButton.tsx` | 2 | 2 | 100.0% |
| `src/app/SessionProvider.tsx` | 1 | 1 | 100.0% |
| `src/app/SideNav.tsx` | 53 | 53 | 100.0% |
| `src/app/ThemeScript.tsx` | 3 | 3 | 100.0% |
| `src/app/ThemeSwitcher.tsx` | 17 | 18 | 94.4% |
| `src/app/api/auth/[...nextauth]/route.ts` | 0 | 1 | 0.0% |
| `src/app/api/dashboard/container-status/route.ts` | 0 | 1 | 0.0% |
| `src/app/api/dashboard/container-status/stream/route.ts` | 0 | 1 | 0.0% |
| `src/app/api/dashboard/service-status/route.ts` | 0 | 1 | 0.0% |
| `src/app/api/dashboard/service-status/stream/route.ts` | 0 | 1 | 0.0% |
| `src/app/api/users/[id]/avatar/route.ts` | 9 | 9 | 100.0% |
| `src/app/client-errors/route.ts` | 15 | 15 | 100.0% |
| `src/app/downloads/vscode-extension/route.ts` | 7 | 7 | 100.0% |
| `src/app/error.tsx` | 5 | 5 | 100.0% |
| `src/app/infoRailActions.ts` | 38 | 39 | 97.4% |
| `src/app/infoRailQueue.ts` | 84 | 84 | 100.0% |
| `src/app/layout.tsx` | 6 | 6 | 100.0% |
| `src/app/login/actions.ts` | 20 | 20 | 100.0% |
| `src/app/login/error/page.tsx` | 1 | 1 | 100.0% |
| `src/app/login/page.tsx` | 7 | 7 | 100.0% |
| `src/app/page.tsx` | 9 | 9 | 100.0% |
| `src/app/setup/SetupForm.tsx` | 6 | 6 | 100.0% |
| `src/app/setup/actions.ts` | 0 | 1 | 0.0% |
| `src/app/setup/page.tsx` | 0 | 1 | 0.0% |
| `src/components/Breadcrumb.tsx` | 6 | 6 | 100.0% |
| `src/components/FetchErrorNotice.tsx` | 5 | 5 | 100.0% |
| `src/components/RouteLoading.tsx` | 1 | 1 | 100.0% |
| `src/components/Tabs.tsx` | 19 | 19 | 100.0% |
| `src/components/ViewerDateTime.tsx` | 6 | 6 | 100.0% |
| `src/lib/apiBaseUrl.ts` | 5 | 5 | 100.0% |
| `src/lib/apiClient.ts` | 179 | 181 | 98.9% |
| `src/lib/apiErrorReason.ts` | 13 | 13 | 100.0% |
| `src/lib/auth.ts` | 23 | 25 | 92.0% |
| `src/lib/deriveRole.ts` | 6 | 6 | 100.0% |
| `src/lib/errorLogger.ts` | 12 | 14 | 85.7% |
| `src/lib/formatDate.ts` | 35 | 38 | 92.1% |
| `src/lib/i18nConfig.ts` | 0 | 0 | 100.0% |
| `src/lib/llmJobResults.ts` | 54 | 54 | 100.0% |
| `src/lib/loadOrReport.ts` | 15 | 15 | 100.0% |
| `src/lib/navigation.ts` | 0 | 0 | 100.0% |
| `src/lib/session.ts` | 20 | 20 | 100.0% |
| `src/lib/sessionExpired.ts` | 3 | 3 | 100.0% |
| `src/lib/siteAdminUrl.ts` | 7 | 7 | 100.0% |
| `src/lib/tokenRefreshPolicy.ts` | 6 | 6 | 100.0% |
| `src/lib/useCustomTagGeneration.ts` | 12 | 13 | 92.3% |
| `src/lib/useCustomTagValidation.ts` | 0 | 1 | 0.0% |
| `src/proxy.ts` | 23 | 26 | 88.5% |
| **合計(57 ファイル)** | **897** | **933** | **96.1%** |

### 完了時

コミット `08e8afb0`(本 Issue のブランチ `test/1832-web-common-coverage`、テストを足したコミット。本ファイルを
足すコミットは `docs/` だけを変えるので、計測値は変わらない)。テスト 268 スイート・2,653 件、失敗 0・skipped 0。
`npm run test:coverage` は終了コード 0(`jest.config.ts` のリポジトリ全体の下限を含む)。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `next.config.ts` | 1 | 1 | 100.0% |
| `src/app/ConnectedServiceStatusPanel.tsx` | 51 | 51 | 100.0% |
| `src/app/ContainerStatusPanel.tsx` | 44 | 44 | 100.0% |
| `src/app/DownloadMenu.tsx` | 33 | 33 | 100.0% |
| `src/app/Footer.tsx` | 1 | 1 | 100.0% |
| `src/app/I18nProvider.tsx` | 19 | 19 | 100.0% |
| `src/app/InfoRail.tsx` | 35 | 35 | 100.0% |
| `src/app/InfoRailOperationLogs.tsx` | 14 | 14 | 100.0% |
| `src/app/InfoRailQueue.tsx` | 27 | 27 | 100.0% |
| `src/app/LanguageSwitcher.tsx` | 5 | 5 | 100.0% |
| `src/app/LogoutButton.tsx` | 2 | 2 | 100.0% |
| `src/app/SessionProvider.tsx` | 1 | 1 | 100.0% |
| `src/app/SideNav.tsx` | 54 | 54 | 100.0% |
| `src/app/ThemeScript.tsx` | 4 | 4 | 100.0% |
| `src/app/ThemeSwitcher.tsx` | 20 | 20 | 100.0% |
| `src/app/api/auth/[...nextauth]/route.ts` | 0 | 0 | 100.0% |
| `src/app/api/dashboard/container-status/route.ts` | 5 | 5 | 100.0% |
| `src/app/api/dashboard/container-status/stream/route.ts` | 5 | 5 | 100.0% |
| `src/app/api/dashboard/service-status/route.ts` | 5 | 5 | 100.0% |
| `src/app/api/dashboard/service-status/stream/route.ts` | 5 | 5 | 100.0% |
| `src/app/api/users/[id]/avatar/route.ts` | 8 | 8 | 100.0% |
| `src/app/client-errors/route.ts` | 15 | 15 | 100.0% |
| `src/app/downloads/vscode-extension/route.ts` | 7 | 7 | 100.0% |
| `src/app/error.tsx` | 5 | 5 | 100.0% |
| `src/app/infoRailActions.ts` | 44 | 44 | 100.0% |
| `src/app/infoRailQueue.ts` | 87 | 87 | 100.0% |
| `src/app/layout.tsx` | 6 | 6 | 100.0% |
| `src/app/login/actions.ts` | 20 | 20 | 100.0% |
| `src/app/login/error/page.tsx` | 1 | 1 | 100.0% |
| `src/app/login/page.tsx` | 7 | 7 | 100.0% |
| `src/app/page.tsx` | 9 | 9 | 100.0% |
| `src/app/setup/SetupForm.tsx` | 6 | 6 | 100.0% |
| `src/app/setup/actions.ts` | 14 | 14 | 100.0% |
| `src/app/setup/page.tsx` | 4 | 4 | 100.0% |
| `src/components/Breadcrumb.tsx` | 6 | 6 | 100.0% |
| `src/components/FetchErrorNotice.tsx` | 5 | 5 | 100.0% |
| `src/components/RouteLoading.tsx` | 1 | 1 | 100.0% |
| `src/components/Tabs.tsx` | 19 | 19 | 100.0% |
| `src/components/ViewerDateTime.tsx` | 6 | 6 | 100.0% |
| `src/lib/apiBaseUrl.ts` | 5 | 5 | 100.0% |
| `src/lib/apiClient.ts` | 201 | 201 | 100.0% |
| `src/lib/apiErrorReason.ts` | 13 | 13 | 100.0% |
| `src/lib/auth.ts` | 24 | 24 | 100.0% |
| `src/lib/deriveRole.ts` | 6 | 6 | 100.0% |
| `src/lib/errorLogger.ts` | 16 | 16 | 100.0% |
| `src/lib/formatDate.ts` | 41 | 41 | 100.0% |
| `src/lib/i18nConfig.ts` | 0 | 0 | 100.0% |
| `src/lib/llmJobResults.ts` | 54 | 54 | 100.0% |
| `src/lib/loadOrReport.ts` | 15 | 15 | 100.0% |
| `src/lib/navigation.ts` | 0 | 0 | 100.0% |
| `src/lib/session.ts` | 22 | 22 | 100.0% |
| `src/lib/sessionExpired.ts` | 3 | 3 | 100.0% |
| `src/lib/siteAdminUrl.ts` | 7 | 7 | 100.0% |
| `src/lib/tokenRefreshPolicy.ts` | 6 | 6 | 100.0% |
| `src/lib/useCustomTagGeneration.ts` | 13 | 13 | 100.0% |
| `src/lib/useCustomTagValidation.ts` | 10 | 10 | 100.0% |
| `src/proxy.ts` | 30 | 30 | 100.0% |
| **合計(57 ファイル)** | **1067** | **1067** | **100.0%** |

未カバーの分岐は 0 件。したがって「テストから到達できない分岐」の一覧(下)は空である。

## モックの境界

境界の考え方は Issue 本文の既定案のとおり。

- **外部との通信**: HTTP は `fetch`(と、SSE の `EventSource`)の層で模す。自前の通信ラッパー
  (`@/lib/apiClient`)・Server Action(`actions.ts`)・`@/lib/session`・`@/lib/auth`・コンポーネント・`react` は
  差し替えない。
- **実行基盤の代替(Q2 の既定案)**: jest の上に実体が無い Next.js のサーバー実行時(`next/headers` など)・
  `next-auth*`・`server-only`・CSS の import は代替する。
- **ブラウザ / JS ランタイムの機能**: jsdom に無い・壊せない機能(`matchMedia`・`Storage`・`Intl`・`navigator`)は、
  その機能が欠けた環境を再現するために差し替える。これらは自前のモジュールではない。

自前モジュールを差し替えていた既存テスト(`@/lib/apiClient`・`@/lib/session`・`@/lib/auth`・`@/lib/formatDate`・
`@/lib/apiBaseUrl`・`@/components/ViewerDateTime`・`@/app/custom-tags/actions`・`../actions`・`../infoRailActions`・
`../SideNav` など 12 コンポーネント・`react`・`next/link`・`jose`・`pageReloader`)は、すべて実物を使う形に
書き直した(アサーションは減らしていない。書き換えた箇所は、同じ振る舞いを HTTP の URL・本文で確かめる形にした)。

共通の足場は `src/lib/__tests__/testUtils/serverRuntime.ts`。`jest.mock` の中から `require` して、実行基盤の
代替(`nextServerModule` / `nextHeadersModule` / `nextAuthJwtModule` / `nextAuthModule`)を共有し、
`installFetch()` で `"<METHOD> <パス>"` ごとに gateway の応答を返す(登録の無い通信は例外にする)。
`respondWith(jest.fn())` は、gateway の向こうのバックエンドの振る舞いを `jest.fn()` で書くためのもので、自前モジュールの
代役ではない。

### 外部との通信

| 差し替えているもの | 方法 | 使うテスト | どの外部通信か |
| --- | --- | --- | --- |
| `global.fetch`(gateway `/api/**`・`/api/operation-logs` の記録・Keycloak の token / logout・Next.js 自身への内部 fetch・ブラウザからの `/client-errors` `/downloads/**` `/api/dashboard/**`) | `installFetch()`(`serverRuntime.ts`)または `global.fetch = jest.fn()` の代入 | `src/lib/__tests__/` の `apiClient*.test.ts`(6 件)・`auth`・`projectSettings`・`session`・`llmJobResults`・`useCustomTagGeneration`・`useCustomTagValidation`・`errorLogger`・`errorLogger.node` の各 `.test.ts`、`src/__tests__/proxy.test.ts`、`src/app/__tests__/` の `DownloadMenu`・`ConnectedServiceStatusPanel`・`ContainerStatusPanel`・`InfoRail`・`InfoRailQueue`・`infoRailActions`・`dashboardPage*`・`errorBoundaryLogsOnce` の各テスト、`src/app/{api,client-errors,downloads,login,setup}/` 配下のテスト | HTTP |
| `global.EventSource` → `FakeEventSource`(`open` / `status` / `error` を合成して発火する自前のテスト用クラス) | `global.EventSource = FakeEventSource` の代入 | `ConnectedServiceStatusPanel.test.tsx`・`ContainerStatusPanel.test.tsx` | HTTP(Server-Sent Events の接続) |
| gateway の向こうのバックエンドの代役 `jest.fn()`(`mockJobs`・`mockMe`・`mockJobDetail`・`mockImage`・`mockList`・`mockGetJob` など。`respondWith()` で応答に変える) | `jest.fn()` を `installFetch` のルートに載せる | `infoRailActions.test.ts`・`InfoRail.test.tsx`・`InfoRailQueue.test.tsx` | HTTP(サーバー側の応答) |
| `logWriterResponse`(log-writer の応答を返す関数) | `installFetch` のルート | `client-errors/__tests__/route.test.ts` | HTTP(log-writer への書き込み) |

### Next.js の実行基盤・next-auth・ビルド時の読み込みの代替

| 差し替えているもの | 方法 | 使うテスト | どの実行基盤の代替か |
| --- | --- | --- | --- |
| `server-only` | `jest.mock('server-only', () => ({}))` | `apiClient*`・`auth`・`session`・`projectSettings`・`llmJobResults`・`useCustomTag*`・`infoRail*`・`InfoRail*`・`dashboardPage*`・`layout.*`・`SetupForm`・`setup/*`・`login/actions`・`client-errors`・`downloads`・`api/**` の各テスト(30 件) | サーバー専用であることを示す印。import するだけで例外を投げ、jest 上には実体が無い |
| `next/headers`(`cookies()` / `headers()`) | `nextHeadersModule` または同等のインライン定義 | `apiClient*`・`session`・`projectSettings`・`llmJobResults`・`useCustomTag*`・`infoRailActions`・`InfoRail`・`InfoRailQueue`・`dashboardPage*`・`SetupForm`・`setup/*`・`login/actions`・`client-errors`・`downloads`・`api/**` の各テスト(25 件) | リクエスト文脈(Cookie・ヘッダー)。jest 上にリクエストは無い |
| `next/server` の `after`(`NextResponse` などは実物のまま) | `nextServerModule`(`jest.requireActual('next/server')` に重ねる形もある) | 上と同じ系統のテスト(23 件) | リクエスト後処理のスケジューラ。リクエスト文脈の外で呼ぶと例外になる |
| `next-auth/jwt`(`getToken`) | `nextAuthJwtModule` | 同上(24 件)、`src/__tests__/proxy.test.ts`(インライン) | セッション Cookie の復号。Next.js のリクエスト文脈に依存する |
| `next-auth`(`getServerSession`) | `nextAuthModule` | `session`・`useCustomTag*`・`infoRailActions`・`InfoRail`・`InfoRailQueue`・`dashboardPage*`・`layout.*`・`client-errors`・`downloads`・`api/**` の各テスト(16 件) | サーバー側のセッション取得。リクエスト文脈に依存する(`@/lib/session` の `getSession` / `requireSession` は実物) |
| `next/navigation`(`redirect`・`usePathname`・`useServerInsertedHTML`・`notFound`) | `jest.mock` の部分定義 | `layout.*`(4)・`SideNav`・`ThemeScript`・`InfoRail`・`InfoRailQueue`・`infoRailActions`・`dashboardPage*`・`setup/page`・`login/actions`・`loadOrReport`・`useCustomTag*` の各テスト(16 件) | App Router のルーティング文脈。`redirect` は `NEXT_REDIRECT` を投げて処理を打ち切る仕組みで、例外にして検証する |
| `next/font/google`(`Geist` / `Geist_Mono`) | `jest.mock` | `layout.*` の 4 テスト | ビルド時にフォントを取得・埋め込む Next.js のコンパイラ機能 |
| `../globals.css`(virtual) | `jest.mock(..., { virtual: true })` | `layout.*` の 4 テスト | CSS の import(Next.js のビルドが処理する) |
| `next-auth/react`(`signIn`・`signOut`・`SessionProvider`) | `jest.mock` | `login/__tests__/page.test.tsx`・`LogoutButton.test.tsx`・`SessionProvider.test.tsx` | NextAuth サーバーへの通信とページ遷移を伴うブラウザ側クライアント |
| `next/headers` の `cookies()`(`set` を記録) | インライン定義 | `login/__tests__/actions.test.ts` | リクエスト文脈の Cookie ストア |

`jest.config.ts` の `transformIgnorePatterns` に `jose` を足し、`jest.setup.ts` で jsdom に無い `TextEncoder` /
`TextDecoder` を補った。これにより `jose`(JWT のデコード)は差し替えずに実物が動く(以前は `jest.mock('jose', ...)` で
差し替えていた)。

### ブラウザ / JS ランタイムの機能

| 差し替えているもの | 方法 | 使うテスト | 何を再現するか |
| --- | --- | --- | --- |
| `Storage.prototype.getItem` / `setItem` が例外を投げる | `jest.spyOn(...).mockImplementation` | `SideNav.test.tsx`・`InfoRail.test.tsx`・`ThemeScript.test.tsx` | localStorage が使えないブラウザ(プライベートモード等)。jsdom の `Storage` は壊せない |
| `window.matchMedia`(OS の配色の好み) | `Object.defineProperty(window, 'matchMedia', ...)` | `ThemeSwitcher.test.tsx`・`ThemeScript.test.tsx` | jsdom は `matchMedia` を実装していない |
| `Intl.DateTimeFormat` が例外を投げる / 部品を返さない | `jest.spyOn(Intl, 'DateTimeFormat').mockImplementation` | `InfoRail.test.tsx`・`InfoRailQueue.test.tsx`・`formatDate.test.ts` | ICU が時刻帯を返せない環境 |
| `globalThis.Intl` を `undefined` / `{}` にする | 代入(`afterEach` で戻す) | `formatDate.test.ts` | `Intl` が無いブラウザ |
| `globalThis.navigator` を `undefined` にする | `Object.defineProperty` | `errorLogger.node.test.ts` | `navigator` が無い JS 実行環境(Node 21 以降は持っているため、外して再現する) |
| `console.error` / `console.warn`(出力を捨てる、または呼び出しを観測する) | `jest.spyOn(console, ...).mockImplementation` または `console.error = jest.fn()` | `auth`・`session`・`loadOrReport`・`errorLogger`・`I18nProvider`・`InfoRail`・`InfoRailQueue`・`SideNav`・`LanguageSwitcher`・`dashboardPageFetchFailure`・`errorBoundaryLogsOnce`・`client-errors` の各テスト | 標準エラー出力。ログを出すこと自体を仕様として検証する箇所は、呼び出し内容を観測する。`LanguageSwitcher` は jsdom の `location.reload()` が出す `Not implemented: navigation` で再読み込みの要求を観測する |
| `jest.useFakeTimers()`(`Date` / タイマー) | jest の偽時計 | `ConnectedServiceStatusPanel`・`ContainerStatusPanel`・`InfoRailQueue`・`login/page` の各テスト | 時刻とポーリング間隔(`Date` だけ偽装して `setInterval` は実物を使うテストもある) |

### 実装を差し替えない記録・設定

| 項目 | 方法 | 使うテスト | 内容 |
| --- | --- | --- | --- |
| `jest.spyOn(document, 'addEventListener')`・`jest.spyOn(upstream, 'text')`・`jest.spyOn(FakeEventSource.prototype, 'close')` | 実装を残したままの呼び出し記録 | `SideNav.test.tsx`・`apiClient.test.ts`・`ConnectedServiceStatusPanel.test.tsx`・`ContainerStatusPanel.test.tsx` | 実装は差し替えない(観測のみ) |
| `process.env.LETS_BLOG_GATEWAY_URL` / `NEXTAUTH_URL` / `NEXTAUTH_SECRET` の代入 | 環境変数(テストごとに戻す) | `proxy.test.ts`・`apiBaseUrl.test.ts`・`api/auth/[...nextauth]/__tests__/route.test.ts` | 設定値。モジュールの差し替えではない(以前の `jest.mock('@/lib/apiBaseUrl')` はやめた) |
| 引数・props として渡す記録用の `jest.fn()`(`refresh`・`onTabChange`・`redirect` ほか) | 関数引数 | `tokenRefreshPolicy.test.ts`・`Tabs.test.tsx`・`loadOrReport.test.ts` | モジュールの差し替えではなく、呼び出しの記録 |

「表に無いものを差し替えているテスト」が 0 件であることは、次で確かめる(出力された行が、上の表のどれかに当たる)。

```bash
cd apps/web
grep -rnE "jest\.(mock|doMock|spyOn|replaceProperty)\(|(global|globalThis|window)\.[A-Za-z]+ *= |Object\.defineProperty\(|console\.(error|warn|log) *=" \
  src/lib src/components src/types src/__tests__ src/app/__tests__ src/app/api src/app/client-errors src/app/downloads \
  src/app/login src/app/setup | grep -E "\.test\.|__tests__"
```

## テストから到達できない分岐

完了時の `coverage-final.json` で、対象の本番コード 57 ファイルに未カバーの分岐は **0 件**。したがって、この一覧は空である。

最初は届かないと見えた分岐は、プロダクションコードを変えずに、実行環境の機能が欠けた状態を再現することで通した
(上の「ブラウザ / JS ランタイムの機能」の表)。主なもの:

| ファイル | 関数 | 行 | 通した方法 |
| --- | --- | ---: | --- |
| `src/lib/formatDate.ts` | `getDefaultTimeZone` | 17・20 | `Intl` が無い・`Intl.DateTimeFormat` が無い・例外を投げる環境を再現 |
| `src/lib/formatDate.ts` | `formatDateYYYYMMDD` の `get` | 98 | `formatToParts` が部品を返さない `Intl` を再現 |
| `src/lib/errorLogger.ts` | `logErrorToBackend` | 26・27 | `window` が無い node 環境(`errorLogger.node.test.ts`)と、`navigator` を外した環境 |
| `src/app/ThemeSwitcher.tsx` | `resolveEffectiveTheme` | 10 | `matchMedia` が dark を返す環境 |
| `src/app/I18nProvider.tsx` | `t` / `useI18n` | 92・94・95・107 | 型に反する `initialLocale`・名前空間・キーを渡す、`I18nProvider` の外で呼ぶ |

### 計測の対象外

`apps/web/jest.config.ts` の `collectCoverageFrom` は `src/**/*.d.ts`・`src/**/*.stories.*`・`src/**/index.*` を計測から外している。
対象の範囲でこれに当たるファイルは `src/types/next-auth.d.ts`(型宣言のみ)の 1 件で、分岐を持たない。`*.stories.*`・`index.*` は
対象の範囲に無い。`src/app/globals.css`・`favicon.ico` は JS ではないので、そもそも分岐が無い。

`collectCoverageFrom` は `src/**` を拾うので、テストの足場 `src/lib/__tests__/testUtils/serverRuntime.ts`・`fakeJwt.ts` も
レポートに載る。これらは本番コードではなく対象に含めない(`serverRuntime.ts` には、テストが使わない補助の既定値の分岐が
5 件未カバーで残る)。

## 再現手順

完了時の数字・「未カバー 0 件」は次で再現できる。

```bash
cd apps/web && npm run test:coverage
```

`extract.py`(`apps/web` で実行する。`unc` を渡すと未カバーの分岐を 1 行ずつ出し、最後の行がその件数):

```python
import json,re,sys,os
d=json.load(open('coverage/coverage-final.json'))
root=os.getcwd()+'/'
pat=re.compile(r'^(src/(lib|components|types)/|src/proxy\.ts$|src/app/[^/]+\.(ts|tsx)$|src/app/(api|client-errors|downloads|login|setup)/|next\.config\.ts$)')
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
    print(f"TOTAL {C} {T} {100*C/T:.1f}%")
else:
    for u in unc: print(u)
    print(len(unc))
```

実行: `cd apps/web && python3 -I extract.py rows`(ファイル別)/ `python3 -I extract.py unc`(未カバー分岐。完了時は最後の行が `0`)。
着手時の値は、同じスクリプトを develop の `561f519c` で `npm run test:coverage` を実行した直後に走らせたもの。
