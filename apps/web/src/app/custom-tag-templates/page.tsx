import { getMyCustomTagTemplates, listCustomTagTemplates, listProjects } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { CustomTagTemplateGallery } from "./CustomTagTemplateGallery";
import { filterMyTemplates } from "./filterMyTemplates";

export default async function CustomTagTemplatesPage({
  searchParams,
}: {
  searchParams: Promise<{ projectId?: string; category?: string; search?: string; showAll?: string; mine?: string }>;
}) {
  await requireAdminSession();
  const params = await searchParams;
  const projectId = params.projectId ? Number(params.projectId) : undefined;
  const category = params.category;
  const search = params.search;
  const showAll = params.showAll === "true";
  const mine = params.mine === "true";

  const [templates, projects] = await Promise.all([
    // 「自分が作ったものだけ」は作成者で決まるため、スコープ・公開状態の条件は使わない
    // (my-templates は自分の作成分を未公開も含めて返す)。検索語とカテゴリーだけ重ねる。
    mine
      ? getMyCustomTagTemplates()
          .then((mineTemplates) => filterMyTemplates(mineTemplates, { category, search }))
          .catch(() => [])
      : listCustomTagTemplates(projectId, {
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
        mine={mine}
      />
    </div>
  );
}
