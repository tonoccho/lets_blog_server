# apps/web のプロジェクト詳細の SNS・アクセス解析のパネルの分岐カバレッジ(#1838)

Epic #1711 の子 Issue #1838 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`apps/web` の `src/app/projects/[id]/` 直下の SNS・アクセス解析のパネルに適用した結果。

対象は次の 16 ファイルの本番コード。

- SNS のセクション: `ProjectSnsFacebookSection.tsx`・`ProjectSnsHatenaSection.tsx`・`ProjectSnsLinkedInSection.tsx`・
  `ProjectSnsThreadsSection.tsx`・`ProjectSnsXSection.tsx`・`ProjectSnsTemplatesSection.tsx`
- その Server Actions: `snsFacebookActions.ts`・`snsHatenaActions.ts`・`snsLinkedInActions.ts`・`snsThreadsActions.ts`・
  `snsXActions.ts`・`snsTemplateActions.ts`
- PV ルール: `ProjectPvRulesSection.tsx`・`pvRuleActions.ts`
- アクセス解析: `ProjectAdSenseSettingsForm.tsx`・`ProjectGoogleAnalyticsSettingsForm.tsx`

同じディレクトリの `actions.ts`(AdSense・GA の Server Action はここにある)は #1836 の対象で、`docs/coverage/web-projects-core.md` に記録がある。
本 Issue のフォームのテストは `actions.ts` を実物で通すが、`actions.ts` の分岐の計測と一覧は #1836 が受け持つ。

テストは `src/app/projects/[id]/__tests__/` のうち、上の 16 ファイルに対応する 16 ファイル(`ProjectSns*Section.test.tsx` 6・
`sns*Actions.test.ts` 6・`ProjectPvRulesSection.test.tsx`・`pvRuleActions.test.ts`・`ProjectAdSenseSettingsForm.test.tsx`・
`ProjectGoogleAnalyticsSettingsForm.test.tsx`)。足場は #1836 の `projectCoreRuntime.ts` をそのまま使い、新しい足場は足していない。

プロダクションコードは変更していない(差分は `apps/web/src/app/projects/[id]/__tests__/` と本ファイルだけ。末尾の確認コマンド)。
`jest.config.ts`・`jest.setup.ts` も変えていない。この Issue の受け入れ基準はテスト資産の状態なので、Gherkin の
受け入れシナリオは書かない(製品の振る舞いではなく web UI から到達できない。CLAUDE.md → Where the tests live の例外)。
足したテストは、本番コードが既にあるので、足した時点で通る。代わりに、着手時の `coverage-final.json` で該当の分岐が
未カバーだったこと(下の着手時の表と、再現手順の `unc` の出力 14 件)が、足したテストの必要性を示している。

## 計測値(着手時・完了時)

数え方は jest の `branches`(v8 プロバイダー)。`coverage/coverage-final.json` の `branchMap` の各分岐を 1 と数え、
`b` の実行回数が 1 以上なら covered。`||` / `&&` / `??` / 三項 / `if` / 既定値は v8 の出力でそれぞれ別の分岐になるので
C1 と C2 を兼ねる。計測コマンド(着手時・完了時とも同じ):

```bash
cd apps/web && npm run test:coverage
```

着手時より完了時の total が 9 つ多いのは、分岐は「実行された関数」の内側だけが数えられるため。着手時は `../sns*Actions` などを
差し替えていたので、差し替えた関数の内側は数に入っていなかった。実物を通すようにして、数えられる分岐が増えた。

### 着手時

コミット `ae48ab9b`(develop 起点、変更なし)。テスト 306 スイート・3,342 件、失敗 0。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `src/app/projects/[id]/ProjectAdSenseSettingsForm.tsx` | 31 | 31 | 100.0% |
| `src/app/projects/[id]/ProjectGoogleAnalyticsSettingsForm.tsx` | 28 | 30 | 93.3% |
| `src/app/projects/[id]/ProjectPvRulesSection.tsx` | 30 | 30 | 100.0% |
| `src/app/projects/[id]/ProjectSnsFacebookSection.tsx` | 49 | 51 | 96.1% |
| `src/app/projects/[id]/ProjectSnsHatenaSection.tsx` | 47 | 48 | 97.9% |
| `src/app/projects/[id]/ProjectSnsLinkedInSection.tsx` | 47 | 48 | 97.9% |
| `src/app/projects/[id]/ProjectSnsTemplatesSection.tsx` | 18 | 18 | 100.0% |
| `src/app/projects/[id]/ProjectSnsThreadsSection.tsx` | 47 | 48 | 97.9% |
| `src/app/projects/[id]/ProjectSnsXSection.tsx` | 40 | 42 | 95.2% |
| `src/app/projects/[id]/pvRuleActions.ts` | 26 | 26 | 100.0% |
| `src/app/projects/[id]/snsFacebookActions.ts` | 33 | 34 | 97.1% |
| `src/app/projects/[id]/snsHatenaActions.ts` | 25 | 26 | 96.2% |
| `src/app/projects/[id]/snsLinkedInActions.ts` | 25 | 26 | 96.2% |
| `src/app/projects/[id]/snsTemplateActions.ts` | 16 | 16 | 100.0% |
| `src/app/projects/[id]/snsThreadsActions.ts` | 25 | 26 | 96.2% |
| `src/app/projects/[id]/snsXActions.ts` | 20 | 21 | 95.2% |
| **合計(16 ファイル)** | **507** | **521** | **97.3%** |

### 完了時

コミット `580cbdfd`(テストを書き直した・足したコミット。本ファイルを足すコミットは `docs/` だけを変えるので計測値は変わらない)。
テスト 306 スイート・3,376 件、失敗 0・skipped 0。`npm run test:coverage` は終了コード 0(`jest.config.ts` の
リポジトリ全体の下限を含む)。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `src/app/projects/[id]/ProjectAdSenseSettingsForm.tsx` | 31 | 31 | 100.0% |
| `src/app/projects/[id]/ProjectGoogleAnalyticsSettingsForm.tsx` | 31 | 31 | 100.0% |
| `src/app/projects/[id]/ProjectPvRulesSection.tsx` | 30 | 30 | 100.0% |
| `src/app/projects/[id]/ProjectSnsFacebookSection.tsx` | 51 | 52 | 98.1% |
| `src/app/projects/[id]/ProjectSnsHatenaSection.tsx` | 49 | 49 | 100.0% |
| `src/app/projects/[id]/ProjectSnsLinkedInSection.tsx` | 49 | 49 | 100.0% |
| `src/app/projects/[id]/ProjectSnsTemplatesSection.tsx` | 18 | 18 | 100.0% |
| `src/app/projects/[id]/ProjectSnsThreadsSection.tsx` | 49 | 49 | 100.0% |
| `src/app/projects/[id]/ProjectSnsXSection.tsx` | 44 | 44 | 100.0% |
| `src/app/projects/[id]/pvRuleActions.ts` | 27 | 27 | 100.0% |
| `src/app/projects/[id]/snsFacebookActions.ts` | 34 | 34 | 100.0% |
| `src/app/projects/[id]/snsHatenaActions.ts` | 26 | 26 | 100.0% |
| `src/app/projects/[id]/snsLinkedInActions.ts` | 26 | 26 | 100.0% |
| `src/app/projects/[id]/snsTemplateActions.ts` | 17 | 17 | 100.0% |
| `src/app/projects/[id]/snsThreadsActions.ts` | 26 | 26 | 100.0% |
| `src/app/projects/[id]/snsXActions.ts` | 21 | 21 | 100.0% |
| **合計(16 ファイル)** | **529** | **530** | **99.8%** |

未カバーは 1 分岐(下の「テストから到達できない分岐」に載せる)。それ以外の分岐はすべて通っている。

## モックの境界

境界の考え方は Issue 本文の既定案(Q1・Q2。Epic #1711 で利用者が確定済み)のとおり。

- **外部との通信**: HTTP は `fetch` の層で模す。自前の通信ラッパー(`@/lib/apiClient`)・Server Action(`../actions`・
  `../sns*Actions`・`../pvRuleActions`・`../snsTemplateActions`)・`@/lib/session`・コンポーネント・`react` は差し替えない。
- **実行基盤の代替(Q2)**: jest の上に実体が無い Next.js のサーバー実行時(`next/headers` など)・`next-auth*`・`server-only` は代替する。
- **ブラウザの機能**: jsdom に無い `window.confirm` は、承認・キャンセルを選ぶために差し替える。自前のモジュールではない。

自前モジュールを差し替えていた既存テスト(`../sns*Actions` 6 件、`../snsTemplateActions`、`../pvRuleActions`、`../actions`(AdSense・GA の 2 件)、
および Server Action のテストの `@/lib/apiClient`・`@/lib/session`)は、すべて実物を使う形に書き直した。書き直しで、アサーションは減らしていない。

- 差し替えたアクションの関数を `toHaveBeenCalledWith(引数)` で確かめていた箇所は、同じ内容を HTTP の経路・本文で確かめる形にした
  (`bodies(fetchMock, "<METHOD> <パス>")`、`calledRequests(fetchMock)`)。例: X の認可の開始は
  `POST /api/projects/5/sns/x/authorize` の本文が `{ clientId, clientSecret, redirectUri }` であること。
- Server Action が返す `{ error }` は、バックエンドの応答(`failWith(状態, 本文)` の HTTP エラー)から `apiClient` が組み立てる
  実物の `APIエラー (状態): 本文` になる。期待する文言はこれに合わせて更新した。失敗の意味(エラーを表示する・成功とは示さない)は変えていない。
- 接続の「認可の開始」は、成功すると `redirect()` が例外を投げて画面を離れる。コンポーネントのテストでは既定を本番サイトに拒否された
  状態(400)にして、送った本文とエラー表示を確かめる。成功時のリダイレクトは Server Action のテスト(`NEXT_REDIRECT:<URL>` を確かめる)が受け持つ。

共通の足場は次のとおり。差し替えるのは `global.fetch` だけ。

- `src/lib/__tests__/testUtils/serverRuntime.ts`(#1832)の `resetServerRuntime()`・`jsonResponse()`・`textResponse()`・
  Next.js の実行基盤の代替(`nextServerModule` など)。
- `src/lib/__tests__/testUtils/webAdminRuntime.ts`(#1833)の `ADMIN_SESSION`・`USER_SESSION`・`installFetchByPath()`。
- `[id]/__tests__/projectCoreRuntime.ts`(#1836): `installBackend()`、`bodies()`、`calledRequests()`、`failWith(状態, 本文)`、
  `failFetchWith(値)`、`noContent`、`form()`、`requestCount()`。

### 外部との通信

| 差し替えているもの | 方法 | 使うテスト | どの外部通信か |
| --- | --- | --- | --- |
| `global.fetch`(gateway の `/api/projects/<id>/sns/**`・`/api/projects/<id>/api-keys/**`・`/api/operation-logs` の記録) | `installBackend()`(`installFetchByPath` が `global.fetch` へ `jest.fn()` を代入する。代入は `webAdminRuntime.ts` の 1 か所に集まる)。経路ごとに応答(成功の JSON・`failWith` の HTTP エラー・`noContent`)を返す | 対象の 16 テストファイルすべて | HTTP |
| 通信そのものが `Error` でない値で失敗する `fetch` | `failFetchWith("boom")`(`global.fetch` へ常に拒否する `jest.fn()` を代入する) | `sns*Actions.test.ts`・`pvRuleActions.test.ts`・`ProjectSns*Section.test.tsx`・`ProjectPvRulesSection.test.tsx` | HTTP(`fetch` 自体の拒否) |

### Next.js の実行基盤・next-auth の代替

| 差し替えているもの | 方法 | 使うテスト | どの実行基盤の代替か |
| --- | --- | --- | --- |
| `server-only` | `jest.mock("server-only", () => ({}))` | 対象の 16 テストファイル | サーバー専用であることを示す印。import するだけで例外を投げ、jest 上には実体が無い |
| `next/headers`(`cookies()` / `headers()`) | `nextHeadersModule`(`serverRuntime.ts`) | 同上 | リクエスト文脈(Cookie・ヘッダー)。jest 上にリクエストは無い |
| `next/server` の `after` | `nextServerModule`(`serverRuntime.ts`) | 同上 | リクエスト後処理のスケジューラ。リクエスト文脈の外で呼ぶと例外になる |
| `next-auth/jwt`(`getToken`) | `nextAuthJwtModule`(`serverRuntime.ts`) | 同上 | セッション Cookie の復号。Next.js のリクエスト文脈に依存する |
| `next-auth`(`getServerSession`) | `nextAuthModule`(`serverRuntime.ts`)。値は `resetServerRuntime(ADMIN_SESSION / USER_SESSION)` で与える | 同上 | サーバー側のセッション取得。リクエスト文脈に依存する(`@/lib/session` の `requireAdminSession` は実物) |
| `next/navigation`(`redirect` は `NEXT_REDIRECT:<パス>` を投げて処理を打ち切る) | `jest.mock("next/navigation", ...)` | 同上 | App Router のルーティング文脈 |
| `next/cache`(`revalidatePath`) | `jest.mock("next/cache", ...)`(Server Action のテストは呼び出しを `toHaveBeenCalledWith` で確かめる) | 同上 | サーバーのキャッシュの再検証。Next.js のリクエスト文脈が無いと使えない |

### ブラウザの機能

| 差し替えているもの | 方法 | 使うテスト | 何を再現するか |
| --- | --- | --- | --- |
| `window.confirm` | `jest.spyOn(window, "confirm").mockReturnValue(...)`(各テストの最後に `mockRestore()`) | `ProjectAdSenseSettingsForm.test.tsx`・`ProjectGoogleAnalyticsSettingsForm.test.tsx` | ブラウザの確認ダイアログ(承認・キャンセルを選ぶ)。jsdom は未実装 |

### 「表に無いものを差し替えているテスト」が 0 件であることの確認

次の出力は、すべて上の表のどれかに当たる(`jest.mock` の対象は `server-only`・`next/server`・`next/headers`・`next-auth/jwt`・
`next-auth`・`next/navigation`・`next/cache` の 7 種類だけで、各 16 件。`@/lib/*`・`../actions`・`../sns*Actions`・コンポーネント・`react` を指すものは 0 件。
`jest.spyOn` は `window.confirm` の 4 件だけ)。

```bash
cd "apps/web/src/app/projects/[id]/__tests__"
FILES="ProjectSnsFacebookSection.test.tsx ProjectSnsHatenaSection.test.tsx ProjectSnsLinkedInSection.test.tsx ProjectSnsThreadsSection.test.tsx ProjectSnsXSection.test.tsx ProjectSnsTemplatesSection.test.tsx snsFacebookActions.test.ts snsHatenaActions.test.ts snsLinkedInActions.test.ts snsThreadsActions.test.ts snsXActions.test.ts snsTemplateActions.test.ts ProjectPvRulesSection.test.tsx pvRuleActions.test.ts ProjectAdSenseSettingsForm.test.tsx ProjectGoogleAnalyticsSettingsForm.test.tsx"
grep -nE "jest\.(spyOn|replaceProperty)\(|(global|globalThis|window)\.[A-Za-z.]+ *= |Object\.defineProperty\(|mockImplementation|mockReturnValue|mockResolvedValue" $FILES

# jest.mock の対象のモジュール名だけを数える(7 種類だけが出ることを確かめる)
grep -hoE "jest\.(mock|doMock)\([\"'][^\"']+[\"']" $FILES | sed -E "s/jest\.(mock|doMock)\(//" | tr -d "\"'" | sort | uniq -c
```

最初の `grep` が出すのは `window.confirm` の 4 行(`jest.spyOn(window, "confirm").mockReturnValue(...)`)だけ。

## テストから到達できない分岐

完了時の `coverage-final.json` で、対象の本番コード 16 ファイルに未カバーの分岐は **1 件**。
プロダクションコードを変えずには、その分岐に入る入力や状態を作れない。行は `branchMap` の `locations[i].start.line`。

| # | ファイル | 関数 | 行 | 分岐 | 到達できない具体的な理由 |
| ---: | --- | --- | ---: | --- | --- |
| 1 | `src/app/projects/[id]/ProjectSnsFacebookSection.tsx` | `ProjectSnsFacebookSection` | 76 | `selectProjectFacebookPageAction(projectId, pageSelection?.state ?? "", …)` の `?? ""` の右辺(`pageSelection` が無いときの空の state) | このアロー関数(`selectState` の `useActionState` のアクション)が呼ばれるのは、`pageSelection` が真のときだけ描かれる投稿先ページ選択のフォーム(`{pageSelection && (… <form action={selectFormAction} …)}`)の送信だけ。`pageSelection` が `undefined` / `null` の描画ではそのフォームが存在せず、アクションを呼ぶ経路がほかに無い。 |

最初は届かないと見えた分岐は、プロダクションコードを変えずに通した。主なもの:

| ファイル | 関数 | 通した方法 |
| --- | --- | --- |
| `ProjectSns{Facebook,Hatena,LinkedIn,Threads,X}Section.tsx` | `statusText` の `status.error ? … : ""` の `""` | 接続状態を取得できず、理由(`error`)も無い `view` を渡し、「取得できない」とだけ表示されることを確かめる |
| `ProjectSnsXSection.tsx` | 履歴の `entry.error ? … : ""` の `""` | 失敗した履歴で理由が `null` のもの |
| `sns{X,Facebook,Hatena,LinkedIn,Threads}Actions.ts` | `formData.get(クライアントID相当) ?? ""` の `""` | クライアントID(アプリID・Consumer Key)の入力項目そのものが無い `FormData` で呼ぶ |
| `ProjectGoogleAnalyticsSettingsForm.tsx` | プロパティ選択肢の `displayName ?? "(名称なし)"`・`accountDisplayName ? … : ""` | 名称もアカウント名も `null` のプロパティを渡す |

### 計測の対象外

`apps/web/jest.config.ts` の `collectCoverageFrom` は `src/**/*.d.ts`・`src/**/*.stories.*`・`src/**/index.*` を計測から外している。
対象の 16 ファイルのディレクトリ(`src/app/projects/[id]/` の直下)にこれに当たるファイルは **0 件**(下のコマンドの出力が空)。
したがって、計測対象外の分岐も無い。

```bash
cd "apps/web/src/app/projects/[id]" && find . -maxdepth 1 \( -name "*.d.ts" -o -name "*.stories.*" -o -name "index.*" \)
```

## 再現手順

完了時の数字・「未カバー 1 件」は次で再現できる。

```bash
cd apps/web && npm run test:coverage
```

`extract.py`(リポジトリの外に置き、`apps/web` で実行する。`rows` はファイル別の covered / total / 率と合計、
`unc` は未カバーの分岐を 1 行ずつ出し、最後の行がその件数。第 2 引数は `coverage/coverage-final.json` のパス):

```python
import json,os,sys
d=json.load(open(sys.argv[2]))
root=os.getcwd()+'/'
names="ProjectSnsFacebookSection.tsx ProjectSnsHatenaSection.tsx ProjectSnsLinkedInSection.tsx ProjectSnsThreadsSection.tsx ProjectSnsXSection.tsx ProjectSnsTemplatesSection.tsx snsFacebookActions.ts snsHatenaActions.ts snsLinkedInActions.ts snsThreadsActions.ts snsXActions.ts snsTemplateActions.ts ProjectPvRulesSection.tsx pvRuleActions.ts ProjectAdSenseSettingsForm.tsx ProjectGoogleAnalyticsSettingsForm.tsx".split()
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
`(ファイル, 関数, 行, 種類, 分岐の番号)` が 1 行と、最後に `1`)。出力の(ファイル, 行)が、上の「テストから到達できない分岐」の
表の # 1 と一致することを確かめる(1 件も欠けず、余りも無い)。着手時の値は、同じスクリプトを `ae48ab9b` で
`npm run test:coverage` を実行した直後に走らせたもの(`unc` の最後の行は `14`)。

差分にプロダクションコードが無いことの確認:

```bash
git diff origin/develop --name-only | grep -v "__tests__/"   # docs/coverage/web-projects-sns.md だけが出る
git diff origin/develop --name-only | python3 -I -c '
import sys; sys.path.insert(0, ".claude/hooks")
import paths
bad = [p for p in sys.stdin.read().split() if paths.is_production(p)]
print(bad); sys.exit(1 if bad else 0)'   # [] が出て終了コード 0
```
