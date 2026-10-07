import { ExternalLink, Settings } from "lucide-react";
import type { Project, ProjectEnvironment, Site } from "@/lib/apiClient";
import { resolveSiteAdminUrl } from "@/lib/siteAdminUrl";
import { CheckConnectionButton } from "@/app/sites/CheckConnectionButton";

const ENVIRONMENT_LABEL: Record<ProjectEnvironment, string> = {
  local: "ローカル",
  test: "テスト",
  production: "本番",
};

const LINK_CLASS =
  "text-blue-700 hover:text-blue-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500";

/**
 * ダッシュボードの「環境設定」ウィジェット(issue #1500 → #1671 で表示専用に変更)。
 * 紐付け・切離しは詳細「概要」タブの `EnvironmentSlot` で行う。ここでは状況を見るだけで、
 * 紐付け済みの環境ごとの「疎通確認」ボタンを押したときだけ接続を確かめる。
 */
function EnvironmentDisplay({ environment, site }: { environment: ProjectEnvironment; site: Site | null }) {
  const adminUrl = site ? resolveSiteAdminUrl(site.baseUrl, "wp-admin", site.adminPath) : null;

  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4">
      <h3 className="mb-2 font-medium">{ENVIRONMENT_LABEL[environment]}環境</h3>
      {site ? (
        <div className="space-y-2 text-sm">
          <p className="font-mono">{site.siteKey}</p>
          <p className="text-neutral-600 dark:text-neutral-400">{site.name}</p>
          <span className="inline-flex items-center gap-3">
            <a
              href={site.baseUrl}
              title={site.baseUrl}
              target="_blank"
              rel="noreferrer"
              aria-label={`${site.name} のサイトを開く`}
              className={LINK_CLASS}
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
                className={LINK_CLASS}
              >
                <Settings className="h-4 w-4" aria-hidden="true" />
              </a>
            )}
          </span>
          <CheckConnectionButton id={site.id} />
        </div>
      ) : (
        <p className="text-sm font-medium text-neutral-500 dark:text-neutral-400">未設定</p>
      )}
    </div>
  );
}

export function EnvironmentWidget({ project }: { project: Project }) {
  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5 space-y-3">
      <h2 className="font-medium">環境設定</h2>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <EnvironmentDisplay environment="local" site={project.localSite} />
        <EnvironmentDisplay environment="test" site={project.testSite} />
        <EnvironmentDisplay environment="production" site={project.productionSite} />
      </div>
    </div>
  );
}
