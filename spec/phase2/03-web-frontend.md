# 03. Web管理フロントエンド(Next.js)拡張

## 目的

Auth.js(NextAuth)を導入し、ユーザーログイン・セッション管理・権限ベースのページ保護を実装する。
既存の全ページ(`/`, `/sites`, `/posts`, `/ai-jobs`, `/system`)をログイン必須にし、ユーザー管理画面(`/users`)をadmin専用として実装する。

## 前提・決定事項

- Auth.js v5系を採用、Credentials Providerで `/api/auth/login` を呼び出す。
- ブラウザ⇔Next.jsサーバー間のセッション情報(user id/email/role)は、Auth.jsが発行するhttpOnly暗号化Cookieに保存。
- ブラウザからAPIサーバーへの呼び出しは引き続きないため、固定APIキーもブラウザに露出しない([phase1/05-web-frontend](../phase1/05-web-frontend.md)の方針を維持)。
- Credentials Providerの実装は「email/password」の2フィールドを想定。OAuthプロバイダ連携(Google/GitHub等)はPhase 2では対象外。

## 実装コンポーネント

### 1. `app/api/auth/[...nextauth]/route.ts` (新規)

Auth.jsのAPIハンドラ。以下を実装:
- Credentials Provider: `authorize()` コールバック内で、Server Action経由でAPIサーバーの `/api/auth/login` を呼び出し、email/passwordを検証。
- session callback: ログインユーザー情報(id/email/role)をセッションにマップ。
- 環境変数 `AUTH_SECRET`(署名・暗号化鍵)を指定。`.env.local` に追記し、 `openssl rand -hex 32` で生成。

```typescript
// 実装例(参考)
import NextAuth from "next-auth";
import CredentialsProvider from "next-auth/providers/credentials";
import { login } from "@/app/auth/actions"; // Server Action

export const { handlers, auth, signIn, signOut } = NextAuth({
  providers: [
    CredentialsProvider({
      async authorize(credentials) {
        if (!credentials?.email || !credentials?.password) return null;
        const user = await login(
          credentials.email as string,
          credentials.password as string
        );
        return user ?? null;
      },
    }),
  ],
  callbacks: {
    async session({ session, token }) {
      if (session.user) {
        session.user.id = token.id as string;
        session.user.role = token.role as "admin" | "user";
      }
      return session;
    },
    async jwt({ token, user }) {
      if (user) {
        token.id = user.id;
        token.role = user.role;
      }
      return token;
    },
  },
});
```

### 2. `app/auth/actions.ts` (新規)

Server Action。APIサーバー側の `/api/auth/login` を呼び出す:

```typescript
// 実装例(参考)
"use server";

import { LETS_BLOG_API_KEY } from "@/lib/config";

export async function login(email: string, password: string) {
  const response = await fetch(
    `${process.env.NEXT_PUBLIC_API_URL}/api/auth/login`,
    {
      method: "POST",
      headers: {
        "X-API-Key": LETS_BLOG_API_KEY,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ email, password }),
    }
  );

  if (!response.ok) return null;

  const user = await response.json();
  return {
    id: String(user.id),
    email: user.email,
    role: user.role,
  };
}
```

### 3. `middleware.ts` (新規・または更新)

全リクエストをインターセプトし、ログイン必須ページへのアクセスを保護:

```typescript
// 実装例(参考)
import { auth } from "@/app/api/auth/[...nextauth]/route";

const protectedRoutes = ["/", "/sites", "/posts", "/ai-jobs", "/system", "/users"];

export default auth((req) => {
  const isProtected = protectedRoutes.some((route) =>
    req.nextUrl.pathname.startsWith(route)
  );

  if (isProtected && !req.auth) {
    return Response.redirect(new URL("/login", req.url));
  }

  // admin ページの保護
  if (req.nextUrl.pathname.startsWith("/users") && req.auth?.user?.role !== "admin") {
    return Response.redirect(new URL("/", req.url));
  }
});

export const config = {
  matcher: ["/((?!api/auth|public|_next).*)"],
};
```

### 4. `app/login/page.tsx` (新規)

ログインフォーム画面。Credentials Provider へのログインを実行:

- email / password の2フィールドと「ログイン」ボタン
- React `useActionState` で `signIn("credentials", ...)` を呼び出し
- ログイン失敗時はエラーメッセージを表示
- ログイン成功時は既存ページにリダイレクト

```typescript
// 実装例(参考)
"use client";

import { signIn } from "next-auth/react";
import { useActionState } from "react";
import { useRouter } from "next/navigation";

export default function LoginPage() {
  const router = useRouter();

  async function handleLogin(formData: FormData) {
    const result = await signIn("credentials", {
      email: formData.get("email") as string,
      password: formData.get("password") as string,
      redirect: false,
    });

    if (result?.ok) {
      router.push("/");
    }
    return result?.error ? "Invalid credentials" : null;
  }

  const [error, formAction, isPending] = useActionState(
    handleLogin,
    null
  );

  return (
    <form action={formAction} className="...">
      <input type="email" name="email" placeholder="Email" required />
      <input type="password" name="password" placeholder="Password" required />
      <button type="submit" disabled={isPending}>
        {isPending ? "Logging in..." : "Login"}
      </button>
      {error && <div className="text-red-600">{error}</div>}
    </form>
  );
}
```

### 5. `app/users/page.tsx` (新規)

ユーザー管理画面。以下を実装:
- admin roleのみアクセス可(`middleware.ts` で保護)
- ユーザー一覧表(email / role / 作成日時、削除ボタン)
- 新規ユーザー追加フォーム(email / 初期パスワード / role)
- 削除確認ダイアログ

### 6. `app/users/actions.ts` (新規)

ユーザー管理のServer Action:

```typescript
"use server";

import { LETS_BLOG_API_KEY } from "@/lib/config";

export async function listUsers() {
  // GET /api/users
}

export async function createUser(email: string, password: string, role: "admin" | "user") {
  // POST /api/users
}

export async function deleteUser(id: string) {
  // DELETE /api/users/{id}
}

export async function updateUserRole(id: string, role: "admin" | "user") {
  // PATCH /api/users/{id} with { role }
}
```

### 7. 既存ページの更新

全ページのレイアウトにユーザー情報表示・ログアウトボタンを追加:

```typescript
// 実装例(参考, app/layout.tsx 等で)
import { auth, signOut } from "@/app/api/auth/[...nextauth]/route";

export default async function Layout() {
  const session = await auth();

  return (
    <header>
      {session?.user && (
        <>
          <span>{session.user.email}</span>
          <form action={async () => { "use server"; await signOut(); }}>
            <button type="submit">Logout</button>
          </form>
        </>
      )}
    </header>
  );
}
```

### 8. `.env.local` / `.env.example` の更新

以下を追記:

```bash
# Auth.js
AUTH_SECRET=<openssl rand -hex 32 で生成>
AUTH_URL=http://localhost:3000
```

`.env.example` にもプレースホルダを記載。

## タスクチェックリスト

- [ ] `package.json` に `next-auth@latest` を追加
- [ ] `app/api/auth/[...nextauth]/route.ts` 実装
- [ ] `app/auth/actions.ts` 実装(login Server Action)
- [ ] `middleware.ts` 実装・更新(ページ保護 + admin 権限チェック)
- [ ] `app/login/page.tsx` 実装
- [ ] `app/users/page.tsx` 実装(ユーザー一覧・追加フォーム)
- [ ] `app/users/actions.ts` 実装(ユーザー管理のServer Action)
- [ ] 既存ページレイアウトにユーザー情報表示・ログアウトボタンを追加
- [ ] `.env.local` / `.env.example` 更新
- [ ] 実機検証(ログイン・ユーザー追加・アクセス制御など一通りをブラウザで確認)

## 未決事項

- セッションのタイムアウト期間(デフォルトで次回訪問時に再検証される設定にするか、明示的にTTLを設定するか)
- 「パスワード変更」画面の実装要否(Phase 2では対象外の想定。必要なら別途追加)
- ユーザー削除時に確認ダイアログを表示するか、また削除対象が自分自身の場合の処理(Next.js側で禁止する想定)
