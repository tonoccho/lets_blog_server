import { requireAdminSession, getViewerTimeZone } from "@/lib/session";
import { listSshKeyPairs } from "@/lib/apiClient";
import { loadOrReport, failedLabels } from "@/lib/loadOrReport";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";
import { SshKeyPairsPanel } from "./SshKeyPairsPanel";

export default async function AdminSshKeysPage() {
  await requireAdminSession();
  const [keyPairsResult, personalTimeZone] = await Promise.all([
    loadOrReport("admin/ssh-keys", "SSH鍵一覧", listSshKeyPairs(), []),
    getViewerTimeZone(),
  ]);

  return (
    <div className="space-y-8">
      <div>
        <h1 className="text-xl font-semibold">SSH鍵管理</h1>
        <p className="mt-1 text-sm text-neutral-600 dark:text-neutral-400">
          デプロイ等で使うSSH鍵ペア(Ed25519)を名前をつけて生成・保存します。秘密鍵は生成直後の画面でのみ
          表示され、保存後は再表示できません。公開鍵は対象サーバーの<code>~/.ssh/authorized_keys</code>
          へ手動で追記してください。
        </p>
      </div>
      <FetchErrorNotice labels={failedLabels(keyPairsResult)} />
      {!keyPairsResult.failed && (
        <SshKeyPairsPanel keyPairs={keyPairsResult.data} personalTimeZone={personalTimeZone} />
      )}
    </div>
  );
}
