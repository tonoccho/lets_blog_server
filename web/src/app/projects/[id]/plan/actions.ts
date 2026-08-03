"use server";

import {
  sendArticlePlanChatMessage,
  suggestArticlePlanTitles,
  acceptArticlePlan,
  listArticlePlanSessions,
  getArticlePlanSession,
  listArticlePlanIssues,
  AcceptPlanResultItem,
  ArticlePlanSessionSummary,
  ArticlePlanSessionDetail,
  RepositoryIssue,
  RepositoryIssueState,
  PlanChatMessage,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export type ActionResult<T> = { ok: true; data: T } | { ok: false; error: string };

export async function sendPlanChatMessage(
  projectId: number,
  history: PlanChatMessage[],
  message: string,
  sessionId: number | null
): Promise<ActionResult<{ reply: string; sessionId: number }>> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const response = await sendArticlePlanChatMessage(projectId, { history, message, sessionId }, actor);
    return { ok: true, data: { reply: response.reply, sessionId: response.sessionId } };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  }
}

export async function loadPlanSessions(
  projectId: number
): Promise<ActionResult<{ sessions: ArticlePlanSessionSummary[] }>> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const sessions = await listArticlePlanSessions(projectId, actor);
    return { ok: true, data: { sessions } };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  }
}

export async function loadPlanSession(
  projectId: number,
  sessionId: number
): Promise<ActionResult<{ session: ArticlePlanSessionDetail }>> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const detail = await getArticlePlanSession(projectId, sessionId, actor);
    return { ok: true, data: { session: detail } };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  }
}

export async function loadRepositoryIssues(
  projectId: number,
  state: RepositoryIssueState
): Promise<ActionResult<{ issues: RepositoryIssue[] }>> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const issues = await listArticlePlanIssues(projectId, state, actor);
    return { ok: true, data: { issues } };
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
