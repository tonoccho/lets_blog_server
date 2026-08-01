"use client";

import { useActionState, useState } from "react";
import type {
  Project,
  ProjectEnvironment,
  BulkOperationLog,
  BulkOperationType,
  TermComparisonPage,
} from "@/lib/apiClient";
import {
  applyToEnvironmentAction,
  runBulkOperationUploadAction,
  replayBulkOperationsAction,
  BulkOperationState,
} from "./actions";
import { TermComparisonTable } from "./TermComparisonTable";

type Tab = "CATEGORY" | "PLUGIN" | "THEME" | "TAG";

const ENVIRONMENT_LABEL: Record<ProjectEnvironment, string> = {
  local: "ローカル",
  test: "テスト",
  production: "本番",
};

const TAB_LABEL: Record<Tab, string> = {
  CATEGORY: "カテゴリ",
  PLUGIN: "プラグイン",
  THEME: "テーマ",
  TAG: "タグ",
};

const PLUGIN_THEME_ACTIONS: Record<"PLUGIN" | "THEME", { type: BulkOperationType; label: string }[]> = {
  PLUGIN: [
    { type: "PLUGIN_INSTALL", label: "インストール" },
    { type: "PLUGIN_ACTIVATE", label: "有効化" },
    { type: "PLUGIN_DEACTIVATE", label: "無効化" },
    { type: "PLUGIN_DELETE", label: "削除" },
  ],
  THEME: [
    { type: "THEME_INSTALL", label: "インストール" },
    { type: "THEME_ACTIVATE", label: "有効化" },
    { type: "THEME_DELETE", label: "削除" },
  ],
};

const OPERATION_LABEL: Record<BulkOperationType, string> = {
  CATEGORY_CREATE: "カテゴリ作成",
  CATEGORY_EDIT: "カテゴリ編集",
  CATEGORY_DELETE: "カテゴリ削除",
  TAG_CREATE: "タグ作成",
  TAG_EDIT: "タグ編集",
  TAG_DELETE: "タグ削除",
  PLUGIN_INSTALL: "プラグインインストール",
  PLUGIN_ACTIVATE: "プラグイン有効化",
  PLUGIN_DEACTIVATE: "プラグイン無効化",
  PLUGIN_DELETE: "プラグイン削除",
  THEME_INSTALL: "テーマインストール",
  THEME_ACTIVATE: "テーマ有効化",
  THEME_DELETE: "テーマ削除",
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
  categoryPage,
  tagPage,
}: {
  projectId: number;
  project: Project;
  logs: BulkOperationLog[];
  categoryPage: TermComparisonPage;
  tagPage: TermComparisonPage;
}) {
  const [tab, setTab] = useState<Tab>("CATEGORY");
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

  if (managedEnvironments.length === 0) {
    return (
      <div className="rounded-lg border border-neutral-200 bg-white p-4 text-sm text-neutral-500">
        <h3 className="mb-2 font-medium text-neutral-700">一括管理</h3>
        自動構築(managed)されたWordPress環境が1つ以上紐付いている場合に、
        カテゴリ・プラグイン・テーマ・タグの管理ができます。
      </div>
    );
  }

  return (
    <div className="space-y-6 rounded-lg border border-neutral-200 bg-white p-4">
      <div>
        <h3 className="mb-1 font-medium text-neutral-700">一括管理</h3>
        <p className="mb-3 text-sm text-neutral-500">
          紐付いている環境({managedEnvironments.map((e) => e.label).join("・")})の
          カテゴリ・プラグイン・テーマ・タグを比較・管理します。
        </p>

        <div className="mb-4 flex gap-2 text-sm">
          {(Object.keys(TAB_LABEL) as Tab[]).map((t) => (
            <button
              key={t}
              type="button"
              onClick={() => setTab(t)}
              className={`rounded px-3 py-1.5 ${tab === t ? "bg-neutral-900 text-white" : "bg-neutral-100 text-neutral-600"}`}
            >
              {TAB_LABEL[t]}
            </button>
          ))}
        </div>

        {tab === "CATEGORY" && <TermComparisonTable projectId={projectId} kind="category" initialPage={categoryPage} />}
        {tab === "TAG" && <TermComparisonTable projectId={projectId} kind="tag" initialPage={tagPage} />}
        {(tab === "PLUGIN" || tab === "THEME") && (
          <PluginThemeInterimPanel
            projectId={projectId}
            tab={tab}
            managedEnvironments={managedEnvironments}
          />
        )}
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
                    <td className="px-2 py-1.5">{describeLogValue(log)}</td>
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

function PluginThemeInterimPanel({
  projectId,
  tab,
  managedEnvironments,
}: {
  projectId: number;
  tab: "PLUGIN" | "THEME";
  managedEnvironments: { value: ProjectEnvironment; label: string }[];
}) {
  const [operationType, setOperationType] = useState<BulkOperationType>(PLUGIN_THEME_ACTIONS[tab][0].type);
  const [inputMode, setInputMode] = useState<"slug" | "zip">("slug");

  const slugAction = (prevState: BulkOperationState, formData: FormData) =>
    applyToEnvironmentAction(projectId, prevState, formData);
  const [slugState, slugFormAction, slugPending] = useActionState(slugAction, initialState);

  const uploadAction = (prevState: BulkOperationState, formData: FormData) =>
    runBulkOperationUploadAction(projectId, prevState, formData);
  const [uploadState, uploadFormAction, uploadPending] = useActionState(uploadAction, initialState);

  const isInstallType = operationType === "PLUGIN_INSTALL" || operationType === "THEME_INSTALL";

  function handleTabActionChange(type: BulkOperationType) {
    setOperationType(type);
    setInputMode("slug");
  }

  function handleSlugSubmit(e: React.FormEvent<HTMLFormElement>) {
    const formData = new FormData(e.currentTarget);
    const environment = String(formData.get("environment") ?? "");
    const value = String(formData.get("value") ?? "");
    if (
      !window.confirm(
        `${ENVIRONMENT_LABEL[environment as ProjectEnvironment] ?? environment}環境に対して、` +
          `${OPERATION_LABEL[operationType]}「${value}」を実行します。よろしいですか?`
      )
    ) {
      e.preventDefault();
    }
  }

  function handleUploadSubmit(e: React.FormEvent<HTMLFormElement>) {
    const formData = new FormData(e.currentTarget);
    const file = formData.get("file") as File | null;
    if (
      !window.confirm(
        `紐付いている全環境に対して、${OPERATION_LABEL[operationType]}「${file?.name ?? ""}」を実行します。よろしいですか?`
      )
    ) {
      e.preventDefault();
    }
  }

  return (
    <div className="space-y-3">
      <p className="text-sm text-neutral-500">
        プラグイン・テーマの環境ごとの比較表示は今後のフェーズで追加予定です。現時点では環境を指定して個別に操作してください。
      </p>

      <div className="flex gap-2 text-sm">
        {PLUGIN_THEME_ACTIONS[tab].map((action) => (
          <button
            key={action.type}
            type="button"
            onClick={() => handleTabActionChange(action.type)}
            className={`rounded px-3 py-1.5 ${
              operationType === action.type ? "bg-neutral-700 text-white" : "bg-neutral-100 text-neutral-600"
            }`}
          >
            {action.label}
          </button>
        ))}
      </div>

      {isInstallType && (
        <div className="flex gap-2 text-sm">
          <button
            type="button"
            onClick={() => setInputMode("slug")}
            className={`rounded px-3 py-1.5 ${inputMode === "slug" ? "bg-neutral-700 text-white" : "bg-neutral-100 text-neutral-600"}`}
          >
            slugを指定(1環境ずつ)
          </button>
          <button
            type="button"
            onClick={() => setInputMode("zip")}
            className={`rounded px-3 py-1.5 ${inputMode === "zip" ? "bg-neutral-700 text-white" : "bg-neutral-100 text-neutral-600"}`}
          >
            zipをアップロード(全環境へ一括)
          </button>
        </div>
      )}

      {!isInstallType || inputMode === "slug" ? (
        <form
          key={operationType}
          action={slugFormAction}
          onSubmit={handleSlugSubmit}
          className="flex flex-wrap items-end gap-2 text-sm"
        >
          <input type="hidden" name="operationType" value={operationType} />
          <label className="flex flex-col gap-1">
            <span className="text-neutral-600">対象環境</span>
            <select name="environment" required className="rounded border border-neutral-300 px-3 py-2 text-sm">
              <option value="">選択してください</option>
              {managedEnvironments.map((env) => (
                <option key={env.value} value={env.value}>
                  {env.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-neutral-600">wordpress.orgのslug</span>
            <input
              name="value"
              placeholder="akismet"
              required
              className="rounded border border-neutral-300 px-3 py-2 text-sm"
            />
          </label>
          <button
            type="submit"
            disabled={slugPending}
            className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {slugPending ? "実行中…" : "実行する"}
          </button>
        </form>
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

      {slugState.error && <p className="text-sm text-red-600">{slugState.error}</p>}
      {slugState.results && <ResultList results={slugState.results} />}
      {uploadState.error && <p className="text-sm text-red-600">{uploadState.error}</p>}
      {uploadState.results && <ResultList results={uploadState.results} />}
    </div>
  );
}

function describeLogValue(log: BulkOperationLog): string {
  if (log.sourceType === "ZIP") {
    return `${log.originalFilename ?? log.value}(zip)`;
  }
  if (log.operationType.startsWith("CATEGORY") || log.operationType.startsWith("TAG")) {
    const slug = log.categorySlug ?? log.categoryTargetSlug;
    return slug ? `${log.value}(${slug})` : log.value;
  }
  return log.value;
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
