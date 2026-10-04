import Link from "next/link";
import {
  listSites,
  listPosts,
  listUnifiedOperationLogs,
  getConnectedServiceStatuses,
  getConnectedServiceStatusDetail,
  getContainerStatuses,
} from "@/lib/apiClient";
import { loadOrReport, failedLabels } from "@/lib/loadOrReport";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";
import { requireSession, getViewerTimeZone } from "@/lib/session";
import { ConnectedServiceStatusPanel } from "./ConnectedServiceStatusPanel";
import { ContainerStatusPanel } from "./ContainerStatusPanel";

export default async function DashboardPage() {
  // セッションが更新不能(session.error === "RefreshAccessTokenError")なときはここで
  // /login へリダイレクトする(issue #1234)。以前はgetSession()でsession.errorを見ずに
  // 描画していたため、リロードしないと再ログイン画面へ遷移できなかった。
  const session = await requireSession();
  const isAdmin = session?.user.role === "admin";

  // 取得失敗を「0件」に見せない(issue #1235)。失敗した項目はカードを「-」にし、通知を出す。
  const [sites, posts, jobLogs, serviceStatuses, serviceStatusDetail, containerStatuses, personalTimeZone] =
    await Promise.all([
      loadOrReport("dashboard", "サイト一覧", listSites(), []),
      loadOrReport("dashboard", "投稿一覧", listPosts(), []),
      // リンク先(/operation-logs?type=AI_JOB)と同じ統合操作ログのAI_JOB絞り込みから件数を取る(issue #1595)。
      loadOrReport(
        "dashboard",
        "AIジョブ件数",
        listUnifiedOperationLogs({ type: "AI_JOB", page: 0, size: 1 }).then((r) => r.totalElements),
        0,
      ),
      loadOrReport("dashboard", "連携サービスの状態", getConnectedServiceStatuses(), []),
      isAdmin
        ? loadOrReport("dashboard", "連携サービスの状態詳細", getConnectedServiceStatusDetail(), null)
        : Promise.resolve({ data: null, failed: false, label: "連携サービスの状態詳細" }),
      loadOrReport("dashboard", "コンテナの状態", getContainerStatuses(), []),
      getViewerTimeZone(),
    ]);

  const cards = [
    { label: "登録サイト数", result: sites, count: sites.data.length, href: "/sites" },
    { label: "投稿数", result: posts, count: posts.data.length, href: "/posts" },
    { label: "AIジョブ数", result: jobLogs, count: jobLogs.data, href: "/operation-logs?type=AI_JOB" },
  ];

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">ダッシュボード</h1>
      <FetchErrorNotice labels={failedLabels(sites, posts, jobLogs, serviceStatuses, serviceStatusDetail, containerStatuses)} />
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        {cards.map((card) => (
          <Link
            key={card.href}
            href={card.href}
            className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5 shadow-sm hover:shadow focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
          >
            <div className="text-sm text-neutral-700 dark:text-neutral-300">{card.label}</div>
            <div className="mt-1 text-3xl font-semibold">{card.result.failed ? "-" : card.count}</div>
          </Link>
        ))}
      </div>
      <ConnectedServiceStatusPanel
        initialStatuses={serviceStatuses.data}
        initialDetail={serviceStatusDetail.data}
        personalTimeZone={personalTimeZone}
      />
      <ContainerStatusPanel initialStatuses={containerStatuses.data} personalTimeZone={personalTimeZone} />
    </div>
  );
}
