# セキュリティポリシー(Let's Blog VSCode拡張)

この文書は、VSCode拡張が扱う資格情報の保管方法・通信の保護・Webviewの制約、および
OWASP Top 10 (2021) に対する現状の対応をまとめたものです。

対象は `apps/extension/` 配下のVSCode拡張です。APIサーバー側のセキュリティはサーバー側の設定に従います。

---

## 1. 資格情報の保管仕様

### 1.1 保管先

| 情報 | 保管先 | キー | 生存期間 |
| --- | --- | --- | --- |
| アクセストークン/リフレッシュトークン | `context.secrets`(VSCode Secret Storage) | `letsBlog.tokens` | リフレッシュトークンが**確定的に失効**するまで(Keycloakが `400 invalid_grant` 等を返した時点で破棄)/`Let's Blog: Logout` 実行まで/再ログインまで |
| ログインユーザー(Actor、表示用) | `context.secrets` | `letsBlog.actor` | `Let's Blog: Logout` 実行まで/再ログインまで |
| 選択中のプロジェクトID | `context.workspaceState` | `letsBlog.projectId` | ワークスペース単位で永続 |

トークンとActorは **VSCode の Secret Storage API** に保管します。Secret Storage は
OSの資格情報ストア(macOS: Keychain、Windows: 資格情報マネージャー、Linux: libsecret/gnome-keyring)へ
委譲されるため、平文ファイルとしてディスクへ書き出されません。

プロジェクトIDは秘密情報ではないため `workspaceState` に保管します。
**設定(`settings.json`)には資格情報を保存しません** — 設定ファイルはリポジトリへコミットされうるためです。

### 1.2 実装上の取り決め

- 資格情報の読み書きは `src/config.ts` に集約します。他のモジュールが `context.secrets` を直接触ることはしません。
- issue #565(Device Authorization Grantへの移行)以降、拡張はメールアドレス/パスワードを一切扱いません。
  ログイン(`src/extension.ts` の `commandLogin`)はKeycloakへのデバイス認可リクエスト・ユーザーによる
  ブラウザ上での承認・トークンエンドポイントのポーリングのみで完結し、資格情報は拡張を経由しません。
- 拡張はデバイス認可要求時に `scope=offline_access` を要求します(issue #1098、`deviceAuth.ts` の
  `DEVICE_SCOPE`)。発行されるリフレッシュトークンは **offline token** となり、KeycloakのSSOセッション
  (realm `letsblog` の `ssoSessionIdleTimeout` = 30分 / `ssoSessionMaxLifespan` = 10時間)ではなく
  offline session(`offlineSessionIdleTimeout` = 14日、`offlineSessionMaxLifespanEnabled` = true /
  `offlineSessionMaxLifespan` = 14日。issue #1100)の寿命に従います。使うたびにidle期限が延長されるため、
  通常の執筆作業の中では再ログインを求められませんが、最後のログインから14日で必ず再ログインが
  必要になります(上限到達時のリフレッシュは `invalid_grant` で拒否され、確定的な失効として
  保存済みトークンを破棄し再ログインを案内します。`src/__tests__/deviceAuth.test.ts` で検証)。
  device_code交換とrefresh_token交換には `scope` を送りません
  (RFC 8628 §3.4 / RFC 6749 §6。refresh時の `scope` は元の許諾の絞り込みを意味するため)。
- アクセストークンは有効期限が近い/切れている場合、`config.ts` の `requireAccessToken` が
  リフレッシュトークンを使って自動的に更新します。**保存済みトークンを破棄するのは、リフレッシュトークン
  自体が失効・取り消しされたと判断できる場合(確定的な失効)だけです**(issue #1098)。判定には
  Keycloakが400応答本文で返す `error` 値(`invalid_grant` / `invalid_client` / `unauthorized_client`)を
  使い、HTTPステータスだけでは判断しません。サーバー再起動・瞬断・タイムアウトのような一過性の失敗
  (`NetworkError` / `TimeoutError` / 429 / 5xx。分類は `errorHandler.ts` の `isRetryable()`)では
  トークンを保持したまま「一時的な失敗である」旨の例外を投げ、復旧後は再ログインなしで更新が成功します。
- Actorの保存値は読み出し時に `ActorSchema`(Zod)で検証し、壊れていた場合は破棄して再ログインを促します。
  Device Authorization Grant移行後のActorはKeycloakのJWTクレーム(email/realm_access.roles)から
  復元した表示専用の値で、ローカルDBの数値ユーザーIDは持ちません。
- `letsBlog.logout`(コマンドパレット表示: `Let's Blog: Logout`、issue #1099)で、利用者が管理者を
  介さずに資格情報を切れます。実行すると `src/config.ts` の `logout()` が、保存済みリフレッシュ
  トークンでKeycloakのrevocation_endpoint(`protocol/openid-connect/revoke`、RFC 7009)を呼んで
  offline sessionを終了させたうえで、`letsBlog.tokens` / `letsBlog.actor` をSecretStorageから
  削除します。**端末側の削除はKeycloakへの通信結果によらず必ず行います**(手元の資格情報を
  消せないほうが危険であるため、`apps/web/src/lib/auth.ts` のNextAuth `signOut` と同じ判断)。
  Keycloak側の終了に失敗した場合は、サーバー側のセッションが残りうる旨を利用者に通知します。
  未ログイン状態で実行してもKeycloakへは通信せず、例外にもなりません。
  上記「offline tokenが実質無期限」であることの対処は、本コマンドの追加により
  「利用者自身が任意のタイミングで終了できる」状態になりました(管理者によるユーザー無効化 /
  offline session削除という既存の失効手段に加わる形です)。

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

### 2.2 平文HTTPへのトークン送信

issue #565(Device Authorization Grantへの移行)以降、拡張はパスワードを一切扱わないため
専用の同意ダイアログは廃止しました。デバイス認可・トークンエンドポイントへの通信は
`letsBlog.serverUrl`(既定 `https://`)を経由し、TLS証明書検証(2.1節)の対象になります。

### 2.3 送信するヘッダ

| ヘッダ | 内容 |
| --- | --- |
| `Authorization` | `Bearer <アクセストークン>`。Secret Storage に保管されたKeycloak発行のJWT。サーバー側(各サービスのoauth2 resource server)がJWTを検証して実行者を判定する |

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
| A01 | アクセス制御の不備 | 認可判定はAPIサーバー側が `Authorization: Bearer` で送られたアクセストークン(JWT、Keycloak発行)の検証結果に基づき実施。拡張側は権限判定を行わず、サーバーの判定結果(403等)に従う。 |
| A02 | 暗号化の失敗 | TLS検証を既定で有効化。トークンはSecret Storage(OSの資格情報ストア)へ委譲。拡張はパスワードを扱わず、独自の暗号処理も実装しない。 |
| A03 | インジェクション | Webviewへの動的値は `textContent`/DOM APIで描画。CSPでnonce付きスクリプトのみ許可。multipartのヘッダ値は改行・引用符を除去(`src/multipart.ts`)。ファイル名は接頭辞とタイムスタンプから生成し、サーバー応答由来のパス要素を混入させない。 |
| A04 | 安全でない設計 | 資格情報の取り扱いを `config.ts` に、通信を `apiClient.ts`/`httpClient.ts` に集約し、経路を限定。再試行は冪等な操作のみに限定し、重複投稿を設計上防止。 |
| A05 | セキュリティ設定ミス | 危険側(TLS検証無効)を既定にしない。有効時は警告ログを出力。Webviewは `default-src 'none'` を起点に必要最小限のみ許可。 |
| A06 | 脆弱で古いコンポーネント | 実行時依存は `gray-matter` と `zod` のみ(`node-fetch`/`form-data` を廃止)。依存更新は自動化していない(Dependabot はGitLabでは動かないため削除済み)。手順は[docs/DEPENDENCY_UPDATE_POLICY.md](../../docs/DEPENDENCY_UPDATE_POLICY.md)を参照。 |
| A07 | 識別と認証の失敗 | 認証はKeycloak(Device Authorization Grant)に委譲し、拡張はパスワードを扱わない。2段階認証等の認証強度はKeycloak側の設定に従う。トークンはSecret Storageに保管し設定ファイルへ書かない。 |
| A08 | ソフトウェアとデータの整合性の不備 | APIレスポンスをZodスキーマで検証し(`src/schemas.ts`)、想定外の形式を拡張内部へ持ち込まない。 |
| A09 | ログとモニタリングの失敗 | 構造化ログ(`src/logger.ts`)で失敗を記録。資格情報らしいキーの値はマスク。`letsBlog.debugMode` で詳細ログを取得可能。 |
| A10 | SSRF | 接続先は利用者が設定した `letsBlog.serverUrl` のみ。サーバー応答に含まれるURLを拡張が自動で取得することはしない(記事プレビューのCSS取得はサーバー側の処理)。 |

### 既知の制約

- **メモリ上のトークンを消去できない**: JavaScriptの文字列は不変のため、アクセストークン/
  リフレッシュトークンをメモリからゼロ埋めで消すことはできません(issue #565以降、拡張はパスワードを
  一切扱わないため、この制約の影響範囲はトークンに限定されます)。
- **offline tokenの寿命と失効手段**(issue #1098 / #1099 / #1100): `offline_access` を要求する設計の
  ため、端末のSecret Storageには最長14日有効なリフレッシュトークンが載ります。方針は次のとおりです
  (2026-09-16のユーザー判断)。

  | 設定(`infra/keycloak/realm-export.json`) | 値 | 採否と理由 |
  | --- | --- | --- |
  | `offlineSessionMaxLifespanEnabled` / `offlineSessionMaxLifespan` | `true` / 1209600(14日) | **上限を設ける**。使い続ける限り無期限に延命する状態をやめ、複製されたトークンの有効期間を最長14日に限る。 |
  | `offlineSessionIdleTimeout` | 1209600(14日) | 上限に揃える。上限より長いidle値は効かず、設定の意図を読み違えさせるため。30分の無操作で切れない利用感(#1098)は保たれる。 |
  | `revokeRefreshToken` | `false`(回転しない) | **有効化しない**。複数ウィンドウ/プロセスが同時にリフレッシュすると、先に回転されたトークンで正規の端末が弾かれうる(同時リフレッシュの有無は未調査)。上限14日により、複製トークンが使える期間は既に限られる。 |

  `scripts/test_realm_export.py` がこの値を検査します。既存の `keycloak_postgres` ボリュームがある環境へは
  realm定義の変更が反映されない(`--import-realm` は初回のみ)ため、反映には再構築
  (`scripts/rebuild-acceptance-env.sh`)か管理コンソールでの手動変更が必要です。

  **端末を操作できない場合(紛失・盗難・故障)の失効手段は「管理者への依頼のみ」とします**。
  Account Consoleへの導線やWebの端末一覧画面は設けません。端末を操作できる場合は
  `Let's Blog: Logout`(1.2節、issue #1099)で利用者自身が失効できます。

  管理者の運用手順(Keycloak管理コンソール、realm `letsblog`):
  1. 利用者を特定し、**Users → 対象ユーザー → Sessions** で `letsblog-vscode` の offline session を
     削除する(最優先。これでリフレッシュトークンが失効する)。
  2. 加えて、必要に応じて **Users → 対象ユーザー → Enabled を Off** にする。無効化したユーザーは
     リフレッシュできず、アクセストークンも最大5分(`accessTokenLifespan`)で `CurrentActorService`
     (`user.isEnabled()`)により拒否される。
  3. 再有効化する場合: **無効化の解除後に古いoffline tokenが復活するかは未検証**です
     (稼働中Keycloakの変更を伴うため本Issueでは確認していない)。無効化だけを失効とみなさず、
     必ず手順1でoffline sessionを削除してから再有効化し、利用者には再ログインしてもらってください。
  4. Account Consoleでoffline sessionを一覧・削除できるかも未検証です。管理者は管理コンソールを使います。
- **`allowInsecureTls: true` 時の中間者攻撃**: 利用者が明示的に有効化した場合、
  証明書検証を行わないため中間者攻撃を検出できません。ローカル環境専用の設定です。
- **Webviewの `style-src 'unsafe-inline'`**: 3章に記載の理由により許容しています。

---

## 5. 脆弱性の報告

このリポジトリのGitHub Issueで報告してください。
資格情報や実際のAPIキーを含む情報は Issue へ記載しないでください。
