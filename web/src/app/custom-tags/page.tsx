import { listCustomTags, listProjects } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { CustomTagManager } from "./CustomTagManager";

export default async function CustomTagsPage({
  searchParams,
}: {
  searchParams: Promise<{ projectId?: string }>;
}) {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const { projectId: projectIdRaw } = await searchParams;
  const projectId = projectIdRaw ? Number(projectIdRaw) : undefined;

  const [tags, projects] = await Promise.all([
    listCustomTags(actor, projectId).catch(() => []),
    listProjects(actor).catch(() => []),
  ]);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">カスタムタグ</h1>
      <CustomTagManager tags={tags} projects={projects} currentProjectId={projectId ?? null} />
    </div>
  );
}
