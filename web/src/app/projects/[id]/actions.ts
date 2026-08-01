"use server";

import { revalidatePath } from "next/cache";
import {
  bindProjectEnvironment,
  unbindProjectEnvironment,
  updateProject,
  addProjectUser,
  updateProjectUserRole,
  removeProjectUser,
  syncProjectEnvironment,
  ProjectEnvironment,
  EnvironmentSyncTarget,
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
