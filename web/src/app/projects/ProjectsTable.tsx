"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import type { Project } from "@/lib/apiClient";
import { formatDateTime } from "@/lib/formatDate";

type SortColumn = "name" | "createdAt" | null;
type SortOrder = "asc" | "desc";

export function ProjectsTable({ projects: initialProjects, timezone }: { projects: Project[]; timezone: string | null }) {
  const [sortBy, setSortBy] = useState<SortColumn>(null);
  const [sortOrder, setSortOrder] = useState<SortOrder>("asc");

  const sortedProjects = useMemo(() => {
    if (sortBy) {
      const sorted = [...initialProjects];
      sorted.sort((a, b) => {
        let compareResult = 0;
        if (sortBy === "name") {
          compareResult = a.name.localeCompare(b.name);
        } else if (sortBy === "createdAt") {
          compareResult = new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
        }
        return sortOrder === "asc" ? compareResult : -compareResult;
      });
      return sorted;
    }
    return initialProjects;
  }, [initialProjects, sortBy, sortOrder]);

  const handleColumnSort = (column: SortColumn) => {
    if (sortBy === column) {
      setSortOrder(sortOrder === "asc" ? "desc" : "asc");
    } else {
      setSortBy(column);
      setSortOrder("asc");
    }
  };

  const renderSortIndicator = (column: SortColumn) => {
    if (sortBy !== column) return null;
    return sortOrder === "asc" ? " ↑" : " ↓";
  };

  return (
    <div className="overflow-x-auto rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 text-neutral-500 dark:text-neutral-400">
          <tr>
            <th className="cursor-pointer px-4 py-2 hover:bg-neutral-100 dark:hover:bg-neutral-800" onClick={() => handleColumnSort("name")}>
              名前{renderSortIndicator("name")}
            </th>
            <th className="px-4 py-2">slug</th>
            <th className="px-4 py-2">環境</th>
            <th className="cursor-pointer px-4 py-2 hover:bg-neutral-100 dark:hover:bg-neutral-800" onClick={() => handleColumnSort("createdAt")}>
              作成日{renderSortIndicator("createdAt")}
            </th>
            <th className="px-4 py-2"></th>
          </tr>
        </thead>
        <tbody>
          {sortedProjects.length === 0 && (
            <tr>
              <td colSpan={5} className="px-4 py-8 text-center">
                <div className="flex flex-col items-center gap-4">
                  <p className="text-neutral-600 dark:text-neutral-400">登録済みプロジェクトはありません</p>
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
          {sortedProjects.map((project) => (
            <tr key={project.id} className="border-b border-neutral-100 dark:border-neutral-800 last:border-0">
              <td className="px-4 py-2">{project.name}</td>
              <td className="px-4 py-2 font-mono">{project.slug}</td>
              <td className="px-4 py-2">
                <div className="flex gap-1">
                  <span
                    className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${
                      project.localSite ? "bg-blue-100 text-blue-700" : "bg-neutral-100 dark:bg-neutral-800 text-neutral-400 dark:text-neutral-500"
                    }`}
                  >
                    local
                  </span>
                  <span
                    className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${
                      project.testSite ? "bg-amber-100 text-amber-700" : "bg-neutral-100 dark:bg-neutral-800 text-neutral-400 dark:text-neutral-500"
                    }`}
                  >
                    test
                  </span>
                  <span
                    className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${
                      project.productionSite ? "bg-green-100 text-green-700" : "bg-neutral-100 dark:bg-neutral-800 text-neutral-400 dark:text-neutral-500"
                    }`}
                  >
                    production
                  </span>
                </div>
              </td>
              <td className="px-4 py-2 text-neutral-500 dark:text-neutral-400">{formatDateTime(project.createdAt, timezone)}</td>
              <td className="px-4 py-2 text-right space-x-2">
                <Link href={`/projects/${project.id}/plan`} className="text-sm text-neutral-600 dark:text-neutral-400 hover:underline">
                  計画
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
  );
}
