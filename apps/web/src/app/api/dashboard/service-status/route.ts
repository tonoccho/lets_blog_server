import { NextResponse } from "next/server";
import { getConnectedServiceStatuses } from "@/lib/apiClient";

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
 * ダッシュボードのクライアントコンポーネントが定期ポーリングで叩くためのルート。
 * apiClient.tsのgetConnectedServiceStatuses()はセッションCookie(HttpOnly)を前提に
 * サーバー側でしか呼べないため、ブラウザからの直接呼び出しをここで中継する。
 */
export async function GET() {
  try {
    const statuses = await getConnectedServiceStatuses();
    return NextResponse.json(statuses);
  } catch (err) {
    const message = err instanceof Error ? err.message : "接続サービスの状態取得に失敗しました。";
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
