"use server";

import { revalidatePath } from "next/cache";
import {
  bindProjectEnvironment,
  unbindProjectEnvironment,
  updateProject,
  updateMasterEnvironment,
  updateProjectGithubRepository,
  updateProjectImageGenerationPromptDefaults,
  updateProjectImageGenerationSizeDefaults,
  updateProjectArticleImageResizeDefault,
  updateProjectImageContentFilterSettings,
  setProjectGithubToken,
  clearProjectGithubToken,
  setProjectBraveSearchApiKey,
  clearProjectBraveSearchApiKey,
  addProjectUser,
  updateProjectUserRole,
  removeProjectUser,
  syncProjectUser,
  ProjectUserSyncSiteResult,
  syncProjectEnvironment,
  applyToEnvironment,
  applyToAllEnvironments,
  saveProjectGoogleAnalyticsClient,
  selectProjectGoogleAnalyticsProperty,
  clearProjectGoogleAnalyticsCredentials,
  setProjectAdSenseSettings,
  setProjectAdSenseClientSecret,
  clearProjectAdSenseCredentials,
  syncCategoryToMaster,
  deleteCategoryEverywhere,
  syncTagToMaster,
  deleteTagEverywhere,
  editCategoryAndSync,
  editTagAndSync,
  syncAllCategoriesToMaster,
  syncAllTagsToMaster,
  EditTermInput,
  listCategoryComparison,
  listTagComparison,
  listPluginComparison,
  listThemeComparison,
  reconcilePluginState,
  reconcileThemeState,
  deletePluginEverywhere,
  deleteThemeEverywhere,
  runBulkOperationUpload,
  listLlmModels,
  selectLlmModel,
  listLlmProvider,
  selectLlmProvider,
  listReviewStepSettings,
  updateReviewStepSetting,
  listImageProvider,
  selectImageProvider,
  listComfyUiCheckpoints,
  selectComfyUiCheckpoint,
  installComfyUiCheckpoint,
  deleteComfyUiCheckpoint,
  scanMediaGarbage,
  deleteMediaGarbage,
  getGenerationJob,
  getImageGenerationOptions,
  generateProjectImages,
  generateImagePromptFromChat,
  uploadProjectAssetImage,
  listGeneratedImages,
  listPostComparison,
  deletePostEverywhere,
  updatePostStatusEverywhere,
  getPostStatuses,
  PostStatusOption,
  ImageGenerationOptionsResponse,
  AiImageGenerationParams,
  AiImageResult,
  PlanChatMessage,
  PostComparisonPage,
  PostType,
  ProjectEnvironment,
  EnvironmentSyncTarget,
  BulkOperationType,
  BulkOperationLog,
  TermComparisonPage,
  StatusComparisonPage,
  PluginThemeStatus,
  LlmModelListResponse,
  LlmProviderListResponse,
  ReviewStepSettingsResponse,
  ImageProviderListResponse,
  ComfyUiCheckpointListResponse,
  GenerationJobDetail,
  GeneratedImageSummary,
  MediaGarbageCollectionScanResponse,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface EnvironmentActionState {
  error?: string;
  success?: boolean;
}

export async function bindEnvironmentAction(
  projectId: number,
  environment: ProjectEnvironment,
  _prevState: EnvironmentActionState,
  formData: FormData
): Promise<EnvironmentActionState> {
  await requireAdminSession();

  const siteId = Number(formData.get("siteId"));
  if (!siteId) {
    return { error: "サイトを選択してください。" };
  }

  try {
    await bindProjectEnvironment(projectId, environment, siteId);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}

export async function unbindEnvironmentAction(projectId: number, environment: ProjectEnvironment) {
  await requireAdminSession();
  await unbindProjectEnvironment(projectId, environment);
  revalidatePath(`/projects/${projectId}`);
}

export interface UpdateProjectNameState {
  error?: string;
  success?: boolean;
}

export async function updateProjectNameAction(
  projectId: number,
  _prevState: UpdateProjectNameState,
  formData: FormData
): Promise<UpdateProjectNameState> {
  await requireAdminSession();

  const name = String(formData.get("name") ?? "").trim();
  if (!name) {
    return { error: "プロジェクト名を入力してください。" };
  }

  try {
    await updateProject(projectId, name);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  revalidatePath("/projects");
  return { success: true };
}

export interface UpdateMasterEnvironmentState {
  error?: string;
  success?: boolean;
}

export async function updateMasterEnvironmentAction(
  projectId: number,
  _prevState: UpdateMasterEnvironmentState,
  formData: FormData
): Promise<UpdateMasterEnvironmentState> {
  await requireAdminSession();

  const masterEnvironment = String(formData.get("masterEnvironment") ?? "");
  if (masterEnvironment !== "test" && masterEnvironment !== "production") {
    return { error: "テスト環境または本番環境を選択してください。" };
  }

  try {
    await updateMasterEnvironment(projectId, masterEnvironment);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}

export interface UpdateProjectGithubRepositoryState {
  error?: string;
  success?: boolean;
}

export async function updateProjectGithubRepositoryAction(
  projectId: number,
  _prevState: UpdateProjectGithubRepositoryState,
  formData: FormData
): Promise<UpdateProjectGithubRepositoryState> {
  await requireAdminSession();

  const githubRepository = String(formData.get("githubRepository") ?? "").trim();

  try {
    await updateProjectGithubRepository(projectId, githubRepository);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}

export interface UpdateImageGenerationPromptDefaultsState {
  error?: string;
  success?: boolean;
}

export async function updateImageGenerationPromptDefaultsAction(
  projectId: number,
  _prevState: UpdateImageGenerationPromptDefaultsState,
  formData: FormData
): Promise<UpdateImageGenerationPromptDefaultsState> {
  await requireAdminSession();

  const defaultNegativePrompt = String(formData.get("defaultNegativePrompt") ?? "").trim();
  const defaultQualityPrompt = String(formData.get("defaultQualityPrompt") ?? "").trim();

  try {
    await updateProjectImageGenerationPromptDefaults(projectId, defaultNegativePrompt, defaultQualityPrompt);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}

export interface UpdateImageGenerationSizeDefaultsState {
  error?: string;
  success?: boolean;
}

export async function updateImageGenerationSizeDefaultsAction(
  projectId: number,
  _prevState: UpdateImageGenerationSizeDefaultsState,
  formData: FormData
): Promise<UpdateImageGenerationSizeDefaultsState> {
  await requireAdminSession();

  const widthRaw = String(formData.get("defaultGeneratedImageWidth") ?? "").trim();
  const heightRaw = String(formData.get("defaultGeneratedImageHeight") ?? "").trim();
  const defaultGeneratedImageWidth = widthRaw ? Number(widthRaw) : null;
  const defaultGeneratedImageHeight = heightRaw ? Number(heightRaw) : null;

  try {
    await updateProjectImageGenerationSizeDefaults(
      projectId, defaultGeneratedImageWidth, defaultGeneratedImageHeight);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}

export interface UpdateImageContentFilterSettingsState {
  error?: string;
  success?: boolean;
}

/** 画像生成の不適切コンテンツフィルタ設定(issue #532)。未チェックのカテゴリは禁止解除として保存する。 */
export async function updateImageContentFilterSettingsAction(
  projectId: number,
  _prevState: UpdateImageContentFilterSettingsState,
  formData: FormData
): Promise<UpdateImageContentFilterSettingsState> {
  await requireAdminSession();

  const blockSexualContent = formData.get("blockSexualContent") === "on";
  const blockViolentContent = formData.get("blockViolentContent") === "on";
  const blockDiscriminatoryContent = formData.get("blockDiscriminatoryContent") === "on";

  try {
    await updateProjectImageContentFilterSettings(
      projectId, blockSexualContent, blockViolentContent, blockDiscriminatoryContent);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}

export interface UpdateArticleImageResizeDefaultState {
  error?: string;
  success?: boolean;
}

export async function updateArticleImageResizeDefaultAction(
  projectId: number,
  _prevState: UpdateArticleImageResizeDefaultState,
  formData: FormData
): Promise<UpdateArticleImageResizeDefaultState> {
  await requireAdminSession();

  const raw = String(formData.get("defaultArticleImageLongEdgePx") ?? "").trim();
  const defaultArticleImageLongEdgePx = raw ? Number(raw) : null;

  try {
    await updateProjectArticleImageResizeDefault(projectId, defaultArticleImageLongEdgePx);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}

export interface ProjectApiKeyFormState {
  error?: string;
  success?: boolean;
}

export async function setProjectGithubTokenAction(
  projectId: number,
  _prevState: ProjectApiKeyFormState,
  formData: FormData
): Promise<ProjectApiKeyFormState> {
  await requireAdminSession();

  const githubToken = String(formData.get("githubToken") ?? "").trim();
  if (!githubToken) {
    return { error: "GitHubトークンを入力してください。" };
  }

  try {
    await setProjectGithubToken(projectId, githubToken);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}

export async function clearProjectGithubTokenAction(projectId: number): Promise<void> {
  await requireAdminSession();
  await clearProjectGithubToken(projectId);
  revalidatePath(`/projects/${projectId}`);
}

export async function setProjectBraveSearchApiKeyAction(
  projectId: number,
  _prevState: ProjectApiKeyFormState,
  formData: FormData
): Promise<ProjectApiKeyFormState> {
  await requireAdminSession();

  const apiKey = String(formData.get("apiKey") ?? "").trim();
  if (!apiKey) {
    return { error: "APIキーを入力してください。" };
  }

  try {
    await setProjectBraveSearchApiKey(projectId, apiKey);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}

export async function clearProjectBraveSearchApiKeyAction(projectId: number): Promise<void> {
  await requireAdminSession();
  await clearProjectBraveSearchApiKey(projectId);
  revalidatePath(`/projects/${projectId}`);
}

/** GA用のGoogle OAuthクライアント(ID/シークレット)を保存する。シークレット欄が空なら保存済みの値を変更しない。 */
export async function setProjectGoogleAnalyticsClientAction(
  projectId: number,
  _prevState: ProjectApiKeyFormState,
  formData: FormData
): Promise<ProjectApiKeyFormState> {
  await requireAdminSession();

  const clientId = String(formData.get("clientId") ?? "").trim();
  const clientSecret = String(formData.get("clientSecret") ?? "").trim();
  if (!clientId) {
    return { error: "Google OAuthクライアントIDを入力してください。" };
  }

  try {
    await saveProjectGoogleAnalyticsClient(projectId, {
      clientId,
      clientSecret: clientSecret || undefined,
    });
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}/settings/google-analytics`);
  return { success: true };
}

/** 連携したGoogleアカウントがアクセスできるGA4プロパティの中から、ダッシュボードで使うものを選んで保存する。 */
export async function selectProjectGoogleAnalyticsPropertyAction(
  projectId: number,
  _prevState: ProjectApiKeyFormState,
  formData: FormData
): Promise<ProjectApiKeyFormState> {
  await requireAdminSession();

  const propertyId = String(formData.get("propertyId") ?? "").trim();
  if (!propertyId) {
    return { error: "GA4プロパティを選択してください。" };
  }

  try {
    await selectProjectGoogleAnalyticsProperty(projectId, propertyId);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}/settings/google-analytics`);
  revalidatePath(`/projects/${projectId}/dashboard`);
  return { success: true };
}

export async function clearProjectGoogleAnalyticsCredentialsAction(projectId: number): Promise<void> {
  await requireAdminSession();
  await clearProjectGoogleAnalyticsCredentials(projectId);
  revalidatePath(`/projects/${projectId}/settings/google-analytics`);
  revalidatePath(`/projects/${projectId}/dashboard`);
}

export async function setProjectAdSenseSettingsAction(
  projectId: number,
  _prevState: ProjectApiKeyFormState,
  formData: FormData
): Promise<ProjectApiKeyFormState> {
  await requireAdminSession();

  const accountId = String(formData.get("accountId") ?? "").trim();
  const clientId = String(formData.get("clientId") ?? "").trim();
  const clientSecret = String(formData.get("clientSecret") ?? "").trim();
  if (!accountId) {
    return { error: "AdSenseパブリッシャーIDを入力してください。" };
  }
  if (!clientId) {
    return { error: "Google OAuthクライアントIDを入力してください。" };
  }

  try {
    await setProjectAdSenseSettings(projectId, { accountId, clientId });
    if (clientSecret) {
      await setProjectAdSenseClientSecret(projectId, clientSecret);
    }
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}/settings/adsense`);
  revalidatePath(`/projects/${projectId}/dashboard`);
  return { success: true };
}

export async function clearProjectAdSenseCredentialsAction(projectId: number): Promise<void> {
  await requireAdminSession();
  await clearProjectAdSenseCredentials(projectId);
  revalidatePath(`/projects/${projectId}/settings/adsense`);
  revalidatePath(`/projects/${projectId}/dashboard`);
}

export interface AddProjectUserState {
  error?: string;
  success?: boolean;
}

export async function addProjectUserAction(
  projectId: number,
  _prevState: AddProjectUserState,
  formData: FormData
): Promise<AddProjectUserState> {
  await requireAdminSession();

  const userId = Number(formData.get("userId"));
  const wpRole = String(formData.get("wpRole") ?? "").trim();
  if (!userId || !wpRole) {
    return { error: "ユーザーとロールを選択してください。" };
  }

  try {
    await addProjectUser(projectId, userId, wpRole);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}

export async function updateProjectUserRoleAction(projectId: number, userId: number, wpRole: string) {
  await requireAdminSession();
  await updateProjectUserRole(projectId, userId, wpRole);
  revalidatePath(`/projects/${projectId}`);
}

export async function removeProjectUserAction(projectId: number, userId: number) {
  await requireAdminSession();
  await removeProjectUser(projectId, userId);
  revalidatePath(`/projects/${projectId}`);
}

export interface SyncProjectUserState {
  error?: string;
  results?: ProjectUserSyncSiteResult[];
}

/**
 * issue #1242: メンバー個別のユーザー情報再同期。追加/ロール変更時の同期と違い、
 * ローカルの`project_users`/表示は変化しないため`revalidatePath`は不要。
 */
export async function syncProjectUserAction(projectId: number, userId: number): Promise<SyncProjectUserState> {
  await requireAdminSession();

  try {
    const results = await syncProjectUser(projectId, userId);
    return { results };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export interface SyncEnvironmentState {
  error?: string;
  success?: boolean;
}

export async function syncEnvironmentAction(
  projectId: number,
  _prevState: SyncEnvironmentState,
  formData: FormData
): Promise<SyncEnvironmentState> {
  await requireAdminSession();

  const from = String(formData.get("from") ?? "") as ProjectEnvironment;
  const to = String(formData.get("to") ?? "") as ProjectEnvironment;
  const targets = formData.getAll("targets") as EnvironmentSyncTarget[];

  if (!from || !to) {
    return { error: "同期元・同期先の環境を選択してください。" };
  }
  if (from === to) {
    return { error: "同期元と同期先には異なる環境を指定してください。" };
  }
  if (targets.length === 0) {
    return { error: "同期する対象(テーマ/プラグイン/メディア/DB)を1つ以上選択してください。" };
  }

  try {
    await syncProjectEnvironment(projectId, { from, to, targets });
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}

export interface BulkOperationState {
  error?: string;
  success?: boolean;
  results?: BulkOperationLog[];
}

export async function applyToEnvironmentAction(
  projectId: number,
  _prevState: BulkOperationState,
  formData: FormData
): Promise<BulkOperationState> {
  await requireAdminSession();

  const environment = String(formData.get("environment") ?? "") as ProjectEnvironment;
  const operationType = String(formData.get("operationType") ?? "") as BulkOperationType;
  const value = String(formData.get("value") ?? "").trim();
  const categorySlug = String(formData.get("categorySlug") ?? "").trim();
  const categoryParentSlug = String(formData.get("categoryParentSlug") ?? "").trim();
  const categoryDescription = String(formData.get("categoryDescription") ?? "").trim();
  const categoryTargetSlug = String(formData.get("categoryTargetSlug") ?? "").trim();

  if (!environment) {
    return { error: "対象環境を選択してください。" };
  }
  if (!operationType) {
    return { error: "操作種別を選択してください。" };
  }
  if (operationType === "CATEGORY_DELETE" || operationType === "TAG_DELETE") {
    if (!categoryTargetSlug) {
      return { error: "削除対象を選択してください。" };
    }
  } else if (
    operationType === "CATEGORY_CREATE" ||
    operationType === "CATEGORY_EDIT" ||
    operationType === "TAG_CREATE" ||
    operationType === "TAG_EDIT"
  ) {
    if (!value || !categorySlug) {
      return { error: "名前とスラッグを入力してください。" };
    }
    if ((operationType === "CATEGORY_EDIT" || operationType === "TAG_EDIT") && !categoryTargetSlug) {
      return { error: "編集対象を選択してください。" };
    }
  } else if (!value) {
    return { error: "slugを入力してください。" };
  }

  try {
    const result = await applyToEnvironment(
      projectId,
      {
        environment,
        operationType,
        value: value || undefined,
        categorySlug: categorySlug || undefined,
        categoryParentSlug: categoryParentSlug || undefined,
        categoryDescription: categoryDescription || undefined,
        categoryTargetSlug: categoryTargetSlug || undefined,
      }
    );
    revalidatePath(`/projects/${projectId}`);
    return { success: true, results: [result] };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function applyToAllEnvironmentsAction(
  projectId: number,
  _prevState: BulkOperationState,
  formData: FormData
): Promise<BulkOperationState> {
  await requireAdminSession();

  const operationType = String(formData.get("operationType") ?? "") as BulkOperationType;
  const value = String(formData.get("value") ?? "").trim();

  if (operationType !== "PLUGIN_INSTALL" && operationType !== "THEME_INSTALL") {
    return { error: "全環境への一括インストールはプラグイン/テーマのインストールのみ対応しています。" };
  }
  if (!value) {
    return { error: "slugを入力してください。" };
  }

  try {
    const results = await applyToAllEnvironments(projectId, { operationType, value });
    revalidatePath(`/projects/${projectId}`);
    return { success: true, results };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function syncTermToMasterAction(
  projectId: number,
  kind: "category" | "tag",
  slug: string
): Promise<BulkOperationState> {
  await requireAdminSession();

  try {
    const results = await (kind === "category"
      ? syncCategoryToMaster(projectId, slug)
      : syncTagToMaster(projectId, slug));
    revalidatePath(`/projects/${projectId}`);
    return { success: true, results };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function editTermAndSyncAction(
  projectId: number,
  kind: "category" | "tag",
  input: EditTermInput
): Promise<BulkOperationState> {
  await requireAdminSession();

  try {
    const results = await (kind === "category"
      ? editCategoryAndSync(projectId, input)
      : editTagAndSync(projectId, input));
    revalidatePath(`/projects/${projectId}`);
    return { success: true, results };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function syncAllTermsToMasterAction(
  projectId: number,
  kind: "category" | "tag"
): Promise<BulkOperationState> {
  await requireAdminSession();

  try {
    const results = await (kind === "category"
      ? syncAllCategoriesToMaster(projectId)
      : syncAllTagsToMaster(projectId));
    revalidatePath(`/projects/${projectId}`);
    return { success: true, results };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function deleteTermEverywhereAction(
  projectId: number,
  kind: "category" | "tag",
  slug: string
): Promise<BulkOperationState> {
  await requireAdminSession();

  try {
    const results = await (kind === "category"
      ? deleteCategoryEverywhere(projectId, slug)
      : deleteTagEverywhere(projectId, slug));
    revalidatePath(`/projects/${projectId}`);
    return { success: true, results };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function runBulkOperationUploadAction(
  projectId: number,
  _prevState: BulkOperationState,
  formData: FormData
): Promise<BulkOperationState> {
  await requireAdminSession();

  const operationType = String(formData.get("operationType") ?? "") as BulkOperationType;
  const file = formData.get("file");

  if (operationType !== "PLUGIN_INSTALL" && operationType !== "THEME_INSTALL") {
    return { error: "zipアップロードはプラグイン/テーマのインストールのみ対応しています。" };
  }
  if (!(file instanceof File) || file.size === 0) {
    return { error: "アップロードするzipファイルを選択してください。" };
  }

  try {
    const results = await runBulkOperationUpload(projectId, { operationType, file });
    revalidatePath(`/projects/${projectId}`);
    return { success: true, results };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function fetchTermComparisonAction(
  projectId: number,
  kind: "category" | "tag",
  page: number
): Promise<TermComparisonPage> {
  await requireAdminSession();

  return kind === "category"
    ? listCategoryComparison(projectId, page)
    : listTagComparison(projectId, page);
}

export async function fetchStatusComparisonAction(
  projectId: number,
  kind: "plugin" | "theme",
  page: number
): Promise<StatusComparisonPage> {
  await requireAdminSession();

  return kind === "plugin"
    ? listPluginComparison(projectId, page)
    : listThemeComparison(projectId, page);
}

export async function reconcileStateAction(
  projectId: number,
  kind: "plugin" | "theme",
  slug: string,
  changes: { environment: ProjectEnvironment; desiredStatus: PluginThemeStatus }[]
): Promise<BulkOperationState> {
  await requireAdminSession();

  try {
    const results = await (kind === "plugin"
      ? reconcilePluginState(projectId, { slug, changes })
      : reconcileThemeState(projectId, { slug, changes }));
    revalidatePath(`/projects/${projectId}`);
    return { success: true, results };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function deleteSlugEverywhereAction(
  projectId: number,
  kind: "plugin" | "theme",
  slug: string
): Promise<BulkOperationState> {
  await requireAdminSession();

  try {
    const results = await (kind === "plugin"
      ? deletePluginEverywhere(projectId, slug)
      : deleteThemeEverywhere(projectId, slug));
    revalidatePath(`/projects/${projectId}`);
    return { success: true, results };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export interface AiModelActionState {
  error?: string;
  jobId?: number;
}

export async function fetchLlmModelsAction(projectId: number): Promise<LlmModelListResponse> {
  await requireAdminSession();
  return listLlmModels(projectId);
}

export async function selectLlmModelAction(
  projectId: number,
  modelName: string
): Promise<{ error?: string }> {
  await requireAdminSession();

  try {
    await selectLlmModel(projectId, modelName);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
  revalidatePath(`/projects/${projectId}`);
  return {};
}

export async function fetchLlmProviderAction(projectId: number): Promise<LlmProviderListResponse> {
  await requireAdminSession();
  return listLlmProvider(projectId);
}

export async function selectLlmProviderAction(
  projectId: number,
  provider: string
): Promise<{ error?: string }> {
  await requireAdminSession();

  try {
    await selectLlmProvider(projectId, provider);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
  revalidatePath(`/projects/${projectId}`);
  return {};
}

export async function fetchReviewStepSettingsAction(projectId: number): Promise<ReviewStepSettingsResponse> {
  await requireAdminSession();
  return listReviewStepSettings(projectId);
}

export async function updateReviewStepSettingAction(
  projectId: number,
  stepKey: string,
  provider: string,
  model: string
): Promise<{ error?: string }> {
  await requireAdminSession();

  try {
    await updateReviewStepSetting(projectId, stepKey, provider, model);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
  revalidatePath(`/projects/${projectId}`);
  return {};
}

export async function fetchImageProviderAction(projectId: number): Promise<ImageProviderListResponse> {
  await requireAdminSession();
  return listImageProvider(projectId);
}

export async function selectImageProviderAction(
  projectId: number,
  provider: string
): Promise<{ error?: string }> {
  await requireAdminSession();

  try {
    await selectImageProvider(projectId, provider);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
  revalidatePath(`/projects/${projectId}`);
  return {};
}

export async function fetchComfyUiCheckpointsAction(projectId: number): Promise<ComfyUiCheckpointListResponse> {
  await requireAdminSession();
  return listComfyUiCheckpoints(projectId);
}

export async function selectComfyUiCheckpointAction(
  projectId: number,
  checkpointName: string
): Promise<{ error?: string }> {
  await requireAdminSession();

  try {
    await selectComfyUiCheckpoint(projectId, checkpointName);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
  revalidatePath(`/projects/${projectId}`);
  return {};
}

export async function installComfyUiCheckpointAction(
  projectId: number,
  downloadUrl: string,
  fileName: string
): Promise<AiModelActionState> {
  await requireAdminSession();

  try {
    const job = await installComfyUiCheckpoint(projectId, downloadUrl, fileName);
    return { jobId: job.id };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function deleteComfyUiCheckpointAction(
  projectId: number,
  fileName: string
): Promise<AiModelActionState> {
  await requireAdminSession();

  try {
    const job = await deleteComfyUiCheckpoint(projectId, fileName);
    return { jobId: job.id };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function fetchGenerationJobAction(jobId: number): Promise<GenerationJobDetail> {
  await requireAdminSession();
  return getGenerationJob(jobId);
}

export async function fetchMediaGarbageScanAction(
  projectId: number,
  environment: ProjectEnvironment
): Promise<{ data?: MediaGarbageCollectionScanResponse; error?: string }> {
  await requireAdminSession();

  try {
    const data = await scanMediaGarbage(projectId, environment);
    return { data };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function deleteMediaGarbageAction(
  projectId: number,
  environment: ProjectEnvironment,
  mediaIds: string[]
): Promise<AiModelActionState> {
  await requireAdminSession();

  try {
    const job = await deleteMediaGarbage(projectId, environment, mediaIds);
    return { jobId: job.id };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function fetchImageGenerationOptionsAction(projectId: number): Promise<ImageGenerationOptionsResponse> {
  await requireAdminSession();
  return getImageGenerationOptions(projectId);
}

export async function generateProjectImagesAction(
  projectId: number,
  params: Omit<AiImageGenerationParams, "projectId">
): Promise<{ images?: AiImageResult[]; error?: string }> {
  await requireAdminSession();

  try {
    const result = await generateProjectImages({ ...params, projectId });
    return { images: result.images };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function generateImagePromptAction(
  projectId: number,
  data: { history: PlanChatMessage[]; message: string; provider?: string }
): Promise<{ prompt?: string; error?: string }> {
  await requireAdminSession();

  try {
    const result = await generateImagePromptFromChat(projectId, data);
    return { prompt: result.prompt };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function fetchPostComparisonAction(
  projectId: number,
  postType: PostType,
  page: number
): Promise<PostComparisonPage> {
  await requireAdminSession();
  return listPostComparison(projectId, postType, page);
}

export async function fetchPostStatusesAction(): Promise<PostStatusOption[]> {
  await requireAdminSession();
  return getPostStatuses();
}

export async function deletePostEverywhereAction(
  projectId: number,
  postType: PostType,
  slug: string
): Promise<BulkOperationState> {
  await requireAdminSession();

  try {
    await deletePostEverywhere(projectId, postType, slug);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
  revalidatePath(`/projects/${projectId}`);
  return {};
}

export async function updatePostStatusEverywhereAction(
  projectId: number,
  postType: PostType,
  slug: string,
  status: string
): Promise<BulkOperationState> {
  await requireAdminSession();

  try {
    await updatePostStatusEverywhere(projectId, postType, slug, status);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
  revalidatePath(`/projects/${projectId}`);
  return {};
}

/** アセット画像生成パネルから、生成画像ギャラリー全体を選択肢として表示するために取得する(issue #436)。 */
export async function fetchGeneratedImagesAction(): Promise<GeneratedImageSummary[]> {
  await requireAdminSession();
  return listGeneratedImages();
}

export async function uploadProjectAssetImageAction(
  projectId: number,
  generatedImageId: number
): Promise<{ logs?: BulkOperationLog[]; error?: string }> {
  await requireAdminSession();

  try {
    const logs = await uploadProjectAssetImage(projectId, generatedImageId);
    revalidatePath(`/projects/${projectId}`);
    return { logs };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}
