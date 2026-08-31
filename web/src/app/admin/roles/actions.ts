"use server";

import { revalidatePath } from "next/cache";
import { assignRole, removeRole } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export async function assignRoleAction(userId: number, roleName: string) {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  await assignRole(userId, roleName, actor);
  revalidatePath("/admin/roles");
  revalidatePath("/users");
}

export async function removeRoleAction(userId: number, roleName: string) {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  await removeRole(userId, roleName, actor);
  revalidatePath("/admin/roles");
  revalidatePath("/users");
}
