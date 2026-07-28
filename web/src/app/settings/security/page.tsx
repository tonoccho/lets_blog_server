import { getTwoFactorStatus } from "@/lib/apiClient";
import { requireSession } from "@/lib/session";
import { TwoFactorSettings } from "./TwoFactorSettings";

export default async function SecuritySettingsPage() {
  const session = await requireSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const status = await getTwoFactorStatus(actor).catch(() => ({ enabled: false }));

  return (
    <div className="mx-auto max-w-lg">
      <h1 className="mb-6 text-xl font-semibold">セキュリティ設定</h1>
      <TwoFactorSettings initialEnabled={status.enabled} />
    </div>
  );
}
