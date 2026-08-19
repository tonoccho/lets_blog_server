import { notFound } from "next/navigation";
import { getSiteDetail, listSshKeyPairs, listStaticContent } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { SiteEditForm } from "./SiteEditForm";
import { StaticContentPanel } from "./StaticContentPanel";

export default async function SiteEditPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const { id } = await params;

  const [site, sshKeyPairs, staticContents] = await Promise.all([
    getSiteDetail(Number(id), actor).catch(() => null),
    listSshKeyPairs(actor).catch(() => []),
    listStaticContent(Number(id), actor).catch(() => []),
  ]);
  if (!site) {
    notFound();
  }

  return (
    <div className="space-y-8">
      <Breadcrumb
        items={[
          { label: "ダッシュボード", href: "/" },
          { label: "サイト", href: "/sites" },
          { label: site.name },
        ]}
      />
      <h1 className="text-xl font-semibold">サイト管理</h1>
      <SiteEditForm site={site} sshKeyPairs={sshKeyPairs} />
      <StaticContentPanel siteId={site.id} initialContents={staticContents} />
    </div>
  );
}
