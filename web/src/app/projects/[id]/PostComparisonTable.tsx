"use client";

import { useState } from "react";
import type { PostComparisonPage, PostType } from "@/lib/apiClient";
import {
  fetchPostComparisonAction,
  deletePostEverywhereAction,
  updatePostStatusEverywhereAction,
} from "./actions";

const STATUS_OPTIONS = [
  { value: "publish", label: "公開" },
  { value: "draft", label: "下書き" },
  { value: "pending", label: "レビュー待ち" },
  { value: "private", label: "非公開" },
];

const ENVIRONMENTS: ("local" | "test" | "production")[] = ["local", "test", "production"];

/**
 * プロジェクト管理画面のポスト/ページ管理タブ。3環境をslugで名寄せして一覧表示し、
 * 行単位で全環境から削除、または全環境のステータスを一括変更する。
 * PluginThemeComparisonTable.tsxの構成に準拠する。
 */
export function PostComparisonTable({
  projectId,
  initialPage,
}: {
  projectId: number;
  initialPage: PostComparisonPage;
}) {
  const [postType, setPostType] = useState<PostType>(initialPage.postType);
  const [pageData, setPageData] = useState(initialPage);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<{ type: "error" | "success"; text: string } | null>(null);
  const [statusSelections, setStatusSelections] = useState<Record<string, string>>({});
  const [pendingAction, setPendingAction] = useState<{ slug: string; type: "status" | "delete" } | null>(null);

  const totalPages = Math.max(1, Math.ceil(pageData.totalCount / pageData.size));

  async function load(nextPostType: PostType, page: number) {
    setLoading(true);
    try {
      const next = await fetchPostComparisonAction(projectId, nextPostType, page);
      setPageData(next);
      setStatusSelections({});
    } finally {
      setLoading(false);
    }
  }

  async function handlePostTypeChange(next: PostType) {
    setPostType(next);
    await load(next, 0);
  }

  async function handleStatusApply(slug: string) {
    const status = statusSelections[slug];
    if (!status) {
      setMessage({ type: "error", text: "変更後のステータスを選択してください。" });
      return;
    }
    if (!window.confirm(`「${slug}」のステータスを全環境で変更します。よろしいですか?`)) {
      return;
    }
    setPendingAction({ slug, type: "status" });
    try {
      const result = await updatePostStatusEverywhereAction(projectId, postType, slug, status);
      setMessage(result.error ? { type: "error", text: result.error } : { type: "success", text: "変更しました。" });
      await load(postType, pageData.page);
    } finally {
      setPendingAction(null);
    }
  }

  async function handleDelete(slug: string) {
    if (!window.confirm(`「${slug}」を、投稿されているすべての環境から削除します。よろしいですか?`)) {
      return;
    }
    setPendingAction({ slug, type: "delete" });
    try {
      const result = await deletePostEverywhereAction(projectId, postType, slug);
      setMessage(result.error ? { type: "error", text: result.error } : { type: "success", text: "削除しました。" });
      await load(postType, pageData.page);
    } finally {
      setPendingAction(null);
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2 text-sm">
          <span className="text-neutral-500">種別:</span>
          <select
            value={postType}
            disabled={loading}
            onChange={(e) => handlePostTypeChange(e.target.value as PostType)}
            className="rounded border border-neutral-300 px-2 py-1"
          >
            <option value="post">ポスト</option>
            <option value="page">ページ</option>
          </select>
        </div>
        <button
          type="button"
          disabled={loading}
          onClick={() => load(postType, pageData.page)}
          className="rounded bg-neutral-100 px-3 py-1.5 text-sm text-neutral-700 disabled:opacity-50"
        >
          更新
        </button>
      </div>

      {message && (
        <p className={`text-sm ${message.type === "error" ? "text-red-600" : "text-green-600"}`}>{message.text}</p>
      )}

      {pageData.items.length === 0 ? (
        <p className="text-sm text-neutral-500">{postType === "post" ? "ポスト" : "ページ"}はまだありません。</p>
      ) : (
        <div className="overflow-x-auto rounded border border-neutral-200">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-neutral-200 bg-neutral-50 text-neutral-500">
              <tr>
                <th className="px-2 py-1.5">slug</th>
                <th className="px-2 py-1.5">ローカル</th>
                <th className="px-2 py-1.5">テスト</th>
                <th className="px-2 py-1.5">本番</th>
                <th className="px-2 py-1.5">操作</th>
              </tr>
            </thead>
            <tbody>
              {pageData.items.map((row) => {
                const isStatusPending = pendingAction?.slug === row.slug && pendingAction?.type === "status";
                const isDeletePending = pendingAction?.slug === row.slug && pendingAction?.type === "delete";
                const rowBusy = isStatusPending || isDeletePending;
                return (
                  <tr key={row.slug} className="border-t border-neutral-100 align-top">
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
                      if (!value.available) {
                        return (
                          <td key={env} className="px-2 py-1.5 text-neutral-300">
                            対象外
                          </td>
                        );
                      }
                      if (!value.postId) {
                        return (
                          <td key={env} className="px-2 py-1.5 text-neutral-300">
                            未投稿
                          </td>
                        );
                      }
                      return (
                        <td key={env} className="px-2 py-1.5">
                          <div className="max-w-[220px] truncate" title={value.title ?? undefined}>
                            {value.title || "(無題)"}
                          </div>
                          <div className="text-xs text-neutral-500">{value.status}</div>
                        </td>
                      );
                    })}
                    <td className="px-2 py-1.5">
                      <div className="flex flex-wrap items-center gap-2">
                        <select
                          value={statusSelections[row.slug] ?? ""}
                          onChange={(e) =>
                            setStatusSelections((prev) => ({ ...prev, [row.slug]: e.target.value }))
                          }
                          className="rounded border border-neutral-300 px-2 py-1 text-xs"
                        >
                          <option value="">ステータス変更…</option>
                          {STATUS_OPTIONS.map((opt) => (
                            <option key={opt.value} value={opt.value}>
                              {opt.label}
                            </option>
                          ))}
                        </select>
                        <button
                          type="button"
                          onClick={() => handleStatusApply(row.slug)}
                          disabled={rowBusy}
                          className="rounded bg-neutral-100 px-2 py-1 text-xs text-neutral-700 disabled:opacity-50"
                        >
                          {isStatusPending ? "変更中…" : "適用"}
                        </button>
                        <button
                          type="button"
                          onClick={() => handleDelete(row.slug)}
                          disabled={rowBusy}
                          className="rounded bg-red-50 px-2 py-1 text-xs text-red-600 disabled:opacity-50"
                        >
                          {isDeletePending ? "削除中…" : "削除"}
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
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
            onClick={() => load(postType, pageData.page - 1)}
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
            onClick={() => load(postType, pageData.page + 1)}
            className="rounded bg-neutral-100 px-3 py-1.5 disabled:opacity-50"
          >
            次へ
          </button>
        </div>
      </div>
    </div>
  );
}
