"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";

/**
 * NextAuthの実ハンドラを自分自身のNext.jsサーバーへ内部fetchするためのベースURL。
 * NEXTAUTH_URL(https://localhost)はreverse-proxy経由の外部URLで、webコンテナ自身からは
 * 到達できない。docker-compose.ymlのweb.healthcheckが同じ理由で使っている
 * http://127.0.0.1:3000 と同じ経路にする。
 */
const INTERNAL_BASE_URL = "http://127.0.0.1:3000";

interface ParsedSetCookie {
  name: string;
  value: string;
  path?: string;
  httpOnly?: boolean;
  secure?: boolean;
  sameSite?: "lax" | "strict" | "none";
  maxAge?: number;
}

function parseSetCookie(setCookie: string): ParsedSetCookie {
  const [pair, ...attrs] = setCookie.split(";").map((part) => part.trim());
  const eq = pair.indexOf("=");
  const parsed: ParsedSetCookie = { name: pair.slice(0, eq), value: pair.slice(eq + 1) };

  for (const attr of attrs) {
    const [rawKey, rawVal] = attr.split("=").map((part) => part.trim());
    switch (rawKey.toLowerCase()) {
      case "path":
        parsed.path = rawVal;
        break;
      case "httponly":
        parsed.httpOnly = true;
        break;
      case "secure":
        parsed.secure = true;
        break;
      case "samesite":
        parsed.sameSite = rawVal?.toLowerCase() as ParsedSetCookie["sameSite"];
        break;
      case "max-age":
        parsed.maxAge = Number(rawVal);
        break;
    }
  }

  return parsed;
}

function getSetCookies(res: Response): string[] {
  // 複数のSet-Cookieは通常のHeaders#get/entriesではカンマ結合されてしまうため、
  // undici/WHATWG fetchが特別扱いするgetSetCookie()で個別に取り出す。
  return (res.headers as Headers & { getSetCookie?: () => string[] }).getSetCookie?.() ?? [];
}

/**
 * JavaScript無効時に/loginのフォームから呼ばれるServer Action(issue #1052)。
 *
 * **意図的に未認証**。これはログインを開始するためのアクションであり、呼び出す時点で
 * 利用者はまだセッションを持っていない(setup/actions.tsのsetupActionと同じ理由)。
 * 保護はサーバー側が担う。実POSTハンドラである`/api/auth/signin/keycloak`自体は
 * NextAuthの標準エンドポイントで、legacy-apiを経由しないためこのアクションの認可以前に
 * 未認証で到達可能であることが前提になっている。
 *
 * ブラウザにNextAuthの実POSTハンドラ(apps/web/src/app/api/auth/[...nextauth]/route.ts)を
 * 直接叩かせる代わりに、このアクションが自分自身のNext.jsサーバーへ
 *   1. GET /api/auth/csrf でCSRF Cookie+トークンを取得
 *   2. そのCookieを添えて POST /api/auth/signin/keycloak を実ハンドラへ直接送信
 * の順で内部fetchを行い、(2)の応答が持つPKCE用Cookie(pkce.code_verifier / state)を
 * ブラウザ向けの実際のレスポンスへ転記してから、Keycloakへredirect()する
 * (CSRF Cookie自体はサーバー内往復にしか使わないため転記しない)。
 */
export async function startNoJsLoginAction(): Promise<void> {
  const csrfRes = await fetch(`${INTERNAL_BASE_URL}/api/auth/csrf`, { cache: "no-store" });
  const { csrfToken } = (await csrfRes.json()) as { csrfToken: string };
  const csrfCookie = getSetCookies(csrfRes)
    .map(parseSetCookie)
    .find((cookie) => cookie.name.endsWith("csrf-token"));

  const signinRes = await fetch(`${INTERNAL_BASE_URL}/api/auth/signin/keycloak`, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      ...(csrfCookie ? { Cookie: `${csrfCookie.name}=${csrfCookie.value}` } : {}),
    },
    body: new URLSearchParams({ csrfToken }),
    redirect: "manual",
    cache: "no-store",
  });

  const location = signinRes.headers.get("location");
  if (!location) {
    redirect("/login?error=nojs");
  }

  const cookieStore = await cookies();
  for (const raw of getSetCookies(signinRes)) {
    const parsed = parseSetCookie(raw);
    if (parsed.name.endsWith("csrf-token")) {
      continue;
    }
    // Set-Cookieの値は既にpercent-encode済みだが、cookies().set()自身がencodeURIComponentで
    // もう一段encodeするため、渡す前に一度decodeしておく必要がある(QA #1052で発見: これを
    // 怠ると__Secure-next-auth.callback-url等が二重encodeされ、NextAuthが自分のCookieを
    // 読めなくなって以後の全ページが500になる)。
    cookieStore.set(parsed.name, decodeURIComponent(parsed.value), {
      path: parsed.path,
      httpOnly: parsed.httpOnly,
      secure: parsed.secure,
      sameSite: parsed.sameSite,
      maxAge: parsed.maxAge,
    });
  }

  redirect(location);
}
