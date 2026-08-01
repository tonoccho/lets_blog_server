"use client";

import { useActionState, useState } from "react";
import type {
  Project,
  ProjectEnvironment,
  BulkOperationLog,
  BulkOperationType,
  CategoryOption,
} from "@/lib/apiClient";
import {
  runBulkOperationAction,
  runBulkOperationUploadAction,
  replayBulkOperationsAction,
  BulkOperationState,
} from "./actions";

type Target = "CATEGORY" | "PLUGIN" | "THEME";

const ENVIRONMENT_LABEL: Record<ProjectEnvironment, string> = {
  local: "ローカル",
  test: "テスト",
  production: "本番",
};

const TARGET_LABEL: Record<Target, string> = {
  CATEGORY: "カテゴリ",
  PLUGIN: "プラグイン",
  THEME: "テーマ",
};

const TARGET_ACTIONS: Record<Target, { type: BulkOperationType; label: string }[]> = {
  CATEGORY: [
    { type: "CATEGORY_CREATE", label: "作成" },
    { type: "CATEGORY_EDIT", label: "編集" },
    { type: "CATEGORY_DELETE", label: "削除" },
  ],
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

const ZIP_INSTALL_TYPES: BulkOperationType[] = ["PLUGIN_INSTALL", "THEME_INSTALL"];
const SLUG_INPUT_TYPES: BulkOperationType[] = [
  "PLUGIN_INSTALL",
  "PLUGIN_ACTIVATE",
  "PLUGIN_DEACTIVATE",
  "PLUGIN_DELETE",
  "THEME_INSTALL",
  "THEME_ACTIVATE",
  "THEME_DELETE",
];

const initialState: BulkOperationState = {};

export function BulkManagementPanel({
  projectId,
  project,
  logs,
  categories,
}: {
  projectId: number;
  project: Project;
  logs: BulkOperationLog[];
  categories: CategoryOption[];
}) {
  const [target, setTarget] = useState<Target>("CATEGORY");
  const [operationType, setOperationType] = useState<BulkOperationType>("CATEGORY_CREATE");
  const [inputMode, setInputMode] = useState<"slug" | "zip">("slug");
  const [replayState, setReplayState] = useState<BulkOperationState | null>(null);
  const [replayPendingEnv, setReplayPendingEnv] = useState<ProjectEnvironment | null>(null);

  // カテゴリ編集: 編集対象を選択したら現在の値をフォームへ反映する
  const [editName, setEditName] = useState("");
  const [editSlug, setEditSlug] = useState("");
  const [editParentSlug, setEditParentSlug] = useState("");
  const [editDescription, setEditDescription] = useState("");

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

  function handleTargetChange(nextTarget: Target) {
    setTarget(nextTarget);
    setOperationType(TARGET_ACTIONS[nextTarget][0].type);
    setInputMode("slug");
  }

  function handleEditTargetChange(slug: string) {
    const found = categories.find((c) => c.slug === slug);
    setEditName(found?.name ?? "");
    setEditSlug(found?.slug ?? "");
    setEditParentSlug(found?.parentSlug ?? "");
    setEditDescription(found?.description ?? "");
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

  function confirmExecute(label: string): boolean {
    const envNames = managedEnvironments.map((env) => env.label).join("・");
    return window.confirm(
      `紐付いている全環境(${envNames})に対して、${OPERATION_LABEL[operationType]}「${label}」を実行します。よろしいですか?`
    );
  }

  function handleSlugSubmit(e: React.FormEvent<HTMLFormElement>) {
    const formData = new FormData(e.currentTarget);
    const label =
      operationType === "CATEGORY_DELETE"
        ? String(formData.get("categoryTargetSlug") ?? "")
        : String(formData.get("value") ?? "");
    if (!confirmExecute(label)) {
      e.preventDefault();
    }
  }

  function handleUploadSubmit(e: React.FormEvent<HTMLFormElement>) {
    const formData = new FormData(e.currentTarget);
    const file = formData.get("file") as File | null;
    if (!confirmExecute(file?.name ?? "")) {
      e.preventDefault();
    }
  }

  if (managedEnvironments.length === 0) {
    return (
      <div className="rounded-lg border border-neutral-200 bg-white p-4 text-sm text-neutral-500">
        <h3 className="mb-2 font-medium text-neutral-700">一括管理</h3>
        自動構築(managed)されたWordPress環境が1つ以上紐付いている場合に、
        カテゴリ・プラグイン・テーマの一括操作ができます。
      </div>
    );
  }

  const isZipInstallType = ZIP_INSTALL_TYPES.includes(operationType);
  const showSlugForm = !isZipInstallType || inputMode === "slug";

  return (
    <div className="space-y-6 rounded-lg border border-neutral-200 bg-white p-4">
      <div>
        <h3 className="mb-1 font-medium text-neutral-700">一括管理</h3>
        <p className="mb-3 text-sm text-neutral-500">
          紐付いている全環境({managedEnvironments.map((e) => e.label).join("・")})に対して、
          カテゴリ・プラグイン・テーマの操作を同時実行します。
        </p>

        <div className="mb-2 flex gap-2 text-sm">
          {(Object.keys(TARGET_LABEL) as Target[]).map((t) => (
            <button
              key={t}
              type="button"
              onClick={() => handleTargetChange(t)}
              className={`rounded px-3 py-1.5 ${
                target === t ? "bg-neutral-900 text-white" : "bg-neutral-100 text-neutral-600"
              }`}
            >
              {TARGET_LABEL[t]}
            </button>
          ))}
        </div>

        <div className="mb-3 flex gap-2 text-sm">
          {TARGET_ACTIONS[target].map((action) => (
            <button
              key={action.type}
              type="button"
              onClick={() => {
                setOperationType(action.type);
                setInputMode("slug");
              }}
              className={`rounded px-3 py-1.5 ${
                operationType === action.type ? "bg-neutral-700 text-white" : "bg-neutral-100 text-neutral-600"
              }`}
            >
              {action.label}
            </button>
          ))}
        </div>

        {isZipInstallType && (
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
              key={operationType}
              action={slugFormAction}
              onSubmit={handleSlugSubmit}
              className="flex flex-wrap items-end gap-2 text-sm"
            >
              <input type="hidden" name="operationType" value={operationType} />

              {operationType === "CATEGORY_CREATE" && (
                <>
                  <Field label="カテゴリ名" name="value" placeholder="お知らせ" required />
                  <Field label="スラッグ" name="categorySlug" placeholder="oshirase" required />
                  <CategorySelect label="親カテゴリ(任意)" name="categoryParentSlug" categories={categories} />
                  <Field label="説明(任意)" name="categoryDescription" placeholder="カテゴリの説明" />
                </>
              )}

              {operationType === "CATEGORY_EDIT" && (
                <>
                  <CategorySelect
                    label="編集対象"
                    name="categoryTargetSlug"
                    categories={categories}
                    required
                    onSelect={handleEditTargetChange}
                  />
                  <Field label="新しいカテゴリ名" name="value" required value={editName} onChange={setEditName} />
                  <Field
                    label="新しいスラッグ"
                    name="categorySlug"
                    required
                    value={editSlug}
                    onChange={setEditSlug}
                  />
                  <CategorySelect
                    label="親カテゴリ(任意)"
                    name="categoryParentSlug"
                    categories={categories}
                    value={editParentSlug}
                    onSelect={setEditParentSlug}
                  />
                  <Field
                    label="説明(任意)"
                    name="categoryDescription"
                    value={editDescription}
                    onChange={setEditDescription}
                  />
                </>
              )}

              {operationType === "CATEGORY_DELETE" && (
                <CategorySelect label="削除対象" name="categoryTargetSlug" categories={categories} required />
              )}

              {SLUG_INPUT_TYPES.includes(operationType) && (
                <Field label="wordpress.orgのslug" name="value" placeholder="akismet" required />
              )}

              <button
                type="submit"
                disabled={slugPending}
                className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
              >
                {slugPending ? "実行中…" : "実行する"}
              </button>
            </form>
            {(operationType === "CATEGORY_CREATE" || operationType === "CATEGORY_EDIT") && (
              <p className="mt-1 text-xs text-neutral-500">
                親カテゴリは各環境の既存カテゴリからスラッグで解決されます。対象環境に存在しない場合、
                その環境の処理は失敗として記録されます。
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

function describeLogValue(log: BulkOperationLog): string {
  if (log.sourceType === "ZIP") {
    return `${log.originalFilename ?? log.value}(zip)`;
  }
  if (log.operationType.startsWith("CATEGORY")) {
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

function Field({
  label,
  name,
  placeholder,
  required,
  value,
  onChange,
}: {
  label: string;
  name: string;
  placeholder?: string;
  required?: boolean;
  value?: string;
  onChange?: (value: string) => void;
}) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-neutral-600">{label}</span>
      <input
        name={name}
        required={required}
        placeholder={placeholder}
        value={value}
        onChange={onChange ? (e) => onChange(e.target.value) : undefined}
        className="rounded border border-neutral-300 px-3 py-2 text-sm"
      />
    </label>
  );
}

function CategorySelect({
  label,
  name,
  categories,
  required,
  value,
  onSelect,
}: {
  label: string;
  name: string;
  categories: CategoryOption[];
  required?: boolean;
  value?: string;
  onSelect?: (slug: string) => void;
}) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-neutral-600">{label}</span>
      <select
        name={name}
        required={required}
        value={value}
        onChange={onSelect ? (e) => onSelect(e.target.value) : undefined}
        className="rounded border border-neutral-300 px-3 py-2 text-sm"
      >
        <option value="">{required ? "選択してください" : "なし"}</option>
        {categories.map((c) => (
          <option key={c.slug} value={c.slug}>
            {c.name}({c.slug})
          </option>
        ))}
      </select>
    </label>
  );
}
