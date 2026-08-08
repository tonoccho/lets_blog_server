import Link from "next/link";
import { listProjects } from "@/lib/apiClient";
import { requireAdminSession, getViewerTimeZone } from "@/lib/session";
import { formatDateTime } from "@/lib/formatDate";
import { ProjectForm } from "./ProjectForm";

export default async function ProjectsPage() {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const [projects, timezone] = await Promise.all([listProjects(actor).catch(() => []), getViewerTimeZone()]);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">プロジェクト</h1>

      <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 bg-neutral-50 text-neutral-500">
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
                <td colSpan={5} className="px-4 py-8 text-center">
                  <div className="flex flex-col items-center gap-4">
                    <p className="text-neutral-600">登録済みプロジェクトはありません</p>
                    <a
                      href="#project-form"
                      className="rounded bg-neutral-900 px-4 py-2 text-sm text-white hover:bg-neutral-800"
                    >
                      プロジェクトを作成する
                    </a>
                  </div>
                </td>
              </tr>
            )}
            {projects.map((project) => (
              <tr key={project.id} className="border-b border-neutral-100 last:border-0">
                <td className="px-4 py-2">{project.name}</td>
                <td className="px-4 py-2 font-mono">{project.slug}</td>
                <td className="px-4 py-2">
                  <div className="flex gap-1">
                    <span
                      className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${
                        project.localSite ? "bg-blue-100 text-blue-700" : "bg-neutral-100 text-neutral-400"
                      }`}
                    >
                      local
                    </span>
                    <span
                      className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${
                        project.testSite ? "bg-amber-100 text-amber-700" : "bg-neutral-100 text-neutral-400"
                      }`}
                    >
                      test
                    </span>
                    <span
                      className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${
                        project.productionSite ? "bg-green-100 text-green-700" : "bg-neutral-100 text-neutral-400"
                      }`}
                    >
                      production
                    </span>
                  </div>
                </td>
                <td className="px-4 py-2 text-neutral-500">{formatDateTime(project.createdAt, timezone)}</td>
                <td className="px-4 py-2 text-right space-x-2">
                  <Link href={`/projects/${project.id}/plan`} className="text-sm text-neutral-600 hover:underline">
                    計画
                  </Link>
                  <Link href={`/projects/${project.id}`} className="text-sm text-neutral-600 hover:underline">
                    詳細
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div id="project-form">
        <ProjectForm />
      </div>
    </div>
  );
}
