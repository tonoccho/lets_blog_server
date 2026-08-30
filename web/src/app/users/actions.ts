"use server";

import { revalidatePath } from "next/cache";
import { createUser, deleteUser } from "@/lib/apiClient";
import { requireAdminSession, getViewerProfile } from "@/lib/session";

export interface CreateUserState {
  error?: string;
  success?: boolean;
}

export async function createUserAction(
  _prevState: CreateUserState,
  formData: FormData
): Promise<CreateUserState> {
  await requireAdminSession();

  const email = String(formData.get("email") ?? "").trim();
  const password = String(formData.get("password") ?? "").trim();
  const role = String(formData.get("role") ?? "").trim();

  if (!email || !password) {
    return { error: "メールアドレスとパスワードを入力してください。" };
  }
  if (role !== "admin" && role !== "user") {
    return { error: "roleを選択してください。" };
  }

  try {
    await createUser({ email, password, role });
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/users");
  return { success: true };
}

export async function deleteUserAction(id: number) {
  await requireAdminSession();

  // session.user.idはKeycloakのsub(UUID)であり、ローカルの数値ユーザーIDではない(issue #784)。
  // 以前はこの比較が常にfalseで、自己削除のガードが機能していなかった。
  //
  // identity-serviceのdeleteにはサーバー側の自己削除禁止が無く、このガードが唯一の防御である。
  // そのため自分が誰かを確定できないときは削除を通さない(フェイルクローズ)。`viewer?.id === id`
  // だけだと取得失敗時にundefined !== idとなってガードを素通りしてしまう。
  const viewer = await getViewerProfile();
  if (viewer == null) {
    throw new Error("ログイン中のユーザー情報を取得できなかったため、削除を中止しました。");
  }
  if (viewer.id === id) {
    throw new Error("自分自身のアカウントは削除できません。");
  }

  await deleteUser(id);
  revalidatePath("/users");
}
