# VSCode拡張 手動受け入れチェックリスト

リリース前に**人が VSCode 上で確認する項目**の一覧(issue #942 / AT-16)。

拡張の検証は二層で行う(docs/ACCEPTANCE_CRITERIA.md §2.14、docs/ACCEPTANCE_TESTING.md)。

| 層 | 何を検証するか | 置き場所 |
| --- | --- | --- |
| Layer 1(APIレベル受け入れテスト) | 拡張自身の `apiClient` / `httpClient` が呼ぶサーバー契約 | `apps/extension/e2e/features/**/*.feature` |
| Layer 2(単体テスト) | front matter・見出し文脈・Markdown抽出・キャッシュ・パネルの状態遷移などの純ロジック | `apps/extension/src/__tests__/**` |
| **このチェックリスト** | **VSCode の UI 操作そのもの**(コマンドパレット、エディタへの挿入位置、Webview のクリック、キーバインド) | この文書 |

## なぜ自動化しないのか

実 VSCode インスタンスを起動する UI テスト(`@vscode/test-electron` 等)は
**AT-16 のスコープ外**である(#942 の Out of Scope。導入するなら別 Issue)。
拡張ホストが要る操作——コマンドパレットからの起動、エディタの選択範囲、
クリップボード、Webview の DOM 操作、draw.io の iframe——は、
Layer 1 / Layer 2 のどちらからも観測できない。
**観測できないものを「検証済」と書かない**ために、ここへ明示的に書き出す。

## 使い方

- リリース(拡張の `.vsix` 配布、AT-14 / #940)の前に、この一覧を上から実行する。
- 環境は開発スタック(`https://localhost`)で足りる。`letsBlog.allowInsecureTls` は `true`。
- 失敗したら Issue を立てる。チェックリストに「既知の不具合」を書き足して回避しない。

---

## 1. 前提の準備

- [ ] `letsBlog.serverUrl` / `letsBlog.allowInsecureTls` を設定した VSCode で拡張を起動できる
- [ ] 出力パネル「Let's Blog」が作られ、`letsBlog.debugMode` を有効にするとログが出る

## 2. ログインと設定(AC-EXT-001 / 014)

- [ ] `Let's Blog: Login` を実行すると user code が表示され、ブラウザが承認画面で開く
- [ ] 承認後に「ログインしました」と表示され、ロール名が読める文言で出る
- [ ] 承認せずに放置した場合、待機中であることが分かる表示のまま失敗しない
- [ ] `Let's Blog: AIプロバイダーを切り替える` の選択肢(既定/OLLAMA/OPENAI/CLAUDE)が出て、選ぶと設定へ保存される

> トークンの保存・自動更新・証明書検証・接続失敗時のメッセージは Layer 1
> (`e2e/features/auth/`)で自動検証済み。ここで見るのは**画面表示だけ**。

## 3. プロジェクト / サイトの選択(AC-EXT-002 / 003)

- [ ] `Let's Blog: Select Project` のクイックピックにプロジェクト名が並び、選ぶとステータスバー/以後の操作へ反映される
- [ ] `Let's Blog: Select Site` のクイックピックにサイトが並び、選択が front matter の `site` へ反映される
- [ ] プロジェクト未選択のまま他コマンドを実行すると、選択を促すメッセージが出る

## 4. 記事の作成(AC-EXT-004 / 005 / 015)

- [ ] `Let's Blog: Create Article` で作成パネルが開き、入力後に `articles/<slug>/article.md` が生成されエディタで開く
- [ ] `Let's Blog: Create Article (No AI)` でも同じ配置のファイルが生成される
- [ ] `Let's Blog: Plan Article` の壁打ちパネルが開き、対話 → 構成案 → 記事生成まで画面遷移する
- [ ] 既存の `articles/<slug>/` がある状態で作成すると上書き確認ダイアログが出る

## 5. 執筆支援(AC-EXT-009 / 010 / 011 / 012 / 013)

- [ ] 選択範囲に対する `Ask AI (Draft/Proofread/Summarize)` の結果が、**選択範囲の位置に**挿入される
- [ ] `Ask AI (Web Search)` のパネルに出典リンクが表示され、クリックでブラウザが開く
- [ ] `Suggest Tags` の候補から選んだタグが front matter の `tags` へ追記される
      (**スタブ環境では候補が空になる。#1004。実プロバイダーでのみ確認可能**)
- [ ] `Generate Section` をカーソル位置の見出し配下で実行すると、その節の本文として挿入される
- [ ] `校正チェックを今すぐ実行` の指摘がエディタに波線で表示され、ホバーで内容が読める
      (**スタブ環境では指摘が空になる。#1004。実プロバイダーでのみ確認可能**)
- [ ] `letsBlog.proofreadEnabled` を有効にすると、入力停止から `proofreadDebounceMs` 後に自動で波線が更新される
- [ ] 生成中に「キャンセル」を押すと処理が中断され、エラー通知にならない

## 6. 公開(AC-EXT-006 / 007 / 008)

- [ ] `Let's Blog: Publish` の進捗通知が出て、完了後に公開URLを開けるアクションが提示される
- [ ] `Let's Blog: Schedule Publication` で日時を入力すると front matter の `publish_scheduled_at` が書き換わる
      (サーバー側の予約公開は`ext:articles/publish.feature`で自動検証済み。#1003で解消)
- [ ] `Let's Blog: Delete Post` の確認ダイアログが出る(**削除自体は #1001 で失敗する**)

## 7. 画像・図(AC-EXT-016 / 017 / 020 / 021 / 022)

- [ ] `Generate Image` パネルでモデル・サイズを選び、生成した画像が `assets/` へ保存され本文へ挿入される
      (**サーバー側の画像生成は #998 で失敗する**)
- [ ] `Image Gallery` の一覧から画像を選ぶと、本文のカーソル位置へ相対パスで挿入される
- [ ] `Add New Diagram` で draw.io の編集画面が開き、保存すると図が登録され本文へ挿入される
- [ ] 図の上にカーソルがあるときだけ `Edit Diagram` がコンテキストメニューに出る
- [ ] `Edit Diagram` で既存の図が開き、保存内容がプレビューへ反映される
- [ ] `Diagram Gallery` から選んだ図が本文へ挿入される

## 8. プレビュー(AC-EXT-018 / 019)

- [ ] `Preview Article` が別カラムで開き、テーマCSSが当たった見た目になる
- [ ] 環境(ローカル/テスト/本番)を切り替えると、その環境のCSSで再描画される
- [ ] 本文を編集するとプレビューが追従する
- [ ] `Open Preview DevTools` で DevTools が開く(開発者向け。受け入れ基準は持たない)

## 9. リンクの貼り付け(AC-EXT-023 / 024)

- [ ] Markdown エディタで `Ctrl+Shift+V`(Mac: `Cmd+Shift+V`)がスマートカードの貼り付けになる
- [ ] Markdown 以外・読み取り専用エディタでは既定の貼り付け動作のままになる
- [ ] `Ctrl+V` でURLを貼ると `[タイトル | サイト名](URL)` になる
      (**メタデータ取得は #1002 で失敗するため、現状はURLのみが貼られる**)
- [ ] URL以外のテキストは通常どおり貼り付けられる

## 10. コード補完・診断

- [ ] front matter で `status:` / `categories:` の補完候補が出る
- [ ] 本文でカスタムタグの補完候補が出る
- [ ] front matter の `publish_scheduled_at` に過去日時を書くと警告が出て、クイックフィックスで直せる

---

## 自動化できていない理由の内訳

| 項目 | 理由 |
| --- | --- |
| コマンドパレットからの起動 | 拡張ホストが必要(#942 Out of Scope) |
| エディタへの挿入位置・選択範囲 | `vscode.TextEditor` の実体が必要 |
| Webview のクリック・フォーム操作 | Webview の DOM は拡張ホスト内にしか存在しない |
| draw.io / プレビューの iframe | 外部エディタの描画結果 |
| クリップボード・キーバインド | VSCode の入力系 |
| 通知・進捗・クイックピックの見え方 | UI そのもの |

自動化されている範囲は `apps/extension/e2e/README.md` を参照。
