"use client";

import { useActionState, useRef, useTransition } from "react";
import type { BraveSearchApiKeyStatus } from "@/lib/apiClient";
import { clearBraveSearchApiKeyAction, setBraveSearchApiKeyAction, SystemSettingsFormState } from "./actions";

const initialState: SystemSettingsFormState = {};

const SOURCE_LABEL: Record<BraveSearchApiKeyStatus["source"], string> = {
  DATABASE: "この画面から設定済み",
  ENVIRONMENT: "環境変数(BRAVE_SEARCH_API_KEY)から設定済み",
  NONE: "未設定",
};

export function SystemSettingsPanel({ braveSearchStatus }: { braveSearchStatus: BraveSearchApiKeyStatus }) {
  const [state, formAction, pending] = useActionState(setBraveSearchApiKeyAction, initialState);
  const [isClearing, startClearTransition] = useTransition();
  const formRef = useRef<HTMLFormElement>(null);

  function handleClear() {
    if (!window.confirm("Brave Search APIキーの画面設定を削除しますか?(環境変数の設定があればそちらにフォールバックします)")) {
      return;
    }
    startClearTransition(() => {
      clearBraveSearchApiKeyAction();
    });
  }

  return (
    <section className="space-y-3 rounded-lg border border-neutral-200 bg-white p-5">
      <h2 className="font-medium">Brave Search APIキー</h2>
      <p className="text-sm text-neutral-600">
        AIによる記事構成・セクション本文生成・下書き/校正/要約で、Web検索結果を出典付きで提案するために使用します。
        未設定の場合、これらの機能は出典なしで動作します。
      </p>
      <p className="text-sm">
        現在の状態:{" "}
        <span className={braveSearchStatus.configured ? "text-green-600" : "text-neutral-500"}>
          {SOURCE_LABEL[braveSearchStatus.source]}
        </span>
      </p>

      <form
        ref={formRef}
        action={formAction}
        className="flex flex-col gap-2 sm:flex-row sm:items-end"
      >
        <label className="flex flex-1 flex-col gap-1 text-sm">
          <span className="text-neutral-600">APIキー</span>
          <input
            type="password"
            name="apiKey"
            placeholder="BSA..."
            autoComplete="off"
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
          />
        </label>
        <button
          type="submit"
          disabled={pending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {pending ? "保存中…" : "保存"}
        </button>
        {braveSearchStatus.source === "DATABASE" && (
          <button
            type="button"
            onClick={handleClear}
            disabled={isClearing}
            className="rounded border border-neutral-300 px-4 py-2 text-sm text-red-600 hover:bg-neutral-50 disabled:text-neutral-400"
          >
            {isClearing ? "削除中…" : "画面設定を削除"}
          </button>
        )}
      </form>
      {state.error && <p className="text-sm text-red-600">{state.error}</p>}
      {state.success && <p className="text-sm text-green-600">保存しました。</p>}
    </section>
  );
}
