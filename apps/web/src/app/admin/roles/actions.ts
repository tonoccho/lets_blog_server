"use server";

import { revalidatePath } from "next/cache";
import { assignRole, removeRole } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export async function assignRoleAction(userId: number, roleName: string) {
  await requireAdminSession();
  await assignRole(userId, roleName);
  revalidatePath("/admin/roles");
  revalidatePath("/users");
}

export async function removeRoleAction(userId: number, roleName: string) {
  await requireAdminSession();
  await removeRole(userId, roleName);
  revalidatePath("/admin/roles");
  revalidatePath("/users");
}
