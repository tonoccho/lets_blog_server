import { Suspense } from "react";
import { notFound } from "next/navigation";
import {
  getProject,
  listProjectUsers,
  getProjectGoogleAnalyticsReport,
  getProjectAdSenseReport,
  type GoogleAnalyticsReport,
  type AdSenseReport,
  type ProjectUser,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { ProjectSectionNav } from "../ProjectSectionNav";
import { DashboardWidgetSlot } from "./DashboardWidgetSlot";
import { GoogleAnalyticsWidget } from "./GoogleAnalyticsWidget";
import { AdSenseWidget } from "./AdSenseWidget";
import { EnvironmentWidget } from "./EnvironmentWidget";
import { MembersWidget } from "./MembersWidget";
import { AiConnectionWidget, AiConnectionWidgetFallback } from "./AiConnectionWidget";

const NOT_ELIGIBLE_GA_REPORT: GoogleAnalyticsReport = {
  eligible: false,
  sessions: null,
  activeUsers: null,
  pageViews: null,
  periodLabel: null,
  errorMessage: null,
  dailyDataPoints: [],
  channelBreakdown: [],
};

const NOT_ELIGIBLE_ADSENSE_REPORT: AdSenseReport = {
  eligible: false,
  estimatedEarnings: null,
  clicks: null,
  impressions: null,
  periodLabel: null,
  errorMessage: null,
  dailyDataPoints: [],
  platformBreakdown: [],
};

export default async function ProjectDashboardPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  await requireAdminSession();
  const projectId = Number(id);

  function logAndFallback<T>(label: string, fallback: T) {
    return (err: unknown) => {
      console.error(`[projects/${projectId}/dashboard] ${label}の取得に失敗しました:`, err);
      return fallback;
    };
  }

  const [project, membersResult, gaReport, adsenseReport] = await Promise.all([
    getProject(projectId).catch(logAndFallback("プロジェクト情報", null)),
    // メンバーウィジェット。取得失敗を「0人」に見せないよう、失敗は別に持つ。
    listProjectUsers(projectId).then(
      (list) => ({ list, failed: false }),
      (err: unknown) => ({ list: logAndFallback<ProjectUser[]>("メンバー一覧", [])(err), failed: true }),
    ),
    getProjectGoogleAnalyticsReport(projectId).catch(() => NOT_ELIGIBLE_GA_REPORT),
    getProjectAdSenseReport(projectId).catch(() => NOT_ELIGIBLE_ADSENSE_REPORT),
  ]);
  if (!project) {
    notFound();
  }

  return (
    <div className="space-y-8">
      <Breadcrumb
        items={[
          { label: "ダッシュボード", href: "/" },
          { label: "プロジェクト", href: "/projects" },
          { label: project.name, href: `/projects/${projectId}` },
          { label: "ダッシュボード" },
        ]}
      />
      <div>
        <h1 className="text-xl font-semibold">{project.name} — ダッシュボード</h1>
      </div>

      <ProjectSectionNav projectId={projectId} active="dashboard" />

      <EnvironmentWidget project={project} />

      <MembersWidget projectId={projectId} members={membersResult.list} fetchFailed={membersResult.failed} />

      {/* 疎通確認で数秒かかりうるため、Promise.all に入れず Suspense の内側で取得する。 */}
      <Suspense fallback={<AiConnectionWidgetFallback />}>
        <AiConnectionWidget projectId={projectId} />
      </Suspense>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <DashboardWidgetSlot
          title="Google Analytics"
          description="本番サイトのアクセス状況(セッション数・ユーザー数・ページビュー等)を表示します。"
          configured={gaReport.eligible}
          settingsHref={`/projects/${projectId}/settings/google-analytics`}
          settingsLabel="Google Analyticsを設定"
        >
          <GoogleAnalyticsWidget report={gaReport} />
        </DashboardWidgetSlot>
        <DashboardWidgetSlot
          title="Google AdSense"
          description="本番サイトの広告収益レポート(推定収益・クリック数・表示回数等)を表示します。"
          configured={adsenseReport.eligible}
          settingsHref={`/projects/${projectId}/settings/adsense`}
          settingsLabel="Google AdSenseを設定"
        >
          <AdSenseWidget report={adsenseReport} />
        </DashboardWidgetSlot>
      </div>
    </div>
  );
}
