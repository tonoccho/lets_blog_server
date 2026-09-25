import { downloadGeneratedImageFile } from "@/lib/apiClient";
import { getSession } from "@/lib/session";

export async function GET(request: Request, { params }: { params: Promise<{ id: string }> }) {
  const session = await getSession();
  if (!session) {
    return Response.json({ error: "ログインが必要です。" }, { status: 401 });
  }

  const { id } = await params;

  try {
    const { body, mimeType } = await downloadGeneratedImageFile(Number(id));
    return new Response(body, {
      status: 200,
      headers: {
        "Content-Type": mimeType,
        "Content-Disposition": "inline",
        // issue #1064: このハンドラは直前でセッションを確認しており、この画像はログイン必須の
        // リソースである。にもかかわらず"public, max-age=3600"を返していたため、ログアウト後や
        // 別ユーザーへの切り替え後もブラウザが自分のHTTPキャッシュから配信してしまい、
        // 上のセッション確認そのものを素通りできた(共有/プロキシキャッシュへ保存されるリスクも
        // 同様)。"private"はブラウザ単体でのキャッシュを許すが、キャッシュされたままの間は
        // ログアウト後の再アクセスでもサーバーへ到達せず素通りする点は"public"と変わらず、
        // かつ検証に使えるETag/Last-Modified等のバリデータをこのレスポンスは持たない
        // (media-service側も付与していない)ため、"max-age=0"で毎回検証させる代替も実質的には
        // 常に全体を再取得するだけで"no-store"と変わらない。したがって単純に"no-store"とし、
        // ブラウザ・共有キャッシュのいずれにも一切保存させず、アクセスのたびに必ずこのハンドラの
        // セッション確認を通す。ギャラリーのサムネイル表示で同じ画像を繰り返し見る場合の
        // 再取得コストは、生成画像1枚あたりのサイズが小さいこと・1エンドポイントに閉じた変更で
        // あることから許容する。
        "Cache-Control": "no-store",
      },
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return Response.json({ error: message }, { status: 502 });
  }
}
