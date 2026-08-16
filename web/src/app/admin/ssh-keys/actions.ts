"use server";

import { revalidatePath } from "next/cache";
import { createSshKeyPair, deleteSshKeyPair, GeneratedSshKeyPair } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface CreateSshKeyPairResult {
  error?: string;
  keyPair?: GeneratedSshKeyPair;
}

export async function createSshKeyPairAction(name: string, comment: string): Promise<CreateSshKeyPairResult> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const keyPair = await createSshKeyPair({ name, comment: comment || undefined }, actor);
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
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    await deleteSshKeyPair(id, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/admin/ssh-keys");
  return {};
}
