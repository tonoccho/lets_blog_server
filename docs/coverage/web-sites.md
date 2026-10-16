# apps/web サイト・投稿・カスタムタグ・画像ギャラリーの分岐カバレッジ(#1834)

Epic #1711 の子 Issue #1834 の記録。利用者の依頼(2026-10-09)「C1、C2 レベルのカバレッジ 100% を目指す。
ただし、外部との通信(DB アクセスや MQ との送受信、HTTP 通信など)以外では Mock を使ってはならない。
このときにプロダクションコードを変更してはならない」を、`apps/web` の `src/app/sites`・`posts`・`custom-tags`・
`custom-tag-templates`・`image-gallery` に適用した結果。対象は本番コード 35 ファイル。

プロダクションコードは変更していない(差分は `apps/web/src/app/**/__tests__/` と本ファイルだけ)。
`jest.config.ts`・`jest.setup.ts` も変えていない。この Issue の受け入れ基準はテスト資産の状態なので、Gherkin の
受け入れシナリオは書かない(製品の振る舞いではなく web UI から到達できない。CLAUDE.md → Where the tests live の例外)。
足したテストは、本番コードが既にあるので足した時点で通る。着手時の `coverage-final.json` で該当分岐が未カバーだったことを、
着手時の計測値が示している。

## 計測値(着手時・完了時)

数え方は jest の `branches`(v8 プロバイダー)。`coverage/coverage-final.json` の `branchMap` の各分岐を 1 と数え、
`b` の実行回数が 1 以上なら covered。`||` / `&&` / `??` / 三項 / `if` / 既定値は v8 の出力でそれぞれ別の分岐になるので
C1 と C2 を兼ねる。計測コマンド(着手時・完了時とも同じ):

```bash
cd apps/web && npm run test:coverage
```

どのテストからも読み込まれていないファイルは `(empty-report)` として 1 分岐の未実行になり、実行されなかった関数の
内側は数えられない。着手時の total が少なく完了時に増えているのはこのため(`DeleteSiteButton.tsx`・
`InstallWpCliButton.tsx`・`CustomTagManager.tsx`・`ValidationPanel.tsx` が着手時は 0/1)。

### 着手時

コミット `732c1e44`(develop 起点、変更なし)。テスト 278 スイート・2,796 件、失敗 0。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `src/app/custom-tag-templates/CustomTagTemplateGallery.tsx` | 116 | 122 | 95.1% |
| `src/app/custom-tag-templates/actions.ts` | 5 | 5 | 100.0% |
| `src/app/custom-tag-templates/filterMyTemplates.ts` | 6 | 6 | 100.0% |
| `src/app/custom-tag-templates/page.tsx` | 4 | 5 | 80.0% |
| `src/app/custom-tags/CustomTagGenerationForm.tsx` | 21 | 21 | 100.0% |
| `src/app/custom-tags/CustomTagGenerationResult.tsx` | 12 | 12 | 100.0% |
| `src/app/custom-tags/CustomTagManager.tsx` | 0 | 1 | 0.0% |
| `src/app/custom-tags/ValidationPanel.tsx` | 0 | 1 | 0.0% |
| `src/app/custom-tags/actions.ts` | 10 | 11 | 90.9% |
| `src/app/image-gallery/ImageEditDialog.tsx` | 41 | 41 | 100.0% |
| `src/app/image-gallery/ImageGalleryGrid.tsx` | 286 | 297 | 96.3% |
| `src/app/image-gallery/[id]/file/route.ts` | 7 | 7 | 100.0% |
| `src/app/image-gallery/actions.ts` | 31 | 31 | 100.0% |
| `src/app/image-gallery/imageEdit.ts` | 40 | 40 | 100.0% |
| `src/app/image-gallery/page.tsx` | 4 | 4 | 100.0% |
| `src/app/image-gallery/pageSize.ts` | 0 | 0 | 100.0% |
| `src/app/posts/PostsTable.tsx` | 8 | 13 | 61.5% |
| `src/app/posts/error.tsx` | 4 | 5 | 80.0% |
| `src/app/posts/page.tsx` | 6 | 6 | 100.0% |
| `src/app/sites/CheckConnectionButton.tsx` | 14 | 14 | 100.0% |
| `src/app/sites/DeleteSiteButton.tsx` | 0 | 1 | 0.0% |
| `src/app/sites/ManagedWordPressForm.tsx` | 19 | 22 | 86.4% |
| `src/app/sites/SiteCreationPanel.tsx` | 7 | 7 | 100.0% |
| `src/app/sites/SiteForm.tsx` | 7 | 13 | 53.8% |
| `src/app/sites/SiteListTable.tsx` | 47 | 52 | 90.4% |
| `src/app/sites/[id]/edit/InstallWpCliButton.tsx` | 0 | 1 | 0.0% |
| `src/app/sites/[id]/edit/LetsblogPluginPanel.tsx` | 25 | 26 | 96.2% |
| `src/app/sites/[id]/edit/LetsblogSyncPanel.tsx` | 29 | 29 | 100.0% |
| `src/app/sites/[id]/edit/SiteEditForm.tsx` | 9 | 14 | 64.3% |
| `src/app/sites/[id]/edit/StaticContentPanel.tsx` | 32 | 35 | 91.4% |
| `src/app/sites/[id]/edit/actions.ts` | 12 | 14 | 85.7% |
| `src/app/sites/[id]/edit/page.tsx` | 13 | 13 | 100.0% |
| `src/app/sites/actions.ts` | 60 | 76 | 78.9% |
| `src/app/sites/error.tsx` | 4 | 5 | 80.0% |
| `src/app/sites/page.tsx` | 10 | 10 | 100.0% |
| **合計(35 ファイル)** | **889** | **960** | **92.6%** |

### 完了時

コミット `509457d2`(テストを足した最後のコミット。本ファイルを足すコミットは `docs/` だけを変えるので計測値は変わらない)。
テスト 288 スイート・2,955 件、失敗 0・skipped 0。`npm run test:coverage` は終了コード 0(`jest.config.ts` の
リポジトリ全体の下限を含む)。

| ファイル | covered | total | 率 |
| --- | ---: | ---: | ---: |
| `src/app/custom-tag-templates/CustomTagTemplateGallery.tsx` | 137 | 138 | 99.3% |
| `src/app/custom-tag-templates/actions.ts` | 39 | 39 | 100.0% |
| `src/app/custom-tag-templates/filterMyTemplates.ts` | 6 | 6 | 100.0% |
| `src/app/custom-tag-templates/page.tsx` | 8 | 8 | 100.0% |
| `src/app/custom-tags/CustomTagGenerationForm.tsx` | 21 | 21 | 100.0% |
| `src/app/custom-tags/CustomTagGenerationResult.tsx` | 12 | 12 | 100.0% |
| `src/app/custom-tags/CustomTagManager.tsx` | 24 | 24 | 100.0% |
| `src/app/custom-tags/ValidationPanel.tsx` | 16 | 16 | 100.0% |
| `src/app/custom-tags/actions.ts` | 12 | 12 | 100.0% |
| `src/app/image-gallery/ImageEditDialog.tsx` | 41 | 41 | 100.0% |
| `src/app/image-gallery/ImageGalleryGrid.tsx` | 300 | 304 | 98.7% |
| `src/app/image-gallery/[id]/file/route.ts` | 6 | 6 | 100.0% |
| `src/app/image-gallery/actions.ts` | 40 | 40 | 100.0% |
| `src/app/image-gallery/imageEdit.ts` | 40 | 40 | 100.0% |
| `src/app/image-gallery/page.tsx` | 4 | 4 | 100.0% |
| `src/app/image-gallery/pageSize.ts` | 0 | 0 | 100.0% |
| `src/app/posts/PostsTable.tsx` | 35 | 35 | 100.0% |
| `src/app/posts/error.tsx` | 5 | 5 | 100.0% |
| `src/app/posts/page.tsx` | 6 | 6 | 100.0% |
| `src/app/sites/CheckConnectionButton.tsx` | 14 | 14 | 100.0% |
| `src/app/sites/DeleteSiteButton.tsx` | 10 | 10 | 100.0% |
| `src/app/sites/ManagedWordPressForm.tsx` | 21 | 23 | 91.3% |
| `src/app/sites/SiteCreationPanel.tsx` | 9 | 9 | 100.0% |
| `src/app/sites/SiteForm.tsx` | 30 | 30 | 100.0% |
| `src/app/sites/SiteListTable.tsx` | 53 | 54 | 98.1% |
| `src/app/sites/[id]/edit/InstallWpCliButton.tsx` | 9 | 9 | 100.0% |
| `src/app/sites/[id]/edit/LetsblogPluginPanel.tsx` | 26 | 26 | 100.0% |
| `src/app/sites/[id]/edit/LetsblogSyncPanel.tsx` | 29 | 29 | 100.0% |
| `src/app/sites/[id]/edit/SiteEditForm.tsx` | 36 | 36 | 100.0% |
| `src/app/sites/[id]/edit/StaticContentPanel.tsx` | 33 | 35 | 94.3% |
| `src/app/sites/[id]/edit/actions.ts` | 14 | 14 | 100.0% |
| `src/app/sites/[id]/edit/page.tsx` | 13 | 13 | 100.0% |
| `src/app/sites/actions.ts` | 94 | 94 | 100.0% |
| `src/app/sites/error.tsx` | 5 | 5 | 100.0% |
| `src/app/sites/page.tsx` | 10 | 10 | 100.0% |
| **合計(35 ファイル)** | **1158** | **1168** | **99.1%** |

## モックの境界

テストが実装を差し替えているもの(`jest.mock`、実装を付けた `jest.spyOn`、`global.fetch` などへの代入)は次の表で全部。
自前のモジュール(`../actions`・`@/lib/apiClient`・`@/lib/auth`・`@/lib/session`・`@/lib/formatDate`・
`@/components/*`・子コンポーネント)と `react` は、どのテストも差し替えていない。

| 差し替えるもの | 分類 | 何の代替か |
| --- | --- | --- |
| `global.fetch`(`installFetch` / `installFetchByPath` / 直接代入) | 外部との通信 | gateway・各サービスへの HTTP |
| `server-only` | 実行基盤の代替(Q2) | Next.js のサーバー専用ガード。jest 上に実体が無い |
| `next/server`(`after`) | 実行基盤の代替(Q2) | リクエスト文脈 |
| `next/headers` | 実行基盤の代替(Q2) | cookie・ヘッダーの取得 |
| `next/cache`(`revalidatePath`) | 実行基盤の代替(Q2) | Next.js のキャッシュ |
| `next/navigation`(`redirect` / `notFound` / 各 hook) | 実行基盤の代替(Q2) | ルーターの遷移 |
| `next-auth` / `next-auth/jwt` / `next-auth/react` | 実行基盤の代替(Q2) | セッション取得がリクエスト文脈に依存する |
| `jest.spyOn(window, "confirm")` / `"alert")` | ブラウザ UI の代替 | jsdom に実装が無い確認・通知ダイアログ。結果(OK/キャンセル)を指定する |
| `navigator.clipboard`(`Object.defineProperty` または `Object.assign` で `writeText` を差し替え。`StaticContentPanel.test.tsx`・`ImageGalleryGrid.test.tsx`) | ブラウザ API の代替 | jsdom に無いクリップボード |
| `global.IntersectionObserver`(`ImageGalleryGrid.{branches,folders,paging,source,sourceIcon,tagClick}.test.tsx`) | ブラウザ API の代替 | jsdom に無い無限スクロール用の監視。交差したかどうかをテストから指定する |
| `jest.useFakeTimers()`(`StaticContentPanel`・`CustomTagManager`・`CustomTagTemplateGallery` のテスト) | 実行環境の時計の代替 | `setTimeout` / `setInterval` / `Date` を進めて待ち時間を省く。自前コードの置き換えではない |
| `jest.spyOn(console, "error")` | 出力の抑止 | 想定したエラーのログでテスト出力が埋まるのを防ぐ。自前コードの置き換えではない |

## テストから到達できない分岐

完了時の `coverage-final.json` で未カバーの分岐は次の 10 件で、すべて本番コードを変えずには入れない。

| ファイル | 関数 | 行 | 理由 |
| --- | --- | ---: | --- |
| `custom-tag-templates/CustomTagTemplateGallery.tsx` | `handleClone` | 284 | `!cloneName.trim()` の分岐(alert して return)。`handleClone` は「複製を作成」ボタンだけが呼び、そのボタンは `disabled={!cloneName.trim()}`。React は disabled のボタンのクリックを発火しないので、空の名前では呼べない |
| `image-gallery/ImageGalleryGrid.tsx` | `handleBulkDelete` | 572 | `checkedIds.size === 0 \|\| bulkDeleting` の return。呼び出すボタンが同じ条件で `disabled` |
| `image-gallery/ImageGalleryGrid.tsx` | `saveTags` | 602 | `selectedId === null` の return。呼び元の `handleAddTag` / `handleRemoveTag` は詳細ダイアログ(`selectedId !== null` のとき)の中にしか描画されない |
| `image-gallery/ImageGalleryGrid.tsx` | `handleRemoveTag` | 630 | `!detail` の return。タグ削除ボタンは `detail` があるときだけ描画される |
| `image-gallery/ImageGalleryGrid.tsx` | `handleCopySettings` | 636 | `!detail` の return。コピーボタンは `detail` があるときだけ描画される |
| `sites/ManagedWordPressForm.tsx` | `deriveWpUsername` | 23 | `email.split("@")[0] ?? ""` の `?? ""`。`split` は常に要素 0 を持つ配列を返すので右辺に入らない |
| `sites/ManagedWordPressForm.tsx` | `Field` | 147 | `isSelect` の中の `wide ? "sm:col-span-2" : ""` の真側。select の `Field`(`managedLocale`)に `wide` を渡す呼び出しが無い |
| `sites/SiteListTable.tsx` | `filteredSites`(並べ替え) | 97 | `sortBy === "updatedAt"`。「更新日」の見出しが無く、`handleColumnSort("updatedAt")` を呼ぶ UI が無い |
| `sites/[id]/edit/StaticContentPanel.tsx` | `handleSave` | 96 | `!generated` の return。保存ボタンは `generated` があるときだけ描画される |
| `sites/[id]/edit/StaticContentPanel.tsx` | `handleCopy` | 112 | `!content` の return。コピーボタンは `content` があるときだけ描画される |

計測対象外(`collectCoverageFrom` が外す `*.d.ts`・`*.stories.*`・`index.*`)で、対象ディレクトリに分岐を持つファイルは無い。
`image-gallery/pageSize.ts` は分岐が 0 件。

### 再現手順

```bash
cd apps/web && npm run test:coverage
node -e '
const d=require("./coverage/coverage-final.json");
for (const [f,v] of Object.entries(d)) {
  if(!/\/src\/app\/(sites|posts|custom-tags|custom-tag-templates|image-gallery)\//.test(f)||f.includes("__tests__"))continue;
  for (const [k,x] of Object.entries(v.b)) x.forEach((n,i)=>{ if(n===0) console.log(f.split("/src/app/")[1], v.branchMap[k].locations[i]?.start.line ?? v.branchMap[k].loc.start.line); });
}'
```

出力が上の表の 10 件(`ImageGalleryGrid.tsx` は行 572・602・630・636)と一致すること。
