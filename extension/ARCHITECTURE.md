# アーキテクチャ(Let's Blog VSCode拡張)

この文書は、拡張の設計・各モジュールの責任・データの流れをまとめたものです。
初めてこのコードベースに触れる人が、全体像を掴んでから個別のファイルを読めることを目的としています。

関連文書:

- [SECURITY.md](SECURITY.md) — 資格情報の保管仕様・通信の保護・OWASP対応
- [API_REFERENCE.md](API_REFERENCE.md) — 呼び出しているAPIエンドポイントの一覧
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md) — よくある不具合と調査手順

---

## 1. 拡張が担うこと

Markdownで書いた記事を、仲介APIサーバー経由でWordPress等のCMSへ投稿するための執筆環境です。
**CMSとの通信・AI呼び出し・GitHub連携はすべてAPIサーバー側が行い、拡張はそれらを呼び出す薄いクライアント**です。
拡張自身がWordPressやOpenAI、GitHubへ直接アクセスすることはありません。

記事は次の構造でワークスペースに置かれます。

```
articles/
  <slug>/
    article.md     # front matter + 本文
    assets/        # 画像(本文からは assets/xxx.png の相対パスで参照)
```

---

## 2. レイヤ構成

```
┌─────────────────────────────────────────────────────────────┐
│ extension.ts            コマンド登録とコマンド本体            │
│                         (エディタ操作・QuickPick・通知)      │
└───────────────┬──────────────────────────┬──────────────────┘
                │                          │
      ┌─────────▼─────────┐      ┌─────────▼──────────────────┐
      │ Webviewパネル群     │      │ ドメインロジック(純粋関数)  │
      │ *Panel.ts          │      │ frontMatter / headingContext│
      │  ← webviewPanelBase│      │ issueParser / multipart     │
      └─────────┬──────────┘      │ articleScaffold             │
                │                 └────────────────────────────┘
      ┌─────────▼───────────────────────────────────────────┐
      │ apiClient.ts   全API呼び出しの単一窓口                 │
      │   schemas.ts (Zod検証) / cache.ts (TTL付きLRU)        │
      └─────────┬───────────────────────────────────────────┘
      ┌─────────▼───────────────────────────────────────────┐
      │ httpClient.ts  ネイティブfetch / node:https           │
      │ errorHandler.ts (例外型・リトライ) / logger.ts        │
      └──────────────────────────────────────────────────────┘
```

**依存の向きは上から下の一方向**です。下位モジュール(`httpClient` / `logger` / `frontMatter` など)は
上位のパネルやコマンドを知りません。

---

## 3. モジュールの責任

### 3.1 エントリポイント

| ファイル | 責任 |
| --- | --- |
| `extension.ts` | コマンドの登録と実装。エディタの読み書き、QuickPick/InputBoxによる対話、進捗通知を担う。ビジネスロジックは持たず、下位モジュールへ委譲する。 |

### 3.2 通信層

| ファイル | 責任 |
| --- | --- |
| `apiClient.ts` | **全API呼び出しの単一窓口**。エンドポイントごとの関数を公開する。タイムアウト・リトライ・ログ・レスポンス検証・キャッシュをここで一元化する。 |
| `httpClient.ts` | HTTPトランスポート。既定はNode 18以降のネイティブ`fetch`。自己署名証明書を許容する設定が有効なHTTPS接続に限り`node:https`へ切り替える(ネイティブfetchはリクエスト単位でTLS検証を緩められないため)。 |
| `multipart.ts` | `multipart/form-data`ボディの組み立て。ストリームではなく`Buffer`を返すため、両トランスポートで同じボディを使える。 |
| `schemas.ts` | APIレスポンスのZod検証スキーマ。**レスポンス型はここから`z.infer`で導出**され、スキーマと型定義が乖離しない。 |
| `cache.ts` | TTL付きLRUキャッシュ。参照系レスポンスの再取得を抑える。同一キーへの並行取得は先行のPromiseを共有する。 |

### 3.3 横断的関心事

| ファイル | 責任 |
| --- | --- |
| `errorHandler.ts` | 例外型(`ApiError` / `NetworkError` / `TimeoutError` / `ResponseValidationError` / `CancelledError`)の定義、原因と対応策を含むメッセージへの整形、指数バックオフによるリトライ。 |
| `logger.ts` | 構造化ログ。出力パネル「Let's Blog」へ書き出す。認証情報らしいキーの値はマスクする。 |
| `config.ts` | 設定値と資格情報の読み書き。**SecretStorageに触れるのはこのファイルだけ**。 |

### 3.4 ドメインロジック(vscode APIに依存しない純粋関数)

| ファイル | 責任 |
| --- | --- |
| `frontMatter.ts` | front matterの解析・生成、画像参照の抽出と解決、公開予定日時の検証、記事テンプレートの組み立て。 |
| `headingContext.ts` | カーソル位置から、AI文章生成に使うモード(本文/リード文/サブセクション考慮リード文)を判定する。 |
| `issueParser.ts` | GitHub Issue本文から記事構成(見出し構造)を抽出する。 |
| `articleScaffold.ts` | 記事ディレクトリと`article.md`の生成。Issue起点とコマンド起点の両方から使う。 |

これらは`vscode`モジュールにほとんど依存しないため、**単体テストの主な対象**です。

### 3.5 Webviewパネル

| ファイル | パネル | 起動方法 |
| --- | --- | --- |
| `planPanel.ts` | Article Plan | `Let's Blog: Plan Article` |
| `articleCreationPanel.ts` | Create Article | `Let's Blog: Create Article` |
| `imageGenPanel.ts` | Generate Image | `Let's Blog: Generate Image` |
| `imageGalleryPanel.ts` | Image Gallery | `Let's Blog: Image Gallery` |
| `sectionGenPanel.ts` | Generate Section | `Let's Blog: Generate Section` |
| `previewPanel.ts` | Article Preview | `Let's Blog: Preview Article` |

| 基盤ファイル | 責任 |
| --- | --- |
| `webviewPanelBase.ts` | パネル生成・メッセージ購読とディスパッチ・例外の整形とログ・HTML資材の読み込みとCSP付与・破棄。派生クラスは`handleMessage()`だけを実装する。`showSingletonPanel()`がviewTypeごとの生存管理を担う。 |
| `webviewMessages.ts` | WebviewとExtension間のメッセージを判別可能合併として定義。`switch`の各分岐でペイロード型が検査される。 |
| `webviewSecurity.ts` | CSPとnonceの生成。 |

`previewPanel.ts`だけは基底クラスを使いません。HTML本体をサーバー応答から動的に構築し、
スクリプトを実行しない(`enableScripts: false`)ため、静的資材を配信する仕組みに馴染まないためです。

### 3.6 Webview資材(`webviews/`)

HTML/CSS/JSは埋め込み文字列ではなく個別ファイルとして置き、`asWebviewUri`経由で配信します。

```
webviews/
  <panel>.html     # {{csp}} {{nonce}} {{styleUri}} {{scriptUri}} {{sharedStyles}} {{sharedScripts}} を含むテンプレート
  <panel>.css
  <panel>.js
  loadingIndicator.css / .js   # 全パネル共通のローディング表示
```

`localResourceRoots`は`webviews/`配下に限定しています。

---

## 4. データフロー

### 4.1 記事の投稿(`Let's Blog: Publish`)

```
エディタの内容
  → parseArticle()                front matterと本文へ分離
  → extractLocalImageReferences() 本文中のローカル画像を抽出
  → 存在しない画像を警告して除外
  → validateScheduledPublication() 公開予定日時を検証
  → getProject()                  投稿先環境(ローカル/テスト/本番)の選択肢を取得
  → QuickPickで環境を選択
  → publishPost()                 multipartで本文と画像を送信
  → front matterへ結果を書き戻す   wp_post_ids / status / wp_post_url
```

front matterの`wp_post_ids`は**サイトキーごとに投稿IDを持ちます**。ローカル/テスト/本番は
別々のCMSサイトのため、単一のIDを使い回すと別サイトの投稿を更新しようとして失敗するためです。

### 4.2 記事の企画(Article Plan)

```
未割り当てIssueの一覧
  → Issueを選択
      → getIssueDescription() → extractIssueOutline() → 構成案として表示
  → 壁打ちチャット(postPlanChat)
  → 構成案の生成/編集 → acceptArticleStructure() でIssue本文を更新
  → メタデータ提案(suggestMetadata) → 編集
  → createArticleScaffold() で articles/<slug>/ を生成
  → assignIssue() でIssueを自分に割り当て
```

### 4.3 プレビュー

```
本文
  → inlineLocalImages()   ローカル画像をdata URIへ置換(サーバー側では解決できないため)
  → renderPreviewHtml()   サーバーでMarkdown→HTML変換(カスタムタグ展開を含む)
  → CSSの取得元サイトを選択
  → getThemeCss()         そのサイトのテーマCSSを取得
  → PreviewPanelで表示
```

---

## 5. 設計上の約束事

新しくコードを足すときは、以下に従うと既存の作りと揃います。

1. **API呼び出しは必ず`apiClient.ts`へ関数を追加する。** パネルやコマンドから直接`fetch`しない。
2. **レスポンスには必ずZodスキーマを用意する。** `requestJson()`はスキーマ引数を必須にしてあります。
3. **リトライは冪等な操作だけ。** GETは既定で対象。POSTは副作用のないもの(生成・提案・変換)だけ`retryable: true`にします。投稿・削除・割り当てを再試行すると重複実行になります。
4. **資格情報は`config.ts`経由。** 他のモジュールから`context.secrets`を直接触らない。
5. **Webviewへ動的な値を渡すときは`textContent`かDOM API。** `innerHTML`へ文字列結合しない。
6. **長時間処理は`runCancellable()`で包む。** 利用者が中断できるようにします。
7. **パネルを追加したら`webviews/<name>.{html,css,js}`を作る。** HTMLへ埋め込まない。

---

## 6. テスト

```bash
npm test              # ユニットテスト
npm run test:coverage # カバレッジ付き(CIと同じ)
npm run compile       # 型チェック + ビルド
```

- テストは`src/__tests__/`に置きます。
- `vscode`モジュールは拡張ホスト外で解決できないため、`src/__mocks__/vscode.ts`のスタブへ差し替えています(`jest.config.js`の`moduleNameMapper`)。
- `frontMatter.ts` / `headingContext.ts` / `config.ts` は**カバレッジ100%を閾値として設定**しており、下回るとCIが失敗します。

---

## 7. 設定項目

| 設定 | 既定値 | 用途 |
| --- | --- | --- |
| `letsBlog.serverUrl` | `https://localhost` | 仲介APIサーバーのベースURL |
| `letsBlog.allowInsecureTls` | `false` | 自己署名証明書を許容する(有効時は中間者攻撃を検出できません) |
| `letsBlog.debugMode` | `false` | デバッグログを出力パネルへ出す |
| `letsBlog.requestTimeoutMs` | `120000` | APIリクエストのタイムアウト |
