import { downloadProjectCustomTagCssBundle } from "@/lib/apiClient";
import { getSession } from "@/lib/session";

export async function GET(_request: Request, { params }: { params: Promise<{ id: string }> }) {
  const session = await getSession();
  if (!session) {
    return Response.json({ error: "ログインが必要です。" }, { status: 401 });
  }

  const { id } = await params;
  const projectId = Number(id);

  try {
    const body = await downloadProjectCustomTagCssBundle(projectId);
    return new Response(body, {
      status: 200,
      headers: {
        "Content-Type": "text/css",
        "Content-Disposition": 'attachment; filename="integrated-css.css"',
      },
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return Response.json({ error: message }, { status: 502 });
  }
}
