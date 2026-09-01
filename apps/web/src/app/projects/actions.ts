"use server";

import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { createProject, deleteProject } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface CreateProjectState {
  error?: string;
  success?: boolean;
}

export async function createProjectAction(
  _prevState: CreateProjectState,
  formData: FormData
): Promise<CreateProjectState> {
  await requireAdminSession();

  const name = String(formData.get("name") ?? "").trim();
  const slug = String(formData.get("slug") ?? "").trim();

  if (!name || !slug) {
    return { error: "プロジェクト名とslugを入力してください。" };
  }

  try {
    await createProject({ name, slug });
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/projects");
  return { success: true };
}

export async function deleteProjectAction(id: number) {
  await requireAdminSession();
  await deleteProject(id);
  revalidatePath("/projects");
  redirect("/projects");
}
