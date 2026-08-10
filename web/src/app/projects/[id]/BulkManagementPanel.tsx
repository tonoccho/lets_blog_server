"use client";

import { useActionState, useState } from "react";
import Link from "next/link";
import type {
  Project,
  ProjectEnvironment,
  BulkOperationLog,
  BulkOperationType,
  TermComparisonPage,
  StatusComparisonPage,
  PostComparisonPage,
} from "@/lib/apiClient";
import {
  runBulkOperationUploadAction,
  fetchTermComparisonAction,
  fetchStatusComparisonAction,
  fetchPostComparisonAction,
  BulkOperationState,
} from "./actions";
import { formatDateTime } from "@/lib/formatDate";
import { TermComparisonTable } from "./TermComparisonTable";
import { PluginThemeComparisonTable } from "./PluginThemeComparisonTable";
import { PostComparisonTable } from "./PostComparisonTable";

type Tab = "CATEGORY" | "PLUGIN" | "THEME" | "TAG" | "POST";

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
  POST: "ポスト/ページ",
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
  POST_FETCH: "ポスト/ページ取得",
  MEDIA_UPLOAD: "アセット画像アップロード",
  POST_DELETE: "ポスト/ページ削除",
  POST_STATUS_UPDATE: "ポスト/ページステータス変更",
};

const STATUS_LABEL: Record<string, string> = {
  SUCCESS: "成功",
  SKIPPED: "スキップ(既存)",
  FAILED: "失敗",
};

const STATUS_COLOR: Record<string, string> = {
  SUCCESS: "text-green-600",
  SKIPPED: "text-neutral-500 dark:text-neutral-400",
  FAILED: "text-red-600",
};

const initialState: BulkOperationState = {};

export function BulkManagementPanel({
  projectId,
  project,
  categoryPage,
  timezone,
}: {
  projectId: number;
  project: Project;
  categoryPage: TermComparisonPage;
  timezone: string | null;
}) {
  const [tab, setTab] = useState<Tab>("CATEGORY");
  // タグ・プラグイン・テーマは、そのタブを初めて開いたときにクライアント側から取得する
  // (初期表示で4種類すべて並行取得すると、同一ホストのSSH接続が集中しやすいため)。
  const [tagPage, setTagPage] = useState<TermComparisonPage | null>(null);
  const [pluginPage, setPluginPage] = useState<StatusComparisonPage | null>(null);
  const [themePage, setThemePage] = useState<StatusComparisonPage | null>(null);
  const [postPage, setPostPage] = useState<PostComparisonPage | null>(null);
  const [loadingTab, setLoadingTab] = useState<Tab | null>(null);

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
    } else if (nextTab === "POST" && postPage === null) {
      setLoadingTab(nextTab);
      setPostPage(await fetchPostComparisonAction(projectId, "post", 0));
      setLoadingTab(null);
    }
  }

  if (managedEnvironments.length === 0) {
    return (
      <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4 text-sm text-neutral-500 dark:text-neutral-400">
        <h3 className="mb-2 font-medium text-neutral-700 dark:text-neutral-300">一括管理</h3>
        自動構築(managed)されたWordPress環境が1つ以上紐付いている場合に、
        カテゴリ・プラグイン・テーマ・タグの管理ができます。
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4">
        <h3 className="mb-1 font-medium text-neutral-700 dark:text-neutral-300">一括管理</h3>
        <p className="mb-3 text-sm text-neutral-500 dark:text-neutral-400">
          紐付いている環境({managedEnvironments.map((e) => e.label).join("・")})の
          カテゴリ・プラグイン・テーマ・タグを比較・管理します。
        </p>

        <div className="mb-4 flex gap-2 text-sm">
          {(Object.keys(TAB_LABEL) as Tab[]).map((t) => (
            <button
              key={t}
              type="button"
              onClick={() => handleTabChange(t)}
              className={`rounded px-3 py-1.5 ${tab === t ? "bg-neutral-900 text-white" : "bg-neutral-100 dark:bg-neutral-800 text-neutral-600 dark:text-neutral-400"}`}
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
            <ZipUploadPanel projectId={projectId} operationType="PLUGIN_INSTALL" timezone={timezone} />
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
            <ZipUploadPanel projectId={projectId} operationType="THEME_INSTALL" timezone={timezone} />
          </div>
        )}
        {tab === "POST" &&
          (postPage ? (
            <PostComparisonTable projectId={projectId} initialPage={postPage} />
          ) : (
            <TabLoading loading={loadingTab === "POST"} />
          ))}
      </div>

      <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4 text-sm text-neutral-500 dark:text-neutral-400">
        一括管理操作の実行履歴は
        <Link href="/operation-logs?type=OPERATION" className="mx-1 text-blue-600 hover:underline">
          操作ログ
        </Link>
        から確認できます。
      </div>
    </div>
  );
}

function TabLoading({ loading }: { loading: boolean }) {
  return (
    <p className="text-sm text-neutral-500 dark:text-neutral-400">
      {loading ? "読み込み中…" : "このタブを開くとデータを取得します。"}
    </p>
  );
}

function ZipUploadPanel({
  projectId,
  operationType,
  timezone,
}: {
  projectId: number;
  operationType: "PLUGIN_INSTALL" | "THEME_INSTALL";
  timezone: string | null;
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
    <div className="rounded border border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 p-3">
      <p className="mb-2 text-sm text-neutral-500 dark:text-neutral-400">
        zipファイルをアップロードして、紐付いている全環境へ同じ内容を一括インストールします
        (非公式・カスタムビルドのプラグイン/テーマ向け)。
      </p>
      <form action={uploadFormAction} onSubmit={handleUploadSubmit} className="flex flex-wrap items-end gap-2 text-sm">
        <input type="hidden" name="operationType" value={operationType} />
        <label className="flex flex-col gap-1">
          <span className="text-neutral-600 dark:text-neutral-400">zipファイル</span>
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
      {uploadState.results && <ResultList results={uploadState.results} timezone={timezone} />}
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
  if (log.operationType === "POST_STATUS_UPDATE" && log.postStatus) {
    return `${log.value} → ${log.postStatus}`;
  }
  return log.value;
}

function ResultList({ results, timezone }: { results: BulkOperationLog[]; timezone: string | null }) {
  return (
    <ul className="mt-2 space-y-0.5 text-sm">
      {results.map((r, index) => (
        <li key={`${r.environment}-${index}`} className={`flex items-center gap-2 ${STATUS_COLOR[r.status]}`}>
          <span>
            {ENVIRONMENT_LABEL[r.environment]}: {STATUS_LABEL[r.status]}
            {r.status === "FAILED" && r.errorMessage ? `(${r.errorMessage})` : ""}
          </span>
          {r.status === "FAILED" && <CopyLogButton log={r} timezone={timezone} />}
        </li>
      ))}
    </ul>
  );
}

function describeLogText(log: BulkOperationLog, timezone: string | null): string {
  return [
    `日時: ${formatDateTime(log.createdAt, timezone)}`,
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

function CopyLogButton({ log, timezone }: { log: BulkOperationLog; timezone: string | null }) {
  const [copied, setCopied] = useState(false);

  async function handleCopy() {
    await navigator.clipboard.writeText(describeLogText(log, timezone));
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <button
      type="button"
      onClick={handleCopy}
      className="rounded bg-neutral-100 dark:bg-neutral-800 px-2 py-1 text-xs text-neutral-700 dark:text-neutral-300"
      title="日時・操作・エラー内容・スタックトレースをコピーします"
    >
      {copied ? "コピーしました" : "コピー"}
    </button>
  );
}
