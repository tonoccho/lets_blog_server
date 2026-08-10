# セキュリティポリシー(Let's Blog VSCode拡張)

この文書は、VSCode拡張が扱う資格情報の保管方法・通信の保護・Webviewの制約、および
OWASP Top 10 (2021) に対する現状の対応をまとめたものです。

対象は `extension/` 配下のVSCode拡張です。APIサーバー側のセキュリティはサーバー側の設定に従います。

---

## 1. 資格情報の保管仕様

### 1.1 保管先

| 情報 | 保管先 | キー | 生存期間 |
| --- | --- | --- | --- |
| APIキー | `context.secrets`(VSCode Secret Storage) | `letsBlog.apiKey` | 明示的な再設定まで |
| ログインユーザー(Actor) | `context.secrets` | `letsBlog.actor` | ログアウト/再ログインまで |
| 選択中のプロジェクトID | `context.workspaceState` | `letsBlog.projectId` | ワークスペース単位で永続 |

APIキーとActorは **VSCode の Secret Storage API** に保管します。Secret Storage は
OSの資格情報ストア(macOS: Keychain、Windows: 資格情報マネージャー、Linux: libsecret/gnome-keyring)へ
委譲されるため、平文ファイルとしてディスクへ書き出されません。

プロジェクトIDは秘密情報ではないため `workspaceState` に保管します。
**設定(`settings.json`)には資格情報を保存しません** — 設定ファイルはリポジトリへコミットされうるためです。

### 1.2 実装上の取り決め

- 資格情報の読み書きは `src/config.ts` に集約します。他のモジュールが `context.secrets` を直接触ることはしません。
- パスワードと2段階認証コードは、送信後に変数の参照を切ります(`src/extension.ts` の `commandLogin`)。
  - JavaScriptの文字列は不変でメモリ上をゼロ埋めできないため、これは「保持し続けない」ための措置であり、
    メモリダンプに対する完全な防御ではありません。この制約は仕様として受け入れています。
- APIキーの入力(`commandSetApiKey`)およびパスワード入力には `showInputBox({ password: true })` を使い、
  画面上で伏字にします。
- Actorの保存値は読み出し時に `ActorSchema`(Zod)で検証し、壊れていた場合は破棄して再ログインを促します。

### 1.3 ログへの出力

`src/logger.ts` は、コンテキストのキー名が `apikey` / `api_key` / `password` / `secret` / `token` /
`authorization` / `credential` のいずれかに一致する場合、値を `***` に置換して出力します。
資格情報そのものをログへ渡さない実装を基本とし、この置換は多層防御として機能します。

---

## 2. 通信の保護

### 2.1 TLS証明書の検証

TLS証明書の検証は **既定で有効**です(`letsBlog.allowInsecureTls` の既定値は `false`)。

自己署名証明書を使うローカル環境へ接続する場合のみ、利用者が明示的に
`letsBlog.allowInsecureTls: true` を設定します。この設定が有効な間は、
リクエストのたびに警告ログを出力します。

> **警告**: `allowInsecureTls: true` にすると中間者攻撃を検出できません。
> 信頼できるネットワーク上のローカル環境でのみ使用してください。

実装は `src/httpClient.ts` にあります。既定ではNode 18以降のネイティブ `fetch` を使用し、
`allowInsecureTls` が有効なHTTPS接続に限り、リクエスト単位で `rejectUnauthorized: false` を
指定できる `node:https` トランスポートへ切り替えます(ネイティブ `fetch` はリクエスト単位で
証明書検証を緩める手段を持たないため)。

### 2.2 平文HTTPへの資格情報送信

`letsBlog.serverUrl` が `https://` で始まらない場合、ログイン時に
モーダルの警告を表示し、利用者の明示的な同意なしにパスワードを送信しません
(`confirmCredentialTransport`)。

### 2.3 送信するヘッダ

| ヘッダ | 内容 |
| --- | --- |
| `X-API-Key` | Secret Storage に保管されたAPIキー |
| `X-Actor-Id` / `X-Actor-Role` | 操作の実行者(サーバー側の権限判定に使用) |

---

## 3. Webview の制約

すべてのWebviewパネルに Content-Security-Policy を設定しています(`src/webviewSecurity.ts`)。

| パネル | `enableScripts` | CSP |
| --- | --- | --- |
| Article Plan / Generate Image / Generate Section | `true` | `default-src 'none'; img-src data: https:; style-src 'unsafe-inline'; script-src 'nonce-<乱数>'` |
| Article Preview | `false` | `default-src 'none'; img-src data: https: http:; style-src 'unsafe-inline'` |

- スクリプトはパネル生成時に発行される **nonce付きの `<script>` に限定**します。
  サーバー応答やAI生成結果にHTMLが混入しても、その中の `<script>` は実行されません。
- `style-src` に `'unsafe-inline'` を許可しているのは、HTML中の `style` 属性を使っているためです
  (nonceでは `style` 属性を許可できません)。スタイル注入の影響はスクリプト実行に比べ限定的なため、
  この範囲で許容しています。
- サーバー/AI由来の文字列は `innerHTML` ではなく `textContent` / DOM API で描画します。
- サーバーが返す画像のMIMEタイプと拡張子は既知の画像種別のみを採用します
  (`data:` URI とローカル保存ファイル名の双方)。

---

## 4. OWASP Top 10 (2021) 対応状況

| # | カテゴリ | 対応状況 |
| --- | --- | --- |
| A01 | アクセス制御の不備 | 認可判定はAPIサーバー側が `X-API-Key` と `X-Actor-*` に基づき実施。拡張側は権限判定を行わず、サーバーの判定結果(403等)に従う。 |
| A02 | 暗号化の失敗 | TLS検証を既定で有効化。資格情報はSecret Storage(OSの資格情報ストア)へ委譲。平文HTTP時は明示的な同意を要求。拡張は独自の暗号処理を実装しない。 |
| A03 | インジェクション | Webviewへの動的値は `textContent`/DOM APIで描画。CSPでnonce付きスクリプトのみ許可。multipartのヘッダ値は改行・引用符を除去(`src/multipart.ts`)。ファイル名は接頭辞とタイムスタンプから生成し、サーバー応答由来のパス要素を混入させない。 |
| A04 | 安全でない設計 | 資格情報の取り扱いを `config.ts` に、通信を `apiClient.ts`/`httpClient.ts` に集約し、経路を限定。再試行は冪等な操作のみに限定し、重複投稿を設計上防止。 |
| A05 | セキュリティ設定ミス | 危険側(TLS検証無効)を既定にしない。有効時は警告ログを出力。Webviewは `default-src 'none'` を起点に必要最小限のみ許可。 |
| A06 | 脆弱で古いコンポーネント | 実行時依存は `gray-matter` と `zod` のみ(`node-fetch`/`form-data` を廃止)。Dependabot が `/extension` を週次で監視。 |
| A07 | 識別と認証の失敗 | 認証はAPIサーバーに委譲。2段階認証(TOTP)に対応。APIキーはSecret Storageに保管し設定ファイルへ書かない。 |
| A08 | ソフトウェアとデータの整合性の不備 | APIレスポンスをZodスキーマで検証し(`src/schemas.ts`)、想定外の形式を拡張内部へ持ち込まない。 |
| A09 | ログとモニタリングの失敗 | 構造化ログ(`src/logger.ts`)で失敗を記録。資格情報らしいキーの値はマスク。`letsBlog.debugMode` で詳細ログを取得可能。 |
| A10 | SSRF | 接続先は利用者が設定した `letsBlog.serverUrl` のみ。サーバー応答に含まれるURLを拡張が自動で取得することはしない(記事プレビューのCSS取得はサーバー側の処理)。 |

### 既知の制約

- **メモリ上の資格情報を消去できない**: JavaScriptの文字列は不変のため、パスワードを
  メモリからゼロ埋めで消すことはできません。参照を早期に切ることで生存期間を短縮しています。
- **`allowInsecureTls: true` 時の中間者攻撃**: 利用者が明示的に有効化した場合、
  証明書検証を行わないため中間者攻撃を検出できません。ローカル環境専用の設定です。
- **Webviewの `style-src 'unsafe-inline'`**: 3章に記載の理由により許容しています。

---

## 5. 脆弱性の報告

このリポジトリのGitHub Issueで報告してください。
資格情報や実際のAPIキーを含む情報は Issue へ記載しないでください。
