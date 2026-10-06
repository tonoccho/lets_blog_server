import { notFound } from "next/navigation";
import {
  getProject,
  getProjectContentSettings,
  getTagDesignSettings,
  listProjectCustomTags,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import {
  loadLlmJobResult,
  readCustomTagJobResult,
  readTagDesignJobResult,
} from "@/lib/llmJobResults";
import { CUSTOM_TAG_GENERATION_JOB_TYPE, TAG_DESIGN_GENERATION_JOB_TYPE } from "@/app/infoRailQueue";
import { loadOrReport, failedLabels } from "@/lib/loadOrReport";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";
import { Breadcrumb } from "@/components/Breadcrumb";
import { Tabs, type TabItem } from "@/components/Tabs";
import { ProjectSectionNav } from "../ProjectSectionNav";
import { TagDesignSettingsPanel } from "../tag-design/TagDesignSettingsPanel";
import { ProjectCustomTagManager } from "../custom-tags/ProjectCustomTagManager";
import { CssBundleViewer } from "../custom-tags/CssBundleViewer";

export default async function ProjectTagsPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  /**
   * `?tab=` で最初に開くタブを選べる。`?customTagJob=` / `?tagDesignJob=` は処理キューの「結果を見る」の遷移先で
   * (issue #1409)、そのAI生成ジョブの結果(未保存)を該当パネルに表示する。
   */
  searchParams?: Promise<{ tab?: string; customTagJob?: string; tagDesignJob?: string }>;
}) {
  const { id } = await params;
  const { tab, customTagJob, tagDesignJob } = (await searchParams) ?? {};
  await requireAdminSession();
  const projectId = Number(id);

  const scope = `projects/${projectId}/tags`;
  const { data: project } = await loadOrReport(scope, "プロジェクト情報", getProject(projectId), null);
  if (!project) {
    notFound();
  }

  const [overview, tagsResult, contentSettingsResult, customTagResult, tagDesignResult] = await Promise.all([
    getTagDesignSettings(projectId),
    loadOrReport(scope, "カスタムタグ一覧", listProjectCustomTags(projectId), []),
    // cssSelectorPrefix は content-service が所有する(issue #576)。GET /api/projects/{id} には
    // 含まれないため個別に取得する。以前は project.cssSelectorPrefix を渡しており、
    // 常に undefined だった(issue #913)。
    loadOrReport(scope, "CSSセレクタ接頭辞", getProjectContentSettings(projectId), { cssSelectorPrefix: null }),
    loadLlmJobResult(customTagJob, CUSTOM_TAG_GENERATION_JOB_TYPE, readCustomTagJobResult),
    loadLlmJobResult(tagDesignJob, TAG_DESIGN_GENERATION_JOB_TYPE, readTagDesignJobResult),
  ]);
  // 別のプロジェクトのジョブの結果は、このプロジェクトの画面には出さない。
  const generatedCustomTag = customTagResult?.projectId === projectId ? customTagResult : null;
  const generatedTagDesign = tagDesignResult?.projectId === projectId ? tagDesignResult : null;
  // 取得に失敗した値を「0件」「未設定」に見せたまま編集させない。通知を出し、管理パネルは描画しない。
  const customTagsFailed = tagsResult.failed || contentSettingsResult.failed;

  const tabs: TabItem[] = [
    {
      id: "tag-design",
      label: "組み込みタグのデザイン",
      content: (
        <div className="space-y-4">
          <p className="max-w-2xl text-sm text-neutral-600 dark:text-neutral-400">
            記事本文の <code>[toc]</code> / <code>[blogcard URL]</code> / <code>[amazon URL]</code>{" "}
            組み込みタグの見た目を、プリセットと色の調整でカスタマイズできます。
            保存した内容は次回のプレビュー・公開から反映されます。
          </p>
          <TagDesignSettingsPanel
            projectId={projectId}
            presets={overview.presets}
            settings={overview.settings}
            jobResult={generatedTagDesign}
          />
        </div>
      ),
    },
    {
      id: "custom-tags",
      label: "カスタムタグ管理",
      content: customTagsFailed ? (
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          取得に失敗したため、カスタムタグを表示できません。
        </p>
      ) : (
        <ProjectCustomTagManager
          projectId={projectId}
          projectName={project.name}
          projectSlug={project.slug}
          cssSelectorPrefix={contentSettingsResult.data.cssSelectorPrefix}
          tags={tagsResult.data}
          generatedResult={generatedCustomTag}
        />
      ),
    },
    {
      id: "css-bundle",
      label: "統合CSSの取得",
      content: (
        <div className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
          <p className="max-w-2xl text-sm text-neutral-600 dark:text-neutral-400">
            組み込みタグ([toc]/[blogcard]/[amazon])のデザインと、このプロジェクトのカスタムタグのCSSをまとめて
            表示します。コピーしてWordPress側のテーマCSSに追加することで、
            記事本文中のタグを装飾できます。
          </p>
          <CssBundleViewer projectId={projectId} />
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-8">
      <Breadcrumb
        items={[
          { label: "ダッシュボード", href: "/" },
          { label: "プロジェクト", href: "/projects" },
          { label: project.name, href: `/projects/${projectId}` },
          { label: "タグ" },
        ]}
      />
      <div>
        <h1 className="text-xl font-semibold">{project.name} — タグ</h1>
      </div>

      <ProjectSectionNav projectId={projectId} active="tags" />

      <FetchErrorNotice labels={failedLabels(tagsResult, contentSettingsResult)} />

      <Tabs tabs={tabs} defaultTabId={tab} />
    </div>
  );
}
