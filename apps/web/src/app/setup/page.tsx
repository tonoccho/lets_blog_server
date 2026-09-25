import { redirect } from "next/navigation";
import { getSetupStatus } from "@/lib/apiClient";
import { SetupForm } from "./SetupForm";

export default async function SetupPage() {
  const { needsSetup } = await getSetupStatus().catch(() => ({ needsSetup: false }));
  if (!needsSetup) {
    redirect("/login");
  }

  return (
    <div className="mx-auto max-w-sm">
      <h1 className="mb-2 text-xl font-semibold">初回セットアップ</h1>
      <p className="mb-6 text-sm text-neutral-600 dark:text-neutral-400">
        最初の管理者アカウントを作成します。この画面はユーザーが1人も登録されていない場合のみ表示されます。
      </p>
      <SetupForm />
    </div>
  );
}
