"use server";

import { revalidatePath } from "next/cache";
import {
  bindProjectEnvironment,
  unbindProjectEnvironment,
  updateProject,
  updateMasterEnvironment,
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
  replayBulkOperations,
  clearBulkOperationLogs,
  ProjectEnvironment,
  EnvironmentSyncTarget,
  BulkOperationType,
  BulkOperationLog,
  TermComparisonPage,
  StatusComparisonPage,
  PluginThemeStatus,
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

export async function replayBulkOperationsAction(
  projectId: number,
  environment: ProjectEnvironment
): Promise<BulkOperationState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const results = await replayBulkOperations(projectId, { environment }, actor);
    revalidatePath(`/projects/${projectId}`);
    return { success: true, results };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function clearBulkOperationLogsAction(projectId: number): Promise<void> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  await clearBulkOperationLogs(projectId, actor);
  revalidatePath(`/projects/${projectId}`);
}
