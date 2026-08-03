"use client";

import { useState } from "react";
import type { PlanChatMessage, ArticlePlanSessionSummary } from "@/lib/apiClient";
import { ArticlePlanChat } from "./ArticlePlanChat";
import { ArticlePlanProposals } from "./ArticlePlanProposals";
import { ArticlePlanSessionList } from "./ArticlePlanSessionList";
import { sendPlanChatMessage, loadPlanSessions, loadPlanSession } from "./actions";

export function ArticlePlanWorkspace({
  projectId,
  initialSessions,
}: {
  projectId: number;
  initialSessions: ArticlePlanSessionSummary[];
}) {
  const [history, setHistory] = useState<PlanChatMessage[]>([]);
  const [sessionId, setSessionId] = useState<number | null>(null);
  const [sessions, setSessions] = useState<ArticlePlanSessionSummary[]>(initialSessions);
  const [isSending, setIsSending] = useState(false);
  const [chatError, setChatError] = useState<string | undefined>(undefined);
  const [isSwitchingSession, setIsSwitchingSession] = useState(false);
  const [sessionError, setSessionError] = useState<string | undefined>(undefined);

  const refreshSessions = async () => {
    const result = await loadPlanSessions(projectId);
    if (result.ok) {
      setSessions(result.data.sessions);
    }
  };

  const handleSend = async (message: string) => {
    setIsSending(true);
    setChatError(undefined);

    const result = await sendPlanChatMessage(projectId, history, message, sessionId);
    if (result.ok) {
      setHistory([
        ...history,
        { role: "user", content: message },
        { role: "assistant", content: result.data.reply },
      ]);
      setSessionId(result.data.sessionId);
      await refreshSessions();
    } else {
      setChatError(result.error);
    }
    setIsSending(false);
  };

  const handleSelectSession = async (id: number) => {
    setIsSwitchingSession(true);
    setSessionError(undefined);

    const result = await loadPlanSession(projectId, id);
    if (result.ok) {
      setHistory(result.data.session.history);
      setSessionId(result.data.session.id);
    } else {
      setSessionError(result.error);
    }
    setIsSwitchingSession(false);
  };

  const handleNewChat = () => {
    setHistory([]);
    setSessionId(null);
    setChatError(undefined);
    setSessionError(undefined);
  };

  return (
    <div className="space-y-6">
      <ArticlePlanSessionList
        sessions={sessions}
        activeSessionId={sessionId}
        onSelect={handleSelectSession}
        onNewChat={handleNewChat}
        isLoading={isSwitchingSession}
        error={sessionError}
      />

      <div className="grid gap-8 lg:grid-cols-2">
        <ArticlePlanChat history={history} onSend={handleSend} isLoading={isSending} error={chatError} />
        <ArticlePlanProposals projectId={projectId} history={history} />
      </div>
    </div>
  );
}
