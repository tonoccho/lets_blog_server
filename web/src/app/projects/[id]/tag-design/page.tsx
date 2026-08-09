import { notFound } from "next/navigation";
import { getProject, getTagDesignSettings } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { TagDesignSettingsPanel } from "./TagDesignSettingsPanel";

export default async function TagDesignPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const projectId = Number(id);

  const project = await getProject(projectId, actor).catch(() => null);
  if (!project) {
    notFound();
  }

  const overview = await getTagDesignSettings(projectId, actor);

  return (
    <div className="space-y-8">
      <Breadcrumb
        items={[
          { label: "ダッシュボード", href: "/" },
          { label: "プロジェクト", href: "/projects" },
          { label: project.name, href: `/projects/${projectId}` },
          { label: "組み込みタグのデザイン" },
        ]}
      />
      <div>
        <h1 className="text-xl font-semibold">{project.name} — 組み込みタグのデザイン</h1>
        <p className="mt-1 text-sm text-neutral-600 dark:text-neutral-400">
          記事本文の <code>[toc]</code> / <code>[blogcard URL]</code> / <code>[amazon URL]</code>{" "}
          組み込みタグの見た目を、プリセットと色の調整でカスタマイズできます。
          保存した内容は次回のプレビュー・公開から反映されます。
        </p>
      </div>

      <TagDesignSettingsPanel projectId={projectId} presets={overview.presets} settings={overview.settings} />
    </div>
  );
}
