"use server";

import { revalidatePath } from "next/cache";
import {
  activateProjectPluginRegistration,
  clearProjectPluginRegistrationCredential,
  createProjectPluginRegistration,
  deleteProjectPluginRegistration,
  listProjectPluginRegistrations,
  updateProjectPluginRegistration,
} from "@/lib/apiClient";
import type { ProjectPluginRegistration } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/** `error` は、画面に出す理由。無ければ成功。 */
export interface ProjectPluginRegistrationActionResult {
  error?: string;
}

export interface SaveProjectPluginRegistrationInput {
  /** 無ければ追加、あれば編集(編集では種類は変えない)。 */
  id?: number;
  kind: string;
  name: string;
  url: string;
  drawioUrl: string | null;
  credential: string | null;
}

/** apiClient のエラー文言 `APIエラー (400): {"message":"..."}` から message だけを取り出す。取り出せなければそのまま。 */
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

async function run(projectId: number, operation: () => Promise<unknown>): Promise<ProjectPluginRegistrationActionResult> {
  try {
    await operation();
    revalidatePath(`/projects/${projectId}`);
    return {};
  } catch (err) {
    return { error: errorMessage(err) };
  }
}

function blankToNull(value: string | null): string | null {
  const trimmed = (value ?? "").trim();
  return trimmed === "" ? null : trimmed;
}

export async function fetchProjectPluginRegistrationsAction(
  projectId: number,
): Promise<{ data?: ProjectPluginRegistration[]; error?: string }> {
  await requireAdminSession();
  try {
    return { data: await listProjectPluginRegistrations(projectId) };
  } catch (err) {
    return { error: errorMessage(err) };
  }
}

/** 上書きの追加または編集。接続先の検査の理由はそのまま画面に返す。 */
export async function saveProjectPluginRegistrationAction(
  projectId: number,
  input: SaveProjectPluginRegistrationInput,
): Promise<ProjectPluginRegistrationActionResult> {
  await requireAdminSession();
  const name = input.name.trim();
  const url = input.url.trim();
  const drawioUrl = blankToNull(input.drawioUrl);
  const credential = blankToNull(input.credential);
  if (input.id !== undefined) {
    return run(projectId, () => updateProjectPluginRegistration(projectId, input.id as number, { name, url, drawioUrl, credential }));
  }
  return run(projectId, () => createProjectPluginRegistration(projectId, { kind: input.kind, name, url, drawioUrl, credential }));
}

export async function deleteProjectPluginRegistrationAction(
  projectId: number,
  id: number,
): Promise<ProjectPluginRegistrationActionResult> {
  await requireAdminSession();
  return run(projectId, () => deleteProjectPluginRegistration(projectId, id));
}

export async function activateProjectPluginRegistrationAction(
  projectId: number,
  id: number,
): Promise<ProjectPluginRegistrationActionResult> {
  await requireAdminSession();
  return run(projectId, () => activateProjectPluginRegistration(projectId, id));
}

export async function clearProjectPluginRegistrationCredentialAction(
  projectId: number,
  id: number,
): Promise<ProjectPluginRegistrationActionResult> {
  await requireAdminSession();
  return run(projectId, () => clearProjectPluginRegistrationCredential(projectId, id));
}
