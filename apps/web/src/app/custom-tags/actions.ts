"use server";

import { startCustomTagGenerationJob, validateCustomTag, type GenerateCustomTagInput, type ValidationResult, type ValidateCustomTagRequest } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * カスタムタグのAI生成を非同期ジョブとして要求する(issue #1409)。生成の完了を待たず、生成と同時に
 * 保存もしない。受理されたジョブ(ID・状態)だけを返し、結果は処理キューの「結果を見る」で確認して
 * 「保存」(`upsertProjectCustomTagAction`)で登録する。`status` が `failed` なのは、待ち行列が満杯で
 * ジョブが作られたうえで失敗として返ったとき。
 */
export async function generateCustomTagAction(
  input: GenerateCustomTagInput
): Promise<{ jobId?: number; status?: string; error?: string }> {
  await requireAdminSession();

  try {
    const job = await startCustomTagGenerationJob(input);
    return { jobId: job.id, status: job.status };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export async function validateCustomTagAction(
  input: ValidateCustomTagRequest
): Promise<{ data?: ValidationResult; error?: string }> {
  await requireAdminSession();

  try {
    const result = await validateCustomTag(input);
    return { data: result };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}
