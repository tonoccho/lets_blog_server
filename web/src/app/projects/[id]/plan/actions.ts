"use server";

import {
  sendArticlePlanChatMessage,
  suggestArticlePlanTitles,
  acceptArticlePlan,
  AcceptPlanResultItem,
  PlanChatMessage,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export type ActionResult<T> = { ok: true; data: T } | { ok: false; error: string };

export async function sendPlanChatMessage(
  projectId: number,
  history: PlanChatMessage[],
  message: string
): Promise<ActionResult<{ reply: string }>> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const response = await sendArticlePlanChatMessage(projectId, { history, message }, actor);
    return { ok: true, data: { reply: response.reply } };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  }
}

export async function suggestPlanTitles(
  projectId: number,
  history: PlanChatMessage[]
): Promise<ActionResult<{ titles: string[] }>> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const response = await suggestArticlePlanTitles(projectId, { history }, actor);
    return { ok: true, data: { titles: response.titles } };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  }
}

export async function acceptPlan(
  projectId: number,
  titles: string[]
): Promise<ActionResult<{ results: AcceptPlanResultItem[] }>> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const response = await acceptArticlePlan(projectId, { titles }, actor);
    return { ok: true, data: { results: response.results } };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  }
}
