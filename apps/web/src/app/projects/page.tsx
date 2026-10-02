import Link from "next/link";
import { listProjects } from "@/lib/apiClient";
import { requireAdminSession, getViewerTimeZone } from "@/lib/session";
import { loadOrReport, failedLabels } from "@/lib/loadOrReport";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";
import { ViewerDateTime } from "@/components/ViewerDateTime";
import { ProjectForm } from "./ProjectForm";
import { ProjectsTable } from "./ProjectsTable";

export default async function ProjectsPage() {
  await requireAdminSession();
  const [projectsResult, timezone] = await Promise.all([
    loadOrReport("projects", "プロジェクト一覧", listProjects(), []),
    getViewerTimeZone(),
  ]);
  const projects = projectsResult.data;
  const failed = projectsResult.failed;

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">プロジェクト</h1>

      <FetchErrorNotice labels={failedLabels(projectsResult)} />

      {!failed && (
        <>
      <div className="text-sm text-neutral-600 dark:text-neutral-400">
        全{projects.length}件を表示
      </div>

      <div className="overflow-x-auto rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 text-neutral-500 dark:text-neutral-400">
            <tr>
              <th className="px-4 py-2">名前</th>
              <th className="px-4 py-2">slug</th>
              <th className="px-4 py-2">環境</th>
              <th className="px-4 py-2">作成日</th>
              <th className="px-4 py-2"></th>
            </tr>
          </thead>
          <tbody>
            {projects.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-6 text-center text-neutral-600 dark:text-neutral-400">
                  登録済みプロジェクトはありません
                </td>
              </tr>
            )}
            {projects.map((project) => (
              <tr key={project.id} className="border-b border-neutral-100 dark:border-neutral-800 last:border-0 cursor-pointer hover:bg-neutral-50 dark:hover:bg-neutral-800 hover:shadow-sm transition-colors">
                <td className="px-4 py-2">
                  <Link href={`/projects/${project.id}/dashboard`} className="hover:underline">
                    {project.name}
                  </Link>
                </td>
                <td className="px-4 py-2 font-mono">{project.slug}</td>
                <td className="px-4 py-2">
                  <div className="flex gap-1">
                    <span
                      className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${
                        project.localSite ? "bg-blue-100 text-blue-700" : "bg-neutral-100 dark:bg-neutral-800 text-neutral-600 dark:text-neutral-400"
                      }`}
                    >
                      local
                    </span>
                    <span
                      className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${
                        project.testSite ? "bg-amber-100 text-amber-700" : "bg-neutral-100 dark:bg-neutral-800 text-neutral-600 dark:text-neutral-400"
                      }`}
                    >
                      test
                    </span>
                    <span
                      className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${
                        project.productionSite ? "bg-green-100 text-green-700" : "bg-neutral-100 dark:bg-neutral-800 text-neutral-600 dark:text-neutral-400"
                      }`}
                    >
                      production
                    </span>
                  </div>
                </td>
                <td className="px-4 py-2 text-neutral-500 dark:text-neutral-400">
                  <ViewerDateTime iso={project.createdAt} personalTimeZone={timezone} />
                </td>
                <td className="px-4 py-2 text-right space-x-2">
                  <Link href={`/projects/${project.id}/plan`} className="text-sm text-neutral-600 dark:text-neutral-400 hover:underline">
                    計画
                  </Link>
                  <Link href={`/projects/${project.id}/tags`} className="text-sm text-neutral-600 dark:text-neutral-400 hover:underline">
                    タグ
                  </Link>
                  <Link href={`/projects/${project.id}`} className="text-sm text-neutral-600 dark:text-neutral-400 hover:underline">
                    詳細
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
        </>
      )}

      <div id="project-form">
        <ProjectForm />
      </div>
    </div>
  );
}

