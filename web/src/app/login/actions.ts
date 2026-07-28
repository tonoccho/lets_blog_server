"use server";

import { confirmPasswordReset, requestPasswordReset } from "@/lib/apiClient";

export interface ForgotPasswordState {
  error?: string;
  success?: boolean;
}

export async function requestPasswordResetAction(
  _prevState: ForgotPasswordState,
  formData: FormData
): Promise<ForgotPasswordState> {
  const email = String(formData.get("email") ?? "").trim();
  if (!email) {
    return { error: "メールアドレスを入力してください。" };
  }

  try {
    await requestPasswordReset(email);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  return { success: true };
}

export interface PasswordResetState {
  error?: string;
  success?: boolean;
}

export async function confirmPasswordResetAction(
  _prevState: PasswordResetState,
  formData: FormData
): Promise<PasswordResetState> {
  const token = String(formData.get("token") ?? "").trim();
  const newPassword = String(formData.get("newPassword") ?? "").trim();

  if (!token) {
    return { error: "トークンが指定されていません。" };
  }
  if (newPassword.length < 8) {
    return { error: "パスワードは8文字以上である必要があります。" };
  }

  try {
    await confirmPasswordReset(token, newPassword);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  return { success: true };
}
