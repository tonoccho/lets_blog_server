import { NextRequest, NextResponse } from "next/server";
import { completeProjectThreadsAuthorization } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * Threads の OAuth コールバック着地点(issue #1579。X の /connect/x/callback と同型)。
 * nginx の `location /api/` は Spring Boot API への直接転送専用のため、ブラウザ発の OAuth リダイレクトは
 * この /connect 配下の Route Handler で受ける。
 *
 * state は `<projectId>.<乱数>`。state の突き合わせ・アプリのシークレットはバックエンドがメモリに持っているので、
 * ここは state と認可コードを渡すだけ(cookie は使わない)。バックエンドは認可を始めた本人・同じプロジェクトでなければ
 * 拒否する。認可コードのトークン交換・長期トークン化と本番サイトへの送信もバックエンドが行い、トークンはこの経路にも現れない。
 *
 * 設定画面は X と同じページなので、戻り先に `sns=threads` を付けて、どちらの欄に結果を出すかを区別する
 * (接続完了は `connected=threads`、失敗は `error=...&sns=threads`)。
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
      target.searchParams.set("connected", "threads");
    } else {
      target.searchParams.set("error", message ?? "unknown_error");
      target.searchParams.set("sns", "threads");
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
    await completeProjectThreadsAuthorization(projectId, { state, code });
  } catch (err) {
    return redirectToSettings("error", err instanceof Error ? err.message : String(err));
  }

  return redirectToSettings("connected");
}
