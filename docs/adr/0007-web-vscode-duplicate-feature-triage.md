# ADR-0007: Web管理画面とVSCode拡張の重複候補機能は機能ごとに判断し、一律の統合はしない

## Status

Accepted

## Context

[#551 (Epic)](https://github.com/tonoccho/lets_blog_server/issues/551) Phase 20 では、
「Web 管理画面と VSCode 拡張の間で機能が重複していれば、Web 側を削除して VSCode 拡張に
一本化する」という方針を掲げていたが、削除範囲は保留になっていた。
[#586 [D3]](https://github.com/tonoccho/lets_blog_server/issues/586) はこの棚卸しと
判断を行うための Issue であり、実装(削除)は含まない。

#586 の Issue 本文には調査済みの重複候補として次の4機能が挙げられていた。

| 機能 | Web | VSCode 拡張 |
|---|---|---|
| 生成画像ギャラリー | `/image-gallery` | `imageGalleryPanel.ts` |
| ダイアグラムギャラリー | `/diagram-gallery` | `diagramGalleryPanel.ts` / `diagramEditorPanel.ts` |
| 記事プラン(壁打ち) | `/projects/[id]/plan` | `planPanel.ts` |
| 投稿一覧・操作 | `/posts`, `/projects/[id]/posts` | `publish` / `deletePost` / `schedulePublication` |

本 ADR 作成にあたり、上記4候補について実際のコード(Web側のページ・Server Action、
VSCode拡張側のパネル・コマンド実装)を読み、Issue本文の記述が現状のコードと一致しているかを
検証した上で判断した。

### 調査で判明した事実(Issue本文の記述との差分を含む)

**1. 生成画像ギャラリー**

- Issue本文は「Web は閲覧専用」としているが、実装はそうではない。
  `web/src/app/image-gallery/ImageGalleryGrid.tsx` / `actions.ts` には、削除
  (`deleteGeneratedImageAction`)に加えて **タグの追加・削除
  (`updateGeneratedImageTagsAction`、issue #281)**、**タグによる絞り込み**、
  **生成パラメータ全体(prompt/negative prompt/steps/cfg scale/sampler/scheduler/
  seed/size/batch size/checkpoint/LoRA)を表示する詳細モーダル**、**設定をJSONで
  クリップボードへコピーする機能(issue #437)** がある。
- `docs/FEATURES_AND_USAGE.md` の「Managing Generated Images」節にも、Web側が
  「タグで絞り込み・タグ編集・削除」を担い、VSCode拡張側は「記事への挿入 + この設定で
  再生成」を担う、という意図的な役割分担が明文化されている。
- VSCode拡張側 (`extension/src/imageGalleryPanel.ts`) のコマンドは
  `loadImages`/`loadThumbnails`/`insertImage`/`setAsEyecatch`/`deleteImage`/
  `regenerateWithSettings` のみで、**タグ管理機能が無い**。Webview
  (`extension/webviews/imageGallery.js`)もプロンプトの先頭40文字を表示するのみで、
  詳細メタデータの一覧表示は無い。
- `listGeneratedImages()` はWeb側では `projectId` を省略してプロジェクト横断で
  一覧取得している(`web/src/lib/apiClient.ts`)。VSCode拡張側は常に現在の
  `_projectId` にスコープされる。Webはプロジェクト横断の監査ビューとして機能している。

→ Web側の機能は「重複+閲覧専用」ではなく、**タグ管理・詳細メタデータ表示・
プロジェクト横断閲覧という独自の付加価値**を持つ。重複しているのは「一覧表示+削除」の
部分のみで、VSCode拡張側は「記事への挿入」という別用途を担っている。

**2. ダイアグラムギャラリー**

- `web/src/app/diagram-gallery/DiagramGalleryGrid.tsx` / `actions.ts` の機能は
  一覧表示 + 詳細モーダル(名前・更新日時のみ) + 削除のみ。タグ・検索・絞り込み等の
  独自機能は無い。
- 編集機能はIssue本文の記述通り、VSCode拡張側 (`diagramEditorPanel.ts`:
  `insertNew`/`saveOverwrite`/`saveAsNew`) にのみ存在する。
- VSCode拡張側の `diagramGalleryPanel.ts` は `loadDiagrams`/`loadThumbnails`/
  `insertDiagram`/`deleteDiagram` を持ち、Web側の「一覧表示+削除」機能を包含している。
- `docs/FEATURES_AND_USAGE.md` にダイアグラム機能への言及自体が無く、画像ギャラリーの
  ような役割分担の明文化もされていない。

→ 画像ギャラリーと異なり、Web側に独自の付加価値が無い**純粋な重複**。

**3. 記事プラン(壁打ち)**

- Web側 (`web/src/app/projects/[id]/plan/`) はセッション一覧・切り替え
  (`ArticlePlanSessionList.tsx`)、チャット (`ArticlePlanChat.tsx`)、構成提案
  (`ArticlePlanStructureProposal.tsx`)、タイトル提案 (`ArticlePlanProposals.tsx`)、
  GitHub Issue一覧 (`ArticlePlanIssueList.tsx`) を持つ(7ファイル計937行)。
- VSCode拡張側 (`planPanel.ts`, 200行) は `loadIssues`/`loadCategories`/
  `loadIssueOutline`/`sendChat`/`suggestStructure`/`acceptStructure`/
  `suggestMetadata`/`approveAndScaffold`/`openArticle` を持つ。
- 重要な差分: VSCode拡張の `approveAndScaffold` は**ローカルファイルシステムに
  記事のMarkdownファイルをスキャフォールドし、front matterを書き込み、GitHub Issueを
  自分にアサインする**処理で、Webからは原理的に実行できない(ブラウザにローカル
  ワークスペースへのファイル書き込み権限が無い)。
- 逆にWeb側の「複数セッションの一覧・履歴切り替え」「タイトル提案タブ」は、
  VSCode拡張側には存在しない。

→ 双方とも実装があるが、**パイプラインの異なる段階を担う相補的な機能**であり、
単純な重複ではない。Web=構想・構成のブレスト+履歴管理、VSCode拡張=Issueを起点にした
最終的なローカル記事スキャフォールド生成、という役割分担になっている。

**4. 投稿一覧・操作**

- Web側 (`web/src/app/posts/page.tsx`, `PostsTable.tsx`,
  `web/src/app/projects/[id]/posts/page.tsx`) は `listPosts()` の結果を表として
  表示するのみで、**削除・公開・スケジュール等の変更操作は一切実装されていない**
  (ソート機能はあるが、これはクライアント側の表示順変更でAPIを呼ばない)。
- VSCode拡張側の `publish`/`deletePost`/`schedulePublication` は、いずれも
  **現在エディタで開いているMarkdown記事1件**を対象にした操作で、一覧・横断閲覧の
  UIは無い(`commandDeletePost` は front matter の `slug` から投稿済みサイトを
  逆引きして削除候補を出す方式で、リストをブラウズする機能ではない)。

→ Issue本文の推測通り、**用途が異なるため重複ではない**。Webは横断的な閲覧・監査
専用、VSCode拡張は執筆中の記事に対する操作専用。

## Decision

4候補それぞれについて、以下の通り決定する。

| 機能 | 決定 | 理由(要約) |
|---|---|---|
| 生成画像ギャラリー (`/image-gallery`) | **Web に残す** | タグ管理・詳細メタデータ表示・プロジェクト横断閲覧という、VSCode拡張に無い独自価値がある。重複するのは「一覧+削除」のみで、大部分は補完関係 |
| ダイアグラムギャラリー (`/diagram-gallery`) | **Web から削除** | Web側に独自価値が無く、VSCode拡張の `diagramGalleryPanel.ts` が同等機能(一覧・削除)を完全に包含している純粋な重複 |
| 記事プラン(壁打ち) (`/projects/[id]/plan`) | **Web に残す** | ローカルファイルシステムへのスキャフォールド生成はVSCode拡張でしか行えない一方、複数セッションの一覧・履歴管理はWebにしか無い。同一パイプラインの異なる段階を担う相補的機能であり重複ではない |
| 投稿一覧・操作 (`/posts`, `/projects/[id]/posts`) | **Web に残す** | Webは横断閲覧専用(変更操作なし)、VSCode拡張は単一記事への操作専用で、対象範囲・用途が異なるため重複ではない |

### 削除対象(ダイアグラムギャラリー)についてのVSCode拡張側の機能不足確認

`diagramGalleryPanel.ts` は Web側の「一覧表示・詳細確認・削除」を機能としてすべて
満たしている(`loadDiagrams`/`loadThumbnails`/`deleteDiagram`)。ブロッキングな
機能不足は無いため、削除に先立つ補完Issueの起票は不要と判断した。

ただし、VSCode拡張の `DiagramGalleryPanel.createOrShow` は Markdownエディタを開いた
状態でのみ起動できる (`extension.ts` 側で `getActiveMarkdownEditor()` を要求する
コマンドに紐づく設計)ため、「記事を編集していない状態でダイアグラムだけを横断的に
棚卸しする」というニッチな用途は失われる。この用途は稀であり、Web側の
`/diagram-gallery` が持っていた価値の大部分(一覧・削除)はVSCode側で代替できるため、
ブロッカーとはしない。削除後にこの用途への要望が実際に出た場合は、別途
フォローアップ Issue で対応する。

### 起票した削除Issue

- [#662] ダイアグラムギャラリー (`/diagram-gallery`) を Web から削除する
  (Epic #551 にリンク、本 ADR および #586 を参照)

画像ギャラリー・記事プラン・投稿一覧については「Web に残す」と判断したため、削除Issueは
起票していない。

## Consequences

**利点**

- ダイアグラムギャラリーの削除により、同一機能を2箇所でメンテナンスするコストが1箇所に
  減る。
- 画像ギャラリー・記事プラン・投稿一覧について、実際のコード調査に基づき「重複ではない」
  ことを明文化できたため、今後同じ議論が再燃したときの参照先ができる。

**欠点・トレードオフ**

- 画像ギャラリー・記事プラン・投稿一覧の3機能はWeb/VSCode拡張の両方に実装が残り続け、
  API変更時などに両方の追随が必要になる状態は解消されない。
- ダイアグラムギャラリー削除後、記事編集中でない状態でのダイアグラム横断閲覧という
  ニッチな用途が失われる(上記の通り許容範囲と判断)。

## Alternatives considered

**Issue本文の当初方針通り、4候補すべてを機械的にWebから削除する(却下)**

Issue本文の初期調査メモは「重複候補」の一次リストであり、コードの実態確認までは
行われていなかった。実際にコードを読むと、画像ギャラリーはタグ管理という独自機能を
持ち(`docs/FEATURES_AND_USAGE.md` にも役割分担が明文化されている)、記事プランは
ローカルファイル書き込みという原理的な制約からWeb側では代替不可能な段階を含み、
投稿一覧はそもそも変更操作を持たない閲覧専用ビューだった。これらを「重複」として
機械的に削除すると、実際にはWebにしか無い機能(タグ管理、セッション履歴、横断監査)を
失うことになり、Issue本文の「重複していれば削除」という前提条件そのものを満たさない。
そのため機械的な一律削除は却下し、機能ごとの個別判断を採用した。

**4候補すべてをWebに残す(却下)**

ダイアグラムギャラリーについては、Web側に一覧表示・削除以外の独自価値が確認できず、
VSCode拡張側の `diagramGalleryPanel.ts` が同等機能を完全に包含していた。この場合まで
一律で「残す」を選ぶと、Epic #551 が掲げる「重複していれば一本化する」という目的に
反し、意味のない二重メンテナンスを放置することになるため却下した。
