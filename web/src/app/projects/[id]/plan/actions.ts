"use server";

import {
  sendArticlePlanChatMessage,
  suggestArticlePlanTitles,
  PlanChatMessage,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface PlanChatState {
  history: PlanChatMessage[];
  error?: string;
}

export async function sendPlanChatMessageAction(
  projectId: number,
  prevState: PlanChatState,
  formData: FormData
): Promise<PlanChatState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const message = String(formData.get("message") ?? "").trim();
  if (!message) {
    return prevState;
  }

  try {
    const response = await sendArticlePlanChatMessage(projectId, { history: prevState.history, message }, actor);
    return {
      history: [
        ...prevState.history,
        { role: "user", content: message },
        { role: "assistant", content: response.reply },
      ],
    };
  } catch (err) {
    return { history: prevState.history, error: err instanceof Error ? err.message : String(err) };
  }
}

export interface SuggestTitlesState {
  titles: string[];
  error?: string;
}

export async function suggestPlanTitlesAction(
  projectId: number,
  history: PlanChatMessage[],
  _prevState: SuggestTitlesState,
  _formData: FormData
): Promise<SuggestTitlesState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const response = await suggestArticlePlanTitles(projectId, { history }, actor);
    return { titles: response.titles };
  } catch (err) {
    return { titles: [], error: err instanceof Error ? err.message : String(err) };
  }
}
