import { getBraveSearchApiKeyStatus } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { SystemSettingsPanel } from "./SystemSettingsPanel";

export default async function AdminSettingsPage() {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const braveSearchStatus = await getBraveSearchApiKeyStatus(actor).catch(() => ({
    configured: false,
    source: "NONE" as const,
  }));

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">システム設定</h1>
      <SystemSettingsPanel braveSearchStatus={braveSearchStatus} />
    </div>
  );
}
