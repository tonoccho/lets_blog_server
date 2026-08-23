import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import { getToken } from "next-auth/jwt";

// /signupはissue #564でKeycloakのregistrationAllowed=false(自己登録オフ)に伴い削除した。
const PUBLIC_PATHS = ["/login", "/setup"];
const ADMIN_ONLY_PREFIXES = ["/users", "/admin"];

async function needsInitialSetup(): Promise<boolean> {
  try {
    const apiUrl = (process.env.LETS_BLOG_API_URL ?? "https://localhost").replace(/\/+$/, "");
    // /api/auth/setup-status はログイン前でも到達できる公開エンドポイントのためAPIキー不要。
    const res = await fetch(`${apiUrl}/api/auth/setup-status`, {
      cache: "no-store",
    });
    if (!res.ok) {
      return false;
    }
    const data = (await res.json()) as { needsSetup: boolean };
    return data.needsSetup;
  } catch {
    return false;
  }
}

const OPERATION_ID_HEADER = "x-operation-id";

/**
 * リクエストごとに操作IDを発番し、リクエストヘッダーに載せて後段(Server Component/Server Action)へ渡す。
 * apiClient.tsのapiFetch()がこのIDを操作ログの紐付けキーとして使い、
 * 1回のブラウザ操作で発生した複数のバックエンドAPI呼び出しを1つの操作としてまとめる(issue #143)。
 */
function withOperationId(request: NextRequest): Headers {
  const requestHeaders = new Headers(request.headers);
  requestHeaders.set(OPERATION_ID_HEADER, crypto.randomUUID());
  return requestHeaders;
}

export async function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const requestHeaders = withOperationId(request);

  if (PUBLIC_PATHS.some((path) => pathname.startsWith(path))) {
    return NextResponse.next({ request: { headers: requestHeaders } });
  }

  const token = await getToken({ req: request, secret: process.env.NEXTAUTH_SECRET });

  // token.errorは"RefreshAccessTokenError"(アクセストークンのリフレッシュ失敗。auth.tsのjwt
  // コールバック参照)。リフレッシュ済みの生きたアクセストークンが無い状態なので、未ログインと
  // 同様に扱いKeycloakへの再ログインを促す。
  if (!token || token.error) {
    if (await needsInitialSetup()) {
      return NextResponse.redirect(new URL("/setup", request.url));
    }
    return NextResponse.redirect(new URL("/login", request.url));
  }

  if (ADMIN_ONLY_PREFIXES.some((prefix) => pathname.startsWith(prefix)) && token.role !== "admin") {
    return NextResponse.redirect(new URL("/", request.url));
  }

  return NextResponse.next({ request: { headers: requestHeaders } });
}

export const config = {
  matcher: ["/((?!api/auth|_next/static|_next/image|favicon.ico).*)"],
};
