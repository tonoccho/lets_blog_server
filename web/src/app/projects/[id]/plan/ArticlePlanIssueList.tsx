"use client";

import { useState } from "react";
import type { RepositoryIssue, RepositoryIssueState } from "@/lib/apiClient";
import { loadRepositoryIssues } from "./actions";

const STATE_LABEL: Record<RepositoryIssueState, string> = {
  open: "対応中",
  closed: "完了",
  all: "すべて",
};

export function ArticlePlanIssueList({
  projectId,
  initialIssues,
  initialState,
}: {
  projectId: number;
  initialIssues: RepositoryIssue[];
  initialState: RepositoryIssueState;
}) {
  const [issues, setIssues] = useState<RepositoryIssue[]>(initialIssues);
  const [state, setState] = useState<RepositoryIssueState>(initialState);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  const handleStateChange = async (next: RepositoryIssueState) => {
    setState(next);
    setIsLoading(true);
    setError(undefined);
    const result = await loadRepositoryIssues(projectId, next);
    if (result.ok) {
      setIssues(result.data.issues);
    } else {
      setError(result.error);
    }
    setIsLoading(false);
  };

  return (
    <div className="rounded-lg border border-neutral-200 bg-white p-5">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="font-medium">登録済み記事(Issue)一覧</h2>
        <select
          value={state}
          onChange={(e) => handleStateChange(e.target.value as RepositoryIssueState)}
          disabled={isLoading}
          className="rounded border border-neutral-300 px-2 py-1 text-sm"
        >
          {(Object.keys(STATE_LABEL) as RepositoryIssueState[]).map((s) => (
            <option key={s} value={s}>
              {STATE_LABEL[s]}
            </option>
          ))}
        </select>
      </div>

      {error && <p className="mb-2 text-sm text-red-600">{error}</p>}

      {issues.length === 0 ? (
        <p className="text-sm text-neutral-500">{isLoading ? "読み込み中…" : "該当するissueはありません。"}</p>
      ) : (
        <ul className="space-y-2">
          {issues.map((issue) => (
            <li
              key={issue.number}
              className="flex items-center justify-between rounded border border-neutral-200 p-3 text-sm"
            >
              <a href={issue.htmlUrl} target="_blank" rel="noreferrer" className="flex-1 truncate hover:underline">
                #{issue.number} {issue.title}
              </a>
              <span
                className={`ml-2 shrink-0 rounded px-2 py-0.5 text-xs font-medium ${
                  issue.state === "open" ? "bg-green-100 text-green-700" : "bg-neutral-200 text-neutral-600"
                }`}
              >
                {issue.state}
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
