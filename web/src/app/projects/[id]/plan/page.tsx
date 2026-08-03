import { notFound } from "next/navigation";
import Link from "next/link";
import { getProject, listArticlePlanSessions, listArticlePlanIssues } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { ArticlePlanWorkspace } from "./ArticlePlanWorkspace";
import { ArticlePlanIssueList } from "./ArticlePlanIssueList";

export default async function ArticlePlanPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const projectId = Number(id);

  const project = await getProject(projectId, actor).catch(() => null);
  if (!project) {
    notFound();
  }

  const [sessions, issues] = await Promise.all([
    listArticlePlanSessions(projectId, actor).catch(() => []),
    project.githubRepository
      ? listArticlePlanIssues(projectId, "open", actor).catch(() => [])
      : Promise.resolve([]),
  ]);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">{project.name} — 記事計画</h1>

      {!project.githubRepository && (
        <div className="rounded-lg border border-yellow-200 bg-yellow-50 p-4 text-sm text-yellow-800">
          このプロジェクトに GitHub リポジトリが紐付けられていません。
          <Link href={`/projects/${projectId}`} className="ml-1 underline">
            プロジェクト詳細
          </Link>
          から設定してください。
        </div>
      )}

      <ArticlePlanWorkspace projectId={projectId} initialSessions={sessions} />

      {project.githubRepository && (
        <ArticlePlanIssueList projectId={projectId} initialIssues={issues} initialState="open" />
      )}
    </div>
  );
}
