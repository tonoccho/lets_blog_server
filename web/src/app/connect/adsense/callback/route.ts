import { NextRequest, NextResponse } from "next/server";
import { completeProjectAdSenseOAuth } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

const STATE_COOKIE = "adsense_oauth_state";

/**
 * Google AdSense連携(issue #387)のOAuthコールバック着地点。stateがstart側で発行したcookieの値と
 * 一致することを確認してからSpring Boot APIへ認可コードを渡す(サーバー間通信、X-API-Key認証)。
 * 詳細は/connect/adsense/start/route.tsのコメント参照。
 */
export async function GET(request: NextRequest) {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const code = request.nextUrl.searchParams.get("code");
  const state = request.nextUrl.searchParams.get("state");
  const oauthError = request.nextUrl.searchParams.get("error");
  const cookieState = request.cookies.get(STATE_COOKIE)?.value;
  const projectId = Number((state ?? "").split(".")[0]);

  function redirectToSettings(status: "connected" | "error", message?: string) {
    const base = process.env.NEXTAUTH_URL;
    const target = projectId
      ? new URL(`/projects/${projectId}/settings/adsense`, base)
      : new URL("/projects", base);
    if (status === "connected") {
      target.searchParams.set("connected", "1");
    } else {
      target.searchParams.set("error", message ?? "unknown_error");
    }
    const response = NextResponse.redirect(target);
    response.cookies.delete(STATE_COOKIE);
    return response;
  }

  if (oauthError) {
    return redirectToSettings("error", oauthError);
  }
  if (!code || !state || !cookieState || state !== cookieState || !projectId || Number.isNaN(projectId)) {
    return redirectToSettings("error", "invalid_state");
  }

  try {
    const redirectUri = `${process.env.NEXTAUTH_URL}/connect/adsense/callback`;
    await completeProjectAdSenseOAuth(projectId, { code, redirectUri }, actor);
  } catch (err) {
    return redirectToSettings("error", err instanceof Error ? err.message : String(err));
  }

  return redirectToSettings("connected");
}
