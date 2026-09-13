import { NextResponse } from "next/server";
import { getAvatarBytes } from "@/lib/apiClient";

/**
 * プロフィール編集画面のアバター画像配信の中継(issue #1241)。
 *
 * <p>`apps/web/src/app/api/dashboard/service-status/route.ts`と同じ理由でこのRoute Handlerが
 * 必要になる: identity-serviceの`GET /api/users/{id}/avatar`は`requireSelfOrAdmin`
 * (要件8)でAuthorizationヘッダー必須だが、ブラウザの`<img src="...">`はヘッダーを
 * 付けられない。`apiClient.ts`は`server-only`で、next-authの`getToken()`が
 * セッションCookie(HttpOnly)から取り出したアクセストークンをAuthorizationヘッダーに
 * 載せるため、ここがブラウザ経路に認証を付ける唯一の手段になる。
 *
 * <p>`infra/nginx/conf.d/default.conf`の`location ~ ^/api/users/[^/]+/avatar$`が
 * GETだけをこのRoute Handlerへ振り分ける(POSTは直接gatewayへ)。
 */
export async function GET(
  _request: Request,
  { params }: { params: Promise<{ id: string }> }
) {
  const { id } = await params;
  const userId = Number(id);
  if (!Number.isInteger(userId)) {
    return NextResponse.json({ error: "不正なユーザーIDです。" }, { status: 400 });
  }

  try {
    const { body, contentType } = await getAvatarBytes(userId);
    return new NextResponse(body, {
      status: 200,
      headers: {
        "Content-Type": contentType,
        "Cache-Control": "no-store",
      },
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : "アバター画像の取得に失敗しました。";
    const match = message.match(/APIエラー \((\d+)\)/);
    const status = match ? Number(match[1]) : 500;
    return NextResponse.json({ error: message }, { status });
  }
}
