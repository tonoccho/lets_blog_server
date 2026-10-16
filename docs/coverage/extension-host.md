# apps/extension 拡張ホスト層の分岐カバレッジ(#1840)

Epic #1711 の子 Issue #1840 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`apps/extension/src/` のうち `vscode` を import する
23 ファイル(`grep -l "from 'vscode'" apps/extension/src/*.ts`)に適用した結果。

プロダクションコードは変更していない(差分は `apps/extension/src/__tests__/`・`apps/extension/src/__mocks__/` と本ファイルだけ)。
`jest.config.js` の閾値も変えていない。この Issue の受け入れ基準はテスト資産の状態なので、Gherkin の受け入れシナリオは書かない
(製品の振る舞いではなく web UI から到達できない。CLAUDE.md → Where the tests live の例外)。足したテストは、本番コードが
既にあるので足した時点で通る。着手時の `coverage-final.json` で該当分岐が未カバーだったことを、着手時の計測値が示している。

## 計測値(着手時・完了時)

数え方は jest の branch カバレッジ(`coverage/coverage-final.json`)。`branchMap` の各分岐(`if` の真偽・`switch` の各 `case`・
`&&` / `||` / `??` の各オペランド・三項演算子の各辺・引数の既定値)を 1 つと数え、`b`(実行回数)が 1 以上なら covered。
短絡評価の各オペランドが別の分岐になるので、C1 と C2 を兼ねる。計測コマンド(着手時・完了時とも同じ):

```bash
npm --prefix apps/extension run test:coverage
```

### 着手時

コミット `537194ec`(develop 起点、変更なし)。テスト 47 スイート・840 件、失敗 0。`extension.ts`・`articleCreationPanel.ts`・
`askAiPanel.ts`・`sectionGenPanel.ts`・`frontMatterCompletionProvider.ts` はテストから 1 度も読み込まれていなかった(0%)。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `src/apiClient.ts` | 87 | 89 | 97.8% |
| `src/articleCreationPanel.ts` | 0 | 19 | 0.0% |
| `src/articleScaffold.ts` | 10 | 10 | 100.0% |
| `src/askAiPanel.ts` | 0 | 8 | 0.0% |
| `src/bodyCustomTagCompletionProvider.ts` | 16 | 16 | 100.0% |
| `src/config.ts` | 20 | 20 | 100.0% |
| `src/diagramEditorPanel.ts` | 16 | 17 | 94.1% |
| `src/diagramGalleryPanel.ts` | 9 | 9 | 100.0% |
| `src/errorHandler.ts` | 51 | 60 | 85.0% |
| `src/extension.ts` | 0 | 346 | 0.0% |
| `src/frontMatterCompletionProvider.ts` | 0 | 14 | 0.0% |
| `src/imageGalleryPanel.ts` | 13 | 13 | 100.0% |
| `src/imageGenPanel.ts` | 18 | 19 | 94.7% |
| `src/logger.ts` | 11 | 14 | 78.6% |
| `src/planPanel.ts` | 12 | 24 | 50.0% |
| `src/previewPanel.ts` | 30 | 32 | 93.8% |
| `src/proofreadDiagnostics.ts` | 62 | 67 | 92.5% |
| `src/realSitePreview.ts` | 6 | 6 | 100.0% |
| `src/reviewChecklistPanel.ts` | 16 | 16 | 100.0% |
| `src/reviewChecklistStore.ts` | 3 | 3 | 100.0% |
| `src/sectionGenPanel.ts` | 0 | 8 | 0.0% |
| `src/vscodeGit.ts` | 8 | 8 | 100.0% |
| `src/webviewPanelBase.ts` | 10 | 10 | 100.0% |
| **合計(23 ファイル)** | **398** | **828** | **48.1%** |

### 完了時

コミット `b6674f54`(テストを足した最後のコミット。本ファイルを足すコミットは `docs/` だけを変えるので計測値は変わらない)。
テスト 59 スイート・1075 件、失敗 0・skipped 0。`npm --prefix apps/extension run test:coverage` は終了コード 0
(`jest.config.js` のファイル単位の閾値を含む)。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `src/apiClient.ts` | 88 | 89 | 98.9% |
| `src/articleCreationPanel.ts` | 19 | 19 | 100.0% |
| `src/articleScaffold.ts` | 10 | 10 | 100.0% |
| `src/askAiPanel.ts` | 8 | 8 | 100.0% |
| `src/bodyCustomTagCompletionProvider.ts` | 16 | 16 | 100.0% |
| `src/config.ts` | 20 | 20 | 100.0% |
| `src/diagramEditorPanel.ts` | 17 | 17 | 100.0% |
| `src/diagramGalleryPanel.ts` | 9 | 9 | 100.0% |
| `src/errorHandler.ts` | 60 | 60 | 100.0% |
| `src/extension.ts` | 341 | 346 | 98.6% |
| `src/frontMatterCompletionProvider.ts` | 14 | 14 | 100.0% |
| `src/imageGalleryPanel.ts` | 13 | 13 | 100.0% |
| `src/imageGenPanel.ts` | 18 | 19 | 94.7% |
| `src/logger.ts` | 14 | 14 | 100.0% |
| `src/planPanel.ts` | 24 | 24 | 100.0% |
| `src/previewPanel.ts` | 32 | 32 | 100.0% |
| `src/proofreadDiagnostics.ts` | 66 | 67 | 98.5% |
| `src/realSitePreview.ts` | 6 | 6 | 100.0% |
| `src/reviewChecklistPanel.ts` | 16 | 16 | 100.0% |
| `src/reviewChecklistStore.ts` | 3 | 3 | 100.0% |
| `src/sectionGenPanel.ts` | 8 | 8 | 100.0% |
| `src/vscodeGit.ts` | 8 | 8 | 100.0% |
| `src/webviewPanelBase.ts` | 10 | 10 | 100.0% |
| **合計(23 ファイル)** | **820** | **828** | **99.0%** |

着手時に 100% 未満だった 13 ファイルのうち、9 ファイルが 100% になった。100% 未満で残るのは `apiClient.ts`(1 分岐)・`extension.ts`(5 分岐)・
`imageGenPanel.ts`(1 分岐)・`proofreadDiagnostics.ts`(1 分岐)の 4 ファイル・計 8 分岐で、すべて「テストから到達できない分岐」に理由つきで載せた。

## モックの境界

方針: 外部との通信(HTTP・git の別プロセス・ファイル)は**実物**を相手にし、差し替えるのは実行基盤(VS Code 拡張ホスト・時計)だけ。
自前のモジュール(`apiClient.ts`・`config.ts`・`httpClient.ts`・`deviceAuth.ts`・`previewPanel.ts`・`imageGenPanel.ts`・各 Panel など)は
どのテストでも差し替えていない。対象の 23 ファイルを実行するテスト(`apps/extension/src/__tests__/` の全ファイル)を調べた結果、
実装を差し替えているものはこの表で全部。`jest.mock` / `jest.doMock` は 0 件。

### 実物を使うもの(モックではない)

- HTTP: `src/__tests__/support/localHttpServer.ts` の `http.createServer`(127.0.0.1 の実ソケット)。gateway(`/api/**`)と Keycloak
  (`/auth/realms/letsblog/...`)の代役として、要求を記録し、テストが決めた応答(ステータス・ヘッダ・本文・応答しない・本文だけ遅らせる)を返す。
  拡張側の `httpClient.ts`(組み込み `fetch`)・`apiClient.ts`・`deviceAuth.ts` は実物がこのサーバーへ送る。
- git: 一時ディレクトリの実リポジトリと、origin 役の bare リポジトリ(`extensionSubmit.test.ts`・`planPanel*.test.ts`)。実際の `git` を起動する。
- ファイル: `os.tmpdir()` 配下の実ディレクトリ(記事・画像・assets)。

### 差し替えているもの(すべて実行基盤の代替)

| 差し替えているもの | 場所 | 何の代替か |
| --- | --- | --- |
| `moduleNameMapper` の `^vscode$` → `src/__mocks__/vscode.ts`(設定値・`window.show*Message`・`showQuickPick`/`showInputBox` の返答・`withProgress`・`createWebviewPanel`・`createOutputChannel`・`commands.registerCommand`/`executeCommand`・`onDid…` 購読・`languages.register…Provider`・`env.openExternal`/`clipboard`・`workspace.applyEdit`/`openTextDocument`・`Uri`/`Range`/`Selection` などのクラス) | `jest.config.js`。ほぼ全テスト | 実行基盤: VS Code 拡張ホストの `vscode` API。拡張ホストの外では解決できない。利用者の返答(QuickPick・入力欄・通知の選択肢)は `queueQuickPick` / `queueInputBox` / `queueWarningResponse` などでテストが順に差し込み、VS Code が各コマンドを呼ぶ操作は `runRegisteredCommand`、イベントの発火は `fireEvent` で再現する |
| `vscode.extensions.getExtension`(`jest.fn`、既定は Git 拡張なし) | `src/__mocks__/vscode.ts`、`vscodeGitResolve.test.ts` が `mockReturnValue` で差し替え | 実行基盤: VS Code 組み込みの Git 拡張(`vscode.git`)の有無と API |
| `jest.spyOn(vscode.workspace, 'openTextDocument')`・`jest.spyOn(vscode.window, 'showTextDocument')`(`mockImplementation`) | `reviewChecklistPanel.test.ts` | 実行基盤: 文書を開く・エディタに表示する VS Code API(開いた先の文書とエディタの選択範囲を返す) |
| `jest.spyOn(vscodeStub.window, 'createWebviewPanel')`(例外を投げる) | `extensionEditing.test.ts`(askAiSearch の起動失敗) | 実行基盤: Webview パネルを作れない状況の VS Code API |
| `setShowTextDocumentResolver`(`showTextDocument` が返すエディタを決める) | `src/__mocks__/vscode.ts`、`extensionEditing.test.ts`・`extensionSubmit.test.ts` | 実行基盤: 開いたエディタ(`FakeEditor`)を返す VS Code API |
| `support/fakeEditor.ts` の `FakeEditor`(メモリ上の文字列で動く `TextEditor` / `TextDocument`。`edit()` は replace / insert を適用する) と、各 Panel テストの `createEditor(...)`・`proofreadDiagnostics.test.ts` の `makeDocument` / `makeEditor`(`setDecorations: jest.fn`) | `extension*.test.ts`、各 Panel テスト | 実行基盤: VS Code のテキストエディタと文書 |
| `support/extensionContext.ts` の `createContext()`(`secrets` / `workspaceState` / `globalState` を `Map` で持つ `ExtensionContext`)、`reviewChecklistPanel.test.ts`・`reviewChecklistStore.test.ts`・`webviewPanelBase.test.ts`・`previewPanel.test.ts` の手書きの `ExtensionContext` | 全 Panel・`config`・`extension` のテスト | 実行基盤: SecretStorage・Memento・拡張のルート(`extensionUri`)。Webview の HTML 資材は実在する `apps/extension/webviews/` を読む |
| `jest.useFakeTimers()`(`doNotFake` で `Date` / `setImmediate` / `nextTick` / `queueMicrotask` を実物のままにした形を含む) | `apiClientImageTimeout.test.ts`・`config.test.ts`(2 件)・`planPanel.test.ts`(`Date` を「いま」に固定)・`proofreadDiagnostics.test.ts`(デバウンス)・`extensionActivate.test.ts`・`extensionAuth.test.ts`・`rejectionNotifier.test.ts` | 実行基盤: タイマーと時計(120 秒のタイムアウト・30 秒のトークン要求・ログインのポーリング間隔・500 ms のデバウンス・差し戻し確認の定期実行を、実時間を待たずに進める)。通信そのものは実ソケットのまま |
| `logger.setSink(...)` に渡す `appendLine` を持つ手書きの出力先 | `logger.test.ts`・`errorHandler.test.ts`・`extensionActivate.test.ts`・`proofreadDiagnostics.test.ts` | 実行基盤: VS Code の `OutputChannel`(`logger.ts` が出力先として抽象化している `LogSink`)。出力された行を読む |
| `realSitePreview.test.ts` の `report: jest.fn()` と `onMessage: jest.fn()`(呼び出し記録だけの関数) | `realSitePreview.test.ts` | 実行基盤: `withProgress` の `progress.report` と、プレビューのパネルから環境切り替えのメッセージを受け取る側 |
| `proofreadDiagnostics.test.ts` の `stepHandler` / `statusesHandler` / `categoriesHandler`(`jest.fn`) | `proofreadDiagnostics.test.ts` | 外部通信: **サーバー側の振る舞い**を差し込む口(実サーバーが返す応答を関数で決める)。拡張側のモジュールは差し替えていない。呼び出し記録は実際にサーバーへ届いた要求を表す |

`#1839` が受け持つ `vscode` を import しないファイルのテストの差し替え(`cache.test.ts` の `loader` など)は
`docs/coverage/extension-logic.md` の表にある。

### 書き直しで変わったテスト

着手時に自前のモジュールを差し替えていた次のテストを、実物を使う形に書き直した。送信した要求・結果のアサーションは移した。
実物を通したことで前提が変わった箇所は次のとおり(アサーションを減らしたものは無い)。

- `apiClientRequests.test.ts` / `apiClientImageTimeout.test.ts` / `apiClientSignedPreview.test.ts`(着手時は `../httpClient` を `jest.mock`):
  実サーバーへ通す形に。URL はポートが毎回変わるためパス(`/api/...`)で比べる。ヘッダ名は実際に届く小文字(`content-type`)。
  本文が空のエラー応答の詳細は、サーバーが返す状態テキストになる(`Internal Server Error`)。
  「既にキャンセル済みのシグナルを渡された場合もリクエスト自体は組み立てる」は、モックがシグナルを無視していたための記述だった。
  実物の `fetch` は中断済みのシグナルを送信前に拒否するので、「`CancelledError` になり、サーバーへは何も届かない」へ改めた
  (利用者の観点で正しい振る舞いを固定。キャンセル済みでも送るという挙動は要件にない)。
- `config.test.ts`(着手時は `deviceAuth.refreshAccessToken` / `revokeRefreshToken` を `jest.spyOn`): Keycloak 役の実サーバーへ通し、
  リフレッシュ要求・失効要求の送信内容(パス・`refresh_token` / `token`)を検証する形に。
- `bodyCustomTagCompletionProvider.test.ts`・`diagramEditorPanel.test.ts`・`diagramGalleryPanel.test.ts`・`imageGalleryPanel.test.ts`・
  `imageGenPanel.test.ts`・`planPanel.test.ts`・`realSitePreview.test.ts`・`proofreadDiagnostics.test.ts`
  (着手時は `../apiClient`・`../config`・`../previewPanel`・`../imageGenPanel` を `jest.mock`、`store.recordReview` を `jest.spyOn`):
  ログイン状態・選択中プロジェクトは `config.ts` の実関数で作り、通信は実サーバーへ。
  Webview への応答は実サーバーとの往復を挟むので、`support/panelKit.ts` の `send` が応答の投稿を待つ。
  `recordReview` の呼び出し記録は、実物の `ReviewChecklistStore` に保存された状態の検証に置き換えた。
  削除後のキャッシュ破棄(`invalidateProjectCache` の呼び出し記録)は、次の一覧取得がサーバーへ届くことで検証する。
  差があるのは次の 3 点: 256 枚生成のテストは 1 枚 1 MB × 256 枚を実際に受け取ると重いため 100 KB にした(「メッセージの大きさが枚数・画像の大きさに
  比例しない」という不変条件は同じ)。レビューステップの失敗は、再試行(500 ms から倍々、最大 3 回)で時間がかかる 5xx ではなく 403 で作る。
  `requireProjectId` が呼ばれないことの検証は、選択中のプロジェクトが無くても front matter の `project_id` だけで実行できることの検証にした。
- `previewPanel.ts` は、どのテストでも差し替えていない(`realSitePreview.test.ts` は、実物の `PreviewPanel` が開いたパネルの表示内容を検証する)。

## テストから到達できない分岐

完了時の `coverage-final.json` で covered が 0 の分岐は次の 8 件だけ(23 ファイルの 828 分岐中)。列は
`branchMap` の開始位置(行:桁)。いずれも「プロダクションコードを変えずには、その分岐に入る入力や状態を作れない」ことを確認した。

| ファイル | 関数 | 行:桁 | 分岐 | 理由 |
| --- | --- | --- | --- | --- |
| `src/apiClient.ts` | `request` の再試行本体 | 152:41 | `...(spec.headers ?? {})` の右辺 `{}` | `RequestSpec.headers` は省略可能な型だが、`request` を呼ぶ `requestJson` / `requestBinary` / `cachedRequestJson` の呼び出し元の全エンドポイント関数(`label:` を持つ全箇所を確認)が `headers: buildHeaders(...)` を渡している。`request` は export されておらず、`headers` を省いた呼び出しを作る経路が無い(呼び出し側を変えるにはプロダクションコードの変更が要る) |
| `src/extension.ts` | `pollForDeviceToken` | 535:6 | 待機(`sleepOrAbort`)から戻った直後の `if (cancellationToken.isCancellationRequested)` の真側 | キャンセルは `onCancellationRequested` の購読が同時に `controller.abort()` を呼ぶので、待機中のキャンセルは `sleepOrAbort` が拒否して `catch` 側で終わる。待機が正常に終わった後でフラグだけが立つのは、タイマーのコールバックとキャンセルの通知が、その間の microtask(`await` の継続)を挟まずに続けて実行される場合だけ。Node のタイマーは 1 つごとに microtask を流すので、実際のイベントループではその順序にならない |
| `src/extension.ts` | `sleepOrAbort` | 575:4 | `if (signal.aborted)` の真側 | 呼び出し元(`pollForDeviceToken`)は、直前のループ先頭で `isCancellationRequested` を確かめて例外にしている。`controller` を中断するのはキャンセルの購読だけで、その購読は同じ瞬間にフラグも立てる。ループ先頭からこの呼び出しまでの間にあるのは同期の `progress.report` だけで、その間にキャンセルは割り込めない(526 行の分岐は、タスク開始時に既にキャンセル済みの場合として `extensionAuth.test.ts` で通している) |
| `src/extension.ts` | `commandSwitchAiProvider` | 901:84 | `?? provider` の右辺 | `provider` は `pickAiProvider` が返す `AI_PROVIDER_ITEMS` の `value` で、直後の `find` で必ず見つかる。QuickPick が返すのは渡した選択肢だけなので、一覧に無い値は作れない |
| `src/extension.ts` | `commandPreviewArticle`(`availableSites` の組み立て) | 1649:26 | `c.siteId ?? null` の右辺 `null` | `choices` を作るのは `buildPreviewSiteChoices` だけで、全要素に `siteId: site.id`(数値)を入れる。`siteId` が `undefined` の選択肢は作れない |
| `src/extension.ts` | `commandPreviewArticle` の `onPreviewMessage` | 1701:56 | `(c.siteId ?? null) === message.siteId` の `c.siteId ?? null` の右辺 | 上と同じ。`choices` の `siteId` は常に数値 |
| `src/imageGenPanel.ts` | `_handleLoadOptions` | 121:4 | `if (this._pendingPrefill)` の真側 | コンストラクタは private で、生成経路は `createOrShow` だけ。`createOrShow` は prefill つきで生成した直後に `applyPrefill(prefill)` を呼び、`_pendingPrefill` を `undefined` に戻して直接 `postMessage('prefill')` する。Webview のメッセージ(`loadOptions`)は生成の後にしか届かないため、`_pendingPrefill` が設定されたまま `loadOptions` を処理する状態は作れない |
| `src/proofreadDiagnostics.ts` | `applyDiagnostics` | 204:79 | `this.frontMatterDiagnostics.get(key) ?? []` の右辺 `[]` | 唯一の呼び出し元 `runFrontMatterCheck` が、直前に同じ `key` で `this.frontMatterDiagnostics.set(key, diagnostics)` している。`applyDiagnostics` は private で、値が無い状態では呼ばれない |

### 未カバー分岐の抜き出し手順(再現)

完了時の一覧と突き合わせる手順。`apps/extension/coverage/coverage-final.json` の `branchMap` と `b`(各分岐の実行回数)から、
対象 23 ファイルの covered が 0 の分岐を、行:桁・種類つきで出す。出力が上の表と 1 対 1 で一致すれば、一覧に無い未カバー分岐は 0 件。

```bash
npm --prefix apps/extension run test:coverage
cd apps/extension
python3 - <<'PY'
import json, subprocess
targets = set(subprocess.check_output(
    "grep -l \"from 'vscode'\" src/*.ts", shell=True, text=True).split())
data = json.load(open('coverage/coverage-final.json'))
for path, cov in sorted(data.items()):
    name = 'src/' + path.split('/src/')[-1]
    if name not in targets:
        continue
    for key, branch in cov['branchMap'].items():
        for index, hits in enumerate(cov['b'][key]):
            if hits == 0:
                start = branch['locations'][index]['start']
                print(f"{name}\t{start['line']}:{start['column']}\t{branch['type']}[{index}]")
PY
```

covered / total の集計(上の表の値)は、同じ `cov['b']` の全要素を数える(要素数が total、1 以上の数が covered)。
