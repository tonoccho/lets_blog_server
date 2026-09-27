import { notFound } from "next/navigation";
import {
  getProject,
  getProjectGoogleAnalyticsStatus,
  listProjectGoogleAnalyticsProperties,
  type GoogleAnalyticsPropertyOption,
  type ProjectGoogleAnalyticsStatus,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { ProjectGoogleAnalyticsSettingsForm } from "../../ProjectGoogleAnalyticsSettingsForm";

const UNCONFIGURED_STATUS: ProjectGoogleAnalyticsStatus = {
  configured: false,
  propertyId: null,
  clientId: null,
  hasClientSecret: false,
  connected: false,
};

export default async function ProjectGoogleAnalyticsSettingsPage({
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
    getProjectGoogleAnalyticsStatus(projectId).catch(() => UNCONFIGURED_STATUS),
  ]);
  if (!project) {
    notFound();
  }

  // 連携済みのときだけ、Googleアカウントがアクセスできるプロパティ一覧を取得する。
  // 失効などで取得できなくても画面全体は落とさず、理由をフォームへ渡す。
  let properties: GoogleAnalyticsPropertyOption[] = [];
  let propertiesError: string | undefined;
  if (status.connected) {
    try {
      properties = await listProjectGoogleAnalyticsProperties(projectId);
    } catch (err) {
      propertiesError = err instanceof Error ? err.message : String(err);
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
          { label: "Google Analytics設定" },
        ]}
      />
      <div>
        <h1 className="text-xl font-semibold">{project.name} — Google Analytics設定</h1>
      </div>

      <ProjectGoogleAnalyticsSettingsForm
        projectId={projectId}
        connected={status.connected}
        propertyId={status.propertyId}
        clientId={status.clientId}
        hasClientSecret={status.hasClientSecret}
        properties={properties}
        propertiesError={propertiesError}
        connectedBanner={connected === "1"}
        errorBanner={error}
      />
    </div>
  );
}
