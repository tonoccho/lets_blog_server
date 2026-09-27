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

### 2.1 セッションの寿命とサーバー瞬断(issue #1098)

`offline_access` を要求した結果 Keycloak が実際に offline token を発行するかは、
**拡張のコード側からは観測できない**(検証対象が Keycloak の応答そのものであり、
単体テストではモックした応答しか見られない)。ローカルスタック(`https://localhost`)に対する
手動確認としてここに置く。

- [ ] ログイン直後、`letsBlog.debugMode` を有効にした状態で保存された `refresh_token` の
      JWT ペイロードをデコードし、`typ` クレームが `Offline` であることを確認する
      (ペイロードは `echo '<refresh_token の2番目のセグメント>' | base64 -d` で読める)
- [ ] ログインから **30分以上**(realm の `ssoSessionIdleTimeout` = 1800 秒を超過)
      拡張のコマンドを一切実行せずに放置した後、`Let's Blog: Select Project` を実行すると
      再ログインを求められずに成功する
- [ ] `docker compose restart keycloak` の実行中(Keycloak が応答しない間)に
      `Let's Blog: Select Project` を実行すると、「一時的な失敗」である旨のメッセージが出て
      **再ログインは案内されない**
- [ ] 上記の直後、Keycloak が復帰してから同じコマンドを再実行すると、
      再ログインなしで成功する(`letsBlog.tokens` が消えていない)

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
- [ ] `校正チェックを今すぐ実行` を実行すると、通知の進捗に現在のステップ名が
      「日本語チェック (1/5)」→「校正チェック (2/5)」→「校閲 (3/5)」→「読者視点でのチェック (4/5)」→「文体チェック (5/5)」の順に表示される
      (**スタブ環境では指摘が空になる。#1004。実プロバイダーでのみ確認可能**)
- [ ] 指摘箇所にステップ別の色のアンダーライン(赤=日本語 / 橙=校正 / 青=校閲 / 緑=読者視点 / 紫=文体)が引かれ、
      別のステップの指摘が同じ色になっていない。ライトテーマとダークテーマの両方で色が読み取れる
- [ ] 指摘箇所にカーソルを重ねると、ホバーにステップ名・指摘内容(あれば提案・出典)が表示される
- [ ] 本文を編集して数秒待っても、本文のAI校正は自動で走らない(通知の進捗も出ず、AIサーバーへのリクエストも発生しない)。
      再度 `校正チェックを今すぐ実行` を実行したときだけ更新される
- [ ] 設定に `letsBlog.proofreadEnabled` / `letsBlog.proofreadDebounceMs` が残っていても拡張は通常どおり起動し、
      設定画面には廃止の注記が表示され、有効にしても自動実行されない
- [ ] front matter の不正(過去日時の `publish_scheduled_at` / 不正な `status` / 存在しないカテゴリ)は、
      編集の都度(約0.5秒のデバウンス後)に赤い波線で表示され、QuickFix が使える(本文のアンダーラインとは別系統)
- [ ] 生成中に「キャンセル」を押すと処理が中断され、エラー通知にならない

## 6. 公開(AC-EXT-006 / 007 / 008)

- [ ] `Let's Blog: Publish` の進捗通知が出て、完了後に公開URLを開けるアクションが提示される
- [ ] `Let's Blog: Schedule Publication` で日時を入力すると front matter の `publish_scheduled_at` が書き換わる
      (サーバー側の予約公開は`ext:articles/publish.feature`で自動検証済み。#1003で解消)
- [ ] `Let's Blog: Delete Post` の確認ダイアログが出て、削除が成功する
      (サーバー側の投稿削除は`ext:articles/deletion.feature`で自動検証済み。#1001で解消)

## 7. 画像・図(AC-EXT-016 / 017 / 020 / 021 / 022)

- [ ] `Generate Image` パネルでモデル・サイズを選び、生成した画像が `assets/` へ保存され本文へ挿入される
      (**サーバー側の画像生成は #998 で失敗する**)
- [ ] `Generate Image` パネルで batch size に 2 以上を指定すると、生成された枚数ぶんの
      サムネイルがプレビューに並び、1枚目が既定で選択されている(#1104)
- [ ] そのうち2枚目を選んで「アイキャッチとして設定」すると、**2枚目**が `assets/` へ保存され
      front matter の `featured_image` がそのファイルを指す(#1104)
- [ ] batch size が 1 のときはサムネイル列が出ず、従来どおり単一のプレビューから保存できる(#1104)
- [ ] `Generate Image` パネルに batch size と batch count の入力が並び、どちらも 16 まで入れられる(#1105)
- [ ] batch size 2・batch count 3 で生成すると 6 枚のサムネイルが並ぶ(#1105)
- [ ] 生成中のローディング表示に「合計6枚を生成しています」と長時間になりうる旨が出る(#1105)
- [ ] サムネイル列を右へスクロールすると、画面に入ったサムネイルから順に画像が現れる
      (遅延読み込み。#1105。**実ブラウザでの描画コストと IntersectionObserver の実挙動は
      拡張ホストが無いと確認できない**)
- [ ] batch size 16 の生成が、`letsBlog.requestTimeoutMs` を既定のままでもクライアント側の
      タイムアウトで中断しない(#1105)
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
