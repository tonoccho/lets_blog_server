import { requireAdminSession } from "@/lib/session";
import { listAppSettings } from "@/lib/apiClient";
import { AppSettingsPanel } from "./AppSettingsPanel";

export default async function AdminSystemSettingsPage() {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const settings = await listAppSettings(actor).catch(() => []);

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-xl font-semibold">システム設定</h1>
        <p className="mt-1 text-sm text-neutral-600 dark:text-neutral-400">
          プロジェクトに紐付かない、アプリ全体の業務系設定です。DBに保存した値は環境変数(.env)より
          優先して使用されます。空欄のまま保存すると、その項目は未設定に戻り環境変数の値にフォールバックします。
          保存はまとめて1回のトランザクションとして扱われ、いずれかの項目の値が不正な場合は
          この保存操作での変更が全てロールバックされます。
        </p>
      </div>
      <AppSettingsPanel settings={settings} />
    </div>
  );
}
