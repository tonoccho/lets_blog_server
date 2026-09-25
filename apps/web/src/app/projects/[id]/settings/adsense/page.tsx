import { notFound } from "next/navigation";
import { getProject, getProjectAdSenseStatus } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { ProjectAdSenseSettingsForm } from "../../ProjectAdSenseSettingsForm";

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
    getProjectAdSenseStatus(projectId).catch(() => ({
      configured: false,
      accountId: null,
      clientId: null,
      hasClientSecret: false,
    })),
  ]);
  if (!project) {
    notFound();
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
        accountId={status.accountId}
        clientId={status.clientId}
        hasClientSecret={status.hasClientSecret}
        connectedBanner={connected === "1"}
        errorBanner={error}
      />
    </div>
  );
}
