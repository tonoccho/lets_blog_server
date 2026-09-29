"use client";

import { useEffect, useState } from "react";
import type { ProjectEnvironment, TermComparisonPage, TermComparisonRow, TermEnvironmentValue } from "@/lib/apiClient";
import {
  applyToEnvironmentAction,
  syncTermToMasterAction,
  deleteTermEverywhereAction,
  fetchTermComparisonAction,
  editTermAndSyncAction,
  syncAllTermsToMasterAction,
} from "./actions";

const ENVIRONMENT_LABEL: Record<ProjectEnvironment, string> = {
  local: "ローカル",
  test: "テスト",
  production: "本番",
};

type Kind = "category" | "tag";

function valueOf(row: TermComparisonRow, environment: ProjectEnvironment): TermEnvironmentValue {
  return environment === "local" ? row.local : environment === "test" ? row.test : row.production;
}

// マスター環境にまだ値が無い行を編集フォームで開いたときの初期値用に、
// マスター→ローカル→テスト→本番の順で最初に見つかった値を返す。
function preferredValue(row: TermComparisonRow, master: ProjectEnvironment): TermEnvironmentValue {
  const order: ProjectEnvironment[] = [master, "local", "test", "production"];
  for (const environment of order) {
    const value = valueOf(row, environment);
    if (value.available && value.slug) {
      return value;
    }
  }
  return valueOf(row, master);
}

export function TermComparisonTable({
  projectId,
  kind,
  initialPage,
}: {
  projectId: number;
  kind: Kind;
  initialPage: TermComparisonPage;
}) {
  const [pageData, setPageData] = useState(initialPage);
  const [loading, setLoading] = useState(false);
  const [showNewForm, setShowNewForm] = useState(false);
  const [editingSlug, setEditingSlug] = useState<string | null>(null);
  const [message, setMessage] = useState<{ type: "error" | "success"; text: string } | null>(null);
  const [pendingAction, setPendingAction] = useState<{ slug: string; type: "sync" | "delete" } | null>(null);
  const [syncAllPending, setSyncAllPending] = useState(false);

  const master = pageData.masterEnvironment;
  const label = kind === "category" ? "カテゴリ" : "タグ";
  const totalPages = Math.max(1, Math.ceil(pageData.totalCount / pageData.size));

  async function goToPage(page: number) {
    setLoading(true);
    try {
      const next = await fetchTermComparisonAction(projectId, kind, page);
      setPageData(next);
    } finally {
      setLoading(false);
    }
  }

  async function handleSync(row: TermComparisonRow) {
    if (
      !window.confirm(
        `「${row.name}」を、マスター環境(${ENVIRONMENT_LABEL[master]})の内容に同期します。マスター以外の環境の内容は上書きされます。よろしいですか?`
      )
    ) {
      return;
    }
    setPendingAction({ slug: row.slug, type: "sync" });
    try {
      const result = await syncTermToMasterAction(projectId, kind, row.slug);
      setMessage(result.error ? { type: "error", text: result.error } : { type: "success", text: "同期しました。" });
      await goToPage(pageData.page);
    } finally {
      setPendingAction(null);
    }
  }

  async function handleDelete(row: TermComparisonRow) {
    if (!window.confirm(`「${row.name}」を、存在するすべての環境から削除します。よろしいですか?`)) {
      return;
    }
    setPendingAction({ slug: row.slug, type: "delete" });
    try {
      const result = await deleteTermEverywhereAction(projectId, kind, row.slug);
      setMessage(result.error ? { type: "error", text: result.error } : { type: "success", text: "削除しました。" });
      setEditingSlug(null);
      await goToPage(pageData.page);
    } finally {
      setPendingAction(null);
    }
  }

  async function handleSyncAll() {
    if (
      !window.confirm(
        `マスター環境(${ENVIRONMENT_LABEL[master]})と異なる${label}をすべて、マスター環境の内容で上書きします。よろしいですか?`
      )
    ) {
      return;
    }
    setSyncAllPending(true);
    try {
      const result = await syncAllTermsToMasterAction(projectId, kind);
      if (result.error) {
        setMessage({ type: "error", text: result.error });
      } else if (!result.results || result.results.length === 0) {
        setMessage({ type: "success", text: "マスターとの差分はありませんでした。" });
      } else {
        setMessage({ type: "success", text: `${result.results.length}件の操作でマスターに揃えました。` });
      }
      await goToPage(pageData.page);
    } finally {
      setSyncAllPending(false);
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-neutral-500 dark:text-neutral-400">
          マスター環境: <span className="font-medium text-neutral-700 dark:text-neutral-300">{ENVIRONMENT_LABEL[master]}</span>
          (マスターと異なる値は赤字、取得エラーは「エラー」で表示されます)
        </p>
        <div className="flex flex-wrap gap-2">
          {(["local", "test", "production"] as ProjectEnvironment[]).map((env) => (
            <button
              key={env}
              type="button"
              disabled={loading}
              onClick={() => goToPage(pageData.page)}
              className="rounded bg-neutral-100 dark:bg-neutral-800 px-3 py-1.5 text-sm text-neutral-700 dark:text-neutral-300 disabled:opacity-50"
              title={`${ENVIRONMENT_LABEL[env]}環境を含め、この一覧を再取得します`}
            >
              {ENVIRONMENT_LABEL[env]}を更新
            </button>
          ))}
          <button
            type="button"
            onClick={handleSyncAll}
            disabled={syncAllPending}
            className="rounded bg-neutral-100 dark:bg-neutral-800 px-3 py-1.5 text-sm text-neutral-700 dark:text-neutral-300 disabled:opacity-50"
          >
            {syncAllPending ? "処理中…" : "マスターに一括で揃える"}
          </button>
          <button
            type="button"
            onClick={() => setShowNewForm((v) => !v)}
            className="rounded bg-neutral-900 px-3 py-1.5 text-sm text-white"
          >
            + 新規追加
          </button>
        </div>
      </div>

      {message && (
        <p className={`text-sm ${message.type === "error" ? "text-red-600" : "text-green-600"}`}>{message.text}</p>
      )}

      {showNewForm && (
        <NewItemForm
          projectId={projectId}
          kind={kind}
          masterEnvironment={master}
          onDone={async () => {
            setShowNewForm(false);
            await goToPage(pageData.page);
          }}
        />
      )}

      {pageData.items.length === 0 ? (
        <p className="text-sm text-neutral-500 dark:text-neutral-400">{label}はまだありません。</p>
      ) : (
        <div className="space-y-4">
          {pageData.items.map((row) => {
            const masterValue = valueOf(row, master);
            // 同期はマスター環境の値を他環境へ反映する操作のため、マスターに値がある行のみ対象。
            // 編集はマスターに値が無い行でも行える(保存するとマスター環境に新規作成される)。
            const canSync = masterValue.available && !!masterValue.slug;
            return (
              <div key={row.slug} className="overflow-x-auto rounded border border-neutral-200 dark:border-neutral-800">
                <table className="w-full text-left text-sm">
                  <thead className="border-b border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 text-neutral-500 dark:text-neutral-400">
                    <tr>
                      <th className="px-2 py-1.5">{row.name}</th>
                      <th className="px-2 py-1.5">ローカル</th>
                      <th className="px-2 py-1.5">テスト</th>
                      <th className="px-2 py-1.5">本番</th>
                      <th className="px-2 py-1.5">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    <AttributeRow label={kind === "category" ? "カテゴリ名" : "タグ名"} row={row} master={master} field={() => row.name} highlightable={false} />
                    <AttributeRow label="スラッグ" row={row} master={master} field={(v) => v.slug} />
                    {kind === "category" && (
                      <AttributeRow label="親カテゴリ" row={row} master={master} field={(v) => v.parentSlug} />
                    )}
                    <AttributeRow label="説明" row={row} master={master} field={(v) => v.description} />
                    <tr className="border-t border-neutral-100 dark:border-neutral-800">
                      <td className="px-2 py-1.5" colSpan={4} />
                      <td className="px-2 py-1.5">
                        {(() => {
                          const isDeleting = pendingAction?.slug === row.slug && pendingAction?.type === "delete";
                          const isSyncing = pendingAction?.slug === row.slug && pendingAction?.type === "sync";
                          const rowBusy = isDeleting || isSyncing;
                          return (
                            <div className="flex flex-wrap gap-2">
                              <button
                                type="button"
                                onClick={() => setEditingSlug(editingSlug === row.slug ? null : row.slug)}
                                disabled={rowBusy}
                                className="rounded bg-neutral-100 dark:bg-neutral-800 px-2 py-1 text-xs text-neutral-700 dark:text-neutral-300 disabled:opacity-50"
                              >
                                編集
                              </button>
                              <button
                                type="button"
                                onClick={() => handleDelete(row)}
                                disabled={rowBusy}
                                className="rounded bg-red-50 px-2 py-1 text-xs text-red-600 disabled:opacity-50"
                              >
                                {isDeleting ? "削除中…" : "削除"}
                              </button>
                              {canSync && (
                                <button
                                  type="button"
                                  onClick={() => handleSync(row)}
                                  disabled={rowBusy}
                                  className="rounded bg-neutral-100 dark:bg-neutral-800 px-2 py-1 text-xs text-neutral-700 dark:text-neutral-300 disabled:opacity-50"
                                >
                                  {isSyncing ? "同期中…" : "同期"}
                                </button>
                              )}
                            </div>
                          );
                        })()}
                      </td>
                    </tr>
                  </tbody>
                </table>
                {editingSlug === row.slug && (
                  <div className="border-t border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 p-3">
                    <EditItemForm
                      projectId={projectId}
                      kind={kind}
                      masterEnvironment={master}
                      row={row}
                      onDone={async () => {
                        setEditingSlug(null);
                        await goToPage(pageData.page);
                      }}
                    />
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      <div className="flex items-center justify-between text-sm">
        <span className="text-neutral-500 dark:text-neutral-400">
          {pageData.totalCount}件中{" "}
          {pageData.items.length === 0 ? 0 : pageData.page * pageData.size + 1}-
          {pageData.page * pageData.size + pageData.items.length}件を表示
        </span>
        <div className="flex items-center gap-2">
          <button
            type="button"
            disabled={pageData.page <= 0 || loading}
            onClick={() => goToPage(pageData.page - 1)}
            className="rounded bg-neutral-100 dark:bg-neutral-800 px-3 py-1.5 disabled:opacity-50"
          >
            前へ
          </button>
          <span className="text-neutral-500 dark:text-neutral-400">
            {pageData.page + 1} / {totalPages}
          </span>
          <button
            type="button"
            disabled={pageData.page + 1 >= totalPages || loading}
            onClick={() => goToPage(pageData.page + 1)}
            className="rounded bg-neutral-100 dark:bg-neutral-800 px-3 py-1.5 disabled:opacity-50"
          >
            次へ
          </button>
        </div>
      </div>
    </div>
  );
}

function AttributeRow({
  label,
  row,
  master,
  field,
  highlightable = true,
}: {
  label: string;
  row: TermComparisonRow;
  master: ProjectEnvironment;
  field: (value: TermEnvironmentValue) => string | null;
  highlightable?: boolean;
}) {
  const masterValue = field(valueOf(row, master));

  function cell(environment: ProjectEnvironment) {
    const value = valueOf(row, environment);
    if (value.error) {
      return (
        <span className="text-red-600" title={value.errorMessage ?? undefined}>
          エラー
        </span>
      );
    }
    if (!value.available) {
      return <span className="text-neutral-300">対象外</span>;
    }
    const text = field(value);
    const display = text ?? (environment === master ? "(未設定)" : "(未登録)");
    const isDiff = highlightable && environment !== master && text !== masterValue;
    return <span className={isDiff ? "text-red-600" : undefined}>{display}</span>;
  }

  return (
    <tr className="border-t border-neutral-100 dark:border-neutral-800">
      <td className="px-2 py-1.5 text-neutral-500 dark:text-neutral-400">{label}</td>
      <td className="px-2 py-1.5">{cell("local")}</td>
      <td className="px-2 py-1.5">{cell("test")}</td>
      <td className="px-2 py-1.5">{cell("production")}</td>
      <td className="px-2 py-1.5" />
    </tr>
  );
}

function NewItemForm({
  projectId,
  kind,
  masterEnvironment,
  onDone,
}: {
  projectId: number;
  kind: Kind;
  masterEnvironment: ProjectEnvironment;
  onDone: () => void;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // issue #1414: ハイドレーション完了(mounted)までは送信ボタンを押せないようにする(#1413と同じ方式)。
  // 完了前はonSubmitが未結線で、クリックがネイティブ送信になり入力値だけが失われる。
  const [mounted, setMounted] = useState(false);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMounted(true);
  }, []);

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const formData = new FormData(e.currentTarget);
    formData.set("environment", masterEnvironment);
    formData.set("operationType", kind === "category" ? "CATEGORY_CREATE" : "TAG_CREATE");
    setPending(true);
    setError(null);
    const result = await applyToEnvironmentAction(projectId, {}, formData);
    setPending(false);
    if (result.error) {
      setError(result.error);
    } else {
      onDone();
    }
  }

  return (
    // issue #1051: JS無効時のネイティブGETフォールバックで入力値がURLへ漏れることを防ぐため、
    // method="post"を明示する。送信自体はhandleSubmitがpreventDefaultして処理する。
    <form
      onSubmit={handleSubmit}
      method="post"
      className="flex flex-wrap items-end gap-2 rounded border border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 p-3 text-sm"
    >
      <Field label="名前" name="value" required />
      <Field label="スラッグ" name="categorySlug" required />
      {kind === "category" && <Field label="親カテゴリのスラッグ(任意)" name="categoryParentSlug" />}
      <Field label="説明(任意)" name="categoryDescription" />
      <button
        type="submit"
        disabled={pending || !mounted}
        className="rounded bg-neutral-900 px-3 py-2 text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "保存中…" : "マスター環境に追加"}
      </button>
      {error && <p className="w-full text-red-600">{error}</p>}
    </form>
  );
}

function EditItemForm({
  projectId,
  kind,
  masterEnvironment,
  row,
  onDone,
}: {
  projectId: number;
  kind: Kind;
  masterEnvironment: ProjectEnvironment;
  row: TermComparisonRow;
  onDone: () => void;
}) {
  const masterValue = preferredValue(row, masterEnvironment);
  const hasMasterValue = valueOf(row, masterEnvironment).available && !!valueOf(row, masterEnvironment).slug;
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // issue #1414: ハイドレーション完了(mounted)までは送信ボタンを押せないようにする(#1413と同じ方式)。
  // 完了前はonSubmitが未結線で、クリックがネイティブ送信になり入力値だけが失われる。
  const [mounted, setMounted] = useState(false);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMounted(true);
  }, []);

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const confirmText = hasMasterValue
      ? `保存すると、マスター環境(${ENVIRONMENT_LABEL[masterEnvironment]})以外の環境の内容も上書きされます。よろしいですか?`
      : `マスター環境(${ENVIRONMENT_LABEL[masterEnvironment]})にはまだ存在しないため、新規作成した上で他の環境にも反映します。よろしいですか?`;
    if (!window.confirm(confirmText)) {
      return;
    }
    const formData = new FormData(e.currentTarget);
    setPending(true);
    setError(null);
    const result = await editTermAndSyncAction(projectId, kind, {
      targetSlug: row.slug,
      value: String(formData.get("value") ?? ""),
      slug: String(formData.get("categorySlug") ?? ""),
      parentSlug: String(formData.get("categoryParentSlug") ?? "") || undefined,
      description: String(formData.get("categoryDescription") ?? "") || undefined,
    });
    setPending(false);
    if (result.error) {
      setError(result.error);
    } else {
      onDone();
    }
  }

  return (
    // issue #1051: JS無効時のネイティブGETフォールバックで入力値がURLへ漏れることを防ぐため、
    // method="post"を明示する。送信自体はhandleSubmitがpreventDefaultして処理する。
    <form onSubmit={handleSubmit} method="post" className="flex flex-wrap items-end gap-2 text-sm">
      <Field label="名前" name="value" defaultValue={row.name} required />
      <Field label="スラッグ" name="categorySlug" defaultValue={masterValue.slug ?? ""} required />
      {kind === "category" && (
        <Field label="親カテゴリのスラッグ(任意)" name="categoryParentSlug" defaultValue={masterValue.parentSlug ?? ""} />
      )}
      <Field label="説明(任意)" name="categoryDescription" defaultValue={masterValue.description ?? ""} />
      <button
        type="submit"
        disabled={pending || !mounted}
        className="rounded bg-neutral-900 px-3 py-2 text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "保存中…" : "保存(全環境に反映)"}
      </button>
      {error && <p className="w-full text-red-600">{error}</p>}
    </form>
  );
}

function Field({
  label,
  name,
  defaultValue,
  required,
}: {
  label: string;
  name: string;
  defaultValue?: string;
  required?: boolean;
}) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-neutral-600 dark:text-neutral-400">{label}</span>
      <input
        name={name}
        defaultValue={defaultValue}
        required={required}
        className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
      />
    </label>
  );
}
