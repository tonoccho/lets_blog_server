import { downloadProjectCustomTagCssBundle } from "@/lib/apiClient";
import { getSession } from "@/lib/session";

export async function GET(_request: Request, { params }: { params: Promise<{ id: string }> }) {
  const session = await getSession();
  if (!session) {
    return Response.json({ error: "ログインが必要です。" }, { status: 401 });
  }

  const { id } = await params;
  const projectId = Number(id);
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const body = await downloadProjectCustomTagCssBundle(projectId, actor);
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
