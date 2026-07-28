"use server";

import { revalidatePath } from "next/cache";
import { disableTwoFactor, setupTwoFactor, verifyTwoFactorSetup } from "@/lib/apiClient";
import { requireSession } from "@/lib/session";

export interface TwoFactorSetupState {
  error?: string;
  qrCodeDataUrl?: string;
  backupCodes?: string[];
}

export async function startTwoFactorSetupAction(
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- useActionStateのシグネチャ上prevStateを受け取る必要がある
  _prevState: TwoFactorSetupState
): Promise<TwoFactorSetupState> {
  const session = await requireSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const result = await setupTwoFactor(actor);
    return { qrCodeDataUrl: result.qrCodeDataUrl, backupCodes: result.backupCodes };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export interface VerifyTwoFactorState {
  error?: string;
  success?: boolean;
}

export async function verifyTwoFactorSetupAction(
  _prevState: VerifyTwoFactorState,
  formData: FormData
): Promise<VerifyTwoFactorState> {
  const session = await requireSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const code = String(formData.get("code") ?? "").trim();
  if (!code) {
    return { error: "認証コードを入力してください。" };
  }

  try {
    await verifyTwoFactorSetup(code, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/settings/security");
  return { success: true };
}

export async function disableTwoFactorAction() {
  const session = await requireSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  await disableTwoFactor(actor);
  revalidatePath("/settings/security");
}
