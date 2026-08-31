"use server";

import { signup } from "@/lib/apiClient";

export interface SignupState {
  error?: string;
}

export async function signupAction(_prevState: SignupState, formData: FormData): Promise<SignupState> {
  const email = String(formData.get("email") ?? "").trim();
  const password = String(formData.get("password") ?? "").trim();

  if (!email || !password) {
    return { error: "メールアドレスとパスワードを入力してください。" };
  }
  if (password.length < 8) {
    return { error: "パスワードは8文字以上である必要があります。" };
  }

  try {
    await signup(email, password);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  return {};
}
