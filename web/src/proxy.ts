import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import { getToken } from "next-auth/jwt";

const PUBLIC_PATHS = ["/login", "/signup", "/setup"];
const ADMIN_ONLY_PREFIXES = ["/users", "/audit-logs", "/admin"];

async function needsInitialSetup(): Promise<boolean> {
  try {
    const apiUrl = (process.env.LETS_BLOG_API_URL ?? "http://localhost:8080").replace(/\/+$/, "");
    const apiKey = process.env.LETS_BLOG_API_KEY;
    if (!apiKey) {
      return false;
    }
    const res = await fetch(`${apiUrl}/api/auth/setup-status`, {
      headers: { "X-API-Key": apiKey },
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

export async function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  if (PUBLIC_PATHS.some((path) => pathname.startsWith(path))) {
    return NextResponse.next();
  }

  const token = await getToken({ req: request, secret: process.env.NEXTAUTH_SECRET });

  if (!token) {
    if (await needsInitialSetup()) {
      return NextResponse.redirect(new URL("/setup", request.url));
    }
    return NextResponse.redirect(new URL("/login", request.url));
  }

  if (ADMIN_ONLY_PREFIXES.some((prefix) => pathname.startsWith(prefix)) && token.role !== "admin") {
    return NextResponse.redirect(new URL("/", request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!api/auth|_next/static|_next/image|favicon.ico).*)"],
};
