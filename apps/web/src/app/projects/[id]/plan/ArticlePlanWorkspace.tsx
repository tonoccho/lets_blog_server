"use client";

import { useState } from "react";
import type { PlanChatMessage, ArticlePlanSessionSummary, ArticlePlanSessionDetail } from "@/lib/apiClient";
import { ArticlePlanChat } from "./ArticlePlanChat";
import { ArticlePlanProposals } from "./ArticlePlanProposals";
import { ArticlePlanStructureProposal } from "./ArticlePlanStructureProposal";
import { ArticlePlanSessionList } from "./ArticlePlanSessionList";
import { sendPlanChatMessage, loadPlanSessions, loadPlanSession, loadIssueDescription } from "./actions";

export function ArticlePlanWorkspace({
  projectId,
  initialSessions,
  initialIssueNumber,
  initialIssueTitle,
  initialIssueSession,
  initialIssueStructure,
  timezone,
}: {
  projectId: number;
  initialSessions: ArticlePlanSessionSummary[];
  initialIssueNumber: number | null;
  initialIssueTitle: string | null;
  initialIssueSession: ArticlePlanSessionDetail | null;
  initialIssueStructure: string | null;
  timezone: string | null;
}) {
  const [history, setHistory] = useState<PlanChatMessage[]>(initialIssueSession?.history ?? []);
  const [sessionId, setSessionId] = useState<number | null>(initialIssueSession?.id ?? null);
  const [issueNumber, setIssueNumber] = useState<number | null>(initialIssueNumber);
  const [issueTitle, setIssueTitle] = useState<string | null>(initialIssueTitle);
  const [issueStructure, setIssueStructure] = useState<string | null>(initialIssueStructure);
  const [proposalTab, setProposalTab] = useState<"structure" | "titles">("structure");
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

    const result = await sendPlanChatMessage(projectId, history, message, sessionId, issueNumber);
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
      const newIssueNumber = result.data.session.githubIssueNumber;
      setIssueNumber(newIssueNumber);
      setIssueTitle(null);
      setProposalTab("structure");

      if (newIssueNumber) {
        const descResult = await loadIssueDescription(projectId, newIssueNumber);
        setIssueStructure(descResult.ok ? descResult.data.body : null);
      } else {
        setIssueStructure(null);
      }
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
        timezone={timezone}
      />

      <div className="grid gap-8 lg:grid-cols-2">
        <ArticlePlanChat
          history={history}
          onSend={handleSend}
          isLoading={isSending}
          error={chatError}
          issueNumber={issueNumber}
          issueTitle={issueTitle}
        />

        <div className="space-y-3">
          {issueNumber && (
            <div className="flex gap-2">
              <button
                onClick={() => setProposalTab("structure")}
                className={`rounded-full border px-3 py-1.5 text-sm ${
                  proposalTab === "structure"
                    ? "border-neutral-900 bg-neutral-900 text-white"
                    : "border-neutral-300 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800"
                }`}
              >
                構成提案
              </button>
              <button
                onClick={() => setProposalTab("titles")}
                className={`rounded-full border px-3 py-1.5 text-sm ${
                  proposalTab === "titles"
                    ? "border-neutral-900 bg-neutral-900 text-white"
                    : "border-neutral-300 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800"
                }`}
              >
                タイトル提案
              </button>
            </div>
          )}

          {issueNumber && proposalTab === "structure" ? (
            <ArticlePlanStructureProposal
              key={issueNumber}
              projectId={projectId}
              issueNumber={issueNumber}
              history={history}
              initialStructure={issueStructure}
            />
          ) : (
            <ArticlePlanProposals projectId={projectId} history={history} />
          )}
        </div>
      </div>
    </div>
  );
}
