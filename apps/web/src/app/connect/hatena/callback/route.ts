import { NextRequest, NextResponse } from "next/server";
import { completeProjectHatenaAuthorization } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * はてなブックマークの OAuth 1.0a コールバック着地点(issue #1582。X の /connect/x/callback と同型)。
 * nginx の `location /api/` は Spring Boot API への直接転送専用のため、ブラウザ発の OAuth リダイレクトは
 * この /connect 配下の Route Handler で受ける。
 *
 * OAuth 1.0a には state が無いので、認可の開始時に callback の URL へ `state=<projectId>.<乱数>` を載せてあり、
 * はてなはそれに `oauth_token`(リクエストトークン)と `oauth_verifier` を足して戻す。state の突き合わせ・
 * consumer secret・リクエストトークンの秘密はバックエンドがメモリに持っているので、ここは state・oauth_token・
 * oauth_verifier を渡すだけ(cookie は使わない)。バックエンドは認可を始めた本人・同じプロジェクト・同じリクエストトークンで
 * なければ拒否する。アクセストークンへの交換・アカウント名の取得と本番サイトへの送信もバックエンドが行い、
 * トークンはこの経路にも現れない。
 *
 * 設定画面は X と同じページなので、戻り先に `sns=hatena` を付けて、どちらの欄に結果を出すかを区別する
 * (接続完了は `connected=hatena`、失敗は `error=...&sns=hatena`)。
 */
export async function GET(request: NextRequest) {
  await requireAdminSession();

  const params = request.nextUrl.searchParams;
  const state = params.get("state");
  const oauthToken = params.get("oauth_token");
  const oauthVerifier = params.get("oauth_verifier");
  const oauthError = params.get("error") ?? params.get("oauth_problem");
  const projectId = Number((state ?? "").split(".")[0]);

  function redirectToSettings(status: "connected" | "error", message?: string) {
    const base = process.env.NEXTAUTH_URL;
    const target = projectId
      ? new URL(`/projects/${projectId}/settings/sns`, base)
      : new URL("/projects", base);
    if (status === "connected") {
      target.searchParams.set("connected", "hatena");
    } else {
      target.searchParams.set("error", message ?? "unknown_error");
      target.searchParams.set("sns", "hatena");
    }
    return NextResponse.redirect(target);
  }

  if (oauthError) {
    return redirectToSettings("error", oauthError);
  }
  if (!state || !oauthToken || !oauthVerifier || !projectId) {
    return redirectToSettings("error", "invalid_state");
  }

  try {
    await completeProjectHatenaAuthorization(projectId, { state, oauthToken, oauthVerifier });
  } catch (err) {
    return redirectToSettings("error", err instanceof Error ? err.message : String(err));
  }

  return redirectToSettings("connected");
}
