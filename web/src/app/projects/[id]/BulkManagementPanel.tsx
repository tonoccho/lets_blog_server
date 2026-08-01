"use client";

import { useActionState, useState } from "react";
import type { Project, ProjectEnvironment, BulkOperationLog, BulkOperationType } from "@/lib/apiClient";
import {
  runBulkOperationAction,
  runBulkOperationUploadAction,
  replayBulkOperationsAction,
  BulkOperationState,
} from "./actions";

const ENVIRONMENT_LABEL: Record<ProjectEnvironment, string> = {
  local: "ローカル",
  test: "テスト",
  production: "本番",
};

const OPERATION_LABEL: Record<BulkOperationType, string> = {
  CATEGORY: "カテゴリ作成",
  PLUGIN: "プラグインインストール",
  THEME: "テーマインストール",
};

const STATUS_LABEL: Record<string, string> = {
  SUCCESS: "成功",
  SKIPPED: "スキップ(既存)",
  FAILED: "失敗",
};

const STATUS_COLOR: Record<string, string> = {
  SUCCESS: "text-green-600",
  SKIPPED: "text-neutral-500",
  FAILED: "text-red-600",
};

const initialState: BulkOperationState = {};

export function BulkManagementPanel({
  projectId,
  project,
  logs,
}: {
  projectId: number;
  project: Project;
  logs: BulkOperationLog[];
}) {
  const [operationType, setOperationType] = useState<BulkOperationType>("CATEGORY");
  const [inputMode, setInputMode] = useState<"slug" | "zip">("slug");
  const [replayState, setReplayState] = useState<BulkOperationState | null>(null);
  const [replayPendingEnv, setReplayPendingEnv] = useState<ProjectEnvironment | null>(null);

  const managedEnvironments: { value: ProjectEnvironment; label: string }[] = (
    [
      ["local", project.localSite] as const,
      ["test", project.testSite] as const,
      ["production", project.productionSite] as const,
    ] as const
  )
    .filter(([, site]) => site?.managedWordpress)
    .map(([environment]) => ({ value: environment, label: ENVIRONMENT_LABEL[environment] }));

  const slugAction = (prevState: BulkOperationState, formData: FormData) =>
    runBulkOperationAction(projectId, prevState, formData);
  const [slugState, slugFormAction, slugPending] = useActionState(slugAction, initialState);

  const uploadAction = (prevState: BulkOperationState, formData: FormData) =>
    runBulkOperationUploadAction(projectId, prevState, formData);
  const [uploadState, uploadFormAction, uploadPending] = useActionState(uploadAction, initialState);

  async function handleReplay(environment: ProjectEnvironment) {
    if (
      !window.confirm(
        `${ENVIRONMENT_LABEL[environment]}環境へ、これまでの作業ログ(成功分)をすべて再適用します。よろしいですか?`
      )
    ) {
      return;
    }
    setReplayPendingEnv(environment);
    const result = await replayBulkOperationsAction(projectId, environment);
    setReplayState(result);
    setReplayPendingEnv(null);
  }

  function handleSlugSubmit(e: React.FormEvent<HTMLFormElement>) {
    const formData = new FormData(e.currentTarget);
    const value = formData.get("value");
    const envNames = managedEnvironments.map((env) => env.label).join("・");
    if (
      !window.confirm(
        `紐付いている全環境(${envNames})に対して、${OPERATION_LABEL[operationType]}「${value}」を実行します。よろしいですか?`
      )
    ) {
      e.preventDefault();
    }
  }

  function handleUploadSubmit(e: React.FormEvent<HTMLFormElement>) {
    const formData = new FormData(e.currentTarget);
    const file = formData.get("file") as File | null;
    const envNames = managedEnvironments.map((env) => env.label).join("・");
    if (
      !window.confirm(
        `紐付いている全環境(${envNames})に対して、${OPERATION_LABEL[operationType]}「${file?.name ?? ""}」を実行します。よろしいですか?`
      )
    ) {
      e.preventDefault();
    }
  }

  if (managedEnvironments.length === 0) {
    return (
      <div className="rounded-lg border border-neutral-200 bg-white p-4 text-sm text-neutral-500">
        <h3 className="mb-2 font-medium text-neutral-700">一括管理</h3>
        自動構築(managed)されたWordPress環境が1つ以上紐付いている場合に、
        カテゴリ作成・プラグイン/テーマインストールを一括実行できます。
      </div>
    );
  }

  const showSlugForm = operationType === "CATEGORY" || inputMode === "slug";

  return (
    <div className="space-y-6 rounded-lg border border-neutral-200 bg-white p-4">
      <div>
        <h3 className="mb-1 font-medium text-neutral-700">一括管理</h3>
        <p className="mb-3 text-sm text-neutral-500">
          紐付いている全環境({managedEnvironments.map((e) => e.label).join("・")})に対して、
          カテゴリ作成・プラグイン/テーマインストールを同時実行します。
        </p>

        <div className="mb-3 flex gap-2 text-sm">
          {(Object.keys(OPERATION_LABEL) as BulkOperationType[]).map((type) => (
            <button
              key={type}
              type="button"
              onClick={() => setOperationType(type)}
              className={`rounded px-3 py-1.5 ${
                operationType === type ? "bg-neutral-900 text-white" : "bg-neutral-100 text-neutral-600"
              }`}
            >
              {OPERATION_LABEL[type]}
            </button>
          ))}
        </div>

        {operationType !== "CATEGORY" && (
          <div className="mb-3 flex gap-2 text-sm">
            <button
              type="button"
              onClick={() => setInputMode("slug")}
              className={`rounded px-3 py-1.5 ${
                inputMode === "slug" ? "bg-neutral-700 text-white" : "bg-neutral-100 text-neutral-600"
              }`}
            >
              slugを指定
            </button>
            <button
              type="button"
              onClick={() => setInputMode("zip")}
              className={`rounded px-3 py-1.5 ${
                inputMode === "zip" ? "bg-neutral-700 text-white" : "bg-neutral-100 text-neutral-600"
              }`}
            >
              zipをアップロード
            </button>
          </div>
        )}

        {showSlugForm ? (
          <>
            <form
              action={slugFormAction}
              onSubmit={handleSlugSubmit}
              className="flex flex-wrap items-end gap-2 text-sm"
            >
            <input type="hidden" name="operationType" value={operationType} />
            <label className="flex flex-col gap-1">
              <span className="text-neutral-600">
                {operationType === "CATEGORY" ? "カテゴリ名" : "wordpress.orgのslug"}
              </span>
              <input
                name="value"
                required
                placeholder={operationType === "CATEGORY" ? "お知らせ" : "akismet"}
                className="rounded border border-neutral-300 px-3 py-2 text-sm"
              />
            </label>
            {operationType === "CATEGORY" && (
              <>
                <label className="flex flex-col gap-1">
                  <span className="text-neutral-600">スラッグ(任意)</span>
                  <input
                    name="categorySlug"
                    placeholder="oshirase"
                    className="rounded border border-neutral-300 px-3 py-2 text-sm"
                  />
                </label>
                <label className="flex flex-col gap-1">
                  <span className="text-neutral-600">親カテゴリ名(任意)</span>
                  <input
                    name="categoryParentName"
                    placeholder="既存カテゴリの名前"
                    className="rounded border border-neutral-300 px-3 py-2 text-sm"
                  />
                </label>
                <label className="flex flex-col gap-1">
                  <span className="text-neutral-600">説明(任意)</span>
                  <input
                    name="categoryDescription"
                    placeholder="カテゴリの説明"
                    className="rounded border border-neutral-300 px-3 py-2 text-sm"
                  />
                </label>
              </>
            )}
            <button
              type="submit"
              disabled={slugPending}
              className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
            >
              {slugPending ? "実行中…" : "実行する"}
            </button>
          </form>
            {operationType === "CATEGORY" && (
              <p className="mt-1 text-xs text-neutral-500">
                親カテゴリ名は各環境に既存のカテゴリ名(完全一致)で指定してください。指定した名前が
                対象環境に存在しない場合、その環境の作成は失敗として記録されます。
              </p>
            )}
          </>
        ) : (
          <form
            action={uploadFormAction}
            onSubmit={handleUploadSubmit}
            className="flex flex-wrap items-end gap-2 text-sm"
          >
            <input type="hidden" name="operationType" value={operationType} />
            <label className="flex flex-col gap-1">
              <span className="text-neutral-600">zipファイル</span>
              <input name="file" type="file" accept=".zip" required className="text-sm" />
            </label>
            <button
              type="submit"
              disabled={uploadPending}
              className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
            >
              {uploadPending ? "アップロード・実行中(数分かかる場合があります)…" : "実行する"}
            </button>
          </form>
        )}

        {slugState.error && <p className="mt-2 text-sm text-red-600">{slugState.error}</p>}
        {slugState.results && <ResultList results={slugState.results} />}
        {uploadState.error && <p className="mt-2 text-sm text-red-600">{uploadState.error}</p>}
        {uploadState.results && <ResultList results={uploadState.results} />}
      </div>

      <div>
        <h3 className="mb-1 font-medium text-neutral-700">ロールフォワード</h3>
        <p className="mb-3 text-sm text-neutral-500">
          過去に成功した一括管理の内容を、指定した環境へまとめて再適用します(例: ローカル環境を再構築した後に使用)。
        </p>
        <div className="mb-3 flex flex-wrap gap-2">
          {managedEnvironments.map((env) => (
            <button
              key={env.value}
              type="button"
              onClick={() => handleReplay(env.value)}
              disabled={replayPendingEnv === env.value}
              className="rounded bg-neutral-100 px-3 py-1.5 text-sm text-neutral-700 disabled:opacity-50"
            >
              {replayPendingEnv === env.value ? "実行中…" : `${env.label}へロールフォワード`}
            </button>
          ))}
        </div>
        {replayState?.error && <p className="text-sm text-red-600">{replayState.error}</p>}
        {replayState?.results && <ResultList results={replayState.results} />}
      </div>

      <div>
        <h3 className="mb-2 font-medium text-neutral-700">作業ログ</h3>
        {logs.length === 0 ? (
          <p className="text-sm text-neutral-500">実行履歴はまだありません。</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-neutral-200 text-neutral-500">
                <tr>
                  <th className="px-2 py-1.5">日時</th>
                  <th className="px-2 py-1.5">操作</th>
                  <th className="px-2 py-1.5">値</th>
                  <th className="px-2 py-1.5">環境</th>
                  <th className="px-2 py-1.5">結果</th>
                  <th className="px-2 py-1.5">再適用</th>
                </tr>
              </thead>
              <tbody>
                {logs.map((log) => (
                  <tr key={log.id} className="border-b border-neutral-100 last:border-0">
                    <td className="px-2 py-1.5 text-neutral-500">
                      {new Date(log.createdAt).toLocaleString("ja-JP")}
                    </td>
                    <td className="px-2 py-1.5">{OPERATION_LABEL[log.operationType]}</td>
                    <td className="px-2 py-1.5">
                      {log.sourceType === "ZIP" ? `${log.originalFilename ?? log.value}(zip)` : log.value}
                    </td>
                    <td className="px-2 py-1.5">{ENVIRONMENT_LABEL[log.environment]}</td>
                    <td className={`px-2 py-1.5 ${STATUS_COLOR[log.status]}`}>
                      {STATUS_LABEL[log.status]}
                      {log.status === "FAILED" && log.errorMessage && (
                        <span className="ml-1 text-xs text-neutral-400">({log.errorMessage})</span>
                      )}
                    </td>
                    <td className="px-2 py-1.5 text-neutral-500">{log.isReplay ? "はい" : "-"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

function ResultList({ results }: { results: BulkOperationLog[] }) {
  return (
    <ul className="mt-2 space-y-0.5 text-sm">
      {results.map((r) => (
        <li key={r.id} className={STATUS_COLOR[r.status]}>
          {ENVIRONMENT_LABEL[r.environment]}: {STATUS_LABEL[r.status]}
          {r.status === "FAILED" && r.errorMessage ? `(${r.errorMessage})` : ""}
        </li>
      ))}
    </ul>
  );
}
