import { notFound } from "next/navigation";
import { getProject, getTagDesignSettings, listProjectCustomTags } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { Tabs, type TabItem } from "@/components/Tabs";
import { ProjectSectionNav } from "../ProjectSectionNav";
import { TagDesignSettingsPanel } from "../tag-design/TagDesignSettingsPanel";
import { ProjectCustomTagManager } from "../custom-tags/ProjectCustomTagManager";

export default async function ProjectTagsPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const projectId = Number(id);

  const project = await getProject(projectId, actor).catch(() => null);
  if (!project) {
    notFound();
  }

  const [overview, tags] = await Promise.all([
    getTagDesignSettings(projectId, actor),
    listProjectCustomTags(projectId, actor).catch(() => []),
  ]);

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
          <TagDesignSettingsPanel projectId={projectId} presets={overview.presets} settings={overview.settings} />
        </div>
      ),
    },
    {
      id: "custom-tags",
      label: "カスタムタグ管理",
      content: <ProjectCustomTagManager projectId={projectId} projectName={project.name} tags={tags} />,
    },
    {
      id: "css-bundle",
      label: "統合CSSダウンロード",
      content: (
        <div className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
          <p className="max-w-2xl text-sm text-neutral-600 dark:text-neutral-400">
            組み込みタグ([toc]/[blogcard]/[amazon])のデザインと、このプロジェクトのカスタムタグのCSSをまとめた
            1つのファイルをダウンロードできます。WordPress側のテーマCSSに追加することで、
            記事本文中のタグを装飾できます。
          </p>
          <a
            href={`/projects/${projectId}/custom-tags/css-bundle`}
            className="inline-block rounded bg-neutral-900 px-4 py-2 text-sm text-white hover:bg-neutral-800"
          >
            統合CSSをダウンロード
          </a>
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

      <Tabs tabs={tabs} />
    </div>
  );
}
