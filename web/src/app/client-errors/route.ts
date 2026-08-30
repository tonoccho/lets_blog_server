import { logFrontendError, type FrontendErrorLogInput } from "@/lib/apiClient";
import { getSession } from "@/lib/session";

/**
 * ブラウザで発生したエラーをlog-writerへ中継するBFF(issue #791)。
 *
 * なぜ /api/ の下に置かないか:
 *   nginx の `location /api/` は NextAuth 用の正規表現locationを除き `/api/**` を
 *   無条件に gateway へ転送する(nginx/conf.d/default.conf:68)。したがって
 *   `/api/**` に置いた Route Handler はコンテナ構成では到達しない。到達可能な
 *   既存の Route Handler(admin/backup/download、connect/adsense/*、
 *   projects/[id]/custom-tags/* など)がいずれも `/api` の外にあるのはこのため。
 *
 * なぜBFFを挟むか:
 *   以前は errorLogger.ts がブラウザから直接 `/api/logs/errors` を叩いており、
 *   Authorizationヘッダーが無かった。#772 で log-writer に ADR-0008 の認証ゲートが
 *   戻った結果このPOSTは401になり、error boundary の try/catch に握りつぶされて
 *   エラーログが無言で全滅していた。log-writer の PUBLIC_PATHS を広げると未認証で
 *   任意の内容を書き込めるエンドポイントになるため、認証を付けられる経路へ移す。
 */
export async function POST(request: Request) {
  let payload: FrontendErrorLogInput;
  try {
    payload = (await request.json()) as FrontendErrorLogInput;
  } catch {
    return Response.json({ error: "リクエストボディがJSONではありません" }, { status: 400 });
  }

  if (typeof payload?.message !== "string" || payload.message.length === 0) {
    // log-writer側の FrontendErrorLog.message は NOT NULL だが、コントローラは検証せず
    // RabbitMQへpublishして即201を返すため、ここで弾かないとコンシューマ側で無言に失敗する。
    return Response.json({ error: "messageは必須です" }, { status: 400 });
  }

  if (payload.level !== "error" && payload.level !== "warn") {
    return Response.json({ error: "levelはerrorまたはwarnである必要があります" }, { status: 400 });
  }

  // 未認証(ログイン画面など)で発生したエラーは記録せずに捨てる。log-writerの
  // POST /api/logs/errors を未認証で通すと、認証不要で無制限に書き込める経路が
  // できてスパム・容量枯渇の的になるため(ADR-0008)。ブラウザ側は
  // errorLogger.logErrorToConsole() が常に走るのでコンソールには残る。
  //
  // このパスは proxy.ts の matcher から除外してあるため、ここが実際の認証判定点になる
  // (除外していないと proxy.ts が先に /login へリダイレクトしてこの分岐に到達しない)。
  // session.error は "RefreshAccessTokenError"(アクセストークンのリフレッシュ失敗)。
  // 生きたアクセストークンが無く log-writer で401になるだけなので、未認証と同じ扱いにする。
  const session = await getSession();
  if (!session || session.error) {
    return new Response(null, { status: 204 });
  }

  try {
    await logFrontendError(payload);
    return new Response(null, { status: 204 });
  } catch (err) {
    // ここで失敗してもブラウザ側の表示は壊さない。原因を追えるようサーバーログには残す。
    console.error("クライアントエラーのlog-writerへの記録に失敗しました", err);
    return Response.json({ error: "記録に失敗しました" }, { status: 502 });
  }
}
