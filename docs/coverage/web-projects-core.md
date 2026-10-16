# apps/web のプロジェクト詳細の共通部分の分岐カバレッジ(#1836)

Epic #1711 の子 Issue #1836 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`apps/web` の `src/app/projects/[id]/` 直下の共通部分に適用した結果。

対象は次の 20 ファイル(`.ts` 6・`.tsx` 14)の本番コード。

- Server Action: `actions.ts`
- ジョブ進捗: `jobProgress.ts`・`useGenerationJobPolling.ts`・`assetUploadJobResult.ts`・`bulkJobResult.ts`
- 基本設定とメンバー: `ProjectNameForm.tsx`・`DeleteProjectButton.tsx`・`deleteConfirmation.ts`・`ProjectSectionNav.tsx`・
  `ProjectUserManager.tsx`・`AddProjectUserModal.tsx`・`ProjectApiKeysForm.tsx`・`ProjectGithubRepositoryForm.tsx`
- 環境と一括管理: `MasterEnvironmentSelector.tsx`・`EnvironmentSlot.tsx`・`EnvironmentSyncPanel.tsx`・`BulkManagementPanel.tsx`・
  `PostComparisonTable.tsx`・`PluginThemeComparisonTable.tsx`・`TermComparisonTable.tsx`

同じディレクトリの他のファイル(`LlmModelPanel.tsx`・`ProjectAssetGenerationPanel.tsx`・`ProjectSns*Section.tsx` など)と、
下位ディレクトリ(`(detail)`・`plan`・`dashboard` など)は他の子 Issue の対象で、ここには含めない。
`actions.ts` は他の子 Issue の対象(AI・アセットや SNS のパネル)からも呼ばれるが、`actions.ts` の分岐の計測と一覧は本 Issue が受け持つ。

テストは `src/app/projects/[id]/__tests__/` のうち、`actions.ts` を直接呼ぶ `*Actions.test.ts`(`adSense`・`aiConnection`・`assetUpload`・
`bulkInstall`・`bulkManagement`・`claudeApiKey`・`environment`・`fetch`・`googleAnalytics`・`imageJob`・`openAiApiKey`・`projectSettings`・
`reviewStepSettings`・`syncEnvironment`・`syncProjectUser`・`updateProjectUserRole`・`uploadGeneratedImage`)と、上のコンポーネント・
フックを対象にするテスト(`BulkManagementPanel.*`・`EnvironmentSlot`・`EnvironmentSyncPanel`・`PluginThemeComparisonTable`・
`PostComparisonTable`・`ProjectSectionNav`・`ProjectSettingsForms`・`ProjectUserManager`・`TermComparisonTable.*`・`jobProgress`・
`useGenerationJobPolling`・`assetUploadJobResult`・`bulkJobResult`)と、足場の `projectCoreRuntime.ts`。

プロダクションコードは変更していない(差分は `apps/web/src/app/projects/[id]/__tests__/` と本ファイルだけ。末尾の確認コマンド)。
`jest.config.ts`・`jest.setup.ts` も変えていない。この Issue の受け入れ基準はテスト資産の状態なので、Gherkin の
受け入れシナリオは書かない(製品の振る舞いではなく web UI から到達できない。CLAUDE.md → Where the tests live の例外)。
足したテストは、本番コードが既にあるので、足した時点で通る。代わりに、着手時の `coverage-final.json` で該当の分岐が
未カバーだったこと(下の着手時の表と、再現手順の `unc` の出力 59 件)が、足したテストの必要性を示している。

## 計測値(着手時・完了時)

数え方は jest の `branches`(v8 プロバイダー)。`coverage/coverage-final.json` の `branchMap` の各分岐を 1 と数え、
`b` の実行回数が 1 以上なら covered。`||` / `&&` / `??` / 三項 / `if` / 既定値は v8 の出力でそれぞれ別の分岐になるので
C1 と C2 を兼ねる。計測コマンド(着手時・完了時とも同じ):

```bash
cd apps/web && npm run test:coverage
```

着手時の total より完了時の total が多いのは、分岐は「実行された関数」の内側だけが数えられるため(`actions.ts` の
Server Action は、着手時は `../actions` を差し替えたコンポーネントのテストが通さない関数が多く、各関数の中の分岐が数に入っていなかった)。
実物を通すようにして、関数の内側の分岐が数えられるようになった。

### 着手時

コミット `ef86621c`(develop 起点、変更なし)。テスト 300 スイート・3,110 件、失敗 0。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `src/app/projects/[id]/AddProjectUserModal.tsx` | 3 | 4 | 75.0% |
| `src/app/projects/[id]/BulkManagementPanel.tsx` | 41 | 55 | 74.5% |
| `src/app/projects/[id]/DeleteProjectButton.tsx` | 1 | 2 | 50.0% |
| `src/app/projects/[id]/EnvironmentSlot.tsx` | 9 | 10 | 90.0% |
| `src/app/projects/[id]/EnvironmentSyncPanel.tsx` | 34 | 35 | 97.1% |
| `src/app/projects/[id]/MasterEnvironmentSelector.tsx` | 5 | 6 | 83.3% |
| `src/app/projects/[id]/PluginThemeComparisonTable.tsx` | 75 | 78 | 96.2% |
| `src/app/projects/[id]/PostComparisonTable.tsx` | 43 | 51 | 84.3% |
| `src/app/projects/[id]/ProjectApiKeysForm.tsx` | 2 | 5 | 40.0% |
| `src/app/projects/[id]/ProjectGithubRepositoryForm.tsx` | 1 | 2 | 50.0% |
| `src/app/projects/[id]/ProjectNameForm.tsx` | 1 | 2 | 50.0% |
| `src/app/projects/[id]/ProjectSectionNav.tsx` | 6 | 6 | 100.0% |
| `src/app/projects/[id]/ProjectUserManager.tsx` | 26 | 27 | 96.3% |
| `src/app/projects/[id]/TermComparisonTable.tsx` | 111 | 118 | 94.1% |
| `src/app/projects/[id]/actions.ts` | 184 | 192 | 95.8% |
| `src/app/projects/[id]/assetUploadJobResult.ts` | 23 | 23 | 100.0% |
| `src/app/projects/[id]/bulkJobResult.ts` | 20 | 20 | 100.0% |
| `src/app/projects/[id]/deleteConfirmation.ts` | 4 | 4 | 100.0% |
| `src/app/projects/[id]/jobProgress.ts` | 2 | 9 | 22.2% |
| `src/app/projects/[id]/useGenerationJobPolling.ts` | 24 | 25 | 96.0% |
| **合計(20 ファイル)** | **615** | **674** | **91.2%** |

### 完了時

コミット `17f0b3bf`(テストを足した最後のコミット。本ファイルを足すコミットは `docs/` だけを変えるので計測値は変わらない)。
テスト 303 スイート・3,287 件、失敗 0・skipped 0。`npm run test:coverage` は終了コード 0(`jest.config.ts` の
リポジトリ全体の下限を含む)。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `src/app/projects/[id]/AddProjectUserModal.tsx` | 8 | 8 | 100.0% |
| `src/app/projects/[id]/BulkManagementPanel.tsx` | 75 | 76 | 98.7% |
| `src/app/projects/[id]/DeleteProjectButton.tsx` | 6 | 6 | 100.0% |
| `src/app/projects/[id]/EnvironmentSlot.tsx` | 12 | 12 | 100.0% |
| `src/app/projects/[id]/EnvironmentSyncPanel.tsx` | 44 | 44 | 100.0% |
| `src/app/projects/[id]/MasterEnvironmentSelector.tsx` | 8 | 8 | 100.0% |
| `src/app/projects/[id]/PluginThemeComparisonTable.tsx` | 79 | 80 | 98.8% |
| `src/app/projects/[id]/PostComparisonTable.tsx` | 61 | 61 | 100.0% |
| `src/app/projects/[id]/ProjectApiKeysForm.tsx` | 17 | 17 | 100.0% |
| `src/app/projects/[id]/ProjectGithubRepositoryForm.tsx` | 5 | 5 | 100.0% |
| `src/app/projects/[id]/ProjectNameForm.tsx` | 4 | 4 | 100.0% |
| `src/app/projects/[id]/ProjectSectionNav.tsx` | 6 | 6 | 100.0% |
| `src/app/projects/[id]/ProjectUserManager.tsx` | 30 | 30 | 100.0% |
| `src/app/projects/[id]/TermComparisonTable.tsx` | 120 | 123 | 97.6% |
| `src/app/projects/[id]/actions.ts` | 383 | 383 | 100.0% |
| `src/app/projects/[id]/assetUploadJobResult.ts` | 23 | 23 | 100.0% |
| `src/app/projects/[id]/bulkJobResult.ts` | 20 | 20 | 100.0% |
| `src/app/projects/[id]/deleteConfirmation.ts` | 4 | 4 | 100.0% |
| `src/app/projects/[id]/jobProgress.ts` | 24 | 24 | 100.0% |
| `src/app/projects/[id]/useGenerationJobPolling.ts` | 25 | 25 | 100.0% |
| **合計(20 ファイル)** | **954** | **959** | **99.5%** |

未カバーは 5 分岐(下の「テストから到達できない分岐」に全件を載せる)。それ以外の分岐はすべて通っている。

## モックの境界

境界の考え方は Issue 本文の既定案(Q1・Q2。Epic #1711 で利用者が確定済み)のとおり。

- **外部との通信**: HTTP は `fetch` の層で模す。自前の通信ラッパー(`@/lib/apiClient`)・Server Action(`../actions`・`../../actions`)・
  `@/lib/session`・`@/lib/auth`・`@/lib/formatDate`・比較表のコンポーネント(`TermComparisonTable`・`PluginThemeComparisonTable`・
  `PostComparisonTable`)・`react` は差し替えない。
- **実行基盤の代替(Q2)**: jest の上に実体が無い Next.js のサーバー実行時(`next/headers` など)・`next-auth*`・`server-only` は代替する。
- **ブラウザ / JS ランタイムの機能**: jsdom に無い・壊せない機能は、その機能が欠けた(または結果を観測できる)環境を
  再現するために差し替える。自前のモジュールではない。

自前モジュールを差し替えていた既存テスト(`@/lib/apiClient`・`@/lib/session`・`../actions`・`@/lib/formatDate`・
`../TermComparisonTable`・`../PluginThemeComparisonTable`・`../PostComparisonTable`・`react`(`useEffect` と `useActionState`))は、
すべて実物を使う形に書き直した。書き直しで、アサーションは減らしていない。
`toHaveBeenCalledWith(引数)` で確かめていたバックエンドの呼び出しは、同じ内容を HTTP の URL・本文で確かめる形にした
(エラーの文言は `apiClient` が組み立てる実物の `APIエラー (ステータス): 本文` になる)。
`react` を差し替えていた 2 つのテストは、次のとおり実物の `react` で同じ振る舞いを確かめる形にした。

- `EnvironmentSyncPanelJob.test.tsx`(`useActionState` を差し替えて `state` を与えていた): `EnvironmentSyncPanel.test.tsx` へ統合し、
  フォームを実際に送信して、`fetch` の応答(受理・エラー・応答の保留)から `state` と `pending` を作る形にした。ファイルは削除。
- `TermComparisonTable.mountGate.test.tsx`(`useEffect` を no-op にして「マウント前」を作っていた): `react` は差し替えず、
  React が送信フォームを DOM へ差し込んだ直後(passive effect で `mounted` が真になる前)の送信ボタンの `disabled` を記録して確かめる
  (下の表の `Node.prototype.insertBefore` / `appendChild`)。フォームはクリックで開くため、サーバー描画ではこの状態が得られない。

共通の足場は次のとおり。差し替えるのは `global.fetch` だけ。

- `src/lib/__tests__/testUtils/serverRuntime.ts`(#1832)の `resetServerRuntime()`・`jsonResponse()`・`textResponse()`・
  Next.js の実行基盤の代替(`nextServerModule` など)。
- `src/lib/__tests__/testUtils/webAdminRuntime.ts`(#1833)の `ADMIN_SESSION`・`installFetchByPath()`・`calledRequests()`・`attachFile()`。
- このディレクトリで足した `[id]/__tests__/projectCoreRuntime.ts`: `installBackend()`(`installFetchByPath` の別名)、
  `bodies(fetchMock, "<METHOD> <パス>")`(送られた JSON / FormData を呼び出し順に読む)、`failWith(status, text)`(HTTP エラー応答)、
  `failFetchWith(値)`(通信そのものが `Error` でない値で失敗)、`noContent`(204)、`form()`、`requestCount()`。

### 外部との通信

| 差し替えているもの | 方法 | 使うテスト | どの外部通信か |
| --- | --- | --- | --- |
| `global.fetch`(gateway の `/api/**`・`/api/operation-logs` の記録) | `installFetchByPath()` / `installBackend()`(`global.fetch` へ `jest.fn()` を代入) | 28 テストファイル(Server Action 17・コンポーネント / フック 11)。`global.fetch = ...` の代入は `webAdminRuntime.ts` の `installFetchByPath`(#1833。`installBackend` が呼ぶ)と `projectCoreRuntime.ts` の `failFetchWith` の 2 か所に集まる | HTTP |
| 通信そのものが `Error` でない値で失敗する `fetch` | `failFetchWith("plain")`(`projectCoreRuntime.ts`)、`BulkManagementPanel.bulkJob.test.tsx` の `global.fetch = jest.fn().mockRejectedValue("boom")` | 18 テストファイル | HTTP(`fetch` 自体の拒否。`catch` の `err instanceof Error ? err.message : String(err)` の右辺を通す) |
| gateway の向こう側のバックエンドの代役(各テストの応答関数。ジョブの状態を順に返す `useGenerationJobPolling.test.tsx` の `script`、保留して後から返す `new Promise<Response>` を含む) | 応答を返す関数を `installFetchByPath` のルートに載せる | 上の各テスト | HTTP(サーバー側の応答) |

### Next.js の実行基盤・next-auth の代替

| 差し替えているもの | 方法 | 使うテスト | どの実行基盤の代替か |
| --- | --- | --- | --- |
| `server-only` | `jest.mock("server-only", () => ({}))` | `fetch` を通すサーバー側のコードを読み込むテスト(28 ファイル) | サーバー専用であることを示す印。import するだけで例外を投げ、jest 上には実体が無い |
| `next/headers`(`cookies()` / `headers()`) | `nextHeadersModule`(`serverRuntime.ts`) | 同上(28 ファイル) | リクエスト文脈(Cookie・ヘッダー)。jest 上にリクエストは無い |
| `next/server` の `after` | `nextServerModule`(`serverRuntime.ts`) | 同上(28 ファイル) | リクエスト後処理のスケジューラ。リクエスト文脈の外で呼ぶと例外になる |
| `next-auth/jwt`(`getToken`) | `nextAuthJwtModule`(`serverRuntime.ts`) | 同上(28 ファイル) | セッション Cookie の復号。Next.js のリクエスト文脈に依存する |
| `next-auth`(`getServerSession`) | `nextAuthModule`(`serverRuntime.ts`)。値は `resetServerRuntime(ADMIN_SESSION / 一般ユーザー)` で与える | 同上(28 ファイル) | サーバー側のセッション取得。リクエスト文脈に依存する(`@/lib/session` の `requireAdminSession` は実物) |
| `next/navigation`(`redirect` は `NEXT_REDIRECT:<パス>` を投げて処理を打ち切る。`ProjectSettingsForms.test.tsx` だけは呼ばれた先を記録するだけで投げない。`useRouter` は `refresh` / `push` を返す) | `jest.mock("next/navigation", ...)` | 同上(28 ファイル) | App Router のルーティング文脈。`redirect` を投げた例外を捕まえて遷移に変える呼び出し基盤は、クライアントから呼んだ Server Action では jest 上に無いため、`deleteProjectAction` の `redirect` は記録だけにする |
| `next/cache`(`revalidatePath`) | `jest.mock("next/cache", () => ({ revalidatePath: ... }))`(呼び出しを検証するテストは呼び出しを観測する) | 同上(28 ファイル) | サーバーのキャッシュの再検証。Next.js のリクエスト文脈が無いと使えない |

### ブラウザ / JS ランタイムの機能

| 差し替えているもの | 方法 | 使うテスト | 何を再現するか |
| --- | --- | --- | --- |
| `window.confirm` | `jest.spyOn(window, "confirm").mockReturnValue(...)`(`afterEach` で `mockRestore`) | `BulkManagementPanel.bulkJob`・`EnvironmentSyncPanel`・`PluginThemeComparisonTable`・`PostComparisonTable`・`ProjectSettingsForms`・`ProjectUserManager`・`TermComparisonTable` | ブラウザの確認ダイアログ(承認・キャンセルを選ぶ)。jsdom は未実装 |
| `navigator.clipboard` | `Object.assign(navigator, { clipboard: { writeText: jest.fn() } })` | `BulkManagementPanel.copyLog.test.tsx` | クリップボードへの書き込み(コピーしたテキストを観測する)。jsdom は未実装 |
| `jest.useFakeTimers()` | 偽タイマーで時計を進める | `useGenerationJobPolling.test.tsx`(ポーリングの 2 秒間隔)・`BulkManagementPanel.copyLog.test.tsx`(コピー表示の 2 秒) | `setTimeout` を進めて待ち時間を省く。自前コードの置き換えではない |
| `Node.prototype.insertBefore` / `Node.prototype.appendChild` | `jest.spyOn(...).mockImplementation(...)`(本物を呼んだ直後に、挿入されたフォームの送信ボタンの `disabled` を記録し、本物の戻り値を返す。テストの最後に `mockRestore`) | `TermComparisonTable.mountGate.test.tsx` | React が DOM へ要素を差し込む瞬間の観測。差し込みそのものは本物へ通す(`useEffect` を差し替えずに「マウント前」を見るため) |

実装の差し替えではないが、`fireEvent.submit(form)`(ブラウザの `required` の検証を通り抜けた送信を直接再現する)、
`attachFile()`(`<input type="file">` に利用者がファイルを選んだ状態を作る)、`MutationObserver`
(`ProjectSettingsForms.test.tsx`: `startTransition` の内側で await されない削除の「削除中…」が一瞬しか描かれないため、
文字列の差し替えの前の値を記録して観測する。DOM の変更を読むだけで、何も差し替えない)も、テストが操作と観測の仕方を選ぶ箇所として使っている。

### 「表に無いものを差し替えているテスト」が 0 件であることの確認

次の出力は、すべて上の表のどれかに当たる(`jest.mock` の対象は `server-only`・`next/server`・`next/headers`・`next-auth/jwt`・
`next-auth`・`next/navigation`・`next/cache` の 7 種類だけで、`@/lib/*`・`../actions`・コンポーネント・`react` を指すものは 0 件)。

```bash
cd "apps/web/src/app/projects/[id]/__tests__"
FILES="BulkManagementPanel.bulkJob.test.tsx BulkManagementPanel.copyLog.test.tsx EnvironmentSlot.test.tsx EnvironmentSyncPanel.test.tsx PluginThemeComparisonTable.test.tsx PostComparisonTable.test.tsx ProjectSectionNav.test.tsx ProjectSettingsForms.test.tsx ProjectUserManager.test.tsx TermComparisonTable.mountGate.test.tsx TermComparisonTable.test.tsx adSenseActions.test.ts aiConnectionActions.test.ts assetUploadActions.test.ts assetUploadJobResult.test.ts bulkInstallActions.test.ts bulkJobResult.test.ts bulkManagementActions.test.ts claudeApiKeyActions.test.ts environmentActions.test.ts fetchActions.test.ts googleAnalyticsActions.test.ts imageJobActions.test.ts jobProgress.test.ts openAiApiKeyActions.test.ts projectCoreRuntime.ts projectSettingsActions.test.ts reviewStepSettingsActions.test.ts syncEnvironmentAction.test.ts syncProjectUserActions.test.ts updateProjectUserRoleActions.test.ts uploadGeneratedImageAction.test.ts useGenerationJobPolling.test.tsx"
grep -nE "jest\.(mock|doMock|spyOn|replaceProperty)\(|(global|globalThis|window)\.[A-Za-z.]+ *= |Object\.defineProperty\(|Object\.assign\(navigator|console\.(error|warn|log) *=" $FILES

# jest.mock の対象のモジュール名だけを数える(7 種類だけが出ることを確かめる)
grep -rhoE "jest\.(mock|doMock)\([\"'][^\"']+[\"']" $FILES | sed -E "s/jest\.(mock|doMock)\(//" | tr -d "\"'" | sort | uniq -c
```

## テストから到達できない分岐

完了時の `coverage-final.json` で、対象の本番コード 20 ファイルに未カバーの分岐は **5 件**(4 種類)。いずれも、
プロダクションコードを変えずには、その分岐に入る入力や状態を作れない。行は `branchMap` の `locations[i].start.line`。

| # | ファイル | 関数 | 行 | 分岐 | 到達できない具体的な理由 |
| ---: | --- | --- | ---: | --- | --- |
| 1 | `src/app/projects/[id]/BulkManagementPanel.tsx` | `ZipUploadPanel` 内の `handleUploadSubmit` | 307 | `` `${file?.name ?? ""}` `` の右辺 | `file` は `new FormData(e.currentTarget).get("file")`。送信されるフォームには常に `<input name="file" type="file" required>` があり、ファイルを選んでいなくても FormData には名前が `""`(空文字)の空の `File` が入る(`null` にならず、`File.name` は常に文字列)。`file` が `null` / `name` が `undefined` になる状態は、フォームから `file` の入力欄を取り除かない限り作れない(本番のコードは取り除かない)。 |
| 2 | `src/app/projects/[id]/PluginThemeComparisonTable.tsx` | `NewInstallForm` 内の `handleSubmit` | 319 | `String(formData.get("environment") ?? "")` の右辺 | `environment` は同じフォームの `<select name="environment" required>`。`<select>` は必ず 1 つの `<option>` を選んだ状態(最初は `value=""`)で FormData に載るので `null` にならない。欄が無い FormData を作れるのは、フォームから `<select>` を取り除いた場合だけ。 |
| 3 | `src/app/projects/[id]/TermComparisonTable.tsx` | `EditItemForm` 内の `handleSubmit` | 454 | `String(formData.get("value") ?? "")` の右辺 | `value` は同じフォームの常に描かれる `<input name="value" required>`。FormData に載らない(`null`)のは入力欄が無いときだけで、編集フォームは常に描く。 |
| 4 | 同上 | 同上 | 455 | `String(formData.get("categorySlug") ?? "")` の右辺 | 3 と同じ。`<input name="categorySlug" required>` は常に描かれる。 |
| 5 | 同上 | 同上 | 457 | `String(formData.get("categoryDescription") ?? "")` の右辺 | 3 と同じ。`<input name="categoryDescription">` は常に描かれる(カテゴリでもタグでも)。同じ行の `categoryParentSlug`(456)は、タグでは入力欄を描かないので `null` になり、タグの編集のテストで通している。 |

最初は届かないと見えた分岐は、プロダクションコードを変えずに通した。主なもの:

| ファイル | 関数 | 行 | 通した方法 |
| --- | --- | ---: | --- |
| `src/app/projects/[id]/actions.ts` | 各 Server Action の `formData.get(...) ?? ""` の右辺 | 158・186・213・343・371・464・491・522・587・712〜762・779・884 ほか | 欄が無い `new FormData()` で直接呼ぶ(Server Action は利用者の送信を信用せず、改ざんされた送信にも備える入り口)。`bulkManagementActions.test.ts`・`projectSettingsActions.test.ts` ほか |
| `src/app/projects/[id]/actions.ts` | 各 Server Action の `catch` | 全 `catch` | `fetch` 自体が `Error` でない値(文字列)で失敗する場合を再現(`failFetchWith("plain")`) |
| `src/app/projects/[id]/PluginThemeComparisonTable.tsx` | `handleApply` | 84 | 対象外(`available: false`)・状態が取れていない(`status: null`)環境のセルを含む行で、残りの環境だけ変更する |
| `src/app/projects/[id]/PluginThemeComparisonTable.tsx` | `NewInstallForm` 内の `handleSubmit` | 315 | テーマのタブで新規インストールを送る(`THEME_INSTALL`) |
| `src/app/projects/[id]/TermComparisonTable.tsx` | `NewItemForm` 内の `handleSubmit` | 380 | タグの新規追加を送る(`TAG_CREATE`) |
| `src/app/projects/[id]/TermComparisonTable.tsx` | `preferredValue` | 36 | どの環境にも値が無い行を編集フォームで開く(マスターの値へ戻る) |
| `src/app/projects/[id]/BulkManagementPanel.tsx` | `TabLoading` | 286 | タグの取得が終わって「読み込み中…」の対象が消えたあとも、取得中のテーマのタブは「このタブを開くとデータを取得します。」になる(2 つの取得を保留して順に終わらせる) |
| `src/app/projects/[id]/BulkManagementPanel.tsx` | `handleTabChange` | 117〜125 | 各タブを初めて開いて取得し、2 回目以降は取り直さない |
| `src/app/projects/[id]/EnvironmentSyncPanel.tsx` | `handleSubmit` | 90 | 同期元・同期先を選ばないまま送る(`fireEvent.submit` で `required` を通り抜ける。確認文は空の環境名で組み立てられる) |
| `src/app/projects/[id]/DeleteProjectButton.tsx` | `DeleteProjectButton` | 25 | `startTransition` の内側で await されない削除の「削除中…」を、`MutationObserver` の `oldValue` で観測する |
| `src/app/projects/[id]/useGenerationJobPolling.ts` | `poll` | 64 | 取得の成功が返る前にアンマウントされた場合に、完了も進捗も通知しない |
| `src/app/projects/[id]/jobProgress.ts` | `parseJobProgress`・`formatJobProgress` | 13〜36 | 純関数なので、`jobProgress.test.ts` で入力を直接与える |

### 計測の対象外

`apps/web/jest.config.ts` の `collectCoverageFrom` は `src/**/*.d.ts`・`src/**/*.stories.*`・`src/**/index.*` を計測から外している。
対象の 20 ファイルのディレクトリ(`src/app/projects/[id]/` の直下)にこれに当たるファイルは **0 件**(下のコマンドの出力が空)。
したがって、計測対象外の分岐も無い。

```bash
cd "apps/web/src/app/projects/[id]" && find . -maxdepth 1 \( -name "*.d.ts" -o -name "*.stories.*" -o -name "index.*" \)
```

`collectCoverageFrom` は `src/**` を拾うので、テストの足場(`src/lib/__tests__/testUtils/*.ts`)も、本ディレクトリの `__tests__/`
に置いた足場(`projectCoreRuntime.ts`)もレポートに載るが、本番コードではないので対象に含めない。

## 再現手順

完了時の数字・「未カバー 5 件」は次で再現できる。

```bash
cd apps/web && npm run test:coverage
```

`extract.py`(リポジトリの外に置き、`apps/web` で実行する。`rows` はファイル別の covered / total / 率と合計、
`unc` は未カバーの分岐を 1 行ずつ出し、最後の行がその件数):

```python
import json,os,sys
d=json.load(open('coverage/coverage-final.json'))
root=os.getcwd()+'/'
names="actions.ts jobProgress.ts useGenerationJobPolling.ts assetUploadJobResult.ts bulkJobResult.ts ProjectNameForm.tsx DeleteProjectButton.tsx deleteConfirmation.ts ProjectSectionNav.tsx ProjectUserManager.tsx AddProjectUserModal.tsx ProjectApiKeysForm.tsx ProjectGithubRepositoryForm.tsx MasterEnvironmentSelector.tsx EnvironmentSlot.tsx EnvironmentSyncPanel.tsx BulkManagementPanel.tsx PostComparisonTable.tsx PluginThemeComparisonTable.tsx TermComparisonTable.tsx".split()
targets={'src/app/projects/[id]/'+n for n in names}
mode=sys.argv[1]
rows=[];unc=[]
for t in sorted(targets):
    v=d.get(root+t)
    if v is None:
        rows.append((t,0,0));continue
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
                unc.append((t,fn,line,locs['type'],i))
    rows.append((t,cov,tot))
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
(未カバーの分岐。完了時は `(ファイル, 関数, 行, 種類, 分岐の番号)` が 5 行と、最後に `5`)。出力の
(ファイル, 行)が、上の「テストから到達できない分岐」の表の # 1〜5 と一致することを確かめる(1 件も欠けず、余りも無い)。
着手時の値は、同じスクリプトを develop 起点の `ef86621c` で `npm run test:coverage` を実行した直後に走らせたもの
(`unc` の最後の行は `59`)。

差分にプロダクションコードが無いことの確認:

```bash
git diff origin/develop --name-only | grep -v "__tests__/"   # docs/coverage/web-projects-core.md だけが出る
git diff origin/develop --name-only | python3 -c '
import sys; sys.path.insert(0, ".claude/hooks")
import paths
bad = [p for p in sys.stdin.read().split() if paths.is_production(p)]
print(bad); sys.exit(1 if bad else 0)'   # [] が出て終了コード 0
```
