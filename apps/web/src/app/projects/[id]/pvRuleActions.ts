"use server";

import { revalidatePath } from "next/cache";
import { addProjectPvRule, deleteProjectPvRule, resendProjectPvRules } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * プロジェクト設定画面の「SNS 告知」欄の PV 達成ルール(issue #1578)のServer Action。いずれも admin 限定。
 * ルールを保存したあとの本番サイトへの送信はバックエンドが行い、結果(送信失敗を含む)は画面の再検証で読み直す。
 */

export interface PvRuleActionState {
  error?: string;
  success?: boolean;
}

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/**
 * ルールの追加。期間(daily / total)と閾値(1以上の整数)はここで検証する。
 * 本番サイトへ送れなくてもルールは保存されるので成功として返し、失敗は一覧側の送信状態に出る。
 */
export async function addProjectPvRuleAction(
  projectId: number,
  _prevState: PvRuleActionState,
  formData: FormData
): Promise<PvRuleActionState> {
  await requireAdminSession();

  const period = String(formData.get("period") ?? "");
  if (period !== "daily" && period !== "total") {
    return { error: "期間を 1日 か 累計 から選んでください。" };
  }
  const rawThreshold = String(formData.get("threshold") ?? "").trim();
  const threshold = Number(rawThreshold);
  if (rawThreshold === "" || !Number.isInteger(threshold) || threshold < 1) {
    return { error: "閾値は 1 以上の整数で入力してください。" };
  }

  try {
    await addProjectPvRule(projectId, { period, threshold });
    revalidatePath(`/projects/${projectId}/settings/sns`);
    return { success: true };
  } catch (err) {
    return { error: messageOf(err) };
  }
}

export async function deleteProjectPvRuleAction(projectId: number, ruleId: string): Promise<PvRuleActionState> {
  await requireAdminSession();

  try {
    await deleteProjectPvRule(projectId, ruleId);
    revalidatePath(`/projects/${projectId}/settings/sns`);
    return { success: true };
  } catch (err) {
    return { error: messageOf(err) };
  }
}

/** 再送。まだ届かなければ理由を返す。成否にかかわらず送信状態を読み直す。 */
export async function resendProjectPvRulesAction(projectId: number): Promise<PvRuleActionState> {
  await requireAdminSession();

  try {
    const view = await resendProjectPvRules(projectId);
    revalidatePath(`/projects/${projectId}/settings/sns`);
    return view.send.state === "FAILED" ? { error: view.send.error ?? "再送に失敗しました。" } : { success: true };
  } catch (err) {
    return { error: messageOf(err) };
  }
}
