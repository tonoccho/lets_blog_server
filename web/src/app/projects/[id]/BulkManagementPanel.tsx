"use client";

import { useActionState, useState } from "react";
import type {
  Project,
  ProjectEnvironment,
  BulkOperationLog,
  BulkOperationType,
  TermComparisonPage,
  StatusComparisonPage,
} from "@/lib/apiClient";
import {
  runBulkOperationUploadAction,
  replayBulkOperationsAction,
  fetchTermComparisonAction,
  fetchStatusComparisonAction,
  BulkOperationState,
} from "./actions";
import { TermComparisonTable } from "./TermComparisonTable";
import { PluginThemeComparisonTable } from "./PluginThemeComparisonTable";

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
  CATEGORY_FETCH: "カテゴリ取得",
  TAG_FETCH: "タグ取得",
  PLUGIN_FETCH: "プラグイン取得",
  THEME_FETCH: "テーマ取得",
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
}: {
  projectId: number;
  project: Project;
  logs: BulkOperationLog[];
  categoryPage: TermComparisonPage;
}) {
  const [tab, setTab] = useState<Tab>("CATEGORY");
  // タグ・プラグイン・テーマは、そのタブを初めて開いたときにクライアント側から取得する
  // (初期表示で4種類すべて並行取得すると、同一ホストのSSH接続が集中しやすいため)。
  const [tagPage, setTagPage] = useState<TermComparisonPage | null>(null);
  const [pluginPage, setPluginPage] = useState<StatusComparisonPage | null>(null);
  const [themePage, setThemePage] = useState<StatusComparisonPage | null>(null);
  const [loadingTab, setLoadingTab] = useState<Tab | null>(null);
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

  async function handleTabChange(nextTab: Tab) {
    setTab(nextTab);
    if (nextTab === "TAG" && tagPage === null) {
      setLoadingTab(nextTab);
      setTagPage(await fetchTermComparisonAction(projectId, "tag", 0));
      setLoadingTab(null);
    } else if (nextTab === "PLUGIN" && pluginPage === null) {
      setLoadingTab(nextTab);
      setPluginPage(await fetchStatusComparisonAction(projectId, "plugin", 0));
      setLoadingTab(null);
    } else if (nextTab === "THEME" && themePage === null) {
      setLoadingTab(nextTab);
      setThemePage(await fetchStatusComparisonAction(projectId, "theme", 0));
      setLoadingTab(null);
    }
  }

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
    <div className="space-y-6">
      <div className="rounded-lg border border-neutral-200 bg-white p-4">
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
              onClick={() => handleTabChange(t)}
              className={`rounded px-3 py-1.5 ${tab === t ? "bg-neutral-900 text-white" : "bg-neutral-100 text-neutral-600"}`}
            >
              {TAB_LABEL[t]}
            </button>
          ))}
        </div>

        {tab === "CATEGORY" && <TermComparisonTable projectId={projectId} kind="category" initialPage={categoryPage} />}
        {tab === "TAG" &&
          (tagPage ? (
            <TermComparisonTable projectId={projectId} kind="tag" initialPage={tagPage} />
          ) : (
            <TabLoading loading={loadingTab === "TAG"} />
          ))}
        {tab === "PLUGIN" && (
          <div className="space-y-4">
            {pluginPage ? (
              <PluginThemeComparisonTable
                projectId={projectId}
                kind="plugin"
                initialPage={pluginPage}
                managedEnvironments={managedEnvironments}
              />
            ) : (
              <TabLoading loading={loadingTab === "PLUGIN"} />
            )}
            <ZipUploadPanel projectId={projectId} operationType="PLUGIN_INSTALL" />
          </div>
        )}
        {tab === "THEME" && (
          <div className="space-y-4">
            {themePage ? (
              <PluginThemeComparisonTable
                projectId={projectId}
                kind="theme"
                initialPage={themePage}
                managedEnvironments={managedEnvironments}
              />
            ) : (
              <TabLoading loading={loadingTab === "THEME"} />
            )}
            <ZipUploadPanel projectId={projectId} operationType="THEME_INSTALL" />
          </div>
        )}
      </div>

      <div className="rounded-lg border border-neutral-200 bg-white p-4">
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

      <div className="rounded-lg border border-neutral-200 bg-white p-4">
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
                  <th className="px-2 py-1.5">詳細</th>
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
                    <td className="px-2 py-1.5">{log.status === "FAILED" && <CopyLogButton log={log} />}</td>
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

function TabLoading({ loading }: { loading: boolean }) {
  return (
    <p className="text-sm text-neutral-500">
      {loading ? "読み込み中…" : "このタブを開くとデータを取得します。"}
    </p>
  );
}

function ZipUploadPanel({
  projectId,
  operationType,
}: {
  projectId: number;
  operationType: "PLUGIN_INSTALL" | "THEME_INSTALL";
}) {
  const uploadAction = (prevState: BulkOperationState, formData: FormData) =>
    runBulkOperationUploadAction(projectId, prevState, formData);
  const [uploadState, uploadFormAction, uploadPending] = useActionState(uploadAction, initialState);

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
    <div className="rounded border border-neutral-200 bg-neutral-50 p-3">
      <p className="mb-2 text-sm text-neutral-500">
        zipファイルをアップロードして、紐付いている全環境へ同じ内容を一括インストールします
        (非公式・カスタムビルドのプラグイン/テーマ向け)。
      </p>
      <form action={uploadFormAction} onSubmit={handleUploadSubmit} className="flex flex-wrap items-end gap-2 text-sm">
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
          {uploadPending ? "アップロード・実行中(数分かかる場合があります)…" : "全環境へインストール"}
        </button>
      </form>
      {uploadState.error && <p className="mt-2 text-sm text-red-600">{uploadState.error}</p>}
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
        <li key={r.id} className={`flex items-center gap-2 ${STATUS_COLOR[r.status]}`}>
          <span>
            {ENVIRONMENT_LABEL[r.environment]}: {STATUS_LABEL[r.status]}
            {r.status === "FAILED" && r.errorMessage ? `(${r.errorMessage})` : ""}
          </span>
          {r.status === "FAILED" && <CopyLogButton log={r} />}
        </li>
      ))}
    </ul>
  );
}

function describeLogText(log: BulkOperationLog): string {
  return [
    `日時: ${new Date(log.createdAt).toLocaleString("ja-JP")}`,
    `操作: ${OPERATION_LABEL[log.operationType]}`,
    `値: ${describeLogValue(log)}`,
    `環境: ${ENVIRONMENT_LABEL[log.environment]}`,
    `ステータス: ${STATUS_LABEL[log.status]}`,
    `エラー: ${log.errorMessage ?? "(なし)"}`,
    "",
    "スタックトレース:",
    log.stackTrace ?? "(なし)",
  ].join("\n");
}

function CopyLogButton({ log }: { log: BulkOperationLog }) {
  const [copied, setCopied] = useState(false);

  async function handleCopy() {
    await navigator.clipboard.writeText(describeLogText(log));
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <button
      type="button"
      onClick={handleCopy}
      className="rounded bg-neutral-100 px-2 py-1 text-xs text-neutral-700"
      title="日時・操作・エラー内容・スタックトレースをコピーします"
    >
      {copied ? "コピーしました" : "コピー"}
    </button>
  );
}
