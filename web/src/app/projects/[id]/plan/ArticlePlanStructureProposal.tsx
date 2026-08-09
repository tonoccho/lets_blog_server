"use client";

import { useState } from "react";
import type { PlanChatMessage } from "@/lib/apiClient";
import { suggestPlanStructure, acceptPlanStructure } from "./actions";

export function ArticlePlanStructureProposal({
  projectId,
  issueNumber,
  history,
  initialStructure,
}: {
  projectId: number;
  issueNumber: number;
  history: PlanChatMessage[];
  initialStructure?: string | null;
}) {
  const [structure, setStructure] = useState<string | null>(
    initialStructure && initialStructure.trim() !== "" ? initialStructure : null
  );
  const [isSuggesting, setIsSuggesting] = useState(false);
  const [suggestError, setSuggestError] = useState<string | undefined>(undefined);

  const [result, setResult] = useState<{ issueNumber: number; issueUrl: string } | null>(null);
  const [isAccepting, setIsAccepting] = useState(false);
  const [acceptError, setAcceptError] = useState<string | undefined>(undefined);

  const handleSuggestStructure = async () => {
    if (history.length === 0) return;

    setIsSuggesting(true);
    setSuggestError(undefined);
    const res = await suggestPlanStructure(projectId, history);
    if (res.ok) {
      setStructure(res.data.structure);
    } else {
      setSuggestError(res.error);
    }
    setIsSuggesting(false);
  };

  const handleAcceptStructure = async () => {
    if (!structure || structure.trim() === "") return;

    setIsAccepting(true);
    setAcceptError(undefined);
    const res = await acceptPlanStructure(projectId, issueNumber, structure);
    if (res.ok) {
      setResult(res.data);
    } else {
      setAcceptError(res.error);
    }
    setIsAccepting(false);
  };

  const handleReset = () => {
    setResult(null);
    setStructure(null);
  };

  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <h2 className="mb-4 font-medium">記事構成の提案(Issue #{issueNumber})</h2>

      {result === null ? (
        <>
          <button
            onClick={handleSuggestStructure}
            disabled={isSuggesting || history.length === 0}
            className="mb-4 rounded bg-blue-600 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {isSuggesting ? "提案取得中…" : "記事の構成を提案"}
          </button>
          {suggestError && <p className="mb-4 text-sm text-red-600">{suggestError}</p>}

          {structure === null ? (
            <p className="text-sm text-neutral-500 dark:text-neutral-400">
              構成案はまだありません。チャットで壁打ちしてから取得してください。
            </p>
          ) : (
            <textarea
              value={structure}
              onChange={(e) => setStructure(e.target.value)}
              rows={10}
              className="mb-4 w-full rounded border border-neutral-300 dark:border-neutral-700 p-3 text-sm font-mono"
            />
          )}

          <button
            onClick={handleAcceptStructure}
            disabled={!structure || structure.trim() === "" || isAccepting}
            className="w-full rounded bg-green-600 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {isAccepting ? "更新中…" : "この内容でIssueを更新"}
          </button>
          {acceptError && <p className="mt-2 text-sm text-red-600">{acceptError}</p>}
        </>
      ) : (
        <>
          <div className="mb-4 rounded border border-green-200 bg-green-50 p-3 text-sm text-green-700">
            ✓ Issue{" "}
            <a href={result.issueUrl} target="_blank" rel="noreferrer" className="underline">
              #{result.issueNumber}
            </a>{" "}
            を更新しました
          </div>
          <button onClick={handleReset} className="w-full rounded bg-neutral-600 px-4 py-2 text-sm text-white">
            別の構成案を作成する
          </button>
        </>
      )}
    </div>
  );
}
