import { downloadVscodeExtension } from "@/lib/apiClient";
import { requireSession } from "@/lib/session";

export async function GET() {
  await requireSession();

  const { body, filename } = await downloadVscodeExtension();

  return new Response(body, {
    status: 200,
    headers: {
      "Content-Type": "application/octet-stream",
      "Content-Disposition": `attachment; filename="${filename}"`,
    },
  });
}
