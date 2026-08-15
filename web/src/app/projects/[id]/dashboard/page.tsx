import { notFound } from "next/navigation";
import {
  getProject,
  getProjectGoogleAnalyticsReport,
  getProjectAdSenseReport,
  getProjectSocialStats,
  type GoogleAnalyticsReport,
  type AdSenseReport,
  type SocialStats,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { ProjectSectionNav } from "../ProjectSectionNav";
import { DashboardWidgetSlot } from "./DashboardWidgetSlot";
import { GoogleAnalyticsWidget } from "./GoogleAnalyticsWidget";
import { AdSenseWidget } from "./AdSenseWidget";
import { SocialStatsWidget } from "./SocialStatsWidget";

const NOT_ELIGIBLE_GA_REPORT: GoogleAnalyticsReport = {
  eligible: false,
  sessions: null,
  activeUsers: null,
  pageViews: null,
  periodLabel: null,
  errorMessage: null,
};

const NOT_ELIGIBLE_ADSENSE_REPORT: AdSenseReport = {
  eligible: false,
  estimatedEarnings: null,
  clicks: null,
  impressions: null,
  periodLabel: null,
  errorMessage: null,
};

const NOT_ELIGIBLE_SOCIAL_STATS: SocialStats = {
  eligible: false,
  postCount: null,
  likes: null,
  shares: null,
  comments: null,
  clicks: null,
  errorMessage: null,
};

export default async function ProjectDashboardPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const projectId = Number(id);

  const [project, gaReport, adsenseReport, socialStats] = await Promise.all([
    getProject(projectId, actor).catch(() => null),
    getProjectGoogleAnalyticsReport(projectId, actor).catch(() => NOT_ELIGIBLE_GA_REPORT),
    getProjectAdSenseReport(projectId, actor).catch(() => NOT_ELIGIBLE_ADSENSE_REPORT),
    getProjectSocialStats(projectId, actor).catch(() => NOT_ELIGIBLE_SOCIAL_STATS),
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
        <DashboardWidgetSlot
          title="Amazonアソシエイト"
          description="本番サイトのAmazonアソシエイト成果(クリック数・成約数・報酬額等)を表示します。"
          configured={false}
          settingsHref={`/projects/${projectId}/settings/amazon-associates`}
          settingsLabel="Amazonアソシエイトを設定"
        />
        <DashboardWidgetSlot
          title="ソーシャル統計"
          description="Buffer経由で送信済みの投稿のエンゲージメント統計(いいね・シェア・コメント・クリック)を表示します。
            Buffer連携が無効、または対象の送信済み投稿がまだない場合は表示されません(環境変数BUFFER_ENABLED/BUFFER_PROFILE_IDSで設定)。"
          configured={socialStats.eligible}
          settingsLabel="Buffer連携の設定が必要です"
        >
          <SocialStatsWidget stats={socialStats} />
        </DashboardWidgetSlot>
      </div>
    </div>
  );
}
