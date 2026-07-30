import { notFound } from "next/navigation";
import { listSites } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { SiteEditForm } from "./SiteEditForm";

export default async function SiteEditPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  await requireAdminSession();
  const { id } = await params;

  const sites = await listSites().catch(() => []);
  const site = sites.find((s) => s.id === Number(id));
  if (!site) {
    notFound();
  }

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">サイト編集</h1>
      <SiteEditForm site={site} />
    </div>
  );
}
