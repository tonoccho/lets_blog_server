import { getMyCustomTagTemplates, listCustomTagTemplates, listProjects } from "@/lib/apiClient";
import { loadOrReport, failedLabels } from "@/lib/loadOrReport";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";
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

  const [templatesResult, projectsResult] = await Promise.all([
    // 「自分が作ったものだけ」は作成者で決まるため、スコープ・公開状態の条件は使わない
    // (my-templates は自分の作成分を未公開も含めて返す)。検索語とカテゴリーだけ重ねる。
    loadOrReport(
      "custom-tag-templates",
      "テンプレート一覧",
      mine
        ? getMyCustomTagTemplates().then((mineTemplates) => filterMyTemplates(mineTemplates, { category, search }))
        : listCustomTagTemplates(projectId, {
            category,
            search,
            showAll,
          }),
      [],
    ),
    loadOrReport("custom-tag-templates", "プロジェクト一覧", listProjects(), []),
  ]);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">カスタムタグテンプレート</h1>
      <FetchErrorNotice labels={failedLabels(templatesResult, projectsResult)} />
      {!templatesResult.failed && (
      <CustomTagTemplateGallery
        templates={templatesResult.data}
        projects={projectsResult.data}
        currentProjectId={projectId ?? null}
        currentCategory={category}
        currentSearch={search}
        showAll={showAll}
        mine={mine}
      />
      )}
    </div>
  );
}
