import { getSiteAdminPath, listSites, listProjects, listUsers, listSshKeyPairs } from "@/lib/apiClient";
import { loadOrReport, failedLabels } from "@/lib/loadOrReport";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";
import { requireSession, getViewerTimeZone } from "@/lib/session";
import { SiteCreationPanel } from "./SiteCreationPanel";
import { SiteListTable } from "./SiteListTable";

export default async function SitesPage() {
  // セッションが更新不能なときはここで /login へリダイレクトする(issue #1234)。
  // 以前はgetSession()でsession.errorを見ておらず、リロードしないと再ログイン画面へ
  // 遷移できなかった。
  const session = await requireSession();
  const [sites, projects, users, timezone, adminPath] = await Promise.all([
    loadOrReport("sites", "サイト一覧", listSites(), []),
    loadOrReport("sites", "プロジェクト一覧", listProjects(), []),
    loadOrReport("sites", "ユーザー一覧", listUsers(), []),
    getViewerTimeZone(),
    loadOrReport("sites", "管理画面パス", getSiteAdminPath().then((r) => r.path), "wp-admin"),
  ]);
  const isAdmin = session?.user.role === "admin";
  const sshKeyPairs = isAdmin
    ? await loadOrReport("sites", "SSH鍵一覧", listSshKeyPairs(), [])
    : { data: [], failed: false, label: "SSH鍵一覧" };

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">サイト</h1>

      <FetchErrorNotice labels={failedLabels(sites, projects, users, sshKeyPairs)} />

      {!sites.failed && (
        <SiteListTable sites={sites.data} projects={projects.data} isAdmin={isAdmin} timezone={timezone} adminPath={adminPath.data} />
      )}

      {/*
        サイト登録は admin 限定(issue #824 で registerSiteAction /
        createManagedWordPressSiteAction に requireAdminSession() を追加した)。
        ガードを付けないと、非 admin にはフォームが見えるのに送信すると
        黙ってトップページへリダイレクトされる行き止まりになる
        (useActionState 経由なのでエラー表示も出ない)。
      */}
      {isAdmin && !users.failed && !sites.failed && !sshKeyPairs.failed && (
        <div id="site-creation">
          <SiteCreationPanel users={users.data} sites={sites.data} sshKeyPairs={sshKeyPairs.data} defaultAdminPath={adminPath.data} />
        </div>
      )}
    </div>
  );
}
