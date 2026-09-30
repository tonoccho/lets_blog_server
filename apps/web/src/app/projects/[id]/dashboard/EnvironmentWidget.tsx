import type { Project, Site } from "@/lib/apiClient";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";
import { EnvironmentSlot } from "../EnvironmentSlot";

/**
 * ダッシュボードの「環境設定」ウィジェット(issue #1500)。
 * 詳細「概要」タブと同じ `EnvironmentSlot` を使うため、どちらからでも紐付け・切離しができる。
 */
export function EnvironmentWidget({
  project,
  candidateSites,
  sitesError = false,
}: {
  project: Project;
  candidateSites: Site[];
  /** サイト一覧の取得に失敗したとき true。「候補サイトなし」と区別して失敗を示す。 */
  sitesError?: boolean;
}) {
  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5 space-y-3">
      <h2 className="font-medium">環境設定</h2>
      <FetchErrorNotice labels={sitesError ? ["サイト一覧"] : []} />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <EnvironmentSlot projectId={project.id} environment="local" site={project.localSite} candidateSites={candidateSites} />
        <EnvironmentSlot projectId={project.id} environment="test" site={project.testSite} candidateSites={candidateSites} />
        <EnvironmentSlot
          projectId={project.id}
          environment="production"
          site={project.productionSite}
          candidateSites={candidateSites}
        />
      </div>
    </div>
  );
}
