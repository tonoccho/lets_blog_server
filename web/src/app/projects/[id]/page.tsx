import { notFound } from "next/navigation";
import {
  getProject,
  listSites,
  listProjectUsers,
  listUsers,
  listBulkOperationLogs,
  listCategoryComparison,
  getProjectGithubTokenStatus,
  getProjectBraveSearchApiKeyStatus,
  BulkOperationType,
  BulkOperationLogLevel,
  ProjectEnvironment,
} from "@/lib/apiClient";
import { requireAdminSession, getViewerTimeZone } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { Tabs, type TabItem } from "@/components/Tabs";
import { ProjectSectionNav } from "./ProjectSectionNav";
import { EnvironmentSlot } from "./EnvironmentSlot";
import { MasterEnvironmentSelector } from "./MasterEnvironmentSelector";
import { ProjectGithubRepositoryForm } from "./ProjectGithubRepositoryForm";
import { ProjectApiKeysForm } from "./ProjectApiKeysForm";
import { EnvironmentSyncPanel } from "./EnvironmentSyncPanel";
import { BulkManagementPanel } from "./BulkManagementPanel";
import { ProjectAiModelsPanel } from "./ProjectAiModelsPanel";
import { ProjectAssetGenerationPanel } from "./ProjectAssetGenerationPanel";
import { ProjectNameForm } from "./ProjectNameForm";
import { DeleteProjectButton } from "./DeleteProjectButton";
import { ProjectUserManager } from "./ProjectUserManager";
import { AddProjectUserModal } from "./AddProjectUserModal";

export default async function ProjectDetailPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ logOperationType?: string; logEnvironment?: string; logLevel?: string }>;
}) {
  const { id } = await params;
  const logFilterParams = await searchParams;
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const projectId = Number(id);

  const logFilter = {
    operationType: (logFilterParams.logOperationType || undefined) as BulkOperationType | undefined,
    environment: (logFilterParams.logEnvironment || undefined) as ProjectEnvironment | undefined,
    level: (logFilterParams.logLevel || undefined) as BulkOperationLogLevel | undefined,
  };

  const emptyComparisonPage = { items: [], page: 0, size: 20, totalCount: 0, masterEnvironment: "test" as const };

  function logAndFallback<T>(label: string, fallback: T) {
    return (err: unknown) => {
      console.error(`[projects/${projectId}] ${label}の取得に失敗しました:`, err);
      return fallback;
    };
  }

  // タグ・プラグイン・テーマは一括管理パネルでタブを開いたときにクライアント側から遅延取得する
  // (初期表示で4種類すべて並行取得すると、同一ホストのSSH接続が集中しやすいため)。
  const [
    project,
    sites,
    members,
    allUsers,
    bulkOperationLogs,
    categoryPage,
    timezone,
    githubTokenStatus,
    braveSearchApiKeyStatus,
  ] = await Promise.all([
    getProject(projectId, actor).catch(logAndFallback("プロジェクト情報", null)),
    listSites().catch(logAndFallback("サイト一覧", [])),
    listProjectUsers(projectId, actor).catch(logAndFallback("プロジェクトメンバー", [])),
    listUsers().catch(logAndFallback("ユーザー一覧", [])),
    listBulkOperationLogs(projectId, actor, logFilter).catch(logAndFallback("作業ログ", [])),
    listCategoryComparison(projectId, 0, actor).catch(logAndFallback("カテゴリ比較", emptyComparisonPage)),
    getViewerTimeZone(),
    getProjectGithubTokenStatus(projectId, actor).catch(logAndFallback("GitHubトークン設定状況", { configured: false })),
    getProjectBraveSearchApiKeyStatus(projectId, actor)
      .catch(logAndFallback("Brave APIキー設定状況", { configured: false })),
  ]);

  if (!project) {
    notFound();
  }

  const candidateUsers = allUsers.filter((user) => !members.some((member) => member.userId === user.id));

  const tabs: TabItem[] = [
    {
      id: "overview",
      label: "概要",
      content: (
        <div className="space-y-6">
          <ProjectNameForm projectId={project.id} name={project.name} />

          <div>
            <h3 className="text-sm font-semibold mb-3">環境設定</h3>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <EnvironmentSlot projectId={project.id} environment="local" site={project.localSite} candidateSites={sites} />
              <EnvironmentSlot projectId={project.id} environment="test" site={project.testSite} candidateSites={sites} />
              <EnvironmentSlot
                projectId={project.id}
                environment="production"
                site={project.productionSite}
                candidateSites={sites}
              />
            </div>
          </div>

          <MasterEnvironmentSelector projectId={project.id} project={project} />
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
              壁打ちチャットで使うOllamaモデル・画像生成で使うComfyUIチェックポイントは、プロジェクトごとに
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
          logs={bulkOperationLogs}
          logFilter={logFilter}
          categoryPage={categoryPage}
          timezone={timezone}
        />
      ),
    },
    {
      id: "ai-models",
      label: "AI・アセット",
      content: (
        <div className="space-y-6">
          <ProjectAiModelsPanel projectId={project.id} />
          <ProjectAssetGenerationPanel projectId={project.id} />
        </div>
      ),
    },
    {
      id: "members",
      label: "メンバー",
      content: (
        <div className="space-y-4">
          <ProjectUserManager projectId={project.id} members={members} />
          <AddProjectUserModal projectId={project.id} candidateUsers={candidateUsers} />
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
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">{project.name}</h1>
        <div className="flex items-center gap-4">
          <DeleteProjectButton id={project.id} />
        </div>
      </div>
      <p className="font-mono text-sm text-neutral-500 dark:text-neutral-400">{project.slug}</p>

      <ProjectSectionNav projectId={project.id} active="detail" />

      <Tabs tabs={tabs} />
    </div>
  );
}
