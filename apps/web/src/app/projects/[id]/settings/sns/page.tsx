import { notFound } from "next/navigation";
import {
  getProject,
  getProjectPvRules,
  getProjectThreadsConnection,
  getProjectXConnection,
  type PvRulesView,
  type XConnectionView,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { ProjectPvRulesSection } from "../../ProjectPvRulesSection";
import { ProjectSnsThreadsSection } from "../../ProjectSnsThreadsSection";
import { ProjectSnsXSection } from "../../ProjectSnsXSection";

export default async function ProjectSnsSettingsPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ connected?: string; error?: string; sns?: string }>;
}) {
  const { id } = await params;
  const { connected, error, sns } = await searchParams;
  await requireAdminSession();
  const projectId = Number(id);

  // 接続状態の取得に失敗しても画面全体は落とさず、欄の側で「取得できない」と示す。
  const [project, view, threadsView, pvView] = await Promise.all([
    getProject(projectId).catch(() => null),
    getProjectXConnection(projectId).catch((): XConnectionView | null => null),
    getProjectThreadsConnection(projectId).catch((): XConnectionView | null => null),
    getProjectPvRules(projectId).catch((): PvRulesView | null => null),
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
          { label: "SNS 告知設定" },
        ]}
      />
      <div>
        <h1 className="text-xl font-semibold">{project.name} — SNS 告知設定</h1>
      </div>

      <ProjectSnsXSection
        projectId={projectId}
        view={view}
        callbackUrl={`${process.env.NEXTAUTH_URL}/connect/x/callback`}
        connectedBanner={connected === "1"}
        errorBanner={sns === "threads" ? undefined : error}
      />

      <ProjectSnsThreadsSection
        projectId={projectId}
        view={threadsView}
        callbackUrl={`${process.env.NEXTAUTH_URL}/connect/threads/callback`}
        connectedBanner={connected === "threads"}
        errorBanner={sns === "threads" ? error : undefined}
      />

      <ProjectPvRulesSection projectId={projectId} view={pvView} />
    </div>
  );
}
