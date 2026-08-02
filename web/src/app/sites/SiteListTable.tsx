"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import type { CmsType, Project, Site } from "@/lib/apiClient";
import { formatDateTime } from "@/lib/formatDate";
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

export function SiteListTable({
  sites,
  projects,
  isAdmin,
  timezone,
}: {
  sites: Site[];
  projects: Project[];
  isAdmin: boolean;
  timezone: string | null;
}) {
  const [searchText, setSearchText] = useState("");
  const [cmsFilter, setCmsFilter] = useState<CmsFilter>("ALL");
  const [projectFilter, setProjectFilter] = useState<ProjectFilter>("ALL");

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
    return sites.filter((site) => {
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
  }, [sites, siteToProject, searchText, cmsFilter, projectFilter]);

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <input
          type="text"
          value={searchText}
          onChange={(e) => setSearchText(e.target.value)}
          placeholder="サイトキー・表示名・URLで検索"
          className="w-64 rounded border border-neutral-300 px-3 py-1.5"
        />
        <select
          value={cmsFilter}
          onChange={(e) => setCmsFilter(e.target.value as CmsFilter)}
          className="rounded border border-neutral-300 px-2 py-1.5"
        >
          <option value="ALL">CMS種別: すべて</option>
          <option value="WORDPRESS">WORDPRESS</option>
          <option value="MICROCMS">MICROCMS</option>
        </select>
        <select
          value={projectFilter}
          onChange={(e) => setProjectFilter(e.target.value as ProjectFilter)}
          className="rounded border border-neutral-300 px-2 py-1.5"
        >
          <option value="ALL">プロジェクト紐付け: すべて</option>
          <option value="BOUND">紐付け済みのみ</option>
          <option value="UNBOUND">未紐付けのみ</option>
        </select>
        <span className="text-neutral-500">
          {sites.length}件中{filteredSites.length}件を表示
        </span>
      </div>

      <div className="max-h-[70vh] overflow-auto rounded-lg border border-neutral-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead className="sticky top-0 border-b border-neutral-200 bg-neutral-50 text-neutral-500">
            <tr>
              <th className="px-4 py-2">サイトキー</th>
              <th className="px-4 py-2">表示名</th>
              <th className="px-4 py-2">CMS種別</th>
              <th className="px-4 py-2">プロジェクト</th>
              <th className="px-4 py-2">URL</th>
              <th className="px-4 py-2">登録日</th>
              <th className="px-4 py-2">疎通確認</th>
              {isAdmin && <th className="px-4 py-2"></th>}
            </tr>
          </thead>
          <tbody>
            {filteredSites.length === 0 && (
              <tr>
                <td colSpan={isAdmin ? 8 : 7} className="px-4 py-6 text-center text-neutral-600">
                  {sites.length === 0 ? "登録済みサイトはありません" : "条件に一致するサイトはありません"}
                </td>
              </tr>
            )}
            {filteredSites.map((site) => {
              const projectInfo = siteToProject.get(site.id);
              return (
                <tr key={site.id} className="border-b border-neutral-100 odd:bg-neutral-50/50 last:border-0 hover:bg-neutral-100">
                  <td className="px-4 py-2 font-mono">{site.siteKey}</td>
                  <td className="px-4 py-2">{site.name}</td>
                  <td className="px-4 py-2">
                    <span
                      className={`inline-block rounded px-2 py-0.5 text-xs font-medium ${
                        site.cmsType === "WORDPRESS" ? "bg-blue-100 text-blue-700" : "bg-green-100 text-green-700"
                      }`}
                    >
                      {site.cmsType}
                    </span>
                    {site.managedWordpress && (
                      <span className="ml-1 inline-block rounded bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-700">
                        自動構築
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
                        <span className="text-neutral-700">{projectInfo.projectName}</span>
                      </span>
                    ) : (
                      <span className="inline-block rounded bg-neutral-100 px-2 py-0.5 text-xs font-medium text-neutral-400">
                        未紐付け
                      </span>
                    )}
                  </td>
                  <td className="max-w-xs truncate px-4 py-2" title={site.baseUrl}>
                    <a href={site.baseUrl} target="_blank" rel="noreferrer" className="text-blue-600 hover:underline">
                      {site.baseUrl}
                    </a>
                  </td>
                  <td className="px-4 py-2 text-neutral-500">{formatDateTime(site.createdAt, timezone)}</td>
                  <td className="px-4 py-2">
                    <CheckConnectionButton id={site.id} />
                  </td>
                  {isAdmin && (
                    <td className="px-4 py-2 text-right">
                      <div className="flex justify-end gap-3">
                        <Link href={`/sites/${site.id}/edit`} className="text-sm text-neutral-600 hover:underline">
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
