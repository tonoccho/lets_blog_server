"use server";

import {
  sendArticlePlanChatMessage,
  suggestArticlePlanTitles,
  acceptArticlePlan,
  listArticlePlanSessions,
  getArticlePlanSession,
  getArticlePlanSessionByIssue,
  getArticlePlanIssueDescription,
  listArticlePlanIssues,
  suggestArticleStructure,
  acceptArticleStructure,
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
  sessionId: number | null,
  githubIssueNumber: number | null
): Promise<ActionResult<{ reply: string; sessionId: number }>> {
  await requireAdminSession();

  try {
    const response = await sendArticlePlanChatMessage(
      projectId,
      { history, message, sessionId, githubIssueNumber });
    return { ok: true, data: { reply: response.reply, sessionId: response.sessionId } };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  }
}

export async function loadPlanSessions(
  projectId: number
): Promise<ActionResult<{ sessions: ArticlePlanSessionSummary[] }>> {
  await requireAdminSession();

  try {
    const sessions = await listArticlePlanSessions(projectId);
    return { ok: true, data: { sessions } };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  }
}

export async function loadPlanSession(
  projectId: number,
  sessionId: number
): Promise<ActionResult<{ session: ArticlePlanSessionDetail }>> {
  await requireAdminSession();

  try {
    const detail = await getArticlePlanSession(projectId, sessionId);
    return { ok: true, data: { session: detail } };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  }
}

export async function loadPlanSessionByIssue(
  projectId: number,
  issueNumber: number
): Promise<ActionResult<{ session: ArticlePlanSessionDetail | null }>> {
  await requireAdminSession();

  try {
    const detail = await getArticlePlanSessionByIssue(projectId, issueNumber);
    return { ok: true, data: { session: detail } };
  } catch {
    return { ok: true, data: { session: null } };
  }
}

export async function loadIssueDescription(
  projectId: number,
  issueNumber: number
): Promise<ActionResult<{ body: string }>> {
  await requireAdminSession();

  try {
    const response = await getArticlePlanIssueDescription(projectId, issueNumber);
    return { ok: true, data: { body: response.body } };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  }
}

export async function loadRepositoryIssues(
  projectId: number,
  state: RepositoryIssueState
): Promise<ActionResult<{ issues: RepositoryIssue[] }>> {
  await requireAdminSession();

  try {
    const issues = await listArticlePlanIssues(projectId, state);
    return { ok: true, data: { issues } };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  }
}

export async function suggestPlanTitles(
  projectId: number,
  history: PlanChatMessage[]
): Promise<ActionResult<{ titles: string[] }>> {
  await requireAdminSession();

  try {
    const response = await suggestArticlePlanTitles(projectId, { history });
    return { ok: true, data: { titles: response.titles } };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  }
}

export async function acceptPlan(
  projectId: number,
  titles: string[]
): Promise<ActionResult<{ results: AcceptPlanResultItem[] }>> {
  await requireAdminSession();

  try {
    const response = await acceptArticlePlan(projectId, { titles });
    return { ok: true, data: { results: response.results } };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  }
}

export async function suggestPlanStructure(
  projectId: number,
  history: PlanChatMessage[]
): Promise<ActionResult<{ structure: string }>> {
  await requireAdminSession();

  try {
    const response = await suggestArticleStructure(projectId, { history });
    return { ok: true, data: { structure: response.structure } };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  }
}

export async function acceptPlanStructure(
  projectId: number,
  issueNumber: number,
  structure: string
): Promise<ActionResult<{ issueNumber: number; issueUrl: string }>> {
  await requireAdminSession();

  try {
    const response = await acceptArticleStructure(projectId, issueNumber, { structure });
    return { ok: true, data: { issueNumber: response.issueNumber, issueUrl: response.issueUrl } };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  }
}
