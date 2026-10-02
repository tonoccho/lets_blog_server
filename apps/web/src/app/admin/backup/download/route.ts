import { downloadBackupFile } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export async function GET() {
  await requireAdminSession();

  try {
    const { body, filename } = await downloadBackupFile();
    return new Response(body, {
      status: 200,
      headers: {
        "Content-Type": "application/octet-stream",
        "Content-Disposition": `attachment; filename="${filename}"`,
        "Cache-Control": "no-store",
      },
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    return Response.json({ error: message }, { status: 502 });
  }
}
