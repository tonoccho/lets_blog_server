"use server";

import { revalidatePath } from "next/cache";
import {
  updateUserProfile,
  updateUserPreferences,
  disableTwoFactor,
  setupTwoFactor,
  verifyTwoFactorSetup,
  type CustomLink,
  type SocialLinks,
} from "@/lib/apiClient";
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

export interface UpdatePreferencesState {
  error?: string;
  success?: boolean;
}

/** 個人設定(言語・タイムゾーン)。システム画面から移動(issue #185)。本人の設定のみ変更する。 */
export async function updatePreferencesAction(
  _prevState: UpdatePreferencesState,
  formData: FormData
): Promise<UpdatePreferencesState> {
  const session = await requireSession();
  const userId = Number(session.user.id);
  const actor = { id: userId, role: session.user.role };

  const locale = String(formData.get("locale") ?? "").trim();
  const timezone = String(formData.get("timezone") ?? "").trim();

  if (!locale || !timezone) {
    return { error: "言語とタイムゾーンを選択してください。" };
  }

  try {
    await updateUserPreferences(userId, { locale, timezone }, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/users/${userId}/edit`);
  return { success: true };
}

export interface TwoFactorSetupState {
  error?: string;
  qrCodeDataUrl?: string;
  backupCodes?: string[];
}

/** 2FA(TOTP)設定。/settings/security から移動(issue #189)。本人のみが対象。 */
export async function startTwoFactorSetupAction(
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- useActionStateのシグネチャ上prevStateを受け取る必要がある
  _prevState: TwoFactorSetupState
): Promise<TwoFactorSetupState> {
  const session = await requireSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const result = await setupTwoFactor(actor);
    return { qrCodeDataUrl: result.qrCodeDataUrl, backupCodes: result.backupCodes };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export interface VerifyTwoFactorState {
  error?: string;
  success?: boolean;
}

export async function verifyTwoFactorSetupAction(
  _prevState: VerifyTwoFactorState,
  formData: FormData
): Promise<VerifyTwoFactorState> {
  const session = await requireSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const code = String(formData.get("code") ?? "").trim();
  if (!code) {
    return { error: "認証コードを入力してください。" };
  }

  try {
    await verifyTwoFactorSetup(code, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/users/${session.user.id}/edit`);
  return { success: true };
}

export async function disableTwoFactorAction() {
  const session = await requireSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  await disableTwoFactor(actor);
  revalidatePath(`/users/${session.user.id}/edit`);
}
