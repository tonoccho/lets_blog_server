"use server";

import { revalidatePath } from "next/cache";
import {
  cloneCustomTagTemplate,
  createCustomTagTemplate,
  deleteCustomTagTemplate,
  publishCustomTagTemplate,
  unpublishCustomTagTemplate,
  updateCustomTagTemplate,
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const result = await createCustomTagTemplate(input, actor);
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const result = await updateCustomTagTemplate(id, input, actor);
    revalidatePath("/custom-tag-templates");
    return { data: result };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function publishCustomTagTemplateAction(
  id: number
): Promise<{ data?: CustomTagTemplate; error?: string }> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const result = await publishCustomTagTemplate(id, actor);
    revalidatePath("/custom-tag-templates");
    return { data: result };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function unpublishCustomTagTemplateAction(
  id: number
): Promise<{ data?: CustomTagTemplate; error?: string }> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const result = await unpublishCustomTagTemplate(id, actor);
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const result = await cloneCustomTagTemplate(id, input, actor);
    revalidatePath("/custom-tag-templates");
    return { data: result };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function deleteCustomTagTemplateAction(
  id: number
): Promise<CustomTagTemplateActionState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    await deleteCustomTagTemplate(id, actor);
    revalidatePath("/custom-tag-templates");
    return { success: true };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}
