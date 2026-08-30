import { streamConnectedServiceStatuses } from "@/lib/apiClient";

/**
 * バックエンドのSSE配信(/api/dashboard/service-status/stream)を、認証ヘッダを付けたうえで
 * ブラウザへそのまま中継するルート(issue #198)。ブラウザはセッションCookie(HttpOnly)しか
 * 持たずAuthorizationヘッダーを付与できないため、他のAPI呼び出しと同様にサーバー側で仲介する。
 */
export async function GET() {
  const upstream = await streamConnectedServiceStatuses();

  if (!upstream.ok || !upstream.body) {
    return new Response(null, { status: upstream.status || 502 });
  }

  return new Response(upstream.body, {
    status: 200,
    headers: {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
    },
  });
}
