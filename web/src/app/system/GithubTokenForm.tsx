"use client";

import { useActionState } from "react";
import { updateGithubTokenAction, UpdateGithubTokenState } from "./actions";

const initialState: UpdateGithubTokenState = {};

export function GithubTokenForm({ githubTokenConfigured }: { githubTokenConfigured: boolean }) {
  const [state, formAction, pending] = useActionState(updateGithubTokenAction, initialState);

  return (
    <form action={formAction} className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <h2 className="mb-3 font-medium">GitHub 連携</h2>

      <div className="mb-4">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">Personal Access Token (PAT)</span>
          <input
            type="password"
            name="githubToken"
            placeholder="ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
            autoComplete="off"
          />
          <span className="text-xs text-neutral-500 dark:text-neutral-400">
            issue 作成に使うため `repo` スコープを持つ PAT を入力してください。入力値は保存時のみサーバーに送信され、確認用に表示はされません。
          </span>
        </label>
      </div>

      <div className="mb-4 text-sm">
        {githubTokenConfigured ? (
          <span className="inline-block rounded bg-green-100 px-2 py-1 text-green-700">✓ 設定済み</span>
        ) : (
          <span className="inline-block rounded bg-neutral-100 dark:bg-neutral-800 px-2 py-1 text-neutral-600 dark:text-neutral-400">未設定</span>
        )}
      </div>

      {state.error && <p className="mb-3 text-sm text-red-600">{state.error}</p>}
      {state.success && <p className="mb-3 text-sm text-green-600">GitHub トークンを更新しました。</p>}

      <button
        type="submit"
        disabled={pending}
        className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "保存中…" : "保存"}
      </button>
    </form>
  );
}
