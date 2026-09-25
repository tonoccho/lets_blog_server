import { notFound } from "next/navigation";
import { getProject, getProjectGoogleAnalyticsStatus } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { ProjectGoogleAnalyticsSettingsForm } from "../../ProjectGoogleAnalyticsSettingsForm";

export default async function ProjectGoogleAnalyticsSettingsPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  await requireAdminSession();
  const projectId = Number(id);

  const [project, status] = await Promise.all([
    getProject(projectId).catch(() => null),
    getProjectGoogleAnalyticsStatus(projectId).catch(() => ({ configured: false, propertyId: null })),
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
          { label: "Google Analytics設定" },
        ]}
      />
      <div>
        <h1 className="text-xl font-semibold">{project.name} — Google Analytics設定</h1>
      </div>

      <ProjectGoogleAnalyticsSettingsForm
        projectId={projectId}
        configured={status.configured}
        propertyId={status.propertyId}
      />
    </div>
  );
}
