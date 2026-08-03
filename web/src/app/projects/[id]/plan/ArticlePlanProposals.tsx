"use client";

import { useActionState, useState } from "react";
import type { PlanChatMessage } from "@/lib/apiClient";
import { suggestPlanTitlesAction, SuggestTitlesState } from "./actions";

const initialState: SuggestTitlesState = { titles: [] };

export function ArticlePlanProposals({ projectId, history }: { projectId: number; history: PlanChatMessage[] }) {
  const action = (prevState: SuggestTitlesState, formData: FormData) =>
    suggestPlanTitlesAction(projectId, history, prevState, formData);
  const [state, formAction, pending] = useActionState(action, initialState);
  const [selected, setSelected] = useState<Set<string>>(new Set());

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

      <form action={formAction}>
        <button
          type="submit"
          disabled={pending || history.length === 0}
          className="mb-4 rounded bg-blue-600 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {pending ? "提案取得中…" : "タイトル提案を取得"}
        </button>
      </form>
      {state.error && <p className="mb-4 text-sm text-red-600">{state.error}</p>}

      {state.titles.length === 0 ? (
        <p className="text-sm text-neutral-500">
          タイトル提案はまだありません。チャットで壁打ちしてから取得してください。
        </p>
      ) : (
        <div className="mb-4 space-y-2">
          {state.titles.map((title, idx) => (
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
        type="button"
        disabled={selected.size === 0}
        className="w-full rounded bg-green-600 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        選択した記事 ({selected.size} 件) の計画を受け入れる
      </button>
    </div>
  );
}
