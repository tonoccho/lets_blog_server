import { NextRequest, NextResponse } from "next/server";
import { completeProjectFacebookAuthorization } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * Facebook の OAuth コールバック着地点(issue #1580。X の /connect/x/callback と同型)。
 * nginx の `location /api/` は Spring Boot API への直接転送専用のため、ブラウザ発の OAuth リダイレクトは
 * この /connect 配下の Route Handler で受ける。
 *
 * state は `<projectId>.<乱数>`。state の突き合わせ・アプリのシークレットはバックエンドがメモリに持っているので、
 * ここは state と認可コードを渡すだけ(cookie は使わない)。コードの交換・長期トークン化・管理しているページの取得も
 * バックエンドが行い、トークンはこの経路にも現れない。
 *
 * 個人アカウントには投稿しないので、認可のあとは投稿先のページを選ぶ。成功したら設定画面へ `facebookState=<state>` を付けて戻し、
 * 設定画面がバックエンドから選べるページを取って選択欄を出す(接続の完了は、ページを選んだあとの `connected=facebook`)。
 * 失敗は `error=...&sns=facebook`(X・Threads と同じ設定画面なので、どの欄に出すかを区別する)。
 */
export async function GET(request: NextRequest) {
  await requireAdminSession();

  const code = request.nextUrl.searchParams.get("code");
  const state = request.nextUrl.searchParams.get("state");
  const oauthError = request.nextUrl.searchParams.get("error");
  const projectId = Number((state ?? "").split(".")[0]);

  function redirectToSettings(result: { selectState: string } | { error: string }) {
    const base = process.env.NEXTAUTH_URL;
    const target = projectId
      ? new URL(`/projects/${projectId}/settings/sns`, base)
      : new URL("/projects", base);
    if ("selectState" in result) {
      target.searchParams.set("facebookState", result.selectState);
    } else {
      target.searchParams.set("error", result.error);
      target.searchParams.set("sns", "facebook");
    }
    return NextResponse.redirect(target);
  }

  if (oauthError) {
    return redirectToSettings({ error: oauthError });
  }
  if (!code || !state || !projectId) {
    return redirectToSettings({ error: "invalid_state" });
  }

  try {
    await completeProjectFacebookAuthorization(projectId, { state, code });
  } catch (err) {
    return redirectToSettings({ error: err instanceof Error ? err.message : String(err) });
  }

  return redirectToSettings({ selectState: state });
}
