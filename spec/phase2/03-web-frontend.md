# 03. Web管理フロントエンド(Next.js)拡張

## 目的

Auth.js(NextAuth)を導入し、ユーザーログイン・セッション管理・権限ベースのページ保護を実装する。
既存の全ページ(`/`, `/sites`, `/posts`, `/ai-jobs`, `/system`)をログイン必須にし、ユーザー管理画面(`/users`)をadmin専用として実装する。

## 前提・決定事項

- **next-auth v4系(`^4.24`)を採用**。当初はv5系(Auth.js)を想定していたが、実装時点でもv5はnpm上で`beta`タグのまま(`latest`は引き続き4.24.15)だったため、安定版のv4を採用した。v4でもApp Routerの`route.ts`ハンドラ + `getServerSession`によるApp Router対応は可能。
- Credentials Providerで `/api/auth/login` を呼び出す。セッション戦略は `jwt`(データベースセッションは持たない)。
- ブラウザ⇔Next.jsサーバー間のセッション情報(user id/email/role)は、next-authが発行するhttpOnly暗号化Cookieに保存。
- ブラウザからAPIサーバーへの呼び出しは引き続きないため、固定APIキーもブラウザに露出しない([phase1/05-web-frontend](../phase1/05-web-frontend.md)の方針を維持)。
- Credentials Providerの実装は「email/password」の2フィールドを想定。OAuthプロバイダ連携(Google/GitHub等)はPhase 2では対象外。
- **重要な前提修正**: このプロジェクトのNext.jsは v16 系であり、`web/AGENTS.md` に明記の通り訓練データと異なる破壊的変更がある。実際に `node_modules/next/dist/docs/` を確認したところ、**v16で`middleware`規約は`proxy`に改称**されており(ファイル名は`proxy.ts`、エクスポート名も`proxy`、実行ランタイムはNode.js固定)、旧来の`middleware.ts`は非推奨。本タスクでは当初案の`middleware.ts`ではなく`src/proxy.ts`として実装した。

## 実装状況(更新: 03-web-frontend 完了時点)

以下の構成で実装済み。当初案(v5想定のコード例)から実装が変わった点は都度注記する。

### 1. `src/lib/auth.ts`(認証設定, 新規)

`NextAuthOptions`(v4)を定義。Credentials Providerの`authorize()`で`src/lib/apiClient.ts`の`login()`(APIサーバーの`/api/auth/login`をX-API-Key付きで呼び出す関数)を利用し、`jwt`/`session`コールバックで`id`/`role`をトークン・セッションに橋渡しする。

### 2. `src/app/api/auth/[...nextauth]/route.ts`(route handler, 新規)

```typescript
import NextAuth from "next-auth";
import { authOptions } from "@/lib/auth";

const handler = NextAuth(authOptions);

export { handler as GET, handler as POST };
```

### 3. `src/lib/session.ts`(セッション取得ヘルパー, 新規)

- `getSession()`: `getServerSession(authOptions)` のラッパー(layout.tsx等での表示用)
- `requireAdminSession()`: 未ログインなら`/login`へ、admin以外なら`/`へ`redirect()`する。`proxy.ts`によるページ保護はオプティミスティックな判定にとどまるため、`/users`のServer Component・Server Actionの両方でこの関数を呼び、admin権限を再検証している。

### 4. `src/proxy.ts`(ページ保護, 新規。当初案の`middleware.ts`から名称変更)

`next-auth/jwt`の`getToken()`でセッションJWTを検証し、未ログインなら`/login`へリダイレクト、`/users`配下はadmin以外を`/`へリダイレクトする。`matcher`で`/api/auth`等を除外。

### 5. `src/app/login/page.tsx`(ログイン画面, 新規)

Client Componentとして実装。`next-auth/react`の`signIn("credentials", { redirect: false, ... })`を呼び出し、成功時は`router.push("/")`、失敗時はエラーメッセージを表示する。

### 6. `src/app/users/page.tsx` + `UserForm.tsx` + `DeleteUserButton.tsx` + `actions.ts`(ユーザー管理画面, 新規)

- `page.tsx`: `requireAdminSession()`でadmin確認後、`listUsers()`で一覧取得・表示。ログイン中の自分自身には削除ボタンを表示しない。
- `actions.ts`: `createUserAction`(バリデーション + `requireAdminSession()` + APIサーバー呼び出し)、`deleteUserAction`(`requireAdminSession()` + 自分自身のid比較で削除拒否)。

### 7. `src/app/layout.tsx` / `src/app/LogoutButton.tsx`(既存レイアウト更新)

`RootLayout`を非同期化し`getSession()`でログインユーザーを取得。ログイン中はメールアドレス表示・ログアウトボタンを表示し、admin roleの場合のみナビに「ユーザー」項目を追加する。

### 8. `src/lib/apiClient.ts`(拡張)

`login()`(401は例外にせずnullを返す)、`listUsers()`、`createUser()`、`updateUserRole()`、`deleteUser()`を追加。

### 9. 環境変数(`.env.local` / `.env.local.example`)

next-auth v4の標準に合わせ、**当初案の`AUTH_SECRET`/`AUTH_URL`ではなく`NEXTAUTH_SECRET`/`NEXTAUTH_URL`を使用**。

```bash
NEXTAUTH_SECRET=<openssl rand -hex 32 で生成>
NEXTAUTH_URL=http://localhost:3000
```

### 実機検証

Dockerで起動したAPIサーバーに対し、`next dev`起動後にcurlでnext-authの資格情報フロー(`/api/auth/csrf`→`/api/auth/callback/credentials`→セッションCookie)を実行し、以下を確認済み:
- 未ログイン時に`/`へアクセスすると`/login`へリダイレクト(307)
- 正しい資格情報でログイン後、`/api/auth/session`にrole付きのセッションが返る
- admin roleでログインした場合、`/`・`/users`ともに200
- user roleでログインした場合、`/users`へのアクセスは`/`へリダイレクト(307)され、ダッシュボードにも「ユーザー」ナビが表示されない

`npx tsc --noEmit` / `npm run build` / `npm run lint` はいずれも成功。

## タスクチェックリスト

- [x] `package.json` に `next-auth`(v4系)を追加
- [x] `src/lib/auth.ts` / `src/app/api/auth/[...nextauth]/route.ts` 実装
- [x] `src/lib/session.ts` 実装(`getSession` / `requireAdminSession`)
- [x] `src/proxy.ts` 実装・更新(ページ保護 + admin 権限チェック)
- [x] `src/app/login/page.tsx` 実装
- [x] `src/app/users/page.tsx` 実装(ユーザー一覧・追加フォーム・削除)
- [x] `src/app/users/actions.ts` 実装(ユーザー管理のServer Action)
- [x] 既存ページレイアウトにユーザー情報表示・ログアウトボタンを追加
- [x] `.env.local` / `.env.local.example` 更新
- [x] 実機検証(ログイン・ユーザー追加・アクセス制御など一通りを確認)

## 未決事項

- セッションのタイムアウト期間(現状はnext-authのデフォルト設定のまま。明示的なTTL調整は将来検討)
- 「パスワード変更」画面の実装要否(Phase 2では対象外の想定。必要なら別途追加)
