import { notFound } from "next/navigation";
import {
  getProject,
  getProjectAdSenseStatus,
  listProjectAdSenseAccounts,
  type AdSenseAccountOption,
  type ProjectAdSenseStatus,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { ProjectAdSenseSettingsForm } from "../../ProjectAdSenseSettingsForm";

const UNCONFIGURED_STATUS: ProjectAdSenseStatus = {
  configured: false,
  accountId: null,
  clientId: null,
  hasClientSecret: false,
  connected: false,
};

export default async function ProjectAdSenseSettingsPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ connected?: string; error?: string }>;
}) {
  const { id } = await params;
  const { connected, error } = await searchParams;
  await requireAdminSession();
  const projectId = Number(id);

  const [project, status] = await Promise.all([
    getProject(projectId).catch(() => null),
    getProjectAdSenseStatus(projectId).catch(() => UNCONFIGURED_STATUS),
  ]);
  if (!project) {
    notFound();
  }

  // 連携済みのときだけ、Googleアカウントが利用できるAdSenseアカウント一覧を取得する(#1232)。
  // 失効・権限不足などで取得できなくても画面全体は落とさず、理由をフォームへ渡す(手入力で復旧できる)。
  let accounts: AdSenseAccountOption[] = [];
  let accountsError: string | undefined;
  if (status.connected) {
    try {
      accounts = await listProjectAdSenseAccounts(projectId);
    } catch (err) {
      accountsError = err instanceof Error ? err.message : String(err);
    }
  }

  return (
    <div className="space-y-8">
      <Breadcrumb
        items={[
          { label: "ダッシュボード", href: "/" },
          { label: "プロジェクト", href: "/projects" },
          { label: project.name, href: `/projects/${projectId}` },
          { label: "ダッシュボード", href: `/projects/${projectId}/dashboard` },
          { label: "Google AdSense設定" },
        ]}
      />
      <div>
        <h1 className="text-xl font-semibold">{project.name} — Google AdSense設定</h1>
      </div>

      <ProjectAdSenseSettingsForm
        projectId={projectId}
        configured={status.configured}
        connected={status.connected}
        accountId={status.accountId}
        clientId={status.clientId}
        hasClientSecret={status.hasClientSecret}
        accounts={accounts}
        accountsError={accountsError}
        connectedBanner={connected === "1"}
        errorBanner={error}
      />
    </div>
  );
}
