import { notFound } from "next/navigation";
import {
  getProject,
  getProjectFacebookConnection,
  getProjectFacebookPages,
  getProjectHatenaConnection,
  getProjectLinkedInConnection,
  getProjectPvRules,
  getProjectSnsTemplates,
  getProjectThreadsConnection,
  getProjectXConnection,
  type PvRulesView,
  type SnsTemplatesView,
  type XConnectionView,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { ProjectPvRulesSection } from "../../ProjectPvRulesSection";
import { ProjectSnsFacebookSection } from "../../ProjectSnsFacebookSection";
import { ProjectSnsHatenaSection } from "../../ProjectSnsHatenaSection";
import { ProjectSnsLinkedInSection } from "../../ProjectSnsLinkedInSection";
import { ProjectSnsTemplatesSection } from "../../ProjectSnsTemplatesSection";
import { ProjectSnsThreadsSection } from "../../ProjectSnsThreadsSection";
import { ProjectSnsXSection } from "../../ProjectSnsXSection";

export default async function ProjectSnsSettingsPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ connected?: string; error?: string; sns?: string; facebookState?: string }>;
}) {
  const { id } = await params;
  const { connected, error, sns, facebookState } = await searchParams;
  await requireAdminSession();
  const projectId = Number(id);

  // 接続状態の取得に失敗しても画面全体は落とさず、欄の側で「取得できない」と示す。
  const [project, view, threadsView, facebookView, linkedinView, hatenaView, pvView, templatesView] = await Promise.all([
    getProject(projectId).catch(() => null),
    getProjectXConnection(projectId).catch((): XConnectionView | null => null),
    getProjectThreadsConnection(projectId).catch((): XConnectionView | null => null),
    getProjectFacebookConnection(projectId).catch((): XConnectionView | null => null),
    getProjectLinkedInConnection(projectId).catch((): XConnectionView | null => null),
    getProjectHatenaConnection(projectId).catch((): XConnectionView | null => null),
    getProjectPvRules(projectId).catch((): PvRulesView | null => null),
    getProjectSnsTemplates(projectId).catch((): SnsTemplatesView | null => null),
  ]);
  if (!project) {
    notFound();
  }

  // Facebook の認可から戻った直後は、投稿先に選べるページを取って選択欄を出す。取れなければ(期限切れ等)理由を Facebook 欄に出す。
  let facebookPageSelection: { state: string; pages: { id: string; name: string }[] } | null = null;
  let facebookPagesError: string | undefined;
  if (facebookState) {
    try {
      const { pages } = await getProjectFacebookPages(projectId, facebookState);
      facebookPageSelection = { state: facebookState, pages };
    } catch (err) {
      facebookPagesError = err instanceof Error ? err.message : String(err);
    }
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
        errorBanner={sns === "threads" || sns === "facebook" || sns === "linkedin" || sns === "hatena" ? undefined : error}
      />

      <ProjectSnsThreadsSection
        projectId={projectId}
        view={threadsView}
        callbackUrl={`${process.env.NEXTAUTH_URL}/connect/threads/callback`}
        connectedBanner={connected === "threads"}
        errorBanner={sns === "threads" ? error : undefined}
      />

      <ProjectSnsFacebookSection
        projectId={projectId}
        view={facebookView}
        callbackUrl={`${process.env.NEXTAUTH_URL}/connect/facebook/callback`}
        connectedBanner={connected === "facebook"}
        errorBanner={sns === "facebook" ? error : facebookPagesError}
        pageSelection={facebookPageSelection}
      />

      <ProjectSnsLinkedInSection
        projectId={projectId}
        view={linkedinView}
        callbackUrl={`${process.env.NEXTAUTH_URL}/connect/linkedin/callback`}
        connectedBanner={connected === "linkedin"}
        errorBanner={sns === "linkedin" ? error : undefined}
      />

      <ProjectSnsHatenaSection
        projectId={projectId}
        view={hatenaView}
        callbackUrl={`${process.env.NEXTAUTH_URL}/connect/hatena/callback`}
        connectedBanner={connected === "hatena"}
        errorBanner={sns === "hatena" ? error : undefined}
      />

      <ProjectPvRulesSection projectId={projectId} view={pvView} />

      <ProjectSnsTemplatesSection projectId={projectId} view={templatesView} />
    </div>
  );
}
