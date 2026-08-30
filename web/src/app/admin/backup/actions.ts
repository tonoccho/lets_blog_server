"use server";

import { restoreBackup } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface RestoreBackupFormState {
  error?: string;
  success?: boolean;
}

export async function restoreBackupAction(
  _prevState: RestoreBackupFormState,
  formData: FormData
): Promise<RestoreBackupFormState> {
  await requireAdminSession();

  const file = formData.get("file");
  if (!(file instanceof File) || file.size === 0) {
    return { error: "バックアップファイルを選択してください。" };
  }
  const acknowledgeKeyMismatch = formData.get("acknowledgeKeyMismatch") === "on";

  try {
    await restoreBackup(file, acknowledgeKeyMismatch);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  return { success: true };
}
