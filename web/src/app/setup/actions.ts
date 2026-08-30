"use server";

import { setupInitialAdmin } from "@/lib/apiClient";

export interface SetupState {
  error?: string;
}

/**
 * 初期管理者を作成する(issue #824 で認可の要否を判断)。
 *
 * **意図的に未認証**。初期セットアップはまだ誰もアカウントを持っていない状態で実行するため、
 * ここに `requireSession()` / `requireAdminSession()` を置くと永久に実行できなくなる。
 *
 * 保護はサーバー側が担う。`POST /api/auth/setup` は legacy-api の `PUBLIC_PATHS` に入っている
 * (ADR-0008)一方、`UserService#setupInitialAdmin` が既にユーザーが存在する場合は拒否する。
 * つまり「一度セットアップが済んだら二度目は通らない」ことで実質的に閉じている。
 * `/api/auth/setup-status` の `needsSetup` も同じ判定を返す。
 */
export async function setupAction(_prevState: SetupState, formData: FormData): Promise<SetupState> {
  const email = String(formData.get("email") ?? "").trim();
  const password = String(formData.get("password") ?? "").trim();

  if (!email || !password) {
    return { error: "メールアドレスとパスワードを入力してください。" };
  }
  if (password.length < 8) {
    return { error: "パスワードは8文字以上である必要があります。" };
  }

  try {
    await setupInitialAdmin(email, password);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  return {};
}
