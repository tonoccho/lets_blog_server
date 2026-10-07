import Link from "next/link";
import { notFound } from "next/navigation";
import {
  getProject,
  listSites,
  listProjectUsers,
  listUsers,
  listCategoryComparison,
  getProjectGithubTokenStatus,
  getProjectBraveSearchApiKeyStatus,
  getProjectImageSettings,
  getSiteAdminPath,
  type ProjectUser,
} from "@/lib/apiClient";
import { requireAdminSession, getViewerTimeZone } from "@/lib/session";
import { loadOrReport, failedLabels } from "@/lib/loadOrReport";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";
import { Breadcrumb } from "@/components/Breadcrumb";
import { Tabs, type TabItem } from "@/components/Tabs";
import { ProjectSectionNav } from "../ProjectSectionNav";
import { EnvironmentSlot } from "../EnvironmentSlot";
import { MasterEnvironmentSelector } from "../MasterEnvironmentSelector";
import { ProjectGithubRepositoryForm } from "../ProjectGithubRepositoryForm";
import { ProjectApiKeysForm } from "../ProjectApiKeysForm";
import { EnvironmentSyncPanel } from "../EnvironmentSyncPanel";
import { BulkManagementPanel } from "../BulkManagementPanel";
import { GarbageCollectionPanel } from "../GarbageCollectionPanel";
import { ProjectAiModelsPanel } from "../ProjectAiModelsPanel";
import { ProjectAssetGenerationPanel } from "../ProjectAssetGenerationPanel";
import { ProjectImageGenerationPromptDefaultsForm } from "../ProjectImageGenerationPromptDefaultsForm";
import { ProjectImageGenerationSizeDefaultsForm } from "../ProjectImageGenerationSizeDefaultsForm";
import { ProjectArticleImageResizeDefaultForm } from "../ProjectArticleImageResizeDefaultForm";
import { ProjectImageContentFilterSettingsForm } from "../ProjectImageContentFilterSettingsForm";
import { ProjectNameForm } from "../ProjectNameForm";
import { DeleteProjectButton } from "../DeleteProjectButton";
import { AiConnectionSection } from "../AiConnectionSection";
import { ChatGptConnectionSection } from "../ChatGptConnectionSection";
import { ClaudeConnectionSection } from "../ClaudeConnectionSection";
import { ProjectUserManager } from "../ProjectUserManager";
import { AddProjectUserModal } from "../AddProjectUserModal";

/**
 * 以前のタブ id を今のタブ id へ読み替える(既存の `?tab=` のリンクを壊さない, issue #1669)。
 * 一括管理とガベージコレクションはメンテナンスタブにまとめた。
 */
const LEGACY_TAB_IDS: Record<string, string> = {
  "bulk-management": "maintenance",
  "garbage-collection": "maintenance",
};

/** 設定タブから辿る、プロジェクト単位の外部サービス設定ページ(`/projects/[id]/settings/<path>`)。 */
const EXTERNAL_SETTINGS_LINKS = [
  { path: "sns", label: "SNS 告知の設定" },
  { path: "google-analytics", label: "Google Analytics の設定" },
  { path: "adsense", label: "Google AdSense の設定" },
];

export default async function ProjectDetailPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  /**
   * `?tab=members` で初期選択タブを指定できる(ダッシュボードのメンバーウィジェットからの遷移先)。
   * 旧タブ id の `bulk-management` / `garbage-collection` は `maintenance` を開く(issue #1669)。
   * `?imageJob=<ジョブID>` を付けると、アセット画像生成パネルがそのジョブの生成結果を開いた状態で
   * 表示する(処理キューの画像生成ジョブの「結果を見る」の遷移先、issue #1408)。
   */
  searchParams?: Promise<{ tab?: string; imageJob?: string }>;
}) {
  const { id } = await params;
  const { tab: requestedTabId, imageJob } = (await searchParams) ?? {};
  const initialTabId = requestedTabId === undefined ? undefined : (LEGACY_TAB_IDS[requestedTabId] ?? requestedTabId);
  const imageJobId = Number(imageJob);
  await requireAdminSession();
  const projectId = Number(id);

  const emptyComparisonPage = { items: [], page: 0, size: 20, totalCount: 0, masterEnvironment: "test" as const };

  // 画像生成設定の取得に失敗したときのフォールバック。全項目 null = 「プロジェクト単位の上書き
  // なし」で、フォームはアプリ全体の既定値のプレースホルダを出す(issue #913)。
  const EMPTY_IMAGE_SETTINGS = {
    projectId,
    imageProvider: null,
    comfyuiCheckpoint: null,
    defaultNegativePrompt: null,
    defaultQualityPrompt: null,
    defaultGeneratedImageWidth: null,
    defaultGeneratedImageHeight: null,
    defaultArticleImageLongEdgePx: null,
    blockSexualContent: null,
    blockViolentContent: null,
    blockDiscriminatoryContent: null,
  };

  const scope = `projects/${projectId}`;

  // タグ・プラグイン・テーマは一括管理パネルでタブを開いたときにクライアント側から遅延取得する
  // (初期表示で4種類すべて並行取得すると、同一ホストのSSH接続が集中しやすいため)。
  const [
    projectResult,
    sitesResult,
    membersResult,
    allUsersResult,
    categoryPageResult,
    timezone,
    githubTokenStatusResult,
    braveSearchApiKeyStatusResult,
    imageSettingsResult,
    adminPathResult,
  ] = await Promise.all([
    loadOrReport(scope, "プロジェクト情報", getProject(projectId), null),
    loadOrReport(scope, "サイト一覧", listSites(), []),
    // 取得失敗を空配列に潰すと「メンバーが居ない」と区別できず、誤った案内を出す(issue #1069)。
    // 失敗したことを別に持ち、0人の案内を出さないようにする。
    loadOrReport(scope, "プロジェクトメンバー", listProjectUsers(projectId), [] as ProjectUser[]),
    loadOrReport(scope, "ユーザー一覧", listUsers(), []),
    loadOrReport(scope, "カテゴリ比較", listCategoryComparison(projectId, 0), emptyComparisonPage),
    getViewerTimeZone(),
    loadOrReport(scope, "GitHubトークン設定状況", getProjectGithubTokenStatus(projectId), { configured: false }),
    loadOrReport(scope, "Brave APIキー設定状況", getProjectBraveSearchApiKeyStatus(projectId), { configured: false }),
    // 画像生成設定は media-service が所有する(issue #583)。GET /api/projects/{id} には含まれない
    // ため個別に取得する。以前はここを取得しておらず、保存できるのに画面には常に空が
    // 表示されていた(issue #913)。取得に失敗しても画面全体は落とさない。
    loadOrReport(scope, "画像生成設定", getProjectImageSettings(projectId), EMPTY_IMAGE_SETTINGS),
    // 環境スロットの管理画面リンク用。取得に失敗しても画面は落とさず wp-admin にフォールバックする(issue #1530)。
    loadOrReport(scope, "管理画面パス", getSiteAdminPath().then((r) => r.path), "wp-admin"),
  ]);
  const project = projectResult.data;
  const sites = sitesResult.data;
  const allUsers = allUsersResult.data;
  const categoryPage = categoryPageResult.data;
  const githubTokenStatus = githubTokenStatusResult.data;
  const braveSearchApiKeyStatus = braveSearchApiKeyStatusResult.data;
  const imageSettings = imageSettingsResult.data;
  const adminPath = adminPathResult.data;
  // メンバーの取得失敗は専用の文言で示すので、共通の通知からは外す(重複させない)。
  const failedFetchLabels = failedLabels(
    sitesResult,
    allUsersResult,
    categoryPageResult,
    githubTokenStatusResult,
    braveSearchApiKeyStatusResult,
    imageSettingsResult,
  );

  if (!project) {
    notFound();
  }

  const { data: members, failed: membersFetchFailed } = membersResult;
  const hasBoundSite = Boolean(project.localSite || project.testSite || project.productionSite);
  const candidateUsers = allUsers.filter((user) => !members.some((member) => member.userId === user.id));

  const tabs: TabItem[] = [
    {
      id: "overview",
      label: "概要",
      content: (
        <div className="space-y-6">
          <div>
            <h2 className="text-sm font-semibold mb-3">環境設定</h2>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <EnvironmentSlot
                projectId={project.id}
                environment="local"
                site={project.localSite}
                candidateSites={sites}
                adminPath={adminPath}
              />
              <EnvironmentSlot
                projectId={project.id}
                environment="test"
                site={project.testSite}
                candidateSites={sites}
                adminPath={adminPath}
              />
              <EnvironmentSlot
                projectId={project.id}
                environment="production"
                site={project.productionSite}
                candidateSites={sites}
                adminPath={adminPath}
              />
            </div>
          </div>

          <MasterEnvironmentSelector projectId={project.id} project={project} />

          {membersFetchFailed ? (
            <p className="text-sm text-red-600">
              メンバー情報を取得できませんでした。時間をおいて画面を再読み込みしてください。
            </p>
          ) : members.length === 0 && !hasBoundSite ? (
            <p className="text-sm text-neutral-600 dark:text-neutral-400">
              サイトが紐付いていません。メンバーを追加する前に、先にサイトを紐付けてください
              (サイトの紐付け前に追加したメンバーには WordPress ユーザーが作られません)。
            </p>
          ) : members.length === 0 ? (
            <div className="space-y-3">
              <p className="text-sm text-neutral-600 dark:text-neutral-400">
                このプロジェクトにはメンバーがいません。メンバータブからユーザーを追加すると、紐付いたサイトの WordPress にも作成されます。
              </p>
              <Link href={`/projects/${project.id}?tab=members`} className="text-sm underline">
                メンバータブ
              </Link>
            </div>
          ) : null}
        </div>
      ),
    },
    {
      id: "settings",
      label: "設定",
      content: (
        <div className="space-y-6">
          <ProjectNameForm projectId={project.id} name={project.name} />
          <EnvironmentSyncPanel projectId={project.id} project={project} />
          <ProjectGithubRepositoryForm projectId={project.id} githubRepository={project.githubRepository} />
          <ProjectApiKeysForm
            projectId={project.id}
            githubTokenConfigured={githubTokenStatus.configured}
            braveSearchApiKeyConfigured={braveSearchApiKeyStatus.configured}
          />
          <div className="space-y-4">
            <h2 className="text-sm font-semibold">AI接続</h2>
            <AiConnectionSection projectId={project.id} provider="OLLAMA" />
            <AiConnectionSection projectId={project.id} provider="COMFYUI" />
            <ChatGptConnectionSection projectId={project.id} />
            <ClaudeConnectionSection projectId={project.id} />
          </div>
          <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
            <h2 className="font-medium">外部サービス連携</h2>
            <p className="mt-1 text-sm text-neutral-600 dark:text-neutral-400">
              SNS 告知・Google Analytics・Google AdSense の接続は、それぞれの設定ページで行います。
            </p>
            <ul className="mt-2 space-y-1 text-sm">
              {EXTERNAL_SETTINGS_LINKS.map((link) => (
                <li key={link.path}>
                  <Link href={`/projects/${project.id}/settings/${link.path}`} className="text-blue-600 hover:underline">
                    {link.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        </div>
      ),
    },
    {
      id: "ai-models",
      label: "AI・アセット",
      content: (
        <div className="space-y-6">
          <ProjectAiModelsPanel projectId={project.id} />
          <ProjectAssetGenerationPanel
            projectId={project.id}
            imageJobId={Number.isInteger(imageJobId) && imageJobId > 0 ? imageJobId : undefined}
          />
          <ProjectImageGenerationPromptDefaultsForm
            projectId={project.id}
            defaultNegativePrompt={imageSettings.defaultNegativePrompt}
            defaultQualityPrompt={imageSettings.defaultQualityPrompt}
          />
          <ProjectImageGenerationSizeDefaultsForm
            projectId={project.id}
            defaultGeneratedImageWidth={imageSettings.defaultGeneratedImageWidth}
            defaultGeneratedImageHeight={imageSettings.defaultGeneratedImageHeight}
          />
          <ProjectArticleImageResizeDefaultForm
            projectId={project.id}
            defaultArticleImageLongEdgePx={imageSettings.defaultArticleImageLongEdgePx}
          />
          <ProjectImageContentFilterSettingsForm
            projectId={project.id}
            blockSexualContent={imageSettings.blockSexualContent}
            blockViolentContent={imageSettings.blockViolentContent}
            blockDiscriminatoryContent={imageSettings.blockDiscriminatoryContent}
          />
        </div>
      ),
    },
    {
      id: "members",
      label: "メンバー",
      content: (
        membersFetchFailed ? (
          <p className="text-sm text-red-600">
            メンバー情報を取得できませんでした。時間をおいて画面を再読み込みしてください。
          </p>
        ) : (
          <div className="space-y-4">
            <ProjectUserManager projectId={project.id} members={members} />
            <AddProjectUserModal projectId={project.id} candidateUsers={candidateUsers} />
          </div>
        )
      ),
    },
    {
      id: "maintenance",
      label: "メンテナンス",
      content: (
        <div className="space-y-8">
          <BulkManagementPanel
            projectId={project.id}
            project={project}
            categoryPage={categoryPage}
            timezone={timezone}
          />
          <GarbageCollectionPanel projectId={project.id} project={project} />
          <div className="border-t border-neutral-200 dark:border-neutral-800 pt-4">
            <DeleteProjectButton id={project.id} />
          </div>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-8">
      <Breadcrumb
        items={[
          { label: "ダッシュボード", href: "/" },
          { label: "プロジェクト", href: "/projects" },
          { label: project.name },
        ]}
      />
      <h1 className="text-xl font-semibold">{project.name}</h1>
      <p className="font-mono text-sm text-neutral-500 dark:text-neutral-400">{project.slug}</p>

      <ProjectSectionNav projectId={project.id} active="detail" />

      <FetchErrorNotice labels={failedFetchLabels} />

      <Tabs tabs={tabs} defaultTabId={initialTabId} />
    </div>
  );
}
