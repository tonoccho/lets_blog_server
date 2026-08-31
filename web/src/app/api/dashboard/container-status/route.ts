import { NextResponse } from "next/server";
import { getContainerStatuses } from "@/lib/apiClient";

/**
 * 到達性についての判定(issue #782)
 *
 * <p>このRoute Handlerは、**コンテナ構成(nginx経由)では到達しない**。
 * `nginx/conf.d/default.conf` の `location /api/` が、NextAuth用の正規表現location
 * (`/api/auth/(session|csrf|...)`)を除く `/api/**` をすべて gateway へ転送するため、
 * ブラウザの `fetch("/api/dashboard/...")` は Next.js サーバーに届かず
 * gateway → platform-service(#695でDashboardControllerを移設)へ到達する。
 *
 * <p>実測(2026-08-31): `curl -sk https://localhost/api/dashboard/service-status` は
 * **ボディ無しの401**を返す。このハンドラは成功時200・失敗時 `{"error":...}` の500しか
 * 返さないため、応答の形からも platform-service 由来と判別できる。
 *
 * <p>ただし**削除はしない**。`npm run dev` でホスト上のNext.jsへ直接アクセスする開発形態では
 * nginxを経由しないため、このハンドラが到達し、意図どおり機能する。
 * `apiClient.ts` は `server-only` でセッションCookieからトークンを取得するため、
 * ブラウザから直接 gateway を叩く経路には認証を付けられない。つまりこのハンドラは
 * ホスト開発時における唯一の認証付与手段である。
 *
 * <p>コンテナ構成でダッシュボードのパネルが実際に動くかどうかは**別の問題**で、
 * issue #876 で扱う(ブラウザは Authorization ヘッダー無しで gateway を叩くため401になる)。
 */

/**
 * ダッシュボードのクライアントコンポーネントが定期ポーリングで叩くためのルート(issue #280)。
 * getConnectedServiceStatuses()と同じ理由でサーバー側からの中継が必要。
 */
export async function GET() {
  try {
    const statuses = await getContainerStatuses();
    return NextResponse.json(statuses);
  } catch (err) {
    const message = err instanceof Error ? err.message : "コンテナの状態取得に失敗しました。";
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
