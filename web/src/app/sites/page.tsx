import { listSites } from "@/lib/apiClient";
import { SiteForm } from "./SiteForm";

export default async function SitesPage() {
  const sites = await listSites().catch(() => []);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">サイト</h1>

      <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 bg-neutral-50 text-neutral-500">
            <tr>
              <th className="px-4 py-2">サイトキー</th>
              <th className="px-4 py-2">表示名</th>
              <th className="px-4 py-2">URL</th>
              <th className="px-4 py-2">ユーザー名</th>
              <th className="px-4 py-2">登録日</th>
            </tr>
          </thead>
          <tbody>
            {sites.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-6 text-center text-neutral-400">
                  登録済みサイトはありません
                </td>
              </tr>
            )}
            {sites.map((site) => (
              <tr key={site.id} className="border-b border-neutral-100 last:border-0">
                <td className="px-4 py-2 font-mono">{site.siteKey}</td>
                <td className="px-4 py-2">{site.name}</td>
                <td className="px-4 py-2">
                  <a href={site.baseUrl} target="_blank" rel="noreferrer" className="text-blue-600 hover:underline">
                    {site.baseUrl}
                  </a>
                </td>
                <td className="px-4 py-2">{site.wpUsername}</td>
                <td className="px-4 py-2 text-neutral-500">{new Date(site.createdAt).toLocaleString("ja-JP")}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <SiteForm />
    </div>
  );
}
