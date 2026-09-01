import { listSites, listProjects, listUsers, listSshKeyPairs } from "@/lib/apiClient";
import { getSession, getViewerTimeZone } from "@/lib/session";
import { SiteCreationPanel } from "./SiteCreationPanel";
import { SiteListTable } from "./SiteListTable";

export default async function SitesPage() {
  const [sites, projects, users, session, timezone] = await Promise.all([
    listSites().catch(() => []),
    listProjects().catch(() => []),
    listUsers().catch(() => []),
    getSession(),
    getViewerTimeZone(),
  ]);
  const isAdmin = session?.user.role === "admin";
  const sshKeyPairs = isAdmin ? await listSshKeyPairs().catch(() => []) : [];

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">サイト</h1>

      <SiteListTable sites={sites} projects={projects} isAdmin={isAdmin} timezone={timezone} />

      {/*
        サイト登録は admin 限定(issue #824 で registerSiteAction /
        createManagedWordPressSiteAction に requireAdminSession() を追加した)。
        ガードを付けないと、非 admin にはフォームが見えるのに送信すると
        黙ってトップページへリダイレクトされる行き止まりになる
        (useActionState 経由なのでエラー表示も出ない)。
      */}
      {isAdmin && (
        <div id="site-creation">
          <SiteCreationPanel users={users} sites={sites} sshKeyPairs={sshKeyPairs} />
        </div>
      )}
    </div>
  );
}
