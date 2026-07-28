import { listSites } from "@/lib/apiClient";
import { getSession } from "@/lib/session";
import { SiteCreationPanel } from "./SiteCreationPanel";
import { DeleteSiteButton } from "./DeleteSiteButton";

export default async function SitesPage() {
  const [sites, session] = await Promise.all([listSites().catch(() => []), getSession()]);
  const isAdmin = session?.user.role === "admin";

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">サイト</h1>

      <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 bg-neutral-50 text-neutral-500">
            <tr>
              <th className="px-4 py-2">サイトキー</th>
              <th className="px-4 py-2">表示名</th>
              <th className="px-4 py-2">CMS種別</th>
              <th className="px-4 py-2">URL</th>
              <th className="px-4 py-2">登録日</th>
              {isAdmin && <th className="px-4 py-2"></th>}
            </tr>
          </thead>
          <tbody>
            {sites.length === 0 && (
              <tr>
                <td colSpan={isAdmin ? 6 : 5} className="px-4 py-6 text-center text-neutral-600">
                  登録済みサイトはありません
                </td>
              </tr>
            )}
            {sites.map((site) => (
              <tr key={site.id} className="border-b border-neutral-100 last:border-0">
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
                  <a href={site.baseUrl} target="_blank" rel="noreferrer" className="text-blue-600 hover:underline">
                    {site.baseUrl}
                  </a>
                </td>
                <td className="px-4 py-2 text-neutral-500">{new Date(site.createdAt).toLocaleString("ja-JP")}</td>
                {isAdmin && (
                  <td className="px-4 py-2 text-right">
                    <DeleteSiteButton id={site.id} managedWordpress={site.managedWordpress} />
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <SiteCreationPanel />
    </div>
  );
}
