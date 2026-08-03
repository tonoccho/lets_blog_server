import { notFound } from "next/navigation";
import Link from "next/link";
import {
  getProject,
  listArticlePlanSessions,
  listArticlePlanIssues,
  getArticlePlanSessionByIssue,
  getArticlePlanIssueDescription,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { ArticlePlanWorkspace } from "./ArticlePlanWorkspace";
import { ArticlePlanIssueList } from "./ArticlePlanIssueList";

export default async function ArticlePlanPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ issue?: string }>;
}) {
  const { id } = await params;
  const { issue: issueRaw } = await searchParams;
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const projectId = Number(id);
  const issueNumber = issueRaw ? Number(issueRaw) : null;

  const project = await getProject(projectId, actor).catch(() => null);
  if (!project) {
    notFound();
  }

  const [sessions, issues, issueSession, allIssues, issueDescription] = await Promise.all([
    listArticlePlanSessions(projectId, actor).catch(() => []),
    project.githubRepository
      ? listArticlePlanIssues(projectId, "open", actor).catch(() => [])
      : Promise.resolve([]),
    issueNumber
      ? getArticlePlanSessionByIssue(projectId, issueNumber, actor).catch(() => null)
      : Promise.resolve(null),
    issueNumber && project.githubRepository
      ? listArticlePlanIssues(projectId, "all", actor).catch(() => [])
      : Promise.resolve([]),
    issueNumber && project.githubRepository
      ? getArticlePlanIssueDescription(projectId, issueNumber, actor).catch(() => null)
      : Promise.resolve(null),
  ]);
  const issueTitle = issueNumber ? allIssues.find((i) => i.number === issueNumber)?.title ?? null : null;
  const issueStructure = issueDescription?.body ?? null;

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

      <ArticlePlanWorkspace
        key={issueNumber ?? "default"}
        projectId={projectId}
        initialSessions={sessions}
        initialIssueNumber={issueNumber}
        initialIssueTitle={issueTitle}
        initialIssueSession={issueSession}
        initialIssueStructure={issueStructure}
      />

      {project.githubRepository && (
        <ArticlePlanIssueList projectId={projectId} initialIssues={issues} initialState="open" />
      )}
    </div>
  );
}
