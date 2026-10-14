"use server";

import { revalidatePath } from "next/cache";
import {
  applyExtensionsConnections,
  fetchExtensionsConnections,
  type ExtensionsImportApplyResult,
  type ExtensionsImportFetchResult,
  type ExtensionsImportRoute,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface ExtensionsFetchState extends Partial<ExtensionsImportFetchResult> {
  error?: string;
  portalUrl?: string;
  route?: ExtensionsImportRoute;
}

export interface ExtensionsApplyState extends Partial<ExtensionsImportApplyResult> {
  error?: string;
}

function field(formData: FormData, name: string): string {
  return String(formData.get(name) ?? "").trim();
}

/** apiClient のエラー文言 `APIエラー (409): {"message":"..."}` から message だけを取り出す。取り出せなければそのまま。 */
function errorMessage(err: unknown): string {
  if (!(err instanceof Error)) {
    return String(err);
  }
  const match = /^APIエラー \(\d+\): ([\s\S]*)$/.exec(err.message);
  if (match) {
    try {
      const message = (JSON.parse(match[1]) as { message?: unknown }).message;
      if (typeof message === "string") {
        return message;
      }
    } catch {
      // JSON ではない本文は、そのまま表示する
    }
  }
  return err.message;
}

/** ポータルの `GET /api/connections` から候補を取得する(issue #1783)。保存はしない。 */
export async function fetchExtensionsConnectionsAction(
  _prevState: ExtensionsFetchState,
  formData: FormData,
): Promise<ExtensionsFetchState> {
  await requireAdminSession();
  const portalUrl = field(formData, "portalUrl");
  const route: ExtensionsImportRoute = formData.get("route") === "OTHER_HOST" ? "OTHER_HOST" : "SAME_PC";
  try {
    const result = await fetchExtensionsConnections(portalUrl, route);
    return { ...result, portalUrl, route };
  } catch (err) {
    return { error: errorMessage(err), portalUrl, route };
  }
}

/** 管理者が確認した候補(`value:<key>`)だけを保存する(issue #1783)。 */
export async function applyExtensionsConnectionsAction(
  _prevState: ExtensionsApplyState,
  formData: FormData,
): Promise<ExtensionsApplyState> {
  await requireAdminSession();
  const portalUrl = field(formData, "portalUrl");
  const values: Record<string, string> = {};
  for (const [name, value] of formData.entries()) {
    if (name.startsWith("value:")) {
      values[name.slice("value:".length)] = String(value);
    }
  }
  try {
    const result = await applyExtensionsConnections(portalUrl, values);
    revalidatePath("/admin/system-settings");
    return result;
  } catch (err) {
    return { error: errorMessage(err) };
  }
}
