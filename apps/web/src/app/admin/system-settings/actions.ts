"use server";

import { revalidatePath } from "next/cache";
import { applyComputeDevice, updateAppSettings } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface UpdateAppSettingsFormState {
  error?: string;
  success?: boolean;
}

/**
 * フォームに含まれる全項目をまとめてPUTする(issue #403)。空欄の項目は「未設定に戻す(環境変数へ
 * フォールバック)」として送信する。APIサーバー側で1つのトランザクションとして扱われ、いずれかの値が
 * 不正な場合はこの保存操作での変更が全てロールバックされる。
 *
 * "$"始まりのキーは除外する。useActionStateでバインドされたフォームがJS介入なしのネイティブ送信
 * (いわゆるMPAアクション)経路を通ると、Next.js/Reactが前回状態のエンコード等に使う内部制御フィールド
 * (例: $ACTION_REF_1)が同じFormDataに混入することがあり、これを未知の設定項目としてそのままAPIに
 * 送ってしまうと409エラーになるため(issue #444)。
 */
export async function updateAppSettingsAction(
  _prevState: UpdateAppSettingsFormState,
  formData: FormData
): Promise<UpdateAppSettingsFormState> {
  await requireAdminSession();

  const settings: Record<string, string> = {};
  for (const [key, value] of formData.entries()) {
    if (key.startsWith("$")) {
      continue;
    }
    settings[key] = String(value).trim();
  }

  try {
    await updateAppSettings(settings);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/admin/system-settings");
  return { success: true };
}

export interface ApplyComputeDeviceFormState {
  error?: string;
  success?: boolean;
}

/**
 * ComfyUI・Ollamaの演算デバイス(GPU / CPU)を切り替える(issue #1399 / #1585)。対象はフォームの `target`。platform-serviceは要求を受け付けたら
 * すぐ返し、切り替えは裏で進む(進行は画面が再取得して表示する)。そのためこの操作は§10.5の
 * 3秒予算の対象である。
 */
export async function applyComputeDeviceAction(
  _prevState: ApplyComputeDeviceFormState,
  formData: FormData
): Promise<ApplyComputeDeviceFormState> {
  await requireAdminSession();

  const device = formData.get("device");
  if (device !== "GPU" && device !== "CPU") {
    return { error: "切り替え先として GPU か CPU を選んでください。" };
  }

  const target = formData.get("target");
  if (target !== "comfyui" && target !== "ollama") {
    return { error: "切り替える対象(ComfyUI か Ollama)が不明です。" };
  }

  try {
    await applyComputeDevice(device, target);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/admin/system-settings");
  return { success: true };
}
