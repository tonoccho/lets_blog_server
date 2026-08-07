"use server";

import { revalidatePath } from "next/cache";
import { createCustomTag, deleteCustomTag, updateCustomTag, generateCustomTag, validateCustomTag, type GenerateCustomTagInput, type CustomTag, type ValidationResult, type ValidateCustomTagRequest } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface CustomTagFormState {
  error?: string;
  success?: boolean;
}

export async function upsertCustomTagAction(
  _prevState: CustomTagFormState,
  formData: FormData
): Promise<CustomTagFormState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const idRaw = String(formData.get("id") ?? "").trim();
  const tagName = String(formData.get("tagName") ?? "").trim();
  const htmlTemplate = String(formData.get("htmlTemplate") ?? "").trim();
  const description = String(formData.get("description") ?? "").trim();
  const cssContent = String(formData.get("cssContent") ?? "").trim();
  const projectIdRaw = String(formData.get("projectId") ?? "").trim();

  if (!tagName || !htmlTemplate) {
    return { error: "タグ名とHTMLテンプレートは必須です。" };
  }

  try {
    const input = {
      tagName,
      htmlTemplate,
      description: description || undefined,
      cssContent: cssContent || undefined,
      projectId: projectIdRaw ? Number(projectIdRaw) : null,
    };
    if (idRaw) {
      await updateCustomTag(Number(idRaw), input, actor);
    } else {
      await createCustomTag(input, actor);
    }
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/custom-tags");
  return { success: true };
}

export async function deleteCustomTagAction(id: number) {
  const session = await requireAdminSession();
  await deleteCustomTag(id, { id: Number(session.user.id), role: session.user.role });
  revalidatePath("/custom-tags");
}

export async function generateCustomTagAction(
  input: GenerateCustomTagInput
): Promise<{ data?: CustomTag; error?: string }> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const result = await generateCustomTag(input, actor);
    return { data: result };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function validateCustomTagAction(
  input: ValidateCustomTagRequest
): Promise<{ data?: ValidationResult; error?: string }> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const result = await validateCustomTag(input, actor);
    return { data: result };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}
