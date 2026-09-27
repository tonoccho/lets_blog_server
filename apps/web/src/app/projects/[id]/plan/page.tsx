import { notFound } from "next/navigation";
import Link from "next/link";
import {
  getProject,
  listArticlePlanSessions,
  listArticlePlanIssues,
  getArticlePlanSessionByIssue,
  getArticlePlanIssueDescription,
} from "@/lib/apiClient";
import { requireAdminSession, getViewerTimeZone } from "@/lib/session";
import { loadOrReport, failedLabels } from "@/lib/loadOrReport";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";
import { Breadcrumb } from "@/components/Breadcrumb";
import { ProjectSectionNav } from "../ProjectSectionNav";
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
  await requireAdminSession();
  const projectId = Number(id);
  const issueNumber = issueRaw ? Number(issueRaw) : null;

  // 404だけを「存在しない」として扱い、それ以外の失敗は notFound() にせず通知する(issue #1235)。
  const scope = `projects/${projectId}/plan`;
  const projectResult = await loadOrReport(scope, "プロジェクト情報", getProject(projectId), null, {
    notFoundIsEmpty: true,
  });
  if (projectResult.failed) {
    return (
      <div className="space-y-8">
        <FetchErrorNotice labels={failedLabels(projectResult)} />
      </div>
    );
  }
  const project = projectResult.data;
  if (!project) {
    notFound();
  }

  // 条件付きの取得(Issue番号・GitHub連携なし)は「取得しない」ので失敗ではない。
  const skipped = <T,>(label: string, data: T) => Promise.resolve({ data, failed: false, label });
  const [sessions, issues, issueSession, allIssues, issueDescription, timezone] = await Promise.all([
    loadOrReport(scope, "記事計画セッション一覧", listArticlePlanSessions(projectId), []),
    project.githubRepository
      ? loadOrReport(scope, "記事計画Issue一覧", listArticlePlanIssues(projectId, "open"), [])
      : skipped("記事計画Issue一覧", []),
    issueNumber
      ? loadOrReport(scope, "Issueのセッション", getArticlePlanSessionByIssue(projectId, issueNumber), null, {
          notFoundIsEmpty: true,
        })
      : skipped("Issueのセッション", null),
    issueNumber && project.githubRepository
      ? loadOrReport(scope, "記事計画Issue一覧(全件)", listArticlePlanIssues(projectId, "all"), [])
      : skipped("記事計画Issue一覧(全件)", []),
    issueNumber && project.githubRepository
      ? loadOrReport(scope, "Issueの構成案", getArticlePlanIssueDescription(projectId, issueNumber), null, {
          notFoundIsEmpty: true,
        })
      : skipped("Issueの構成案", null),
    getViewerTimeZone(),
  ]);
  const issueTitle = issueNumber ? allIssues.data.find((i) => i.number === issueNumber)?.title ?? null : null;
  const issueStructure = issueDescription.data?.body ?? null;

  return (
    <div className="space-y-8">
      <Breadcrumb
        items={[
          { label: "ダッシュボード", href: "/" },
          { label: "プロジェクト", href: "/projects" },
          { label: project.name, href: `/projects/${projectId}` },
          { label: "記事計画" },
        ]}
      />
      <h1 className="text-xl font-semibold">{project.name} — 記事計画</h1>

      <ProjectSectionNav projectId={projectId} active="plan" />

      <FetchErrorNotice labels={failedLabels(sessions, issues, issueSession, allIssues, issueDescription)} />

      {!project.githubRepository && (
        <div className="rounded-lg border border-yellow-200 bg-yellow-50 p-4 text-sm text-yellow-800">
          このプロジェクトに GitHub リポジトリが紐付けられていません。
          <Link href={`/projects/${projectId}`} className="ml-1 underline">
            プロジェクト詳細
          </Link>
          から設定してください。
        </div>
      )}

      {!sessions.failed && (
      <ArticlePlanWorkspace
        key={issueNumber ?? "default"}
        projectId={projectId}
        initialSessions={sessions.data}
        initialIssueNumber={issueNumber}
        initialIssueTitle={issueTitle}
        initialIssueSession={issueSession.data}
        initialIssueStructure={issueStructure}
        timezone={timezone}
      />
      )}

      {project.githubRepository && !issues.failed && (
        <ArticlePlanIssueList projectId={projectId} initialIssues={issues.data} initialState="open" />
      )}
    </div>
  );
}
