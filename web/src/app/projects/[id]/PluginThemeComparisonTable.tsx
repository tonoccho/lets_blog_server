"use client";

import { useState } from "react";
import type { ProjectEnvironment, StatusComparisonPage, PluginThemeStatus } from "@/lib/apiClient";
import {
  applyToEnvironmentAction,
  reconcileStateAction,
  deleteSlugEverywhereAction,
  fetchStatusComparisonAction,
} from "./actions";

const ENVIRONMENT_LABEL: Record<ProjectEnvironment, string> = {
  local: "ローカル",
  test: "テスト",
  production: "本番",
};

const STATUS_LABEL: Record<PluginThemeStatus, string> = {
  NOT_INSTALLED: "未インストール",
  INACTIVE: "無効",
  ACTIVE: "有効",
};

const ENVIRONMENTS: ProjectEnvironment[] = ["local", "test", "production"];

type Kind = "plugin" | "theme";

export function PluginThemeComparisonTable({
  projectId,
  kind,
  initialPage,
  managedEnvironments,
}: {
  projectId: number;
  kind: Kind;
  initialPage: StatusComparisonPage;
  managedEnvironments: { value: ProjectEnvironment; label: string }[];
}) {
  const [pageData, setPageData] = useState(initialPage);
  const [loading, setLoading] = useState(false);
  const [showNewForm, setShowNewForm] = useState(false);
  const [message, setMessage] = useState<{ type: "error" | "success"; text: string } | null>(null);
  const [selections, setSelections] = useState<Record<string, Partial<Record<ProjectEnvironment, PluginThemeStatus>>>>(
    {}
  );

  const master = pageData.masterEnvironment;
  const label = kind === "plugin" ? "プラグイン" : "テーマ";
  const totalPages = Math.max(1, Math.ceil(pageData.totalCount / pageData.size));

  async function goToPage(page: number) {
    setLoading(true);
    try {
      const next = await fetchStatusComparisonAction(projectId, kind, page);
      setPageData(next);
      setSelections({});
    } finally {
      setLoading(false);
    }
  }

  function selectedStatus(slug: string, environment: ProjectEnvironment, current: PluginThemeStatus): PluginThemeStatus {
    return selections[slug]?.[environment] ?? current;
  }

  function handleSelectChange(slug: string, environment: ProjectEnvironment, status: PluginThemeStatus) {
    setSelections((prev) => ({
      ...prev,
      [slug]: { ...prev[slug], [environment]: status },
    }));
  }

  async function handleApply(slug: string, row: StatusComparisonPage["items"][number]) {
    const changes = ENVIRONMENTS.filter((env) => {
      const value = row[env];
      if (!value.available || !value.status) {
        return false;
      }
      return selectedStatus(slug, env, value.status) !== value.status;
    }).map((env) => ({ environment: env, desiredStatus: selections[slug]?.[env] as PluginThemeStatus }));

    if (changes.length === 0) {
      setMessage({ type: "error", text: "変更されたセルがありません。" });
      return;
    }
    if (!window.confirm(`「${slug}」について、変更した環境の状態を反映します。よろしいですか?`)) {
      return;
    }
    const result = await reconcileStateAction(projectId, kind, slug, changes);
    setMessage(result.error ? { type: "error", text: result.error } : { type: "success", text: "反映しました。" });
    await goToPage(pageData.page);
  }

  async function handleDelete(slug: string) {
    if (!window.confirm(`「${slug}」を、インストールされているすべての環境から削除します。よろしいですか?`)) {
      return;
    }
    const result = await deleteSlugEverywhereAction(projectId, kind, slug);
    setMessage(result.error ? { type: "error", text: result.error } : { type: "success", text: "削除しました。" });
    await goToPage(pageData.page);
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-neutral-500">
          マスター環境: <span className="font-medium text-neutral-700">{ENVIRONMENT_LABEL[master]}</span>
          (取得エラーは「エラー」で表示されます)
        </p>
        <div className="flex flex-wrap gap-2">
          {ENVIRONMENTS.map((env) => (
            <button
              key={env}
              type="button"
              disabled={loading}
              onClick={() => goToPage(pageData.page)}
              className="rounded bg-neutral-100 px-3 py-1.5 text-sm text-neutral-700 disabled:opacity-50"
              title={`${ENVIRONMENT_LABEL[env]}環境を含め、この一覧を再取得します`}
            >
              {ENVIRONMENT_LABEL[env]}を更新
            </button>
          ))}
          <button
            type="button"
            onClick={() => setShowNewForm((v) => !v)}
            className="rounded bg-neutral-900 px-3 py-1.5 text-sm text-white"
          >
            + 新規インストール
          </button>
        </div>
      </div>

      {message && (
        <p className={`text-sm ${message.type === "error" ? "text-red-600" : "text-green-600"}`}>{message.text}</p>
      )}

      {showNewForm && (
        <NewInstallForm
          projectId={projectId}
          kind={kind}
          managedEnvironments={managedEnvironments}
          onDone={async () => {
            setShowNewForm(false);
            await goToPage(pageData.page);
          }}
        />
      )}

      {pageData.items.length === 0 ? (
        <p className="text-sm text-neutral-500">{label}はまだありません。</p>
      ) : (
        <div className="overflow-x-auto rounded border border-neutral-200">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-neutral-200 bg-neutral-50 text-neutral-500">
              <tr>
                <th className="px-2 py-1.5">{label}</th>
                <th className="px-2 py-1.5">ローカル</th>
                <th className="px-2 py-1.5">テスト</th>
                <th className="px-2 py-1.5">本番</th>
                <th className="px-2 py-1.5">操作</th>
              </tr>
            </thead>
            <tbody>
              {pageData.items.map((row) => (
                <tr key={row.slug} className="border-t border-neutral-100">
                  <td className="px-2 py-1.5 font-medium text-neutral-700">{row.slug}</td>
                  {ENVIRONMENTS.map((env) => {
                    const value = row[env];
                    if (value.error) {
                      return (
                        <td key={env} className="px-2 py-1.5 text-red-600" title={value.errorMessage ?? undefined}>
                          エラー
                        </td>
                      );
                    }
                    if (!value.available || !value.status) {
                      return (
                        <td key={env} className="px-2 py-1.5 text-neutral-300">
                          対象外
                        </td>
                      );
                    }
                    const current = value.status;
                    const selected = selectedStatus(row.slug, env, current);
                    const disableNotInstalled = current !== "NOT_INSTALLED";
                    const disableInactive = kind === "theme" && current === "ACTIVE";
                    return (
                      <td key={env} className="px-2 py-1.5">
                        <select
                          value={selected}
                          onChange={(e) => handleSelectChange(row.slug, env, e.target.value as PluginThemeStatus)}
                          className="rounded border border-neutral-300 px-2 py-1 text-sm"
                        >
                          <option value="NOT_INSTALLED" disabled={disableNotInstalled}>
                            {STATUS_LABEL.NOT_INSTALLED}
                          </option>
                          <option value="INACTIVE" disabled={disableInactive}>
                            {STATUS_LABEL.INACTIVE}
                          </option>
                          <option value="ACTIVE">{STATUS_LABEL.ACTIVE}</option>
                        </select>
                      </td>
                    );
                  })}
                  <td className="px-2 py-1.5">
                    <div className="flex flex-wrap gap-2">
                      <button
                        type="button"
                        onClick={() => handleApply(row.slug, row)}
                        className="rounded bg-neutral-100 px-2 py-1 text-xs text-neutral-700"
                      >
                        反映
                      </button>
                      <button
                        type="button"
                        onClick={() => handleDelete(row.slug)}
                        className="rounded bg-red-50 px-2 py-1 text-xs text-red-600"
                      >
                        削除
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="flex items-center justify-between text-sm">
        <span className="text-neutral-500">
          {pageData.totalCount}件中{" "}
          {pageData.items.length === 0 ? 0 : pageData.page * pageData.size + 1}-
          {pageData.page * pageData.size + pageData.items.length}件を表示
        </span>
        <div className="flex items-center gap-2">
          <button
            type="button"
            disabled={pageData.page <= 0 || loading}
            onClick={() => goToPage(pageData.page - 1)}
            className="rounded bg-neutral-100 px-3 py-1.5 disabled:opacity-50"
          >
            前へ
          </button>
          <span className="text-neutral-500">
            {pageData.page + 1} / {totalPages}
          </span>
          <button
            type="button"
            disabled={pageData.page + 1 >= totalPages || loading}
            onClick={() => goToPage(pageData.page + 1)}
            className="rounded bg-neutral-100 px-3 py-1.5 disabled:opacity-50"
          >
            次へ
          </button>
        </div>
      </div>
    </div>
  );
}

function NewInstallForm({
  projectId,
  kind,
  managedEnvironments,
  onDone,
}: {
  projectId: number;
  kind: Kind;
  managedEnvironments: { value: ProjectEnvironment; label: string }[];
  onDone: () => void;
}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const formData = new FormData(e.currentTarget);
    formData.set("operationType", kind === "plugin" ? "PLUGIN_INSTALL" : "THEME_INSTALL");
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
    <form
      onSubmit={handleSubmit}
      className="flex flex-wrap items-end gap-2 rounded border border-neutral-200 bg-neutral-50 p-3 text-sm"
    >
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
        <input name="value" placeholder="akismet" required className="rounded border border-neutral-300 px-3 py-2 text-sm" />
      </label>
      <button
        type="submit"
        disabled={pending}
        className="rounded bg-neutral-900 px-3 py-2 text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "インストール中…" : "インストール"}
      </button>
      {error && <p className="w-full text-red-600">{error}</p>}
    </form>
  );
}
