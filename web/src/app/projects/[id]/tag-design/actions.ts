"use server";

import { revalidatePath } from "next/cache";
import { saveTagDesignSetting, type EmbedTagType, type SaveTagDesignSettingInput } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface TagDesignFormState {
  error?: string;
  success?: boolean;
}

export async function saveTagDesignSettingAction(
  _prevState: TagDesignFormState,
  formData: FormData
): Promise<TagDesignFormState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const projectIdRaw = String(formData.get("projectId") ?? "").trim();
  const tagType = String(formData.get("tagType") ?? "").trim() as EmbedTagType;
  const projectId = Number(projectIdRaw);

  if (!projectIdRaw || !tagType) {
    return { error: "不正なリクエストです。" };
  }

  const input: SaveTagDesignSettingInput = {
    presetId: String(formData.get("presetId") ?? "").trim(),
    backgroundColor: String(formData.get("backgroundColor") ?? "").trim(),
    textColor: String(formData.get("textColor") ?? "").trim(),
    accentColor: String(formData.get("accentColor") ?? "").trim(),
  };

  try {
    await saveTagDesignSetting(projectId, tagType, input, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}/tag-design`);
  return { success: true };
}
