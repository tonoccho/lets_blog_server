# apps/web プロジェクト一覧とプロジェクト詳細の下位画面の分岐カバレッジ(#1835)

Epic #1711 の子 Issue #1835 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`apps/web` の `src/app/projects` に適用した結果。

対象は次の 42 ファイル(`.ts` 8・`.tsx` 34)の本番コード。

- `src/app/projects/` 直下: `page.tsx`・`ProjectsTable.tsx`・`ProjectForm.tsx`・`actions.ts`・`error.tsx`
- `src/app/projects/[id]/` の下位ディレクトリ: `(detail)`・`article-review`・`custom-tags`・`dashboard`・`plan`・`posts`・
  `settings`(`adsense`・`google-analytics`・`sns`)・`tag-design`・`tags`

`src/app/projects/[id]/` 直下のファイル(`ProjectNameForm.tsx` など)は他の子 Issue の対象で、ここには含めない。
テストは、上のディレクトリの `__tests__/`・`css-bundle/__tests__/`・`settings/*/__tests__/` と、
`src/app/projects/[id]/__tests__/` のうち `ProjectDetail*`・`CssSelectorPrefixForm*`・`projectDetailBackend.ts`。

プロダクションコードは変更していない(差分は `apps/web/src/app/projects/**/__tests__/` と本ファイルだけ。末尾の確認コマンド)。
`jest.config.ts`・`jest.setup.ts` も変えていない。この Issue の受け入れ基準はテスト資産の状態なので、Gherkin の
受け入れシナリオは書かない(製品の振る舞いではなく web UI から到達できない。CLAUDE.md → Where the tests live の例外)。
足したテストは、本番コードが既にあるので、足した時点で通る。代わりに、着手時の `coverage-final.json` で該当の分岐が
未カバーだったことを、着手時の計測値が示している。

## 計測値(着手時・完了時)

数え方は jest の `branches`(v8 プロバイダー)。`coverage/coverage-final.json` の `branchMap` の各分岐を 1 と数え、
`b` の実行回数が 1 以上なら covered。`||` / `&&` / `??` / 三項 / `if` / 既定値は v8 の出力でそれぞれ別の分岐になるので
C1 と C2 を兼ねる。計測コマンド(着手時・完了時とも同じ):

```bash
cd apps/web && npm run test:coverage
```

どのテストからも読み込まれていないファイルは `(empty-report)` として 1 分岐の未実行になり、実行されなかった関数の
内側は数えられない。着手時の total が少なく完了時に増えているのはこのため(`ProjectForm.tsx`・`CssBundleViewer.tsx`・
`preview/route.ts`・`ArticlePlanIssueList.tsx`・`ArticlePlanProposals.tsx`・`ArticlePlanStructureProposal.tsx`・
`plan/actions.ts` が着手時は 0/1)。

### 着手時

コミット `b0054563`(develop 起点、変更なし)。テスト 288 スイート・2,955 件、失敗 0。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `src/app/projects/ProjectForm.tsx` | 0 | 1 | 0.0% |
| `src/app/projects/ProjectsTable.tsx` | 26 | 27 | 96.3% |
| `src/app/projects/[id]/(detail)/loading.tsx` | 1 | 1 | 100.0% |
| `src/app/projects/[id]/(detail)/page.tsx` | 30 | 30 | 100.0% |
| `src/app/projects/[id]/article-review/ArticleReviewButton.tsx` | 5 | 5 | 100.0% |
| `src/app/projects/[id]/article-review/ArticleReviewDecision.tsx` | 24 | 26 | 92.3% |
| `src/app/projects/[id]/article-review/ArticleReviewPullRequestList.tsx` | 9 | 10 | 90.0% |
| `src/app/projects/[id]/article-review/actions.ts` | 18 | 18 | 100.0% |
| `src/app/projects/[id]/article-review/articleReviewStateLabels.ts` | 3 | 3 | 100.0% |
| `src/app/projects/[id]/article-review/page.tsx` | 10 | 10 | 100.0% |
| `src/app/projects/[id]/custom-tags/CssBundleViewer.tsx` | 0 | 1 | 0.0% |
| `src/app/projects/[id]/custom-tags/CssSelectorPrefixForm.tsx` | 5 | 5 | 100.0% |
| `src/app/projects/[id]/custom-tags/ProjectCustomTagManager.tsx` | 10 | 30 | 33.3% |
| `src/app/projects/[id]/custom-tags/actions.ts` | 6 | 16 | 37.5% |
| `src/app/projects/[id]/custom-tags/css-bundle/route.ts` | 7 | 7 | 100.0% |
| `src/app/projects/[id]/custom-tags/preview/route.ts` | 0 | 1 | 0.0% |
| `src/app/projects/[id]/dashboard/AdSenseWidget.tsx` | 5 | 8 | 62.5% |
| `src/app/projects/[id]/dashboard/AiConnectionWidget.tsx` | 23 | 23 | 100.0% |
| `src/app/projects/[id]/dashboard/DashboardWidgetSlot.tsx` | 4 | 4 | 100.0% |
| `src/app/projects/[id]/dashboard/EnvironmentWidget.tsx` | 5 | 5 | 100.0% |
| `src/app/projects/[id]/dashboard/GoogleAnalyticsWidget.tsx` | 6 | 9 | 66.7% |
| `src/app/projects/[id]/dashboard/MembersWidget.tsx` | 7 | 7 | 100.0% |
| `src/app/projects/[id]/dashboard/page.tsx` | 9 | 9 | 100.0% |
| `src/app/projects/[id]/plan/ArticlePlanChat.tsx` | 17 | 17 | 100.0% |
| `src/app/projects/[id]/plan/ArticlePlanIssueList.tsx` | 0 | 1 | 0.0% |
| `src/app/projects/[id]/plan/ArticlePlanIssueListError.tsx` | 1 | 1 | 100.0% |
| `src/app/projects/[id]/plan/ArticlePlanProposals.tsx` | 0 | 1 | 0.0% |
| `src/app/projects/[id]/plan/ArticlePlanSessionList.tsx` | 8 | 9 | 88.9% |
| `src/app/projects/[id]/plan/ArticlePlanStructureProposal.tsx` | 0 | 1 | 0.0% |
| `src/app/projects/[id]/plan/ArticlePlanWorkspace.tsx` | 1 | 5 | 20.0% |
| `src/app/projects/[id]/plan/actions.ts` | 0 | 1 | 0.0% |
| `src/app/projects/[id]/plan/page.tsx` | 28 | 30 | 93.3% |
| `src/app/projects/[id]/posts/page.tsx` | 7 | 7 | 100.0% |
| `src/app/projects/[id]/settings/adsense/page.tsx` | 11 | 11 | 100.0% |
| `src/app/projects/[id]/settings/google-analytics/page.tsx` | 11 | 11 | 100.0% |
| `src/app/projects/[id]/settings/sns/page.tsx` | 30 | 30 | 100.0% |
| `src/app/projects/[id]/tag-design/TagDesignSettingsPanel.tsx` | 89 | 92 | 96.7% |
| `src/app/projects/[id]/tag-design/actions.ts` | 25 | 31 | 80.6% |
| `src/app/projects/[id]/tags/page.tsx` | 13 | 13 | 100.0% |
| `src/app/projects/actions.ts` | 0 | 0 | 100.0% |
| `src/app/projects/error.tsx` | 4 | 5 | 80.0% |
| `src/app/projects/page.tsx` | 3 | 6 | 50.0% |
| **合計(42 ファイル)** | **461** | **528** | **87.3%** |

### 完了時

コミット `da79f319`(テストを足した最後のコミット。本ファイルを足すコミットは `docs/` だけを変えるので計測値は変わらない)。
テスト 300 スイート・3,110 件、失敗 0・skipped 0。`npm run test:coverage` は終了コード 0(`jest.config.ts` の
リポジトリ全体の下限を含む)。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `src/app/projects/ProjectForm.tsx` | 10 | 10 | 100.0% |
| `src/app/projects/ProjectsTable.tsx` | 27 | 27 | 100.0% |
| `src/app/projects/[id]/(detail)/loading.tsx` | 1 | 1 | 100.0% |
| `src/app/projects/[id]/(detail)/page.tsx` | 32 | 32 | 100.0% |
| `src/app/projects/[id]/article-review/ArticleReviewButton.tsx` | 5 | 5 | 100.0% |
| `src/app/projects/[id]/article-review/ArticleReviewDecision.tsx` | 25 | 26 | 96.2% |
| `src/app/projects/[id]/article-review/ArticleReviewPullRequestList.tsx` | 9 | 10 | 90.0% |
| `src/app/projects/[id]/article-review/actions.ts` | 19 | 19 | 100.0% |
| `src/app/projects/[id]/article-review/articleReviewStateLabels.ts` | 3 | 3 | 100.0% |
| `src/app/projects/[id]/article-review/page.tsx` | 11 | 11 | 100.0% |
| `src/app/projects/[id]/custom-tags/CssBundleViewer.tsx` | 25 | 25 | 100.0% |
| `src/app/projects/[id]/custom-tags/CssSelectorPrefixForm.tsx` | 5 | 5 | 100.0% |
| `src/app/projects/[id]/custom-tags/ProjectCustomTagManager.tsx` | 58 | 58 | 100.0% |
| `src/app/projects/[id]/custom-tags/actions.ts` | 32 | 32 | 100.0% |
| `src/app/projects/[id]/custom-tags/css-bundle/route.ts` | 7 | 7 | 100.0% |
| `src/app/projects/[id]/custom-tags/preview/route.ts` | 12 | 12 | 100.0% |
| `src/app/projects/[id]/dashboard/AdSenseWidget.tsx` | 9 | 9 | 100.0% |
| `src/app/projects/[id]/dashboard/AiConnectionWidget.tsx` | 27 | 27 | 100.0% |
| `src/app/projects/[id]/dashboard/DashboardWidgetSlot.tsx` | 4 | 4 | 100.0% |
| `src/app/projects/[id]/dashboard/EnvironmentWidget.tsx` | 5 | 5 | 100.0% |
| `src/app/projects/[id]/dashboard/GoogleAnalyticsWidget.tsx` | 10 | 10 | 100.0% |
| `src/app/projects/[id]/dashboard/MembersWidget.tsx` | 7 | 7 | 100.0% |
| `src/app/projects/[id]/dashboard/page.tsx` | 10 | 10 | 100.0% |
| `src/app/projects/[id]/plan/ArticlePlanChat.tsx` | 27 | 27 | 100.0% |
| `src/app/projects/[id]/plan/ArticlePlanIssueList.tsx` | 12 | 12 | 100.0% |
| `src/app/projects/[id]/plan/ArticlePlanIssueListError.tsx` | 1 | 1 | 100.0% |
| `src/app/projects/[id]/plan/ArticlePlanProposals.tsx` | 24 | 26 | 92.3% |
| `src/app/projects/[id]/plan/ArticlePlanSessionList.tsx` | 11 | 11 | 100.0% |
| `src/app/projects/[id]/plan/ArticlePlanStructureProposal.tsx` | 19 | 21 | 90.5% |
| `src/app/projects/[id]/plan/ArticlePlanWorkspace.tsx` | 26 | 26 | 100.0% |
| `src/app/projects/[id]/plan/actions.ts` | 49 | 49 | 100.0% |
| `src/app/projects/[id]/plan/page.tsx` | 33 | 33 | 100.0% |
| `src/app/projects/[id]/posts/page.tsx` | 8 | 8 | 100.0% |
| `src/app/projects/[id]/settings/adsense/page.tsx` | 12 | 12 | 100.0% |
| `src/app/projects/[id]/settings/google-analytics/page.tsx` | 12 | 12 | 100.0% |
| `src/app/projects/[id]/settings/sns/page.tsx` | 29 | 29 | 100.0% |
| `src/app/projects/[id]/tag-design/TagDesignSettingsPanel.tsx` | 89 | 92 | 96.7% |
| `src/app/projects/[id]/tag-design/actions.ts` | 34 | 34 | 100.0% |
| `src/app/projects/[id]/tags/page.tsx` | 14 | 14 | 100.0% |
| `src/app/projects/actions.ts` | 11 | 11 | 100.0% |
| `src/app/projects/error.tsx` | 5 | 5 | 100.0% |
| `src/app/projects/page.tsx` | 10 | 10 | 100.0% |
| **合計(42 ファイル)** | **779** | **788** | **98.9%** |

未カバーは 9 分岐(下の「テストから到達できない分岐」に全件を載せる)。それ以外の分岐はすべて通っている。

## モックの境界

境界の考え方は Issue 本文の既定案(Q1・Q2。Epic #1711 で利用者が確定済み)のとおり。

- **外部との通信**: HTTP は `fetch` の層で模す。自前の通信ラッパー(`@/lib/apiClient`)・Server Action(`actions.ts`)・
  `@/lib/session`・`@/lib/auth`・`@/lib/loadOrReport`・`@/lib/llmJobResults`・`@/lib/formatDate`・コンポーネント
  (`Breadcrumb`・`Tabs`・`ViewerDateTime`・各パネル)・`react`・`next/link` は差し替えない。
- **実行基盤の代替(Q2)**: jest の上に実体が無い Next.js のサーバー実行時(`next/headers` など)・`next-auth*`・
  `server-only` は代替する。
- **ブラウザ / JS ランタイムの機能**: jsdom に無い・壊せない機能は、その機能が欠けた(または結果を観測できる)環境を
  再現するために差し替える。自前のモジュールではない。

自前モジュールを差し替えていた既存テスト(`@/lib/apiClient`・`@/lib/session`・`@/lib/auth`・`@/lib/formatDate`・
`@/components/ViewerDateTime`・`@/components/Breadcrumb`・`@/components/Tabs`・`next/link`・`react`・`../actions`・
`../custom-tags/actions`・`@/app/sites/actions`・`@/app/custom-tags/*`(生成フォーム・結果・マネージャー)・
`../ProjectForm`・`../ProjectsTable`・`../ProjectSectionNav`・`../EnvironmentSlot` など `[id]/` 直下の約 30 の子コンポーネント・
`../ArticlePlan*`・`../CssSelectorPrefixForm`・`../CssBundleViewer`・`../GoogleAnalyticsWidget`・`../AdSenseWidget`・
`../../tag-design/TagDesignSettingsPanel`・`../../custom-tags/ProjectCustomTagManager`・`../../../ProjectSns*Section` など)は、
すべて実物を使う形に書き直した。書き直しで、アサーションは減らしていない。
`toHaveBeenCalledWith(引数)` で確かめていたバックエンドの呼び出しは、同じ内容を HTTP の URL・本文で確かめる形にした
(エラーの文言は `apiClient` が組み立てる実物の `APIエラー (ステータス): 本文` になる)。子コンポーネントを
`() => null` に替えて渡された props を覗いていたページのテストは、実物を描画して、描かれた文言で確かめる形にした
(`page.tsx` が欄へ何を渡したかは、欄が出す文言・値で観測できる)。

共通の足場は次のとおり。差し替えるのは `global.fetch` だけ。

- `src/lib/__tests__/testUtils/serverRuntime.ts`(#1832)の `installFetch()` は `"<METHOD> <パスと検索文字列>"` ごとに gateway の
  応答を返し、登録の無い通信は例外にする。`resetServerRuntime()` はセッションの既定状態にそろえる。
- `src/lib/__tests__/testUtils/webAdminRuntime.ts`(#1833)の `ADMIN_SESSION` と `installFetchByPath()`(検索文字列を照合に使わない変種)。
- このディレクトリ内で足したバックエンドの代役(いずれも `installFetch` / `installFetchByPath` を呼ぶだけ):
  - `[id]/__tests__/projectDetailBackend.ts`: プロジェクト詳細ページが集める 10 種類の取得を既定で成功させる。
  - `[id]/article-review/__tests__/articleReviewBackend.ts`: レビュー開始・完了・差し戻しの 3 つの POST。
  - `[id]/plan/__tests__/planBackend.ts`: 記事計画(セッション・Issue・チャット・提案・構成)のすべての通信。

### 外部との通信

| 差し替えているもの | 方法 | 使うテスト | どの外部通信か |
| --- | --- | --- | --- |
| `global.fetch`(gateway の `/api/**`・`/api/operation-logs` の記録・ブラウザからの `/client-errors`・BFF の `/projects/**/custom-tags/*`) | `installFetch()` / `installFetchByPath()`、または `global.fetch = fetchMock` の代入(`error.test.tsx`・`CssBundleViewer.test.tsx`。通信そのものが `Error` でない値で失敗する場合は `jest.fn().mockRejectedValue("...")` を代入) | 28 テストファイルが `installFetch` / `installFetchByPath` を使い、13 ファイルが直接代入する(Server Action・Route Handler・Server Component のページ・クライアントコンポーネントの各テスト) | HTTP |
| gateway の向こう側のバックエンドの代役(`PlanBackend`・`ReviewBackend` の各応答と、`getProject`・`listPosts`・`listCustomTags` などの応答変数) | 応答を返す関数を `installFetch` のルートに載せる | 上の各テスト | HTTP(サーバー側の応答) |

### Next.js の実行基盤・next-auth の代替

| 差し替えているもの | 方法 | 使うテスト | どの実行基盤の代替か |
| --- | --- | --- | --- |
| `server-only` | `jest.mock("server-only", () => ({}))` | `fetch` を通すサーバー側のコードを読み込むテスト(38 ファイル) | サーバー専用であることを示す印。import するだけで例外を投げ、jest 上には実体が無い |
| `next/headers`(`cookies()` / `headers()`) | `nextHeadersModule`(`serverRuntime.ts`) | 同上(38 ファイル) | リクエスト文脈(Cookie・ヘッダー)。jest 上にリクエストは無い |
| `next/server` の `after` | `nextServerModule`(`serverRuntime.ts`) | 同上(38 ファイル) | リクエスト後処理のスケジューラ。リクエスト文脈の外で呼ぶと例外になる |
| `next-auth/jwt`(`getToken`) | `nextAuthJwtModule`(`serverRuntime.ts`) | 同上(38 ファイル) | セッション Cookie の復号。Next.js のリクエスト文脈に依存する |
| `next-auth`(`getServerSession`) | `nextAuthModule`(`serverRuntime.ts`)。値は `resetServerRuntime(ADMIN_SESSION / 一般ユーザー / null)` で与える | 同上(38 ファイル) | サーバー側のセッション取得。リクエスト文脈に依存する(`@/lib/session` の `requireAdminSession` / `getViewerTimeZone` は実物) |
| `next-auth/react`(`useSession`) | `jest.mock("next-auth/react")` の自動モックに認証済みの値を返させる(`ProjectCustomTagManager.*.test.tsx`)、または `useSession` だけを返す工場関数(`tags/__tests__/*.test.tsx`) | カスタムタグの生成フォームを描画する 5 ファイル | ブラウザ側のセッション取得。`SessionProvider` が無いと使えない |
| `next/navigation`(`redirect` は `NEXT_REDIRECT:<パス>` を投げて処理を打ち切る(実物と同じく、呼び出しの先へ進まない)。`notFound` は `NEXT_NOT_FOUND` を、`useRouter` は `refresh` を返す) | `jest.mock("next/navigation", ...)` | 同上(35 ファイル) | App Router のルーティング文脈 |
| `next/cache`(`revalidatePath`) | `jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }))`(呼び出しを検証するテストは呼び出しを観測する) | Server Action とそれを呼ぶクライアントコンポーネントのテスト(16 ファイル) | サーバーのキャッシュの再検証。Next.js のリクエスト文脈が無いと使えない |

### ブラウザ / JS ランタイムの機能

| 差し替えているもの | 方法 | 使うテスト | 何を再現するか |
| --- | --- | --- | --- |
| `window.confirm` | `jest.spyOn(window, "confirm").mockReturnValue(...)`(`afterEach` で戻す) | `custom-tags/__tests__/ProjectCustomTagManager.test.tsx` | ブラウザの確認ダイアログ(承認・キャンセルを選ぶ)。jsdom は未実装 |
| `navigator.clipboard` | `Object.assign(navigator, { clipboard: { writeText: jest.fn() } })` | `custom-tags/__tests__/CssBundleViewer.test.tsx` | クリップボードへの書き込み(コピーしたテキストを観測する)。jsdom は未実装 |
| `jest.useFakeTimers()` | 偽タイマーで時計を進める | `CssBundleViewer.test.tsx`(コピー表示の 2 秒)・`TagDesignSettingsPanel.test.tsx`(プレビューの 300ms デバウンス) | `setTimeout` を進めて待ち時間を省く。自前コードの置き換えではない |
| `console.error` | `jest.spyOn(console, "error").mockImplementation(...)` | 取得失敗を記録することを仕様として検証するページ・アクションのテスト、`error.test.tsx` | 標準エラー出力。記録を観測する(出力を捨てるだけのテストもある) |

実装の差し替えではないが、`fireEvent.submit(form)`(ブラウザの `required` の検証を通り抜けた送信を直接再現する)、
`react-dom/server` の `renderToStaticMarkup` / `renderToString`(effect を走らせない・マウント前の出力を得る)と
`react-dom/static` の `prerender`(`<Suspense>` の内側の async サーバーコンポーネントの完了を待つ。
`dashboard/__tests__/ProjectDashboardPageAiConnections.test.tsx`)も、テストが描画の仕方を選ぶ箇所として使っている。

### 「表に無いものを差し替えているテスト」が 0 件であることの確認

次の出力は、すべて上の表のどれかに当たる(`jest.mock` の対象は `server-only`・`next/server`・`next/headers`・`next-auth/jwt`・
`next-auth`・`next-auth/react`・`next/cache`・`next/navigation` の 8 種類だけで、`@/lib/*`・`../actions`・コンポーネント・
`react`・`next/link` を指すものは 0 件)。

```bash
cd apps/web/src/app/projects
FILES=$(find . -path '*__tests__*' -type f | grep -E "^\./(__tests__|\[id\]/(\(detail\)|article-review|custom-tags|dashboard|plan|posts|settings|tag-design|tags)/|\[id\]/__tests__/(ProjectDetail|CssSelectorPrefixForm|projectDetailBackend))")
grep -nE "jest\.(mock|doMock|spyOn|replaceProperty)\(|(global|globalThis|window)\.[A-Za-z.]+ *= |Object\.defineProperty\(|Object\.assign\(navigator|console\.(error|warn|log) *=" $FILES

# jest.mock の対象のモジュール名だけを数える(8 種類だけが出ることを確かめる)
grep -rhoE "jest\.(mock|doMock)\([\"'][^\"']+[\"']" $FILES | sed -E "s/jest\.(mock|doMock)\(//" | tr -d "\"'" | sort | uniq -c
```

## テストから到達できない分岐

完了時の `coverage-final.json` で、対象の本番コード 42 ファイルに未カバーの分岐は **9 件**(4 種類)。いずれも、
プロダクションコードを変えずには、その分岐に入る入力や状態を作れない。行は `branchMap` の `locations[i].start.line`。

| # | ファイル | 関数 | 行 | 分岐 | 到達できない具体的な理由 |
| ---: | --- | --- | ---: | --- | --- |
| 1 | `src/app/projects/[id]/article-review/ArticleReviewDecision.tsx` | `ArticleReviewDecision` 内の `reject` | 67 | `setError(result.error ?? "差し戻せませんでした")` の右辺 | `reject` が呼ぶ `rejectArticleReviewAction`(`actions.ts`)の戻り値は `{ rejected: true }` か `{ error: <文字列> }` の 2 通りだけで、`rejected` が偽のときは必ず `error` が文字列として入る(`err instanceof Error ? err.message : String(err)` は常に文字列で、`Error` の `message` が空文字でも `??` は空文字では右辺へ進まない)。`rejected` も `error` も無い戻り値を返す経路が、Server Action に存在しない。 |
| 2 | `src/app/projects/[id]/article-review/ArticleReviewPullRequestList.tsx` | `ArticleReviewPullRequestList` 内の `retain` | 23 | `current.some(...) ? current : [...]` の真側(既に残している PR) | `retain` を呼ぶのは行ごとの `ArticleReviewDecision` の `onSucceeded` だけで、`onSucceeded` は `succeed()` の中で 1 回だけ呼ばれる。成功すると `outcome` が入って操作ボタン(`outcome === null` のときだけ描画)が消えるので、同じ PR の `onSucceeded` が 2 回呼ばれる状態を作れない。 |
| 3 | `src/app/projects/[id]/plan/ArticlePlanProposals.tsx` | `ArticlePlanProposals` 内の `handleSuggestTitles` | 18 | `if (history.length === 0) return;` の `return` | `handleSuggestTitles` を呼べる入口は「タイトル提案を取得」ボタンの `onClick` だけで、ボタンは `disabled={isSuggesting \|\| history.length === 0}`。React は disabled のボタンのクリックを発火しないので、履歴が空のときは呼べない。 |
| 4 | 同上 | `handleAcceptPlan` | 33 | `if (selected.size === 0) return;` の `return` | 入口は「計画を受け入れる」ボタンだけで、`disabled={selected.size === 0 \|\| isAccepting}`。選択が空のときは押せない。 |
| 5 | `src/app/projects/[id]/plan/ArticlePlanStructureProposal.tsx` | `ArticlePlanStructureProposal` 内の `handleSuggestStructure` | 29 | `if (history.length === 0) return;` の `return` | 入口は「記事の構成を提案」ボタンだけで、`disabled={isSuggesting \|\| history.length === 0}`。 |
| 6 | 同上 | `handleAcceptStructure` | 43 | `if (!structure \|\| structure.trim() === "") return;` の `return` | 入口は「この内容でIssueを更新」ボタンだけで、`disabled={!structure \|\| structure.trim() === "" \|\| isAccepting}`。構成案が空のときは押せない。 |
| 7 | `src/app/projects/[id]/tag-design/TagDesignSettingsPanel.tsx` | `buildDefaultCss` | 141 | `switch (tagType)` の `default: return ""` | `tagType` の型 `EmbedTagType` は `"TOC" \| "BLOGCARD" \| "AMAZON"` の 3 値で、`case` がその 3 値をすべて持つ。タグ種別は `settings` の `tagType`(バックエンドの列挙)か、固定の `TAG_LABELS` のキーからしか入らず、4 つ目の値を渡す経路が無い。 |
| 8 | 同上 | `TagDesignGenerationForm` 内の `handleApply` | 252 | `if (!pendingResult) return;` の `return` | `handleApply` を呼べるのは「この結果を適用」ボタンの `onClick` だけで、そのボタンは `showResult`(`pendingResult !== null` を含む)が真のブロックの中にしか描画されない。同じ `pendingResult` を閉包しているので、偽の状態でボタンは DOM に無い。 |
| 9 | 同上 | `handleSave` | 258 | `if (!pendingResult) return;` の `return` | `handleSave` を呼べるのは「保存」ボタンだけで、8 と同じブロックの中にしか描画されない。 |

最初は届かないと見えた分岐は、プロダクションコードを変えずに通した。主なもの:

| ファイル | 関数 | 行 | 通した方法 |
| --- | --- | ---: | --- |
| `src/app/projects/[id]/article-review/ArticleReviewDecision.tsx` | `approve` | 46・48 | 完了の応答に `productionPostUrl` が無い場合の文言と、`branchDeleted` が無い応答(ブランチは削除できたものとして扱う) |
| `src/app/projects/[id]/plan/ArticlePlanChat.tsx` | `handleSendMessage` | 99 | 空のまま Enter を押す(ボタンは無効でも、Enter キーは入力欄から直接届く) |
| `src/app/projects/[id]/plan/actions.ts` | 各 Action | 全 `catch` | `fetch` 自体が `Error` でない値(文字列)で失敗する場合を再現(`global.fetch` へ `mockRejectedValue("...")` を代入) |
| `src/app/projects/[id]/tag-design/actions.ts` | `saveTagDesignSettingAction` | 43〜46 | 色・プリセットの項目がフォームに無い場合(`formData.get(...)` が `null`) |
| `src/app/projects/[id]/dashboard/AdSenseWidget.tsx`・`GoogleAnalyticsWidget.tsx` | 各ウィジェット | 26〜35 | 数値が `null` で返った場合の「-」表示 |
| `src/app/projects/[id]/dashboard/page.tsx` | `AiConnectionWidget`(`<Suspense>` の内側) | 全体 | `react-dom/static` の `prerender` で Suspense の完了を待つ(jsdom の描画は async サーバーコンポーネントを解決できない) |
| `src/app/projects/[id]/plan/ArticlePlanSessionList.tsx` | `ArticlePlanSessionList` | 全体 | `renderToStaticMarkup`(effect を走らせない)でマウント前の出力を得る。`react` も `formatDate` も差し替えない |

### 計測の対象外

`apps/web/jest.config.ts` の `collectCoverageFrom` は `src/**/*.d.ts`・`src/**/*.stories.*`・`src/**/index.*` を計測から外している。
対象のディレクトリにこれに当たるファイルは **0 件**(下のコマンドの出力が空)。したがって、計測対象外の分岐も無い。

```bash
cd apps/web/src/app/projects && find . \( -name "*.d.ts" -o -name "*.stories.*" -o -name "index.*" \)
```

`collectCoverageFrom` は `src/**` を拾うので、テストの足場(`src/lib/__tests__/testUtils/*.ts`)も、本ディレクトリの `__tests__/` に置いた
バックエンドの代役(`projectDetailBackend.ts`・`articleReviewBackend.ts`・`planBackend.ts`)もレポートに載るが、
本番コードではないので対象に含めない。`src/app/projects/page.tsx` は `ProjectsTable` を import しているが使っていない
(一覧は `page.tsx` の中に直接書かれている)。`ProjectsTable.tsx` は `ProjectsTable.test.tsx` が単体で通している。

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
pat=re.compile(r'^src/app/projects/(?:[^/\[]+|\[id\]/(?:\(detail\)|article-review|custom-tags|dashboard|plan|posts|settings|tag-design|tags)/.+)$')
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
着手時の値は、同じスクリプトを develop の `b0054563` で `npm run test:coverage` を実行した直後に走らせたもの。

差分にプロダクションコードが無いことの確認:

```bash
git diff origin/develop --name-only | grep -v "__tests__/"   # docs/coverage/web-projects-pages.md だけが出る
git diff origin/develop --name-only | python3 -c '
import sys; sys.path.insert(0, ".claude/hooks")
import paths
bad = [p for p in sys.stdin.read().split() if paths.is_production(p)]
print(bad); sys.exit(1 if bad else 0)'   # [] が出て終了コード 0
```
