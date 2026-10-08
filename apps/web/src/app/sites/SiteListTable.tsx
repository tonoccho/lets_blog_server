"use client";

import Link from "next/link";
import { ExternalLink, Settings } from "lucide-react";
import { useMemo, useState } from "react";
import type { CmsType, Project, Site } from "@/lib/apiClient";
import { resolveSiteAdminUrl } from "@/lib/siteAdminUrl";
import { ViewerDateTime } from "@/components/ViewerDateTime";
import { DeleteSiteButton } from "./DeleteSiteButton";
import { CheckConnectionButton } from "./CheckConnectionButton";

type Environment = "local" | "test" | "production";

interface ProjectBinding {
  projectName: string;
  environment: Environment;
}

const ENVIRONMENT_LABEL: Record<Environment, string> = {
  local: "ローカル",
  test: "テスト",
  production: "本番",
};

const ENVIRONMENT_BADGE_COLOR: Record<Environment, string> = {
  local: "bg-green-100 text-green-700",
  test: "bg-amber-100 text-amber-700",
  production: "bg-red-100 text-red-700",
};

type CmsFilter = "ALL" | CmsType;
type ProjectFilter = "ALL" | "BOUND" | "UNBOUND";
type SortColumn = "name" | "createdAt" | "updatedAt" | null;
type SortOrder = "asc" | "desc";

export function SiteListTable({
  sites,
  projects,
  isAdmin,
  timezone,
  adminPath,
}: {
  sites: Site[];
  projects: Project[];
  isAdmin: boolean;
  timezone: string | null;
  adminPath: string;
}) {
  const [searchText, setSearchText] = useState("");
  const [cmsFilter, setCmsFilter] = useState<CmsFilter>("ALL");
  const [projectFilter, setProjectFilter] = useState<ProjectFilter>("ALL");
  const [sortBy, setSortBy] = useState<SortColumn>(null);
  const [sortOrder, setSortOrder] = useState<SortOrder>("asc");

  const siteToProject = useMemo(() => {
    const map = new Map<number, ProjectBinding>();
    for (const project of projects) {
      if (project.localSite) map.set(project.localSite.id, { projectName: project.name, environment: "local" });
      if (project.testSite) map.set(project.testSite.id, { projectName: project.name, environment: "test" });
      if (project.productionSite) {
        map.set(project.productionSite.id, { projectName: project.name, environment: "production" });
      }
    }
    return map;
  }, [projects]);

  const filteredSites = useMemo(() => {
    const query = searchText.trim().toLowerCase();
    const filtered = sites.filter((site) => {
      const projectInfo = siteToProject.get(site.id);
      if (cmsFilter !== "ALL" && site.cmsType !== cmsFilter) {
        return false;
      }
      if (projectFilter === "BOUND" && !projectInfo) {
        return false;
      }
      if (projectFilter === "UNBOUND" && projectInfo) {
        return false;
      }
      if (query === "") {
        return true;
      }
      return (
        site.siteKey.toLowerCase().includes(query) ||
        site.name.toLowerCase().includes(query) ||
        site.baseUrl.toLowerCase().includes(query)
      );
    });

    if (sortBy) {
      return [...filtered].sort((a, b) => {
        let compareResult = 0;
        if (sortBy === "name") {
          compareResult = a.name.localeCompare(b.name);
        } else if (sortBy === "createdAt") {
          compareResult = new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime();
        } else if (sortBy === "updatedAt") {
          compareResult = new Date(a.updatedAt).getTime() - new Date(b.updatedAt).getTime();
        }
        return sortOrder === "asc" ? compareResult : -compareResult;
      });
    }

    return filtered;
  }, [sites, siteToProject, searchText, cmsFilter, projectFilter, sortBy, sortOrder]);

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
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <input
          type="text"
          value={searchText}
          onChange={(e) => setSearchText(e.target.value)}
          placeholder="サイトキー・表示名・URLで検索"
          className="w-64 rounded border border-neutral-300 dark:border-neutral-700 px-3 py-1.5 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
        />
        <select
          value={cmsFilter}
          onChange={(e) => setCmsFilter(e.target.value as CmsFilter)}
          aria-label="CMS種別で絞り込む"
          className="rounded border border-neutral-300 dark:border-neutral-700 px-2 py-1.5 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
        >
          <option value="ALL">CMS種別: すべて</option>
          <option value="WORDPRESS">WORDPRESS</option>
        </select>
        <select
          value={projectFilter}
          onChange={(e) => setProjectFilter(e.target.value as ProjectFilter)}
          aria-label="プロジェクト紐付け状況で絞り込む"
          className="rounded border border-neutral-300 dark:border-neutral-700 px-2 py-1.5 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
        >
          <option value="ALL">プロジェクト紐付け: すべて</option>
          <option value="BOUND">紐付け済みのみ</option>
          <option value="UNBOUND">未紐付けのみ</option>
        </select>
        <span className="text-neutral-700 dark:text-neutral-300">
          {filteredSites.length > 0 ? `${filteredSites.length}件を表示 (全${sites.length}件中)` : `全${sites.length}件`}
        </span>
      </div>

      <div className="max-h-[70vh] overflow-auto rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
        <table className="w-full text-left text-sm">
          <thead className="sticky top-0 border-b border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 text-neutral-700 dark:text-neutral-300 font-medium">
            <tr>
              <th className="px-4 py-2">サイトキー</th>
              <th className="cursor-pointer px-4 py-2 hover:bg-neutral-100 dark:hover:bg-neutral-800" onClick={() => handleColumnSort("name")}>
                表示名{renderSortIndicator("name")}
              </th>
              <th className="px-4 py-2">CMS種別</th>
              <th className="px-4 py-2">プロジェクト</th>
              <th className="px-4 py-2">リンク</th>
              <th className="cursor-pointer px-4 py-2 hover:bg-neutral-100 dark:hover:bg-neutral-800" onClick={() => handleColumnSort("createdAt")}>
                登録日{renderSortIndicator("createdAt")}
              </th>
              <th className="px-4 py-2">疎通確認</th>
              {isAdmin && <th className="px-4 py-2"></th>}
            </tr>
          </thead>
          <tbody>
            {filteredSites.length === 0 && (
              <tr>
                <td colSpan={isAdmin ? 8 : 7} className="px-4 py-6 text-center text-neutral-700 dark:text-neutral-300">
                  {sites.length === 0 ? "登録済みサイトはありません" : "条件に一致するサイトはありません"}
                </td>
              </tr>
            )}
            {filteredSites.map((site) => {
              const projectInfo = siteToProject.get(site.id);
              const adminUrl = resolveSiteAdminUrl(site.baseUrl, adminPath, site.adminPath);
              return (
                <tr key={site.id} className="border-b border-neutral-100 dark:border-neutral-800 odd:bg-neutral-50/50 last:border-0 cursor-pointer hover:bg-neutral-100 dark:hover:bg-neutral-800 hover:shadow-sm transition-colors">
                  <td className="px-4 py-2 font-mono">{site.siteKey}</td>
                  <td className="px-4 py-2">{site.name}</td>
                  <td className="px-4 py-2">
                    <span
                      className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${
                        site.cmsType === "WORDPRESS" ? "bg-blue-100 text-blue-800" : "bg-green-100 text-green-800"
                      }`}
                    >
                      {site.cmsType}
                    </span>
                    {site.managedWordpress && (
                      <span className="ml-1 inline-block rounded bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
                        自動構築
                      </span>
                    )}
                    {site.letsblogSync?.status === "FAILED" && (
                      <span
                        data-testid="letsblog-sync-failed"
                        title={site.letsblogSync.error ?? undefined}
                        className="ml-1 inline-block rounded bg-red-100 px-2 py-0.5 text-xs font-medium text-red-800"
                      >
                        同期失敗
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-2">
                    {projectInfo ? (
                      <span className="inline-flex items-center gap-1.5">
                        <span
                          className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${ENVIRONMENT_BADGE_COLOR[projectInfo.environment]}`}
                        >
                          {ENVIRONMENT_LABEL[projectInfo.environment]}
                        </span>
                        <span className="text-neutral-900 dark:text-neutral-50">{projectInfo.projectName}</span>
                      </span>
                    ) : (
                      <span className="inline-block rounded bg-neutral-200 dark:bg-neutral-700 px-2 py-0.5 text-xs font-medium text-neutral-700 dark:text-neutral-300">
                        未紐付け
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-2">
                    <span className="inline-flex items-center gap-3">
                      <a
                        href={site.baseUrl}
                        title={site.baseUrl}
                        target="_blank"
                        rel="noreferrer"
                        aria-label={`${site.name} のサイトを開く`}
                        className="text-blue-700 hover:text-blue-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
                      >
                        <ExternalLink className="h-4 w-4" aria-hidden="true" />
                      </a>
                      {adminUrl && (
                        <a
                          href={adminUrl}
                          title={adminUrl}
                          target="_blank"
                          rel="noreferrer"
                          aria-label={`${site.name} の管理画面を開く`}
                          className="text-blue-700 hover:text-blue-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
                        >
                          <Settings className="h-4 w-4" aria-hidden="true" />
                        </a>
                      )}
                    </span>
                  </td>
                  <td className="px-4 py-2 text-neutral-700 dark:text-neutral-300">
                    <ViewerDateTime iso={site.createdAt} personalTimeZone={timezone} />
                  </td>
                  <td className="px-4 py-2">
                    <CheckConnectionButton id={site.id} />
                  </td>
                  {isAdmin && (
                    <td className="px-4 py-2 text-right">
                      <div className="flex justify-end gap-3">
                        <Link href={`/sites/${site.id}/edit`} className="text-sm text-neutral-600 dark:text-neutral-400 hover:underline">
                          管理
                        </Link>
                        <DeleteSiteButton id={site.id} managedWordpress={site.managedWordpress} />
                      </div>
                    </td>
                  )}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
