"use server";

import { revalidatePath } from "next/cache";
import { createUser, deleteUser } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface CreateUserState {
  error?: string;
  success?: boolean;
}

export async function createUserAction(
  _prevState: CreateUserState,
  formData: FormData
): Promise<CreateUserState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

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
    await createUser({ email, password, role }, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/users");
  return { success: true };
}

export async function deleteUserAction(id: number) {
  const session = await requireAdminSession();

  if (String(id) === session.user.id) {
    throw new Error("自分自身のアカウントは削除できません。");
  }

  await deleteUser(id, { id: Number(session.user.id), role: session.user.role });
  revalidatePath("/users");
}
