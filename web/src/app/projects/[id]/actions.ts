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
  setProjectGithubToken,
  clearProjectGithubToken,
  setProjectBraveSearchApiKey,
  clearProjectBraveSearchApiKey,
  addProjectUser,
  updateProjectUserRole,
  removeProjectUser,
  syncProjectEnvironment,
  applyToEnvironment,
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
  listOllamaModels,
  selectOllamaModel,
  installOllamaModel,
  deleteOllamaModel,
  listComfyUiCheckpoints,
  selectComfyUiCheckpoint,
  installComfyUiCheckpoint,
  deleteComfyUiCheckpoint,
  getGenerationJob,
  getImageGenerationOptions,
  generateProjectImages,
  generateImagePromptFromChat,
  uploadProjectAssetImage,
  listPostComparison,
  deletePostEverywhere,
  updatePostStatusEverywhere,
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
  OllamaModelListResponse,
  ComfyUiCheckpointListResponse,
  GenerationJobDetail,
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const siteId = Number(formData.get("siteId"));
  if (!siteId) {
    return { error: "サイトを選択してください。" };
  }

  try {
    await bindProjectEnvironment(projectId, environment, siteId, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}

export async function unbindEnvironmentAction(projectId: number, environment: ProjectEnvironment) {
  const session = await requireAdminSession();
  await unbindProjectEnvironment(projectId, environment, {
    id: Number(session.user.id),
    role: session.user.role,
  });
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const name = String(formData.get("name") ?? "").trim();
  if (!name) {
    return { error: "プロジェクト名を入力してください。" };
  }

  try {
    await updateProject(projectId, name, actor);
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const masterEnvironment = String(formData.get("masterEnvironment") ?? "");
  if (masterEnvironment !== "test" && masterEnvironment !== "production") {
    return { error: "テスト環境または本番環境を選択してください。" };
  }

  try {
    await updateMasterEnvironment(projectId, masterEnvironment, actor);
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const githubRepository = String(formData.get("githubRepository") ?? "").trim();

  try {
    await updateProjectGithubRepository(projectId, githubRepository, actor);
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const defaultNegativePrompt = String(formData.get("defaultNegativePrompt") ?? "").trim();
  const defaultQualityPrompt = String(formData.get("defaultQualityPrompt") ?? "").trim();

  try {
    await updateProjectImageGenerationPromptDefaults(projectId, defaultNegativePrompt, defaultQualityPrompt, actor);
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const widthRaw = String(formData.get("defaultGeneratedImageWidth") ?? "").trim();
  const heightRaw = String(formData.get("defaultGeneratedImageHeight") ?? "").trim();
  const defaultGeneratedImageWidth = widthRaw ? Number(widthRaw) : null;
  const defaultGeneratedImageHeight = heightRaw ? Number(heightRaw) : null;

  try {
    await updateProjectImageGenerationSizeDefaults(
      projectId, defaultGeneratedImageWidth, defaultGeneratedImageHeight, actor);
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const githubToken = String(formData.get("githubToken") ?? "").trim();
  if (!githubToken) {
    return { error: "GitHubトークンを入力してください。" };
  }

  try {
    await setProjectGithubToken(projectId, githubToken, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}

export async function clearProjectGithubTokenAction(projectId: number): Promise<void> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  await clearProjectGithubToken(projectId, actor);
  revalidatePath(`/projects/${projectId}`);
}

export async function setProjectBraveSearchApiKeyAction(
  projectId: number,
  _prevState: ProjectApiKeyFormState,
  formData: FormData
): Promise<ProjectApiKeyFormState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const apiKey = String(formData.get("apiKey") ?? "").trim();
  if (!apiKey) {
    return { error: "APIキーを入力してください。" };
  }

  try {
    await setProjectBraveSearchApiKey(projectId, apiKey, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}

export async function clearProjectBraveSearchApiKeyAction(projectId: number): Promise<void> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  await clearProjectBraveSearchApiKey(projectId, actor);
  revalidatePath(`/projects/${projectId}`);
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const userId = Number(formData.get("userId"));
  const wpRole = String(formData.get("wpRole") ?? "").trim();
  if (!userId || !wpRole) {
    return { error: "ユーザーとロールを選択してください。" };
  }

  try {
    await addProjectUser(projectId, userId, wpRole, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}

export async function updateProjectUserRoleAction(projectId: number, userId: number, wpRole: string) {
  const session = await requireAdminSession();
  await updateProjectUserRole(projectId, userId, wpRole, {
    id: Number(session.user.id),
    role: session.user.role,
  });
  revalidatePath(`/projects/${projectId}`);
}

export async function removeProjectUserAction(projectId: number, userId: number) {
  const session = await requireAdminSession();
  await removeProjectUser(projectId, userId, {
    id: Number(session.user.id),
    role: session.user.role,
  });
  revalidatePath(`/projects/${projectId}`);
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

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
    await syncProjectEnvironment(projectId, { from, to, targets }, actor);
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

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
      },
      actor
    );
    revalidatePath(`/projects/${projectId}`);
    return { success: true, results: [result] };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function syncTermToMasterAction(
  projectId: number,
  kind: "category" | "tag",
  slug: string
): Promise<BulkOperationState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const results = await (kind === "category"
      ? syncCategoryToMaster(projectId, slug, actor)
      : syncTagToMaster(projectId, slug, actor));
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const results = await (kind === "category"
      ? editCategoryAndSync(projectId, input, actor)
      : editTagAndSync(projectId, input, actor));
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const results = await (kind === "category"
      ? syncAllCategoriesToMaster(projectId, actor)
      : syncAllTagsToMaster(projectId, actor));
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const results = await (kind === "category"
      ? deleteCategoryEverywhere(projectId, slug, actor)
      : deleteTagEverywhere(projectId, slug, actor));
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const operationType = String(formData.get("operationType") ?? "") as BulkOperationType;
  const file = formData.get("file");

  if (operationType !== "PLUGIN_INSTALL" && operationType !== "THEME_INSTALL") {
    return { error: "zipアップロードはプラグイン/テーマのインストールのみ対応しています。" };
  }
  if (!(file instanceof File) || file.size === 0) {
    return { error: "アップロードするzipファイルを選択してください。" };
  }

  try {
    const results = await runBulkOperationUpload(projectId, { operationType, file }, actor);
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  return kind === "category"
    ? listCategoryComparison(projectId, page, actor)
    : listTagComparison(projectId, page, actor);
}

export async function fetchStatusComparisonAction(
  projectId: number,
  kind: "plugin" | "theme",
  page: number
): Promise<StatusComparisonPage> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  return kind === "plugin"
    ? listPluginComparison(projectId, page, actor)
    : listThemeComparison(projectId, page, actor);
}

export async function reconcileStateAction(
  projectId: number,
  kind: "plugin" | "theme",
  slug: string,
  changes: { environment: ProjectEnvironment; desiredStatus: PluginThemeStatus }[]
): Promise<BulkOperationState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const results = await (kind === "plugin"
      ? reconcilePluginState(projectId, { slug, changes }, actor)
      : reconcileThemeState(projectId, { slug, changes }, actor));
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const results = await (kind === "plugin"
      ? deletePluginEverywhere(projectId, slug, actor)
      : deleteThemeEverywhere(projectId, slug, actor));
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

export async function fetchOllamaModelsAction(projectId: number): Promise<OllamaModelListResponse> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  return listOllamaModels(projectId, actor);
}

export async function selectOllamaModelAction(
  projectId: number,
  modelName: string
): Promise<{ error?: string }> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    await selectOllamaModel(projectId, modelName, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
  revalidatePath(`/projects/${projectId}`);
  return {};
}

export async function installOllamaModelAction(
  projectId: number,
  modelName: string
): Promise<AiModelActionState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const job = await installOllamaModel(projectId, modelName, actor);
    return { jobId: job.id };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function deleteOllamaModelAction(
  projectId: number,
  modelName: string
): Promise<AiModelActionState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const job = await deleteOllamaModel(projectId, modelName, actor);
    return { jobId: job.id };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function fetchComfyUiCheckpointsAction(projectId: number): Promise<ComfyUiCheckpointListResponse> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  return listComfyUiCheckpoints(projectId, actor);
}

export async function selectComfyUiCheckpointAction(
  projectId: number,
  checkpointName: string
): Promise<{ error?: string }> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    await selectComfyUiCheckpoint(projectId, checkpointName, actor);
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const job = await installComfyUiCheckpoint(projectId, downloadUrl, fileName, actor);
    return { jobId: job.id };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function deleteComfyUiCheckpointAction(
  projectId: number,
  fileName: string
): Promise<AiModelActionState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const job = await deleteComfyUiCheckpoint(projectId, fileName, actor);
    return { jobId: job.id };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function fetchGenerationJobAction(jobId: number): Promise<GenerationJobDetail> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  return getGenerationJob(jobId, actor);
}

export async function fetchImageGenerationOptionsAction(projectId: number): Promise<ImageGenerationOptionsResponse> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  return getImageGenerationOptions(projectId, actor);
}

export async function generateProjectImagesAction(
  projectId: number,
  params: Omit<AiImageGenerationParams, "projectId">
): Promise<{ images?: AiImageResult[]; error?: string }> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const result = await generateProjectImages({ ...params, projectId }, actor);
    return { images: result.images };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function generateImagePromptAction(
  projectId: number,
  data: { history: PlanChatMessage[]; message: string }
): Promise<{ prompt?: string; error?: string }> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const result = await generateImagePromptFromChat(projectId, data, actor);
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  return listPostComparison(projectId, postType, page, actor);
}

export async function deletePostEverywhereAction(
  projectId: number,
  postType: PostType,
  slug: string
): Promise<BulkOperationState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    await deletePostEverywhere(projectId, postType, slug, actor);
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    await updatePostStatusEverywhere(projectId, postType, slug, status, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
  revalidatePath(`/projects/${projectId}`);
  return {};
}

export async function uploadProjectAssetImageAction(
  projectId: number,
  generatedImageId: number
): Promise<{ logs?: BulkOperationLog[]; error?: string }> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const logs = await uploadProjectAssetImage(projectId, generatedImageId, actor);
    revalidatePath(`/projects/${projectId}`);
    return { logs };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}
