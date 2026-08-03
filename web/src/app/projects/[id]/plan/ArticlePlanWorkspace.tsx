"use client";

import { useActionState } from "react";
import { ArticlePlanChat } from "./ArticlePlanChat";
import { ArticlePlanProposals } from "./ArticlePlanProposals";
import { sendPlanChatMessageAction, PlanChatState } from "./actions";

const initialState: PlanChatState = { history: [] };

export function ArticlePlanWorkspace({ projectId }: { projectId: number }) {
  const action = (prevState: PlanChatState, formData: FormData) =>
    sendPlanChatMessageAction(projectId, prevState, formData);
  const [chatState, chatFormAction, chatPending] = useActionState(action, initialState);

  return (
    <div className="grid gap-8 lg:grid-cols-2">
      <ArticlePlanChat
        history={chatState.history}
        error={chatState.error}
        formAction={chatFormAction}
        pending={chatPending}
      />
      <ArticlePlanProposals projectId={projectId} history={chatState.history} />
    </div>
  );
}
