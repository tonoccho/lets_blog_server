"use server";

import { revalidatePath } from "next/cache";
import {
  saveTagDesignSetting,
  generateTagDesign,
  type EmbedTagType,
  type SaveTagDesignSettingInput,
  type GenerateTagDesignResult,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface TagDesignFormState {
  error?: string;
  success?: boolean;
}

export async function saveTagDesignSettingAction(
  _prevState: TagDesignFormState,
  formData: FormData
): Promise<TagDesignFormState> {
  await requireAdminSession();

  const projectIdRaw = String(formData.get("projectId") ?? "").trim();
  const tagType = String(formData.get("tagType") ?? "").trim() as EmbedTagType;

  if (!tagType) {
    return { error: "不正なリクエストです。" };
  }

  // projectIdが空ならプロジェクト未紐付けサイト向けのグローバル既定(issue #763)。
  // 空文字をNumber()すると0になり、存在しないプロジェクトIDとして送ってしまうため、
  // 明示的にnullへ倒す。
  const projectId = projectIdRaw === "" ? null : Number(projectIdRaw);
  if (projectId !== null && !Number.isInteger(projectId)) {
    return { error: "不正なリクエストです。" };
  }

  const customCss = String(formData.get("customCss") ?? "").trim();
  const htmlTemplate = String(formData.get("htmlTemplate") ?? "").trim();
  const input: SaveTagDesignSettingInput = {
    presetId: String(formData.get("presetId") ?? "").trim(),
    backgroundColor: String(formData.get("backgroundColor") ?? "").trim(),
    textColor: String(formData.get("textColor") ?? "").trim(),
    accentColor: String(formData.get("accentColor") ?? "").trim(),
    customCss: customCss || undefined,
    htmlTemplate: htmlTemplate || undefined,
  };

  try {
    await saveTagDesignSetting(projectId, tagType, input);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(projectId === null ? "/admin/tag-design" : `/projects/${projectId}/tags`);
  return { success: true };
}

export async function generateTagDesignAction(
  projectId: number | null,
  tagType: EmbedTagType,
  prompt: string
): Promise<{ data?: GenerateTagDesignResult; error?: string }> {
  await requireAdminSession();

  try {
    const result = await generateTagDesign(projectId, tagType, prompt);
    return { data: result };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}
