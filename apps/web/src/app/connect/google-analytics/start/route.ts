import { randomUUID } from "node:crypto";
import { NextRequest, NextResponse } from "next/server";
import { getProjectGoogleAnalyticsStatus } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

const STATE_COOKIE = "google_analytics_oauth_state";
const SCOPE = "https://www.googleapis.com/auth/analytics.readonly";

/**
 * Google Analytics連携(issue #1231)の3-legged OAuthフローの起点(AdSenseの/connect/adsense/startと同型)。ブラウザをGoogleの同意画面へ
 * リダイレクトする。nginxの `location /api/` はSpring Boot APIへの直接転送専用のため、
 * ブラウザ発のOAuthリダイレクトはこの/connect配下のRoute Handlerで受ける
 * (downloads/vscode-extensionと同じ理由、詳細はそちらのコメント参照)。
 * stateパラメータにprojectIdとCSRF対策用nonceを埋め込み、nonceはHttpOnly cookieにも保存して
 * コールバック時に一致を確認する。
 * クライアントIDはそのプロジェクトのGoogle Analytics設定で保存した値を使う(GA専用の列で、AdSenseの設定には
 * 依存しない)。要求するスコープはanalytics.readonlyのみ(プロパティ一覧とレポート取得の両方を賄える)。
 */
export async function GET(request: NextRequest) {
  await requireAdminSession();

  const projectId = request.nextUrl.searchParams.get("projectId");
  if (!projectId || Number.isNaN(Number(projectId))) {
    return Response.json({ error: "projectIdが指定されていません。" }, { status: 400 });
  }

  const status = await getProjectGoogleAnalyticsStatus(Number(projectId));
  const clientId = status.clientId;
  if (!clientId || !status.hasClientSecret) {
    return Response.json(
      { error: "このプロジェクトにはGoogle OAuthクライアントID/シークレットが設定されていません。" },
      { status: 500 }
    );
  }

  const nonce = randomUUID();
  const state = `${projectId}.${nonce}`;
  const redirectUri = `${process.env.NEXTAUTH_URL}/connect/google-analytics/callback`;

  const authorizeUrl = new URL("https://accounts.google.com/o/oauth2/v2/auth");
  authorizeUrl.searchParams.set("client_id", clientId);
  authorizeUrl.searchParams.set("redirect_uri", redirectUri);
  authorizeUrl.searchParams.set("response_type", "code");
  authorizeUrl.searchParams.set("scope", SCOPE);
  authorizeUrl.searchParams.set("access_type", "offline");
  authorizeUrl.searchParams.set("prompt", "consent");
  authorizeUrl.searchParams.set("state", state);

  const response = NextResponse.redirect(authorizeUrl);
  response.cookies.set(STATE_COOKIE, state, {
    httpOnly: true,
    secure: true,
    sameSite: "lax",
    maxAge: 600,
    path: "/connect/google-analytics",
  });
  return response;
}
