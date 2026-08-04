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
        "Cache-Control": "public, max-age=3600",
      },
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return Response.json({ error: message }, { status: 502 });
  }
}
