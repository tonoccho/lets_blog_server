import { listCustomTagTemplates, listProjects } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { CustomTagTemplateGallery } from "./CustomTagTemplateGallery";

export default async function CustomTagTemplatesPage({
  searchParams,
}: {
  searchParams: Promise<{ projectId?: string; category?: string; search?: string; showAll?: string }>;
}) {
  await requireAdminSession();
  const params = await searchParams;
  const projectId = params.projectId ? Number(params.projectId) : undefined;
  const category = params.category;
  const search = params.search;
  const showAll = params.showAll === "true";

  const [templates, projects] = await Promise.all([
    listCustomTagTemplates(projectId, {
      category,
      search,
      showAll,
    }).catch(() => []),
    listProjects().catch(() => []),
  ]);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">カスタムタグテンプレート</h1>
      <CustomTagTemplateGallery
        templates={templates}
        projects={projects}
        currentProjectId={projectId ?? null}
        currentCategory={category}
        currentSearch={search}
        showAll={showAll}
      />
    </div>
  );
}
