"use server";

import { revalidatePath } from "next/cache";
import { updateSite } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface UpdateSiteState {
  error?: string;
  success?: boolean;
  connectionCheckStatus?: "SUCCESS" | "FAILED" | null;
}

const CREDENTIAL_FIELDS = [
  "baseUrl",
  "username",
  "serviceId",
  "apiKey",
  "managementApiKey",
  "postsEndpoint",
  "categoriesEndpoint",
  "tagsEndpoint",
  "transport",
  "sshHost",
  "sshPort",
  "sshUser",
  "wpPath",
  "sshPrivateKeyPem",
  "sshKeyPairId",
  "sshHostKeyFingerprint",
];

export async function updateSiteAction(
  siteId: number,
  _prevState: UpdateSiteState,
  formData: FormData
): Promise<UpdateSiteState> {
  await requireAdminSession();

  const name = String(formData.get("name") ?? "").trim();
  const credentials: Record<string, string> = {};
  for (const field of CREDENTIAL_FIELDS) {
    const value = String(formData.get(field) ?? "").trim();
    if (value) {
      credentials[field] = value;
    }
  }

  try {
    const site = await updateSite(
      siteId,
      {
        name: name || undefined,
        credentials: Object.keys(credentials).length > 0 ? credentials : undefined,
    });
    revalidatePath("/sites");
    revalidatePath(`/sites/${siteId}/edit`);
    return { success: true, connectionCheckStatus: site.connectionCheckStatus };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}
