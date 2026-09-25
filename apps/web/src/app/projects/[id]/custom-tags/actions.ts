"use server";

import { revalidatePath } from "next/cache";
import {
  createCustomTag,
  deleteCustomTag,
  updateCustomTag,
  updateProjectCssSelectorPrefix,
  type CustomTagFormat,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface CustomTagFormState {
  error?: string;
  success?: boolean;
}

export interface CssSelectorPrefixFormState {
  error?: string;
  success?: boolean;
}

/** CSSセレクタのプリフィックス設定(issue #298)。空欄で保存するとプロジェクトのslugに戻る。 */
export async function updateProjectCssSelectorPrefixAction(
  projectId: number,
  _prevState: CssSelectorPrefixFormState,
  formData: FormData
): Promise<CssSelectorPrefixFormState> {
  await requireAdminSession();
  const cssSelectorPrefix = String(formData.get("cssSelectorPrefix") ?? "").trim();

  try {
    await updateProjectCssSelectorPrefix(projectId, cssSelectorPrefix);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}/tags`);
  return { success: true };
}

/** プロジェクト詳細のカスタムタグ画面向け。projectIdはフォームの隠しフィールドに固定値として埋め込まれる。 */
export async function upsertProjectCustomTagAction(
  _prevState: CustomTagFormState,
  formData: FormData
): Promise<CustomTagFormState> {
  await requireAdminSession();

  const idRaw = String(formData.get("id") ?? "").trim();
  const tagName = String(formData.get("tagName") ?? "").trim();
  const htmlTemplate = String(formData.get("htmlTemplate") ?? "").trim();
  const description = String(formData.get("description") ?? "").trim();
  const cssContent = String(formData.get("cssContent") ?? "").trim();
  const tagFormatRaw = String(formData.get("tagFormat") ?? "").trim();
  const projectIdRaw = String(formData.get("projectId") ?? "").trim();

  if (!tagName || !htmlTemplate) {
    return { error: "タグ名とHTMLテンプレートは必須です。" };
  }
  if (!projectIdRaw) {
    return { error: "プロジェクトIDが不正です。" };
  }
  const projectId = Number(projectIdRaw);
  const tagFormat: CustomTagFormat = tagFormatRaw === "INLINE" ? "INLINE" : "BLOCK";

  try {
    const input = {
      tagName,
      htmlTemplate,
      description: description || undefined,
      cssContent: cssContent || undefined,
      tagFormat,
      projectId,
    };
    if (idRaw) {
      await updateCustomTag(Number(idRaw), input);
    } else {
      await createCustomTag(input);
    }
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}/tags`);
  return { success: true };
}

export async function deleteProjectCustomTagAction(projectId: number, id: number) {
  await requireAdminSession();
  await deleteCustomTag(id);
  revalidatePath(`/projects/${projectId}/tags`);
}
