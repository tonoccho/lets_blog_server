import { requireAdminSession } from "@/lib/session";
import { getComputeDeviceStatus, listAppSettings } from "@/lib/apiClient";
import { loadOrReport, failedLabels } from "@/lib/loadOrReport";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";
import { AppSettingsPanel } from "./AppSettingsPanel";
import { ComputeDevicePanel } from "./ComputeDevicePanel";

export default async function AdminSystemSettingsPage() {
  await requireAdminSession();
  const [settings, computeDevice] = await Promise.all([
    loadOrReport("admin/system-settings", "システム設定", listAppSettings(), []),
    loadOrReport("admin/system-settings", "演算デバイスの状態", getComputeDeviceStatus("comfyui"), null),
  ]);

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
      <FetchErrorNotice labels={failedLabels(settings, computeDevice)} />
      {/* 取得失敗時に空の設定を表示すると、そのまま保存して既存値を消しかねないため描画しない */}
      {!settings.failed && <AppSettingsPanel settings={settings.data} />}
      {/* 「保存する設定」とは別の「適用する操作」。取得できなければ操作させない */}
      {computeDevice.data && <ComputeDevicePanel status={computeDevice.data} />}
    </div>
  );
}
