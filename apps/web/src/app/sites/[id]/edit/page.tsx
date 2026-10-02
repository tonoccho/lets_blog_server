import { notFound } from "next/navigation";
import { getSiteAdminPath, getSiteDetail, listSshKeyPairs, listStaticContent } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { loadOrReport, failedLabels } from "@/lib/loadOrReport";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";
import { Breadcrumb } from "@/components/Breadcrumb";
import { SiteEditForm } from "./SiteEditForm";
import { StaticContentPanel } from "./StaticContentPanel";

export default async function SiteEditPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  await requireAdminSession();
  const { id } = await params;

  // 404だけを「存在しない」として扱い、それ以外の失敗は notFound() にせず通知する(issue #1235)。
  const scope = `sites/${id}/edit`;
  const [siteResult, sshKeyPairs, staticContents, defaultAdminPath] = await Promise.all([
    loadOrReport(scope, "サイト情報", getSiteDetail(Number(id)), null, { notFoundIsEmpty: true }),
    loadOrReport(scope, "SSH鍵一覧", listSshKeyPairs(), []),
    loadOrReport(scope, "静的コンテンツ一覧", listStaticContent(Number(id)), []),
    // プレースホルダ表示用。取得失敗でも編集自体は妨げない(通知だけ出す)。
    loadOrReport(scope, "管理画面パスの既定値", getSiteAdminPath(), null),
  ]);
  if (siteResult.failed) {
    return (
      <div className="space-y-8">
        <FetchErrorNotice labels={failedLabels(siteResult)} />
      </div>
    );
  }
  const site = siteResult.data;
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
      <FetchErrorNotice labels={failedLabels(sshKeyPairs, staticContents, defaultAdminPath)} />
      {/* 選択肢が空のフォームを保存すると鍵の紐付けを外しかねないため、取得失敗時は描画しない */}
      {!sshKeyPairs.failed && (
        <SiteEditForm site={site} sshKeyPairs={sshKeyPairs.data} defaultAdminPath={defaultAdminPath.data?.path ?? null} />
      )}
      {!staticContents.failed && <StaticContentPanel siteId={site.id} initialContents={staticContents.data} />}
    </div>
  );
}
