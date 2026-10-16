# apps/web のプロジェクト詳細の AI・アセットのパネルの分岐カバレッジ(#1837)

Epic #1711 の子 Issue #1837 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`apps/web` の `src/app/projects/[id]/` 直下の AI・アセットのパネルに適用した結果。

対象は次の 16 ファイル(`.tsx`)の本番コード。

- アセット画像の生成: `ProjectAssetGenerationPanel.tsx`・`GarbageCollectionPanel.tsx`
- AI モデルの管理: `ProjectAiModelsPanel.tsx`・`LlmModelPanel.tsx`・`LlmProviderPanel.tsx`・`ReviewStepSettingsPanel.tsx`・
  `ImageProviderPanel.tsx`・`ComfyUiCheckpointTable.tsx`
- 接続設定: `AiConnectionSection.tsx`・`ClaudeConnectionSection.tsx`・`ChatGptConnectionSection.tsx`・`OllamaModelPullForm.tsx`
- 画像の既定値: `ProjectImageContentFilterSettingsForm.tsx`・`ProjectImageGenerationPromptDefaultsForm.tsx`・
  `ProjectImageGenerationSizeDefaultsForm.tsx`・`ProjectArticleImageResizeDefaultForm.tsx`

同じディレクトリの他のファイル(`actions.ts`・`useGenerationJobPolling.ts` など)は #1836 の対象で、`docs/coverage/web-projects-core.md` に記録がある。
`ProjectPluginOverridesPanel.tsx`・`ProjectSns*Section.tsx` なども他の子 Issue の対象で、ここには含めない。
本 Issue のテストは `actions.ts` を実物で通すが、`actions.ts` の分岐の計測と一覧は #1836 が受け持つ。

テストは `src/app/projects/[id]/__tests__/` のうち、上の 16 ファイル名で始まる 20 ファイル(既存 17 を書き直し、
`ProjectImageGenerationPromptDefaultsForm`・`ProjectImageGenerationSizeDefaultsForm`・`ProjectArticleImageResizeDefaultForm` の 3 つを新規)と、
足場の `projectCoreRuntime.ts`(#1836 のものに `answer()` を足した)・`assetPanelRuntime.ts`(新規)。

プロダクションコードは変更していない(差分は `apps/web/src/app/projects/[id]/__tests__/` と本ファイルだけ。末尾の確認コマンド)。
`jest.config.ts`・`jest.setup.ts` も変えていない。この Issue の受け入れ基準はテスト資産の状態なので、Gherkin の
受け入れシナリオは書かない(製品の振る舞いではなく web UI から到達できない。CLAUDE.md → Where the tests live の例外)。
足したテストは、本番コードが既にあるので、足した時点で通る。代わりに、着手時の `coverage-final.json` で該当の分岐が
未カバーだったこと(下の着手時の表と、再現手順の `unc` の出力 40 件)が、足したテストの必要性を示している。

## 計測値(着手時・完了時)

数え方は jest の `branches`(v8 プロバイダー)。`coverage/coverage-final.json` の `branchMap` の各分岐を 1 と数え、
`b` の実行回数が 1 以上なら covered。`||` / `&&` / `??` / 三項 / `if` / 既定値は v8 の出力でそれぞれ別の分岐になるので
C1 と C2 を兼ねる。計測コマンド(着手時・完了時とも同じ):

```bash
cd apps/web && npm run test:coverage
```

着手時より完了時の total が多いのは、分岐は「実行された関数」の内側だけが数えられるため。着手時は `../actions` を差し替えていたので、
差し替えた関数の内側の分岐は数に入っていなかった。実物を通すようにして、関数の内側の分岐も数えられるようになった。

### 着手時

コミット `4538ea42`(develop 起点、変更なし)。テスト 303 スイート・3,287 件、失敗 0。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `src/app/projects/[id]/AiConnectionSection.tsx` | 54 | 56 | 96.4% |
| `src/app/projects/[id]/ChatGptConnectionSection.tsx` | 32 | 36 | 88.9% |
| `src/app/projects/[id]/ClaudeConnectionSection.tsx` | 32 | 36 | 88.9% |
| `src/app/projects/[id]/ComfyUiCheckpointTable.tsx` | 15 | 15 | 100.0% |
| `src/app/projects/[id]/GarbageCollectionPanel.tsx` | 43 | 59 | 72.9% |
| `src/app/projects/[id]/ImageProviderPanel.tsx` | 10 | 11 | 90.9% |
| `src/app/projects/[id]/LlmModelPanel.tsx` | 19 | 19 | 100.0% |
| `src/app/projects/[id]/LlmProviderPanel.tsx` | 13 | 14 | 92.9% |
| `src/app/projects/[id]/OllamaModelPullForm.tsx` | 33 | 33 | 100.0% |
| `src/app/projects/[id]/ProjectAiModelsPanel.tsx` | 55 | 59 | 93.2% |
| `src/app/projects/[id]/ProjectArticleImageResizeDefaultForm.tsx` | 1 | 2 | 50.0% |
| `src/app/projects/[id]/ProjectAssetGenerationPanel.tsx` | 239 | 244 | 98.0% |
| `src/app/projects/[id]/ProjectImageContentFilterSettingsForm.tsx` | 3 | 3 | 100.0% |
| `src/app/projects/[id]/ProjectImageGenerationPromptDefaultsForm.tsx` | 1 | 2 | 50.0% |
| `src/app/projects/[id]/ProjectImageGenerationSizeDefaultsForm.tsx` | 1 | 2 | 50.0% |
| `src/app/projects/[id]/ReviewStepSettingsPanel.tsx` | 40 | 40 | 100.0% |
| **合計(16 ファイル)** | **591** | **631** | **93.7%** |

### 完了時

コミット `c21005c9`(テストを足した最後のコミット。本ファイルを足すコミットは `docs/` だけを変えるので計測値は変わらない)。
テスト 306 スイート・3,342 件、失敗 0・skipped 0。`npm run test:coverage` は終了コード 0(`jest.config.ts` の
リポジトリ全体の下限を含む)。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `src/app/projects/[id]/AiConnectionSection.tsx` | 59 | 59 | 100.0% |
| `src/app/projects/[id]/ChatGptConnectionSection.tsx` | 38 | 39 | 97.4% |
| `src/app/projects/[id]/ClaudeConnectionSection.tsx` | 38 | 39 | 97.4% |
| `src/app/projects/[id]/ComfyUiCheckpointTable.tsx` | 15 | 15 | 100.0% |
| `src/app/projects/[id]/GarbageCollectionPanel.tsx` | 73 | 75 | 97.3% |
| `src/app/projects/[id]/ImageProviderPanel.tsx` | 11 | 11 | 100.0% |
| `src/app/projects/[id]/LlmModelPanel.tsx` | 19 | 19 | 100.0% |
| `src/app/projects/[id]/LlmProviderPanel.tsx` | 15 | 15 | 100.0% |
| `src/app/projects/[id]/OllamaModelPullForm.tsx` | 33 | 33 | 100.0% |
| `src/app/projects/[id]/ProjectAiModelsPanel.tsx` | 67 | 67 | 100.0% |
| `src/app/projects/[id]/ProjectArticleImageResizeDefaultForm.tsx` | 5 | 5 | 100.0% |
| `src/app/projects/[id]/ProjectAssetGenerationPanel.tsx` | 242 | 247 | 98.0% |
| `src/app/projects/[id]/ProjectImageContentFilterSettingsForm.tsx` | 4 | 4 | 100.0% |
| `src/app/projects/[id]/ProjectImageGenerationPromptDefaultsForm.tsx` | 6 | 6 | 100.0% |
| `src/app/projects/[id]/ProjectImageGenerationSizeDefaultsForm.tsx` | 6 | 6 | 100.0% |
| `src/app/projects/[id]/ReviewStepSettingsPanel.tsx` | 40 | 40 | 100.0% |
| **合計(16 ファイル)** | **671** | **680** | **98.7%** |

未カバーは 9 分岐(下の「テストから到達できない分岐」に全件を載せる)。それ以外の分岐はすべて通っている。

## モックの境界

境界の考え方は Issue 本文の既定案(Q1・Q2。Epic #1711 で利用者が確定済み)のとおり。

- **外部との通信**: HTTP は `fetch` の層で模す。自前の通信ラッパー(`@/lib/apiClient`)・Server Action(`../actions`・
  `../projectPluginRegistrationActions`)・`@/lib/session`・`@/lib/auth`・子コンポーネント(`ProjectPluginOverridesPanel`・
  `OllamaModelPullForm`・`LlmModelPanel` など)・`react` は差し替えない。
- **実行基盤の代替(Q2)**: jest の上に実体が無い Next.js のサーバー実行時(`next/headers` など)・`next-auth*`・`server-only` は代替する。
- **ブラウザ / JS ランタイムの機能**: jsdom に無い・壊せない機能は、その機能が欠けた(または結果を観測できる)環境を
  再現するために差し替える。自前のモジュールではない。

自前モジュールを差し替えていた既存テスト(`../actions`(17 ファイル 19 件)・`../projectPluginRegistrationActions`・`react`(`useEffect` と
`useActionState`))は、すべて実物を使う形に書き直した。書き直しで、アサーションは減らしていない。

- `../actions` の関数を `jest.fn()` にして引数を `toHaveBeenCalledWith(引数)` で確かめていた箇所は、同じ内容を HTTP の経路・本文で
  確かめる形にした。経路ごとに「バックエンドの代役」の `jest.fn()`(下の表)を置き、実際に送られた HTTP 要求の本文や URL のパラメータを
  代役の引数に渡して、引数を確かめる。代役が受け取る引数は HTTP 要求から取り出した値で、自前モジュールの呼び出し引数ではない。
- Server Action が返す `{ error }` は、バックエンドの応答(`failWith(状態, 本文)` の HTTP エラー)から `apiClient` が組み立てる
  実物の `APIエラー (状態): 本文` になる。期待する文言はこれに合わせて更新した。失敗の意味(エラーを表示する・成功とは示さない)は変えていない。
- `react` を差し替えていた 2 つのテストは、次のとおり実物の `react` で同じ振る舞いを確かめる形にした。
  - `LlmModelPanel.mountGate.test.tsx`(`useEffect` を no-op にして「マウント前」を作っていた): `react` は差し替えず、
    React が DOM へ要素を差し込んだ直後(passive effect で `mounted` が真になる前)の保存ボタンの `disabled` を記録して確かめる。
  - `ProjectImageContentFilterSettingsForm.test.tsx`(`useActionState` の戻り値を固定して「保存中」「エラー」「成功」を作っていた):
    フォームを実際に送信し、`fetch` の応答(応答の保留・HTTP エラー・成功)から `state` と `pending` を作る形にした。

共通の足場は次のとおり。差し替えるのは `global.fetch` だけ。

- `src/lib/__tests__/testUtils/serverRuntime.ts`(#1832)の `resetServerRuntime()`・`jsonResponse()`・`textResponse()`・
  Next.js の実行基盤の代替(`nextServerModule` など)。
- `src/lib/__tests__/testUtils/webAdminRuntime.ts`(#1833)の `ADMIN_SESSION`・`installFetchByPath()`・`calledRequests()`。
- `[id]/__tests__/projectCoreRuntime.ts`(#1836、本 Issue で `answer()` を追加): `installBackend()`、`bodies(fetchMock, "<METHOD> <パス>")`、
  `failWith(状態, 本文)`、`noContent`。`answer(backend)` は「バックエンドの代役」の `jest.fn()` を経路に載せる関数で、解決値を 200 の JSON、
  `Response` はそのまま、`Error` の拒否は 500 の応答、`Error` でない値の拒否は `fetch` 自体がその値で失敗する状態にする。
- `[id]/__tests__/assetPanelRuntime.ts`(本 Issue): `ProjectAssetGenerationPanel` の 4 つのテストが共有する代役
  `backend.imageOptions`・`gallery`・`chatPrompt`・`imageJob`・`job`・`assetUpload`・`imageUpload`(HTTP の経路ごとに 1 つ)と、
  それを `fetch` に載せる `installAssetPanelBackend()`。

### 外部との通信

| 差し替えているもの | 方法 | 使うテスト | どの外部通信か |
| --- | --- | --- | --- |
| `global.fetch`(gateway の `/api/**`・`/api/operation-logs` の記録) | `installFetchByPath()` / `installBackend()` / `installAssetPanelBackend()`(`global.fetch` へ `jest.fn()` を代入。代入は `webAdminRuntime.ts` の `installFetchByPath` の 1 か所に集まる) | 対象の 20 テストファイルすべて | HTTP |
| gateway の向こう側のバックエンドの代役(各テストの `fetchLlmModelsMock` のような `jest.fn()`、`assetPanelRuntime.ts` の `backend.*`。保留して後から返す `new Promise` を含む) | `answer(() => 代役())` を `installBackend` の経路に載せる | 上の各テスト | HTTP(サーバー側の応答) |
| 通信そのものが `Error` でない値で失敗する `fetch` | 代役が文字列を `mockRejectedValue("…")` し、`answer()` が `fetch` の拒否として投げる | `AiConnectionSection`・`Claude/ChatGptConnectionSection`・`ProjectAssetGenerationPanel*` など | HTTP(`fetch` 自体の拒否) |

### Next.js の実行基盤・next-auth の代替

| 差し替えているもの | 方法 | 使うテスト | どの実行基盤の代替か |
| --- | --- | --- | --- |
| `server-only` | `jest.mock("server-only", () => ({}))` | 対象の 20 テストファイル | サーバー専用であることを示す印。import するだけで例外を投げ、jest 上には実体が無い |
| `next/headers`(`cookies()` / `headers()`) | `nextHeadersModule`(`serverRuntime.ts`) | 同上 | リクエスト文脈(Cookie・ヘッダー)。jest 上にリクエストは無い |
| `next/server` の `after` | `nextServerModule`(`serverRuntime.ts`) | 同上 | リクエスト後処理のスケジューラ。リクエスト文脈の外で呼ぶと例外になる |
| `next-auth/jwt`(`getToken`) | `nextAuthJwtModule`(`serverRuntime.ts`) | 同上 | セッション Cookie の復号。Next.js のリクエスト文脈に依存する |
| `next-auth`(`getServerSession`) | `nextAuthModule`(`serverRuntime.ts`)。値は `resetServerRuntime(ADMIN_SESSION / USER_SESSION)` で与える | 同上 | サーバー側のセッション取得。リクエスト文脈に依存する(`@/lib/session` の `requireAdminSession` は実物) |
| `next/navigation`(`redirect` は `NEXT_REDIRECT:<パス>` を投げて処理を打ち切る。`useRouter` は `refresh` / `push` を返す) | `jest.mock("next/navigation", ...)` | 同上 | App Router のルーティング文脈 |
| `next/cache`(`revalidatePath`) | `jest.mock("next/cache", () => ({ revalidatePath: jest.fn() }))` | 同上 | サーバーのキャッシュの再検証。Next.js のリクエスト文脈が無いと使えない |

### ブラウザ / JS ランタイムの機能

| 差し替えているもの | 方法 | 使うテスト | 何を再現するか |
| --- | --- | --- | --- |
| `window.confirm` | `jest.spyOn(window, "confirm").mockReturnValue(...)`(`afterEach` の `jest.restoreAllMocks()` で戻す) | `GarbageCollectionPanel.test.tsx` | ブラウザの確認ダイアログ(承認・キャンセルを選ぶ)。jsdom は未実装 |
| `navigator.clipboard` | `Object.assign(navigator, { clipboard: { readText: jest.fn() } })` | `ProjectAssetGenerationPanel.test.tsx` | クリップボードの読み取り(返す文字列・拒否を選ぶ)。jsdom は未実装 |
| `jest.useFakeTimers()` | 偽タイマーで時計を進める | `GarbageCollectionPanel.test.tsx`・`OllamaModelPullForm.test.tsx`(ポーリングの 2 秒間隔) | `setTimeout` を進めて待ち時間を省く。自前コードの置き換えではない |
| `Node.prototype.insertBefore` / `Node.prototype.appendChild` | `jest.spyOn(...).mockImplementation(...)`(本物を呼んだ直後に、挿入された要素の保存ボタンの `disabled` を記録し、本物の戻り値を返す。テストの最後に `mockRestore`) | `LlmModelPanel.mountGate.test.tsx` | React が DOM へ要素を差し込む瞬間の観測。差し込みそのものは本物へ通す(`useEffect` を差し替えずに「マウント前」を見るため) |
| `console.error` | `jest.spyOn(console, "error").mockImplementation(...)`(`mockRestore` で戻す) | `ProjectAssetGenerationPanel.test.tsx`・`ProjectAssetGenerationPanel.assetUploadJob.test.tsx` | アンマウント後の状態更新で React が警告を出さないことの観測(`not.toHaveBeenCalled()`)。出力を黙らせるためではない |

実装の差し替えではないが、`fireEvent.submit(form)`(ブラウザの検証を通り抜けた送信を直接再現する)、`Object.defineProperty(file, "size", ...)`
(`ProjectAssetGenerationPanel.upload.test.tsx`: 20MB を超えるファイルを実際に作らずに、`File.size` だけを大きくして上限の検査を通す。
`File` の中身は 1 バイトのまま)も、テストが操作と観測の仕方を選ぶ箇所として使っている。

### 「表に無いものを差し替えているテスト」が 0 件であることの確認

次の出力は、すべて上の表のどれかに当たる(`jest.mock` の対象は `server-only`・`next/server`・`next/headers`・`next-auth/jwt`・
`next-auth`・`next/navigation`・`next/cache` の 7 種類だけで、`@/lib/*`・`../actions`・コンポーネント・`react` を指すものは 0 件)。

```bash
cd "apps/web/src/app/projects/[id]/__tests__"
FILES="AiConnectionSection.test.tsx ChatGptConnectionSection.test.tsx ClaudeConnectionSection.test.tsx ComfyUiCheckpointTable.test.tsx GarbageCollectionPanel.test.tsx ImageProviderPanel.test.tsx LlmModelPanel.mountGate.test.tsx LlmModelPanel.test.tsx LlmProviderPanel.test.tsx OllamaModelPullForm.test.tsx ProjectAiModelsPanel.test.tsx ProjectArticleImageResizeDefaultForm.test.tsx ProjectAssetGenerationPanel.assetUploadJob.test.tsx ProjectAssetGenerationPanel.reference.test.tsx ProjectAssetGenerationPanel.test.tsx ProjectAssetGenerationPanel.upload.test.tsx ProjectImageContentFilterSettingsForm.test.tsx ProjectImageGenerationPromptDefaultsForm.test.tsx ProjectImageGenerationSizeDefaultsForm.test.tsx ReviewStepSettingsPanel.test.tsx projectCoreRuntime.ts assetPanelRuntime.ts"
grep -nE "jest\.(mock|doMock|spyOn|replaceProperty)\(|(global|globalThis|window)\.[A-Za-z.]+ *= |Object\.defineProperty\(|Object\.assign\(navigator|console\.(error|warn|log) *=" $FILES

# jest.mock の対象のモジュール名だけを数える(7 種類だけが出ることを確かめる)
grep -rhoE "jest\.(mock|doMock)\([\"'][^\"']+[\"']" $FILES | sed -E "s/jest\.(mock|doMock)\(//" | tr -d "\"'" | sort | uniq -c
```

## テストから到達できない分岐

完了時の `coverage-final.json` で、対象の本番コード 16 ファイルに未カバーの分岐は **9 件**(7 種類)。いずれも、
プロダクションコードを変えずには、その分岐に入る入力や状態を作れない。行は `branchMap` の `locations[i].start.line`。

| # | ファイル | 関数 | 行 | 分岐 | 到達できない具体的な理由 |
| ---: | --- | --- | ---: | --- | --- |
| 1 | `src/app/projects/[id]/ClaudeConnectionSection.tsx` | `reload` | 43 | `try { return true } catch { return false } finally { … }` の `finally` の後ろ(`try` と `catch` が両方とも `return` するので、`finally` を抜けたあとに続く流れ) | `try` は正常終了で `return true`、`catch` は `return false` と、どちらの経路も関数から抜ける。`finally` のあとへ流れが続く経路は、構文上存在しない(v8 が `finally` の直後の継続を 1 分岐として数える)。 |
| 2 | `src/app/projects/[id]/ChatGptConnectionSection.tsx` | `reload` | 43 | 1 と同じ | 1 と同じ(同じ構造のコード)。 |
| 3 | `src/app/projects/[id]/GarbageCollectionPanel.tsx` | `handleScanClick` | 93 | `if (!environment) return` の `return` | 呼ばれるのは「スキャン」ボタンの `onClick` だけで、そのボタンは `disabled={!environment \|\| scanning}`。環境が空のときは無効で、React は無効なボタンの `onClick` を呼ばない。ほかに `handleScanClick` を呼ぶ経路は無い。 |
| 4 | 同上 | `toggleSelectAll` | 112 | `if (!scanResult) return` の `return` | 呼ばれるのは、`scanResult` が真のときだけ描かれる「全選択」ボタンと見出しのチェックボックスの `onChange` だけ(`{scanResult && (...)}` の内側)。`scanResult` が `null` の描画ではこの 2 つの要素が存在しない。 |
| 5 | `src/app/projects/[id]/ProjectAssetGenerationPanel.tsx` | `imageJobId` の `useEffect` | 178 | `setImages(result.images ?? [])` の右辺 | `result` は `fetchImageJobResultAction` の戻り値で、`error` が無い成功の経路は常に `{ images: readImageIds(...).map(...) }`(配列)を返す。`images` が `undefined` で `error` も無い戻り値は、`actions.ts` を変えない限り作れない。 |
| 6 | 同上 | `handleCreateFromClipboard` | 288 | `setPrompt(settings.prompt ?? "")` の右辺 | 直前の判定で `typeof parsed.prompt !== "string"` を弾いているので、ここへ来るときの `settings.prompt` は必ず文字列。`undefined` / `null` の状態は作れない。 |
| 7 | 同上 | `handleUpload` | 342 | `if (selectedId == null) return` の `return` | 呼ばれるのは「アセットとして追加(全環境へアップロード)」ボタンの `onClick` だけで、そのボタンは `disabled={selectedId == null \|\| uploading}`。`selectedId` が空のときは無効で、`onClick` は呼ばれない。 |
| 8 | 同上 | `handleImageUpload` | 364 | `if (!uploadFile) return` の `return` | 呼ばれるのは「ギャラリーへ登録」ボタンの `onClick` だけで、そのボタンは `disabled={!uploadFile \|\| imageUploading}`。ファイルが無いときは無効で、`onClick` は呼ばれない。 |
| 9 | 同上 | `handleGalleryUpload` | 417 | `if (gallerySelectedId == null) return` の `return` | 呼ばれるのは「選択した画像をアセットとして追加(全環境へアップロード)」ボタンの `onClick` だけで、そのボタンは `disabled={gallerySelectedId == null \|\| galleryUploading}`。選択が空のときは無効で、`onClick` は呼ばれない。 |

最初は届かないと見えた分岐は、プロダクションコードを変えずに通した。主なもの:

| ファイル | 関数 | 通した方法 |
| --- | --- | --- |
| `AiConnectionSection.tsx`・`Claude/ChatGptConnectionSection.tsx` | 取得の `useEffect` の `if (cancelled) return` | 応答を保留したままアンマウントし、そのあとで応答を返す(`deferred()`)。成功・失敗・利用可否の 3 経路 |
| `Claude/ChatGptConnectionSection.tsx` | `reload` の `?? null`・`errorText` の `String(err)` | 接続を解除した直後の再取得で該当プロバイダーの行が無い応答にする。再取得を `Error` でない値で失敗させる |
| `GarbageCollectionPanel.tsx` | `parseDeleteResult` の各分岐 | 完了したジョブの結果を、`null`・JSON でない文字列・削除件数の無い JSON・失敗件数や一覧が無い JSON・失敗の一覧つきの JSON にする |
| `GarbageCollectionPanel.tsx` | `handleDelete`・`handleJobSettled` の `environment` が空の分岐 | 環境を選んでスキャンしたあとで「選択してください」へ戻してから削除を押す。削除が終わる前に環境を空にする |
| `GarbageCollectionPanel.tsx` | 環境の選択肢(SSH 管理のみ)・`runScan` の既定メッセージ・全選択解除・行のチェック・「(無題)」・進捗の表示 | SSH 管理だけのサイト、204(本文なし)のスキャン応答、全選択後の解除、タイトルが空のメディア、`running` のジョブの進捗 JSON を使う |
| `ProjectAiModelsPanel.tsx` | `loadLlm`・`loadImage` の `: null` の側 | 一部の取得だけが失敗した状態でタブを押し直し、取得できていない分だけが再取得されることを確かめる(LLM のモデルだけ・プロバイダーだけ失敗、画像生成 AI だけ失敗の 3 通り) |
| `LlmModelPanel.tsx` | `onSaved?.()` | `onSaved` を渡して保存の成功・失敗を試す |
| `LlmProviderPanel.tsx`・`ImageProviderPanel.tsx` | `PROVIDER_LABEL[value] ?? value` の右辺 | 表示名の対応が無い未知のプロバイダーを選択肢に含める |
| `ProjectImageGeneration*Form.tsx`・`ProjectArticleImageResizeDefaultForm.tsx` | `defaultValue={… ?? ""}` の左辺 | 設定済みの値を渡し、保存と空での保存(`null` を送る)を試す |
| `ProjectAssetGenerationPanel.tsx` | `handleImageUpload` の取得し直しの `catch` の `String(err)` | 登録に成功したあと、開いているギャラリー一覧の取得し直しを `Error` と `Error` でない値で失敗させる |

### 計測の対象外

`apps/web/jest.config.ts` の `collectCoverageFrom` は `src/**/*.d.ts`・`src/**/*.stories.*`・`src/**/index.*` を計測から外している。
対象の 16 ファイルのディレクトリ(`src/app/projects/[id]/` の直下)にこれに当たるファイルは **0 件**(下のコマンドの出力が空)。
したがって、計測対象外の分岐も無い。

```bash
cd "apps/web/src/app/projects/[id]" && find . -maxdepth 1 \( -name "*.d.ts" -o -name "*.stories.*" -o -name "index.*" \)
```

## 再現手順

完了時の数字・「未カバー 9 件」は次で再現できる。

```bash
cd apps/web && npm run test:coverage
```

`extract.py`(リポジトリの外に置き、`apps/web` で実行する。`rows` はファイル別の covered / total / 率と合計、
`unc` は未カバーの分岐を 1 行ずつ出し、最後の行がその件数。第 2 引数は `coverage/coverage-final.json` のパス):

```python
import json,os,sys
d=json.load(open(sys.argv[2]))
root=os.getcwd()+'/'
names="ProjectAssetGenerationPanel.tsx GarbageCollectionPanel.tsx ProjectAiModelsPanel.tsx AiConnectionSection.tsx ClaudeConnectionSection.tsx ChatGptConnectionSection.tsx ReviewStepSettingsPanel.tsx OllamaModelPullForm.tsx ComfyUiCheckpointTable.tsx LlmModelPanel.tsx LlmProviderPanel.tsx ImageProviderPanel.tsx ProjectImageContentFilterSettingsForm.tsx ProjectImageGenerationPromptDefaultsForm.tsx ProjectImageGenerationSizeDefaultsForm.tsx ProjectArticleImageResizeDefaultForm.tsx".split()
targets=['src/app/projects/[id]/'+n for n in names]
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
                best=None
                for m in v['fnMap'].values():
                    if m['loc']['start']['line']<=line<=m['loc']['end']['line']:
                        if best is None or m['loc']['start']['line']>=best[0]: best=(m['loc']['start']['line'],m['name'])
                if best: fn=best[1]
                unc.append((t.split('/')[-1],fn,line,locs['type'],i))
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

実行: `cd apps/web && python3 -I /path/to/extract.py rows coverage/coverage-final.json`(ファイル別)/
`python3 -I /path/to/extract.py unc coverage/coverage-final.json`(未カバーの分岐。完了時は
`(ファイル, 関数, 行, 種類, 分岐の番号)` が 9 行と、最後に `9`)。出力の(ファイル, 行)が、上の「テストから到達できない分岐」の
表の # 1〜9 と一致することを確かめる(1 件も欠けず、余りも無い)。着手時の値は、同じスクリプトを `4538ea42` で
`npm run test:coverage` を実行した直後に走らせたもの(`unc` の最後の行は `40`)。

差分にプロダクションコードが無いことの確認:

```bash
git diff origin/develop --name-only | grep -v "__tests__/"   # docs/coverage/web-projects-ai.md だけが出る
git diff origin/develop --name-only | python3 -I -c '
import sys; sys.path.insert(0, ".claude/hooks")
import paths
bad = [p for p in sys.stdin.read().split() if paths.is_production(p)]
print(bad); sys.exit(1 if bad else 0)'   # [] が出て終了コード 0
```
