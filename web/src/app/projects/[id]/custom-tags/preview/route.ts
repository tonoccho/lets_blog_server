import { previewProjectCustomTag } from "@/lib/apiClient";
import { getSession } from "@/lib/session";

export async function POST(request: Request, { params }: { params: Promise<{ id: string }> }) {
  const session = await getSession();
  if (!session) {
    return Response.json({ error: "ログインが必要です。" }, { status: 401 });
  }

  const { id } = await params;
  const projectId = Number(id);
  const actor = { id: Number(session.user.id), role: session.user.role };
  const body = await request.json().catch(() => null);
  if (!body || typeof body.htmlTemplate !== "string" || typeof body.testContent !== "string") {
    return Response.json({ error: "リクエストの形式が不正です。" }, { status: 400 });
  }

  try {
    const result = await previewProjectCustomTag(
      projectId,
      { htmlTemplate: body.htmlTemplate, cssContent: body.cssContent, testContent: body.testContent },
      actor
    );
    return Response.json(result);
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return Response.json({ error: message }, { status: 502 });
  }
}
