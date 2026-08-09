import { notFound } from "next/navigation";
import { getProject, listProjectCustomTags } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { ProjectCustomTagManager } from "./ProjectCustomTagManager";

export default async function ProjectCustomTagsPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const projectId = Number(id);

  const project = await getProject(projectId, actor).catch(() => null);
  if (!project) {
    notFound();
  }

  const tags = await listProjectCustomTags(projectId, actor).catch(() => []);

  return (
    <div className="space-y-8">
      <Breadcrumb
        items={[
          { label: "ダッシュボード", href: "/" },
          { label: "プロジェクト", href: "/projects" },
          { label: project.name, href: `/projects/${projectId}` },
          { label: "カスタムタグ" },
        ]}
      />
      <div>
        <h1 className="text-xl font-semibold">{project.name} — カスタムタグ</h1>
        <p className="mt-1 text-sm text-neutral-600 dark:text-neutral-400">
          投稿本文で使えるカスタムタグ(HTML/CSSテンプレート)を、このプロジェクト専用に管理します。
        </p>
      </div>

      <ProjectCustomTagManager projectId={projectId} projectName={project.name} tags={tags} />
    </div>
  );
}
