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
      │   apiBaseUrl.ts (gateway宛URLの組み立て)              │
      │   schemas.ts (Zod検証) / cache.ts (TTL付きLRU)        │
      └─────────┬───────────────────────────────────────────┘
      ┌─────────▼───────────────────────────────────────────┐
      │ httpClient.ts  ネイティブfetch / node:https           │
      │ errorHandler.ts (例外型・リトライ) / logger.ts        │
      │   downstreamServices.ts (パス→担当サービスの逆引き)    │
      └──────────────────────────────────────────────────────┘

config.ts(資格情報・設定値)は deviceAuth.ts 経由でKeycloakのデバイス認可/トークン
エンドポイントを呼び、httpClient.ts を共有する(apiClient.ts とは独立した経路)。

apiClient.ts が呼ぶ `/api/**` は例外なく APIゲートウェイ(services/gateway)経由で、
宛先URLの組み立ては apiBaseUrl.ts の1箇所に集約している(issue #585)。
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
| `apiBaseUrl.ts` | `/api/**` を呼ぶ際のベースURLを組み立てる**唯一の場所**(issue #585)。呼び先は常にAPIゲートウェイ(実体はリバースプロキシの`location /api/`経由)。Keycloak・draw.ioは対象外で`config.ts`の`getServerUrl()`を使う。 |
| `httpClient.ts` | HTTPトランスポート。既定はNode 18以降のネイティブ`fetch`。自己署名証明書を許容する設定が有効なHTTPS接続に限り`node:https`へ切り替える(ネイティブfetchはリクエスト単位でTLS検証を緩められないため)。応答ヘッダの読み取り(`header()`)はgatewayの相関ID取得に使う。 |
| `multipart.ts` | `multipart/form-data`ボディの組み立て。ストリームではなく`Buffer`を返すため、両トランスポートで同じボディを使える。 |
| `schemas.ts` | APIレスポンスのZod検証スキーマ。**レスポンス型はここから`z.infer`で導出**され、スキーマと型定義が乖離しない。 |
| `cache.ts` | TTL付きLRUキャッシュ。参照系レスポンスの再取得を抑える。同一キーへの並行取得は先行のPromiseを共有する。 |

### 3.3 横断的関心事

| ファイル | 責任 |
| --- | --- |
| `errorHandler.ts` | 例外型(`ApiError` / `NetworkError` / `TimeoutError` / `ResponseValidationError` / `CancelledError`)の定義、原因と対応策を含むメッセージへの整形、指数バックオフによるリトライ。5xx・タイムアウトでは`downstreamServices.ts`で担当サービスを逆引きして通知に含める(issue #585)。 |
| `downstreamServices.ts` | リクエストパスから、gatewayが転送する下流サービス(コンテナ名・日本語表示名)を逆引きする純粋関数(issue #585)。gatewayのルート表のうち拡張が呼ぶ部分だけを同じ「先勝ち」順序で写している。他の拡張内モジュールへ依存しない。 |
| `logger.ts` | 構造化ログ。出力パネル「Let's Blog」へ書き出す。認証情報らしいキーの値はマスクする。 |
| `config.ts` | 設定値と資格情報の読み書き。**SecretStorageに触れるのはこのファイルだけ**。アクセストークンの期限管理・自動リフレッシュ(`requireAccessToken`)もここに置く。 |
| `deviceAuth.ts` | Device Authorization Grant(issue #565)のプロトコル部分。デバイス認可/トークンエンドポイントへのリクエストと、応答の解釈(成功/pending/slow_down/denied/expired)。`config.ts`から呼ばれる。 |
| `jwtClaims.ts` | アクセストークン(JWT)のペイロードを署名検証なしでデコードし、表示用のemail/roleを取り出す純粋関数(issue #565)。 |

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

## 6. apiClient の使い方

新しいエンドポイントを呼ぶときの型です。

> **issue #565での変更**: `buildHeaders`は`Authorization: Bearer <apiKey引数>`を送るようになり、
> `actor`引数はヘッダ組み立てには使いません(サーバーがJWTから実行者を判定するため)。
> 以下のコード例にある`apiKey`という変数名/引数名は歴史的な名残で、実体はKeycloak発行の
> アクセストークンです(全呼び出し箇所の一括リネームは#566のスコープとして見送っています)。
> `actor`引数自体は既存の呼び出し元シグネチャを変えない目的で残していますが、値としては未使用です。

### 参照系(キャッシュあり・リトライあり)

> **issue #585での変更**: ベースURLは `apiBaseUrl.ts` の `gatewayUrl()` が組み立てる gateway 宛の
> URLに固定したため、各エンドポイント関数は `serverUrl` 引数を取りません(呼び出し元が別のベースURLを
> 渡す余地自体を無くしています)。

```ts
export async function listSites(
  apiKey: string,
  actor?: Actor
): Promise<SiteSummary[]> {
  return cachedRequestJson(
    'sites',                                  // キャッシュキー(パラメータを含めて一意にする)
    '/api/sites',
    { label: 'listSites', headers: buildHeaders(apiKey, actor) },
    schemas.SiteSummaryListSchema             // レスポンス検証スキーマ(必須)
  );
}
```

### 更新系(リトライしない)

```ts
export async function assignIssue(/* ... */): Promise<AssignIssueResult> {
  const result = await requestJson(
    `/api/projects/${projectId}/article-plan/issues/${issueNumber}/assign`,
    {
      label: 'assignIssue',
      method: 'POST',
      headers: buildHeaders(apiKey, actor, 'application/json'),
    },
    schemas.AssignIssueResultSchema
  );
  invalidateProjectCache(projectId);          // 一覧の内容が変わるためキャッシュを破棄
  return result;
}
```

### 中断できる長時間処理

`signal` を受け取り `RequestSpec` へ渡すと、パネルの「キャンセル」で実際に打ち切れます。

```ts
// apiClient側
export async function generateSection(
  /* ... */,
  signal?: AbortSignal
): Promise<AiSectionResult> {
  return requestJson('/api/ai/section', {
    label: 'generateSection',
    signal,
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody(params),
    retryable: true,                          // 副作用が無いので再試行してよい
  }, schemas.AiGenerationResultSchema);
}

// パネル側
const result = await this.runCancellable((signal) =>
  api.generateSection(apiKey, actor, message.params, signal)
);
```

### 呼び出し側(コマンド)

```ts
try {
  const apiKey = await requireAccessToken(context); // 未ログイン/リフレッシュ失敗なら対応方法付きの例外
  const actor = await getActor(context);
  const sites = await api.listSites(getServerUrl(), apiKey, actor);
  // ...
} catch (err) {
  reportError('サイトの選択に失敗しました', err); // ログ記録 + 対応策付きの通知
}
```

---

## 7. テスト

```bash
npm test              # ユニットテスト
npm run test:coverage # カバレッジ付き(CIと同じ)
npm run compile       # 型チェック + ビルド
```

- テストは`src/__tests__/`に置きます。
- `vscode`モジュールは拡張ホスト外で解決できないため、`src/__mocks__/vscode.ts`のスタブへ差し替えています(`jest.config.js`の`moduleNameMapper`)。
- `frontMatter.ts` / `headingContext.ts` / `config.ts` は**カバレッジ100%を閾値として設定**しており、下回るとCIが失敗します。

---

## 8. 設定項目

| 設定 | 既定値 | 用途 |
| --- | --- | --- |
| `letsBlog.serverUrl` | `https://localhost` | リバースプロキシ(nginx)の公開URL。`/api/**` はここからAPIゲートウェイへ中継される(issue #585) |
| `letsBlog.allowInsecureTls` | `false` | 自己署名証明書を許容する(有効時は中間者攻撃を検出できません) |
| `letsBlog.debugMode` | `false` | デバッグログを出力パネルへ出す |
| `letsBlog.requestTimeoutMs` | `120000` | APIリクエストのタイムアウト |
