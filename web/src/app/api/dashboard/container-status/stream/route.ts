import { streamContainerStatuses } from "@/lib/apiClient";

/**
 * バックエンドのSSE配信(/api/dashboard/container-status/stream)を、認証ヘッダを付けたうえで
 * ブラウザへそのまま中継するルート(issue #280)。service-status/stream/route.tsと同じ理由。
 */
export async function GET() {
  const upstream = await streamContainerStatuses();

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
