"use server";

import { revalidatePath } from "next/cache";
import {
  saveTagDesignSetting,
  startTagDesignGenerationJob,
  type EmbedTagType,
  type SaveTagDesignSettingInput,
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

  // FormData(multipart)は改行を CRLF にするため、保存した内容が生成結果(LF)と食い違わないよう LF に揃える(issue #1409)。
  const normalizeNewlines = (value: string) => value.replace(/\r\n?/g, "\n");
  const customCss = normalizeNewlines(String(formData.get("customCss") ?? "")).trim();
  const htmlTemplate = normalizeNewlines(String(formData.get("htmlTemplate") ?? "")).trim();
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

/**
 * タグデザインのAI生成(プロジェクト個別・グローバル)を非同期ジョブとして要求する(issue #1409)。
 * 生成の完了を待たず、生成と同時に保存もしない。受理されたジョブ(ID・状態)だけを返し、結果は処理キューの
 * 「結果を見る」で確認して「保存」(`saveTagDesignSettingAction`)で書き込む。`status` が `failed` なのは、
 * 待ち行列が満杯でジョブが作られたうえで失敗として返ったとき。
 */
export async function generateTagDesignAction(
  projectId: number | null,
  tagType: EmbedTagType,
  prompt: string
): Promise<{ jobId?: number; status?: string; error?: string }> {
  await requireAdminSession();

  try {
    const job = await startTagDesignGenerationJob(projectId, tagType, prompt);
    return { jobId: job.id, status: job.status };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}
