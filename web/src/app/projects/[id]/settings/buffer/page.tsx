import { notFound } from "next/navigation";
import { getProject, getProjectBufferStatus } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { ProjectBufferSettingsForm } from "../../ProjectBufferSettingsForm";

export default async function ProjectBufferSettingsPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const projectId = Number(id);

  const [project, status] = await Promise.all([
    getProject(projectId, actor).catch(() => null),
    getProjectBufferStatus(projectId, actor).catch(() => ({
      configured: false,
      enabled: false,
      hasAccessToken: false,
      profileIds: null,
      delayMinutes: null,
      messageTemplate: null,
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
          { label: "Buffer連携設定" },
        ]}
      />
      <div>
        <h1 className="text-xl font-semibold">{project.name} — Buffer連携設定</h1>
      </div>

      <ProjectBufferSettingsForm
        projectId={projectId}
        configured={status.configured}
        enabled={status.enabled}
        hasAccessToken={status.hasAccessToken}
        profileIds={status.profileIds}
        delayMinutes={status.delayMinutes}
        messageTemplate={status.messageTemplate}
      />
    </div>
  );
}
