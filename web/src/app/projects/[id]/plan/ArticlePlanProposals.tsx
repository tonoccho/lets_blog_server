"use client";

import { useState } from "react";
import type { PlanChatMessage, AcceptPlanResultItem } from "@/lib/apiClient";
import { suggestPlanTitles, acceptPlan } from "./actions";

export function ArticlePlanProposals({ projectId, history }: { projectId: number; history: PlanChatMessage[] }) {
  const [titles, setTitles] = useState<string[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [isSuggesting, setIsSuggesting] = useState(false);
  const [suggestError, setSuggestError] = useState<string | undefined>(undefined);

  const [results, setResults] = useState<AcceptPlanResultItem[] | null>(null);
  const [isAccepting, setIsAccepting] = useState(false);
  const [acceptError, setAcceptError] = useState<string | undefined>(undefined);

  const handleSuggestTitles = async () => {
    if (history.length === 0) return;

    setIsSuggesting(true);
    setSuggestError(undefined);
    const result = await suggestPlanTitles(projectId, history);
    if (result.ok) {
      setTitles(result.data.titles);
      setSelected(new Set());
    } else {
      setSuggestError(result.error);
    }
    setIsSuggesting(false);
  };

  const handleAcceptPlan = async () => {
    if (selected.size === 0) return;

    setIsAccepting(true);
    setAcceptError(undefined);
    const result = await acceptPlan(projectId, Array.from(selected));
    if (result.ok) {
      setResults(result.data.results);
    } else {
      setAcceptError(result.error);
    }
    setIsAccepting(false);
  };

  const handleReset = () => {
    setResults(null);
    setTitles([]);
    setSelected(new Set());
  };

  const toggleSelected = (title: string) => {
    const next = new Set(selected);
    if (next.has(title)) {
      next.delete(title);
    } else {
      next.add(title);
    }
    setSelected(next);
  };

  return (
    <div className="rounded-lg border border-neutral-200 bg-white p-5">
      <h2 className="mb-4 font-medium">記事タイトル提案</h2>

      {results === null ? (
        <>
          <button
            onClick={handleSuggestTitles}
            disabled={isSuggesting || history.length === 0}
            className="mb-4 rounded bg-blue-600 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {isSuggesting ? "提案取得中…" : "タイトル提案を取得"}
          </button>
          {suggestError && <p className="mb-4 text-sm text-red-600">{suggestError}</p>}

          {titles.length === 0 ? (
            <p className="text-sm text-neutral-500">
              タイトル提案はまだありません。チャットで壁打ちしてから取得してください。
            </p>
          ) : (
            <div className="mb-4 space-y-2">
              {titles.map((title, idx) => (
                <label
                  key={idx}
                  className="flex items-center gap-2 rounded border border-neutral-200 p-3 hover:bg-neutral-50"
                >
                  <input
                    type="checkbox"
                    checked={selected.has(title)}
                    onChange={() => toggleSelected(title)}
                    className="cursor-pointer"
                  />
                  <span className="flex-1 text-sm">{title}</span>
                </label>
              ))}
            </div>
          )}

          <button
            onClick={handleAcceptPlan}
            disabled={selected.size === 0 || isAccepting}
            className="w-full rounded bg-green-600 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {isAccepting ? "登録中…" : `選択した記事 (${selected.size} 件) の計画を受け入れる`}
          </button>
          {acceptError && <p className="mt-2 text-sm text-red-600">{acceptError}</p>}
        </>
      ) : (
        <>
          <div className="mb-4 space-y-2">
            {results.map((item, idx) => (
              <div
                key={idx}
                className={`rounded border p-3 text-sm ${
                  item.error ? "border-red-200 bg-red-50" : "border-green-200 bg-green-50"
                }`}
              >
                <div className="font-medium">{item.title}</div>
                {item.error ? (
                  <div className="text-red-700">✗ {item.error}</div>
                ) : (
                  <div className="text-green-700">
                    ✓ Issue{" "}
                    <a href={item.issueUrl} target="_blank" rel="noreferrer" className="underline">
                      #{item.issueNumber}
                    </a>{" "}
                    として登録しました
                  </div>
                )}
              </div>
            ))}
          </div>
          <button
            onClick={handleReset}
            className="w-full rounded bg-neutral-600 px-4 py-2 text-sm text-white"
          >
            別のテーマで提案を取得
          </button>
        </>
      )}
    </div>
  );
}
