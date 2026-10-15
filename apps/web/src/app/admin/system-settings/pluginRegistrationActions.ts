"use server";

import { revalidatePath } from "next/cache";
import {
  activatePluginRegistration,
  createPluginRegistration,
  deletePluginRegistration,
  updatePluginRegistration,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/** `saved: true` は、操作が成功したこと。`error` は、画面に出す理由。 */
export interface PluginRegistrationActionState {
  error?: string;
  saved?: boolean;
}

function field(formData: FormData, name: string): string {
  return String(formData.get(name) ?? "").trim();
}

/** apiClient のエラー文言 `APIエラー (409): {"message":"..."}` から message だけを取り出す。取り出せなければそのまま。 */
function errorMessage(err: unknown): string {
  if (!(err instanceof Error)) {
    return String(err);
  }
  const match = /^APIエラー \(\d+\): ([\s\S]*)$/.exec(err.message);
  if (match) {
    try {
      const message = (JSON.parse(match[1]) as { message?: unknown }).message;
      if (typeof message === "string") {
        return message;
      }
    } catch {
      // JSON ではない本文は、そのまま表示する
    }
  }
  return err.message;
}

async function run(operation: () => Promise<unknown>): Promise<PluginRegistrationActionState> {
  try {
    await operation();
    revalidatePath("/admin/system-settings");
    return { saved: true };
  } catch (err) {
    return { error: errorMessage(err) };
  }
}

/** 登録の追加(id が無い)または編集(id がある)。接続先の検査の理由はそのまま画面に返す(issue #1807)。 */
export async function savePluginRegistrationAction(
  _prevState: PluginRegistrationActionState,
  formData: FormData,
): Promise<PluginRegistrationActionState> {
  await requireAdminSession();
  const id = field(formData, "id");
  const name = field(formData, "name");
  const url = field(formData, "url");
  const drawioUrl = field(formData, "drawioUrl") || null;
  if (id) {
    return run(() => updatePluginRegistration(Number(id), { name, url, drawioUrl }));
  }
  return run(() => createPluginRegistration({ kind: field(formData, "kind"), name, url, drawioUrl }));
}

export async function deletePluginRegistrationAction(
  _prevState: PluginRegistrationActionState,
  formData: FormData,
): Promise<PluginRegistrationActionState> {
  await requireAdminSession();
  return run(() => deletePluginRegistration(Number(field(formData, "id"))));
}

export async function activatePluginRegistrationAction(
  _prevState: PluginRegistrationActionState,
  formData: FormData,
): Promise<PluginRegistrationActionState> {
  await requireAdminSession();
  return run(() => activatePluginRegistration(Number(field(formData, "id"))));
}
