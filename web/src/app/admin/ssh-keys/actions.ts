"use server";

import { revalidatePath } from "next/cache";
import { createSshKeyPair, deleteSshKeyPair, GeneratedSshKeyPair } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface CreateSshKeyPairResult {
  error?: string;
  keyPair?: GeneratedSshKeyPair;
}

export async function createSshKeyPairAction(name: string, comment: string): Promise<CreateSshKeyPairResult> {
  await requireAdminSession();

  try {
    const keyPair = await createSshKeyPair({ name, comment: comment || undefined });
    revalidatePath("/admin/ssh-keys");
    return { keyPair };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export interface DeleteSshKeyPairResult {
  error?: string;
}

export async function deleteSshKeyPairAction(id: number): Promise<DeleteSshKeyPairResult> {
  await requireAdminSession();

  try {
    await deleteSshKeyPair(id);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/admin/ssh-keys");
  return {};
}
