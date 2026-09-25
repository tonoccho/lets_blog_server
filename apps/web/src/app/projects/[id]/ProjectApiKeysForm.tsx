"use client";

import { useActionState, useTransition } from "react";
import {
  setProjectGithubTokenAction,
  clearProjectGithubTokenAction,
  setProjectBraveSearchApiKeyAction,
  clearProjectBraveSearchApiKeyAction,
  type ProjectApiKeyFormState,
} from "./actions";

const initialState: ProjectApiKeyFormState = {};

function ApiKeyField({
  label,
  description,
  placeholder,
  fieldName,
  configured,
  action,
  onClear,
}: {
  label: string;
  description: string;
  placeholder: string;
  fieldName: string;
  configured: boolean;
  action: (prevState: ProjectApiKeyFormState, formData: FormData) => Promise<ProjectApiKeyFormState>;
  onClear: () => void;
}) {
  const [state, formAction, pending] = useActionState(action, initialState);
  const [isClearing, startClearTransition] = useTransition();

  function handleClear() {
    if (!window.confirm(`${label}のプロジェクト設定を削除しますか?(フォールバック先の設定があればそちらが使われます)`)) {
      return;
    }
    startClearTransition(onClear);
  }

  return (
    <div className="space-y-2 border-t border-neutral-200 dark:border-neutral-800 pt-4 first:border-0 first:pt-0">
      <h3 className="text-sm font-medium">{label}</h3>
      <p className="text-sm text-neutral-600 dark:text-neutral-400">{description}</p>
      <p className="text-sm">
        現在の状態:{" "}
        <span className={configured ? "text-green-600" : "text-neutral-500 dark:text-neutral-400"}>
          {configured ? "このプロジェクトで設定済み" : "未設定(フォールバック先の設定を使用)"}
        </span>
      </p>
      <form action={formAction} className="flex flex-col gap-2 sm:flex-row sm:items-end">
        <label className="flex flex-1 flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">値</span>
          <input
            type="password"
            name={fieldName}
            placeholder={placeholder}
            autoComplete="off"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <button
          type="submit"
          disabled={pending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {pending ? "保存中…" : "保存"}
        </button>
        {configured && (
          <button
            type="button"
            onClick={handleClear}
            disabled={isClearing}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm text-red-600 hover:bg-neutral-50 dark:hover:bg-neutral-800 disabled:text-neutral-400"
          >
            {isClearing ? "削除中…" : "プロジェクト設定を削除"}
          </button>
        )}
      </form>
      {state.error && <p className="text-sm text-red-600">{state.error}</p>}
      {state.success && <p className="text-sm text-green-600">保存しました。</p>}
    </div>
  );
}

/** プロジェクト単位のAPIキー設定(issue #184)。未設定時のフォールバック先を各フィールドの説明に明記する。 */
export function ProjectApiKeysForm({
  projectId,
  githubTokenConfigured,
  braveSearchApiKeyConfigured,
}: {
  projectId: number;
  githubTokenConfigured: boolean;
  braveSearchApiKeyConfigured: boolean;
}) {
  return (
    <div className="space-y-6 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <div>
        <h2 className="font-medium">APIキー</h2>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          プロジェクトごとに個別のAPIキーを設定できます。未設定の場合は、GitHubトークンは操作者本人のユーザー設定、
          Brave Search APIキーはシステム全体設定にフォールバックします。値は暗号化して保存されます。
        </p>
      </div>

      <ApiKeyField
        label="GitHub Personal Access Token"
        description="記事計画でissueを作成する際に使用します。repoスコープを持つPATを入力してください。"
        placeholder="ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
        fieldName="githubToken"
        configured={githubTokenConfigured}
        action={(prevState, formData) => setProjectGithubTokenAction(projectId, prevState, formData)}
        onClear={() => clearProjectGithubTokenAction(projectId)}
      />

      <ApiKeyField
        label="Brave Search APIキー"
        description="記事計画でのWeb検索結果を出典付きで提案するために使用します。"
        placeholder="BSA..."
        fieldName="apiKey"
        configured={braveSearchApiKeyConfigured}
        action={(prevState, formData) => setProjectBraveSearchApiKeyAction(projectId, prevState, formData)}
        onClear={() => clearProjectBraveSearchApiKeyAction(projectId)}
      />
    </div>
  );
}
