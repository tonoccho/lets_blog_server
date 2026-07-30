"use server";

import { revalidatePath } from "next/cache";
import { updateUserProfile, type CustomLink, type SocialLinks } from "@/lib/apiClient";
import { requireSession } from "@/lib/session";

export interface UpdateProfileState {
  error?: string;
  success?: boolean;
}

const SOCIAL_LINK_KEYS: (keyof SocialLinks)[] = [
  "facebook",
  "youtube",
  "whatsapp",
  "tiktok",
  "instagram",
  "wechat",
  "x",
  "threads",
  "github",
  "pinterest",
  "meetup",
  "line",
  "linkedin",
  "hatena",
];

export async function updateUserProfileAction(
  userId: number,
  _prevState: UpdateProfileState,
  formData: FormData
): Promise<UpdateProfileState> {
  const session = await requireSession();

  const isSelf = session.user.id === String(userId);
  if (!isSelf && session.user.role !== "admin") {
    return { error: "この操作を行う権限がありません。" };
  }

  const field = (name: string) => {
    const value = String(formData.get(name) ?? "").trim();
    return value === "" ? null : value;
  };

  const actor = { id: Number(session.user.id), role: session.user.role };

  const customLinksJson = String(formData.get("customLinks") ?? "[]");
  let customLinks: CustomLink[];
  try {
    customLinks = JSON.parse(customLinksJson);
  } catch {
    return { error: "カスタムリンクの解析に失敗しました。" };
  }

  const socialLinks = Object.fromEntries(
    SOCIAL_LINK_KEYS.map((key) => [key, field(`socialLinks.${key}`)])
  ) as unknown as SocialLinks;

  try {
    await updateUserProfile(
      userId,
      {
        firstName: field("firstName"),
        lastName: field("lastName"),
        displayName: field("displayName"),
        nickname: field("nickname"),
        websiteUrl: field("websiteUrl"),
        bio: field("bio"),
        locale: field("locale"),
        avatarUrl: field("avatarUrl"),
        department: field("department"),
        position: field("position"),
        socialLinks,
        customLinks,
      },
      actor
    );
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/users/${userId}/edit`);
  return { success: true };
}
