import { downloadMcpServer } from "@/lib/apiClient";
import { getSession } from "@/lib/session";

/**
 * MCPサーバーのソースZipのダウンロード(issue #1491)。nginxの `location /api/` はSpring Boot APIサーバーへの
 * 直接転送専用のため、Web BFF側の中継ルートは /api/ 配下に置けない(vscode-extension/route.ts と同じ理由)。
 */
export async function GET() {
  const session = await getSession();
  if (!session) {
    return Response.json({ error: "ログインが必要です。" }, { status: 401 });
  }

  try {
    const { body, filename } = await downloadMcpServer();
    return new Response(body, {
      status: 200,
      headers: {
        "Content-Type": "application/zip",
        "Content-Disposition": `attachment; filename="${filename}"`,
        "Cache-Control": "no-store",
      },
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return Response.json({ error: message }, { status: 502 });
  }
}
