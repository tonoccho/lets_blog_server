import { NextRequest, NextResponse } from "next/server";
import { completeProjectXAuthorization } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * X の OAuth コールバック着地点(issue #1574。GA の /connect/google-analytics/callback と同型)。
 * nginx の `location /api/` は Spring Boot API への直接転送専用のため、ブラウザ発の OAuth リダイレクトは
 * この /connect 配下の Route Handler で受ける。
 *
 * state は `<projectId>.<乱数>`。state の突き合わせ・PKCE の検証子・クライアントの秘密はバックエンドが
 * メモリに持っているので、ここは state と認可コードを渡すだけ(cookie は使わない)。バックエンドは認可を
 * 始めた本人・同じプロジェクトでなければ拒否する。認可コードのトークン交換と本番サイトへの送信もバックエンドが行い、
 * トークンはこの経路にも現れない。
 */
export async function GET(request: NextRequest) {
  await requireAdminSession();

  const code = request.nextUrl.searchParams.get("code");
  const state = request.nextUrl.searchParams.get("state");
  const oauthError = request.nextUrl.searchParams.get("error");
  const projectId = Number((state ?? "").split(".")[0]);

  function redirectToSettings(status: "connected" | "error", message?: string) {
    const base = process.env.NEXTAUTH_URL;
    const target = projectId
      ? new URL(`/projects/${projectId}/settings/sns`, base)
      : new URL("/projects", base);
    if (status === "connected") {
      target.searchParams.set("connected", "1");
    } else {
      target.searchParams.set("error", message ?? "unknown_error");
    }
    return NextResponse.redirect(target);
  }

  if (oauthError) {
    return redirectToSettings("error", oauthError);
  }
  if (!code || !state || !projectId) {
    return redirectToSettings("error", "invalid_state");
  }

  try {
    await completeProjectXAuthorization(projectId, { state, code });
  } catch (err) {
    return redirectToSettings("error", err instanceof Error ? err.message : String(err));
  }

  return redirectToSettings("connected");
}
