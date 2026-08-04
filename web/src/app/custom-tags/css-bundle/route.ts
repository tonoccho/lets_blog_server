import { downloadCustomTagCssBundle } from "@/lib/apiClient";
import { getSession } from "@/lib/session";

export async function GET(request: Request) {
  const session = await getSession();
  if (!session) {
    return Response.json({ error: "ログインが必要です。" }, { status: 401 });
  }

  const { searchParams } = new URL(request.url);
  const projectIdRaw = searchParams.get("projectId");
  const projectId = projectIdRaw ? Number(projectIdRaw) : undefined;

  try {
    const body = await downloadCustomTagCssBundle(projectId);
    return new Response(body, {
      status: 200,
      headers: {
        "Content-Type": "text/css",
        "Content-Disposition": 'attachment; filename="custom-tags.css"',
      },
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return Response.json({ error: message }, { status: 502 });
  }
}
