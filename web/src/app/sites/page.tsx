import { listSites, listProjects, listUsers } from "@/lib/apiClient";
import { getSession } from "@/lib/session";
import { SiteCreationPanel } from "./SiteCreationPanel";
import { SiteListTable } from "./SiteListTable";

export default async function SitesPage() {
  const [sites, projects, users, session] = await Promise.all([
    listSites().catch(() => []),
    listProjects().catch(() => []),
    listUsers().catch(() => []),
    getSession(),
  ]);
  const isAdmin = session?.user.role === "admin";

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">サイト</h1>

      <SiteListTable sites={sites} projects={projects} isAdmin={isAdmin} />

      <SiteCreationPanel users={users} sites={sites} />
    </div>
  );
}
