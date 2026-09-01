import { streamConnectedServiceStatuses } from "@/lib/apiClient";

/**
 * 到達性についての判定(issue #782 → #876 で変更)
 *
 * <p>**#876 以降、このRoute Handlerはコンテナ構成でも到達する。**
 * `infra/nginx/conf.d/default.conf` に `location /api/dashboard/` を追加し、web へ振り分けている
 * (prefix location は最長一致が優先されるため、`location /api/` より先に効く)。
 *
 * <p>#782 の調査時点では `location /api/` が `/api/**` を一律 gateway へ送っていたため、
 * ここには到達せず、ブラウザは認証なしで platform-service(#695 で DashboardController を移設、
 * #705 以降は認証必須)を叩いて401になっていた。しかもパネル側が `if (!res.ok) return;` で
 * 握り潰すため、画面上は「データが無い」ようにしか見えなかった。
 *
 * <p>このハンドラが必要な理由は変わらない。`apps/web/src/lib/apiClient.ts` は `server-only` で、
 * next-auth の `getToken()` がセッションCookie(HttpOnly)から取り出したアクセストークンを
 * `Authorization` ヘッダーに載せる。ブラウザ側にはこの手段が無く(`fetch` は素で呼んでおり、
 * `EventSource` は仕様上ヘッダーを付けられない)、**ここがブラウザ経路に認証を付ける唯一の手段**である。
 *
 * <p>`npm run dev` でホストのNext.jsへ直接アクセスする開発形態では従来どおり nginx を経由せずに到達する。
 */

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
