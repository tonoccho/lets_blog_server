import { notFound } from "next/navigation";
import { getSiteAdminPath, getSiteDetail, listSshKeyPairs, listStaticContent } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { loadLlmJobResult, readStaticContentJobResult } from "@/lib/llmJobResults";
import { STATIC_CONTENT_GENERATION_JOB_TYPE } from "@/app/infoRailQueue";
import { loadOrReport, failedLabels } from "@/lib/loadOrReport";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";
import { Breadcrumb } from "@/components/Breadcrumb";
import { SiteEditForm } from "./SiteEditForm";
import { LetsblogPluginPanel } from "./LetsblogPluginPanel";
import { LetsblogSyncPanel } from "./LetsblogSyncPanel";
import { StaticContentPanel } from "./StaticContentPanel";

export default async function SiteEditPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  /** `?staticContentJob=` は処理キューの「結果を見る」の遷移先で(issue #1409)、静的コンテンツ生成ジョブの結果(未保存)を表示する。 */
  searchParams?: Promise<{ staticContentJob?: string }>;
}) {
  await requireAdminSession();
  const { id } = await params;
  const { staticContentJob } = (await searchParams) ?? {};

  // 404だけを「存在しない」として扱い、それ以外の失敗は notFound() にせず通知する(issue #1235)。
  const scope = `sites/${id}/edit`;
  const [siteResult, sshKeyPairs, staticContents, defaultAdminPath, staticContentResult] = await Promise.all([
    loadOrReport(scope, "サイト情報", getSiteDetail(Number(id)), null, { notFoundIsEmpty: true }),
    loadOrReport(scope, "SSH鍵一覧", listSshKeyPairs(), []),
    loadOrReport(scope, "静的コンテンツ一覧", listStaticContent(Number(id)), []),
    // プレースホルダ表示用。取得失敗でも編集自体は妨げない(通知だけ出す)。
    loadOrReport(scope, "管理画面パスの既定値", getSiteAdminPath(), null),
    loadLlmJobResult(staticContentJob, STATIC_CONTENT_GENERATION_JOB_TYPE, readStaticContentJobResult),
  ]);
  // 別のサイトのジョブの結果は、このサイトの画面には出さない。
  const generatedStaticContent = staticContentResult?.siteId === Number(id) ? staticContentResult : null;
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
      <LetsblogPluginPanel siteId={site.id} />
      <LetsblogSyncPanel siteId={site.id} />
      {!staticContents.failed && (
        <StaticContentPanel
          siteId={site.id}
          initialContents={staticContents.data}
          generatedResult={generatedStaticContent}
        />
      )}
    </div>
  );
}
