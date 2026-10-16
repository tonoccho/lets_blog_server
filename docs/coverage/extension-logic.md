# apps/extension 純ロジックの分岐カバレッジ(#1839)

Epic #1711 の子 Issue #1839 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`apps/extension/src/` のうち `vscode` を import しない
25 ファイル(`grep -L "from 'vscode'" apps/extension/src/*.ts`)に適用した結果。

プロダクションコードは変更していない(差分は `apps/extension/src/__tests__/` と本ファイルだけ)。`jest.config.js` の
閾値も変えていない。この Issue の受け入れ基準はテスト資産の状態なので、Gherkin の受け入れシナリオは書かない
(製品の振る舞いではなく web UI から到達できない。CLAUDE.md → Where the tests live の例外)。
足したテストは、本番コードが既にあるので足した時点で通る。着手時の `coverage-final.json` で該当分岐が未カバーだったことを、
着手時の計測値が示している。

## 計測値(着手時・完了時)

数え方は jest の `branches`(v8 プロバイダー)。`coverage/coverage-final.json` の `branchMap` の各分岐を 1 と数え、
`b` の実行回数が 1 以上なら covered。`||` / `&&` / `??` / 三項 / `if` / 既定値は v8 の出力でそれぞれ別の分岐になるので
C1 と C2 を兼ねる。計測コマンド(着手時・完了時とも同じ。結果は `apps/extension/coverage/coverage-final.json`):

```bash
npm --prefix apps/extension run test:coverage
```

`schemas.ts` は型定義だけで分岐が無い(0/0)。

### 着手時

コミット `6d3827a2`(develop 起点、変更なし)。テスト 47 スイート・819 件、失敗 0。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `src/apiBaseUrl.ts` | 2 | 2 | 100.0% |
| `src/articleGit.ts` | 25 | 25 | 100.0% |
| `src/articleSubmit.ts` | 31 | 31 | 100.0% |
| `src/bodyCustomTagCompletionLogic.ts` | 11 | 12 | 91.7% |
| `src/cache.ts` | 23 | 24 | 95.8% |
| `src/deviceAuth.ts` | 66 | 66 | 100.0% |
| `src/downstreamServices.ts` | 18 | 18 | 100.0% |
| `src/frontMatter.ts` | 52 | 52 | 100.0% |
| `src/frontMatterCompletionLogic.ts` | 26 | 27 | 96.3% |
| `src/headingContext.ts` | 18 | 18 | 100.0% |
| `src/httpClient.ts` | 4 | 22 | 18.2% |
| `src/issueParser.ts` | 16 | 16 | 100.0% |
| `src/jwtClaims.ts` | 22 | 22 | 100.0% |
| `src/markdownSources.ts` | 4 | 4 | 100.0% |
| `src/multipart.ts` | 4 | 4 | 100.0% |
| `src/previewReviewLogic.ts` | 4 | 4 | 100.0% |
| `src/proofreadLogic.ts` | 38 | 39 | 97.4% |
| `src/publishReviewLogic.ts` | 4 | 4 | 100.0% |
| `src/rejectionEdit.ts` | 12 | 12 | 100.0% |
| `src/rejectionNotifier.ts` | 28 | 29 | 96.6% |
| `src/reviewChecklistLogic.ts` | 21 | 21 | 100.0% |
| `src/schemas.ts` | 0 | 0 | 100.0% |
| `src/urlPaste.ts` | 18 | 18 | 100.0% |
| `src/webviewSecurity.ts` | 4 | 4 | 100.0% |
| **合計(25 ファイル)** | **451** | **474** | **95.1%** |

### 完了時

コミット `066b4120`(テストを足したコミット。本ファイルを足すコミットは `docs/` だけを変えるので計測値は変わらない)。
テスト 47 スイート・840 件、失敗 0・skipped 0。`npm --prefix apps/extension run test:coverage` は終了コード 0
(`jest.config.js` のファイル単位の閾値を含む)。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `src/apiBaseUrl.ts` | 2 | 2 | 100.0% |
| `src/articleGit.ts` | 25 | 25 | 100.0% |
| `src/articleSubmit.ts` | 31 | 31 | 100.0% |
| `src/bodyCustomTagCompletionLogic.ts` | 12 | 12 | 100.0% |
| `src/cache.ts` | 24 | 24 | 100.0% |
| `src/deviceAuth.ts` | 66 | 66 | 100.0% |
| `src/downstreamServices.ts` | 18 | 18 | 100.0% |
| `src/frontMatter.ts` | 52 | 52 | 100.0% |
| `src/frontMatterCompletionLogic.ts` | 27 | 27 | 100.0% |
| `src/headingContext.ts` | 18 | 18 | 100.0% |
| `src/httpClient.ts` | 19 | 22 | 86.4% |
| `src/issueParser.ts` | 16 | 16 | 100.0% |
| `src/jwtClaims.ts` | 22 | 22 | 100.0% |
| `src/markdownSources.ts` | 4 | 4 | 100.0% |
| `src/multipart.ts` | 4 | 4 | 100.0% |
| `src/previewReviewLogic.ts` | 4 | 4 | 100.0% |
| `src/proofreadLogic.ts` | 39 | 39 | 100.0% |
| `src/publishReviewLogic.ts` | 4 | 4 | 100.0% |
| `src/rejectionEdit.ts` | 12 | 12 | 100.0% |
| `src/rejectionNotifier.ts` | 29 | 29 | 100.0% |
| `src/reviewChecklistLogic.ts` | 21 | 21 | 100.0% |
| `src/schemas.ts` | 0 | 0 | 100.0% |
| `src/urlPaste.ts` | 18 | 18 | 100.0% |
| `src/webviewSecurity.ts` | 4 | 4 | 100.0% |
| **合計(25 ファイル)** | **471** | **474** | **99.4%** |

着手時に 100% 未満だったのは `httpClient.ts`(18.2%。テストが組み込み `fetch` を差し替えていて `node:https` 経路が動かなかった)、
`bodyCustomTagCompletionLogic.ts`・`cache.ts`・`frontMatterCompletionLogic.ts`・`proofreadLogic.ts`・`rejectionNotifier.ts` の 6 ファイル。
完了時に 100% 未満なのは `httpClient.ts` の 3 分岐だけで、「テストから到達できない分岐」に理由を載せた。

## モックの境界

方針: 差し替えてよいのは外部との通信(HTTP・Git の別プロセス)と実行基盤(VS Code 拡張ホスト・タイマー)だけ。自前のモジュール
(`httpClient.ts` を含む)は差し替えない。対象 25 ファイルのテスト(`httpClient.test.ts` など 24 ファイル)を調べた結果、
実装を差し替えているものはこの表で全部。

実物を使うもの(モックではない): HTTP は `apps/extension/src/__tests__/support/localHttpServer.ts` の `http.createServer`
(127.0.0.1)、自己署名 HTTPS は `support/selfSignedCert.ts` の証明書で立てる `https.createServer`、Git は実際の `git`
(`execFileSync` と一時ディレクトリ)。外部プロセスも外部ネットワークも要らない。

| 差し替えているもの | 場所 | 何の代替か |
| --- | --- | --- |
| `moduleNameMapper` の `^vscode$` → `src/__mocks__/vscode.ts` | `jest.config.js`(`apiBaseUrl.test.ts` が `setConfiguration` で設定値を入れる) | 実行基盤: VS Code 拡張ホストの `vscode` API(設定 `letsBlog.serverUrl` の読み出し)。拡張ホストの外では解決できない |
| `jest.useFakeTimers()` | `rejectionNotifier.test.ts`(`RejectionPoller`) | 実行基盤: 定期確認の `setInterval`(実時間を待たない) |
| 注入した `run` の `jest.fn` | `rejectionNotifier.test.ts` | 外部通信: 差し戻し確認の呼び出し(サーバー API への問い合わせ) |
| 注入した `fakeClock`(`now`) | `cache.test.ts` | 実行基盤: 現在時刻(`Date.now`)。TTL の経過を即座に作る |
| 注入した `loader` の `jest.fn` | `cache.test.ts` | 外部通信: キャッシュ未命中時に呼ぶ取得処理(サーバー API) |
| 注入した `fetchStep` | `proofreadLogic.test.ts` | 外部通信: AI 校正のステップごとの API 呼び出し |
| 注入した `runReview` の `jest.fn` | `previewReviewLogic.test.ts`・`publishReviewLogic.test.ts` | 外部通信: AI レビューの API 呼び出し |
| 注入した `submission`(`createSubmission` の `jest.fn`) | `articleSubmit.test.ts` | 外部通信: GitHub/GitLab の Pull Request 作成 API |
| 注入した `scaffold`(`fakeScaffold`)・`choose`・`chooseOnExistingBranch` | `articleGit.test.ts` | 実行基盤: 記事の雛形生成(`articleScaffold.ts`)の書き込みと、VS Code の選択ダイアログ(利用者の操作) |
| `GitBackend` の部分差し替え(`Object.create(cli)` + `checkout` / `deleteBranch` を例外にする)、`GitBackend` を満たす手書きオブジェクト | `articleGit.test.ts`・`articleSubmit.test.ts` | 外部プロセス: `git` の失敗(実 `git` では巻き戻し自体の失敗や push の呼び出し順を作れない)。正常系は実 `git` で通している |
| `VscodeGitBackend` に渡す `repository` オブジェクト | `articleGit.test.ts` | 実行基盤: VS Code 組み込みの Git 拡張の `Repository`(拡張ホストでしか得られない) |
| `RejectionFindings` に渡す `memoryState`(`jest.fn` の `get` / `update`) | `rejectionEdit.test.ts` | 実行基盤: VS Code の `Memento`(`globalState`) |

`deviceAuth.test.ts` は、着手時は自前の `../httpClient` を `jest.mock` で差し替えていた。完了時は
`support/localHttpServer.ts` の実サーバーへ `httpRequest`(組み込み `fetch`)を通す形に書き直し、`jest.mock` は 0 件。
送信したメソッド・パス・本文のアサーションはそのまま移した(URL の全体比較は、待ち受けポートが毎回変わるためパスの比較にした)。
`httpClient.test.ts` は、着手時はグローバルの `fetch` を代入で差し替えていた。完了時は実サーバー(平文 HTTP と自己署名 HTTPS)
へ通し、グローバルへの代入は 0 件。`jest.spyOn` は対象のテストに 0 件。

## テストから到達できない分岐

完了時の `coverage-final.json` で covered が 0 の分岐は次の 3 件だけ(25 ファイルの 474 分岐中)。いずれも
`apps/extension/src/httpClient.ts` の非公開関数 `requestViaNodeHttps`(呼び出しは `httpRequest` からだけ)の中。

| ファイル | 関数 | 行 | 分岐 | 理由 |
| --- | --- | --- | --- | --- |
| `src/httpClient.ts` | `requestViaNodeHttps` | 72 | `parsed.protocol === 'http:' ? http : https` の真側(`http`) | `requestViaNodeHttps` を呼ぶのは `httpRequest` の `allowInsecureTls && url.startsWith('https://')` が真のときだけで、大文字小文字を区別する `startsWith('https://')` を通った URL の `protocol` は必ず `https:`。`requestViaNodeHttps` は export されておらず、`http:` の URL を渡す経路が無い(呼び出し側の条件を変えるにはプロダクションコードの変更が要る) |
| `src/httpClient.ts` | `requestViaNodeHttps` | 89 | `res.statusCode ?? 0` の右辺(`0`) | `statusCode` が `undefined` になるのは、型定義が共用する `IncomingMessage` のサーバー側(リクエスト)だけ。`request` のコールバックに渡る応答では、応答行の解析が済んでから呼ばれるので必ず数値。応答行を壊した実サーバーでも、解析に失敗した時点でコールバックは呼ばれず `error` で拒否される |
| `src/httpClient.ts` | `requestViaNodeHttps` | 93 | `res.statusMessage ?? ''` の右辺(`''`) | `statusMessage` が `undefined` にならない。理由句の無い応答行(`HTTP/1.1 200` + 改行)を実際に返して Node v25.7.0 で確かめたところ、`undefined` ではなく `''` が入った。左辺が `''` の場合は `??` の右辺を取らない |

到達できる入力が非現実的だが、型を越えて入力して通した分岐(一覧には載せず、テストで通している):

- `cache.ts` の `set` 内 `oldestKey === undefined`: キーに `undefined` を型を越えて渡し、上限を 0 にした `cache.test.ts` で通す。
- `frontMatterCompletionLogic.ts` の `lines[lineNumber] ?? ''`: 整数でない行番号(1.5)を渡して通す。
- `httpClient.ts` の `parsed.port || undefined` の右辺: ポート無しの `https://127.0.0.1/` を、中断済みの `AbortSignal` つきで渡して通す。既定の 443 番へは実際に接続しない(待ち受けの有無で結果が変わるため)。

### 未カバー分岐の抜き出し手順(再現)

完了時の一覧と突き合わせる手順。`apps/extension/coverage/coverage-final.json` の `branchMap` と `b`(各分岐の実行回数)から、
対象 25 ファイルの covered が 0 の分岐を行番号つきで出す。

```bash
npm --prefix apps/extension run test:coverage
cd apps/extension
grep -L "from 'vscode'" src/*.ts | xargs -n1 basename > /tmp/logic-files.txt   # 対象 25 ファイル
python3 -I - /tmp/logic-files.txt <<'PY'
import json, os, sys
targets = set(open(sys.argv[1]).read().split())
for path, data in sorted(json.load(open('coverage/coverage-final.json')).items()):
    if os.path.basename(path) not in targets:
        continue
    for key, counts in data['b'].items():
        info = data['branchMap'][key]
        for i, count in enumerate(counts):
            if count == 0:
                loc = info['locations'][i] if i < len(info['locations']) else info['loc']
                print(os.path.basename(path), info['type'], 'line', loc['start'].get('line'), 'branch', i)
PY
```

完了時の出力は `httpClient.ts` の `cond-expr` 72 行・`binary-expr` 89 行・`binary-expr` 93 行の 3 行だけで、上の表と一致する。
(`if` で `else` が書かれていない分岐は v8 が位置を持たない場合があり、その行は `line None` と出る。完了時の対象にはその行は無い。)
