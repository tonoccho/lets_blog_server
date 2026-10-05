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
import { ProjectUserManager } from "../ProjectUserManager";
import { AddProjectUserModal } from "../AddProjectUserModal";

export default async function ProjectDetailPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  /**
   * `?tab=members` で初期選択タブを指定できる(ダッシュボードのメンバーウィジェットからの遷移先)。
   * `?imageJob=<ジョブID>` を付けると、アセット画像生成パネルがそのジョブの生成結果を開いた状態で
   * 表示する(処理キューの画像生成ジョブの「結果を見る」の遷移先、issue #1408)。
   */
  searchParams?: Promise<{ tab?: string; imageJob?: string }>;
}) {
  const { id } = await params;
  const { tab: initialTabId, imageJob } = (await searchParams) ?? {};
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
          <ProjectNameForm projectId={project.id} name={project.name} />

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

          <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
            <h2 className="font-medium">SNS 告知</h2>
            <p className="mt-1 text-sm text-neutral-600 dark:text-neutral-400">
              プロジェクトの公式 X アカウントを本番サイトのプラグインへ接続し、記事の公開を告知します。
            </p>
            <Link
              href={`/projects/${project.id}/settings/sns`}
              className="mt-2 inline-block text-sm text-blue-600 hover:underline"
            >
              SNS 告知の設定
            </Link>
          </div>

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
                このプロジェクトにはメンバーがいません。ユーザーを追加すると、紐付いたサイトの WordPress にも作成されます。
              </p>
              <AddProjectUserModal projectId={project.id} candidateUsers={candidateUsers} />
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
          <ProjectGithubRepositoryForm projectId={project.id} githubRepository={project.githubRepository} />
          <ProjectApiKeysForm
            projectId={project.id}
            githubTokenConfigured={githubTokenStatus.configured}
            braveSearchApiKeyConfigured={braveSearchApiKeyStatus.configured}
          />
          <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
            <h2 className="font-medium">モデル設定</h2>
            <p className="mt-1 text-sm text-neutral-600 dark:text-neutral-400">
              壁打ちチャットで使うLLMモデル・画像生成で使うComfyUIチェックポイントは、プロジェクトごとに
              「AI・アセット」タブから切り替えられます。
            </p>
          </div>
          <EnvironmentSyncPanel projectId={project.id} project={project} />
        </div>
      ),
    },
    {
      id: "bulk-management",
      label: "一括管理",
      content: (
        <BulkManagementPanel
          projectId={project.id}
          project={project}
          categoryPage={categoryPage}
          timezone={timezone}
        />
      ),
    },
    {
      id: "garbage-collection",
      label: "ガベージコレクション",
      content: <GarbageCollectionPanel projectId={project.id} project={project} />,
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
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">{project.name}</h1>
        <div className="flex items-center gap-4">
          <DeleteProjectButton id={project.id} />
        </div>
      </div>
      <p className="font-mono text-sm text-neutral-500 dark:text-neutral-400">{project.slug}</p>

      <ProjectSectionNav projectId={project.id} active="detail" />

      <FetchErrorNotice labels={failedFetchLabels} />

      <Tabs tabs={tabs} defaultTabId={initialTabId} />
    </div>
  );
}
