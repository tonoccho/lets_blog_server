"use server";

import { revalidatePath } from "next/cache";
import {
  applyCustomTagTemplate,
  cloneCustomTagTemplate,
  createCustomTagTemplate,
  deleteCustomTagTemplate,
  publishCustomTagTemplate,
  unpublishCustomTagTemplate,
  updateCustomTagTemplate,
  type ApplyCustomTagTemplateInput,
  type CustomTag,
  type CustomTagTemplate,
  type CustomTagTemplateInput,
  type CloneCustomTagTemplateInput,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface CustomTagTemplateActionState {
  error?: string;
  success?: boolean;
}

export async function createCustomTagTemplateAction(
  input: CustomTagTemplateInput
): Promise<{ data?: CustomTagTemplate; error?: string }> {
  await requireAdminSession();

  try {
    const result = await createCustomTagTemplate(input);
    revalidatePath("/custom-tag-templates");
    return { data: result };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function updateCustomTagTemplateAction(
  id: number,
  input: CustomTagTemplateInput
): Promise<{ data?: CustomTagTemplate; error?: string }> {
  await requireAdminSession();

  try {
    const result = await updateCustomTagTemplate(id, input);
    revalidatePath("/custom-tag-templates");
    return { data: result };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function publishCustomTagTemplateAction(
  id: number
): Promise<{ data?: CustomTagTemplate; error?: string }> {
  await requireAdminSession();

  try {
    const result = await publishCustomTagTemplate(id);
    revalidatePath("/custom-tag-templates");
    return { data: result };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function unpublishCustomTagTemplateAction(
  id: number
): Promise<{ data?: CustomTagTemplate; error?: string }> {
  await requireAdminSession();

  try {
    const result = await unpublishCustomTagTemplate(id);
    revalidatePath("/custom-tag-templates");
    return { data: result };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function cloneCustomTagTemplateAction(
  id: number,
  input: CloneCustomTagTemplateInput
): Promise<{ data?: CustomTagTemplate; error?: string }> {
  await requireAdminSession();

  try {
    const result = await cloneCustomTagTemplate(id, input);
    revalidatePath("/custom-tag-templates");
    return { data: result };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

/** テンプレートをプロジェクトのカスタムタグとして適用する(issue #1131)。 */
export async function applyCustomTagTemplateAction(
  id: number,
  input: ApplyCustomTagTemplateInput
): Promise<{ data?: CustomTag; error?: string }> {
  await requireAdminSession();

  try {
    const result = await applyCustomTagTemplate(id, input);
    revalidatePath(`/projects/${input.projectId}/custom-tags`);
    return { data: result };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function deleteCustomTagTemplateAction(
  id: number
): Promise<CustomTagTemplateActionState> {
  await requireAdminSession();

  try {
    await deleteCustomTagTemplate(id);
    revalidatePath("/custom-tag-templates");
    return { success: true };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}
