import { notFound } from "next/navigation";
import { getSiteDetail } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { SiteEditForm } from "./SiteEditForm";

export default async function SiteEditPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const { id } = await params;

  const site = await getSiteDetail(Number(id), actor).catch(() => null);
  if (!site) {
    notFound();
  }

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">サイト管理</h1>
      <SiteEditForm site={site} />
    </div>
  );
}
