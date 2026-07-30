import { notFound } from "next/navigation";
import { getProject, listSites, listProjectUsers, listUsers } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { EnvironmentSlot } from "./EnvironmentSlot";
import { EnvironmentSyncPanel } from "./EnvironmentSyncPanel";
import { ProjectNameForm } from "./ProjectNameForm";
import { DeleteProjectButton } from "./DeleteProjectButton";
import { ProjectUserManager } from "./ProjectUserManager";
import { AddProjectUserModal } from "./AddProjectUserModal";

export default async function ProjectDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const projectId = Number(id);

  const [project, sites, members, allUsers] = await Promise.all([
    getProject(projectId, actor).catch(() => null),
    listSites().catch(() => []),
    listProjectUsers(projectId, actor).catch(() => []),
    listUsers().catch(() => []),
  ]);

  if (!project) {
    notFound();
  }

  const candidateUsers = allUsers.filter((user) => !members.some((member) => member.userId === user.id));

  return (
    <div className="space-y-8">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">{project.name}</h1>
        <DeleteProjectButton id={project.id} />
      </div>
      <p className="font-mono text-sm text-neutral-500">{project.slug}</p>

      <ProjectNameForm projectId={project.id} name={project.name} />

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <EnvironmentSlot projectId={project.id} environment="local" site={project.localSite} candidateSites={sites} />
        <EnvironmentSlot projectId={project.id} environment="test" site={project.testSite} candidateSites={sites} />
        <EnvironmentSlot
          projectId={project.id}
          environment="production"
          site={project.productionSite}
          candidateSites={sites}
        />
      </div>

      <EnvironmentSyncPanel projectId={project.id} project={project} />

      <div className="space-y-4">
        <h2 className="text-lg font-semibold">プロジェクトメンバー</h2>
        <ProjectUserManager projectId={project.id} members={members} />
        <AddProjectUserModal projectId={project.id} candidateUsers={candidateUsers} />
      </div>
    </div>
  );
}
