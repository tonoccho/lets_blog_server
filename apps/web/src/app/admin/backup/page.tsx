import { requireAdminSession } from "@/lib/session";
import { BackupPanel } from "./BackupPanel";

export default async function AdminBackupPage() {
  await requireAdminSession();

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">データバックアップ</h1>
      <p className="text-sm text-neutral-600 dark:text-neutral-400">
        Let&apos;s Blogアプリ自身のデータベースと生成画像ファイルをバックアップ/リストアします(managed
        WordPressサイト個別のDB/ファイルは対象外です)。
      </p>
      <BackupPanel />
    </div>
  );
}
