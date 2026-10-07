import Link from "next/link";
import { notFound } from "next/navigation";
import { getProject, listArticleReviewPullRequests } from "@/lib/apiClient";
import type { ArticleReviewPullRequest } from "@/lib/apiClient";
import { requireAdminSession, getViewerTimeZone } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { ProjectSectionNav } from "../ProjectSectionNav";
import { ArticleReviewPullRequestList } from "./ArticleReviewPullRequestList";

/**
 * 取得失敗は「0件」と区別できる形で返す(issue #1340、#1235 の `.catch(() => [])` を増やさない)。
 * 例外の握り潰しではなく、失敗を値として画面まで運ぶ。
 */
async function loadPullRequests(projectId: number): Promise<ArticleReviewPullRequest[] | null> {
  try {
    return await listArticleReviewPullRequests(projectId);
  } catch (error) {
    console.error("レビュー待ち Pull Request の取得に失敗しました", error);
    return null;
  }
}

export default async function ProjectArticleReviewPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  await requireAdminSession();
  const projectId = Number(id);

  const [project, timezone] = await Promise.all([
    getProject(projectId).catch(() => null),
    getViewerTimeZone(),
  ]);
  if (!project) {
    notFound();
  }

  const pullRequests = project.githubRepository ? await loadPullRequests(projectId) : null;

  return (
    <div className="space-y-8">
      <Breadcrumb
        items={[
          { label: "ダッシュボード", href: "/" },
          { label: "プロジェクト", href: "/projects" },
          { label: project.name, href: `/projects/${projectId}` },
          { label: "記事レビュー" },
        ]}
      />
      <div>
        <h1 className="text-xl font-semibold">{project.name} — 記事レビュー</h1>
      </div>

      <ProjectSectionNav projectId={projectId} active="article-review" />

      {!project.githubRepository ? (
        <p className="text-sm text-neutral-700 dark:text-neutral-300">
          GitHub リポジトリが未設定です。{" "}
          <Link href={`/projects/${projectId}`} className="text-blue-600 dark:text-blue-400 hover:underline">
            プロジェクト設定
          </Link>
          で GitHub リポジトリを設定してください。
        </p>
      ) : pullRequests === null ? (
        <p role="alert" className="text-sm text-red-600 dark:text-red-400">
          Pull Request の取得に失敗しました。時間をおいて再読み込みするか、GitHub の認証設定を確認してください。
        </p>
      ) : (
        <ArticleReviewPullRequestList projectId={projectId} pullRequests={pullRequests} timezone={timezone} />
      )}
    </div>
  );
}
