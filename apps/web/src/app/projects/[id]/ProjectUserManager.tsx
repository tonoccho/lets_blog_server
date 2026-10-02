"use client";

import { useState, useTransition } from "react";
import type { ProjectUser, ProjectUserSyncSiteResult } from "@/lib/apiClient";
import { updateProjectUserRoleAction, removeProjectUserAction, syncProjectUserAction } from "./actions";

const WP_ROLES = ["administrator", "editor", "author", "contributor", "subscriber"];

/** issue #1242: メンバー個別のユーザー情報同期の結果を、成功/失敗の別なく一覧表示する。 */
function SyncResultList({ results }: { results: ProjectUserSyncSiteResult[] }) {
  if (results.length === 0) {
    return (
      <p className="mt-1 text-xs text-neutral-500 dark:text-neutral-400">
        紐づくWordPress環境がありません。
      </p>
    );
  }
  return (
    <ul className="mt-1 space-y-0.5 text-xs">
      {results.map((r) => (
        <li key={r.siteId} className={r.success ? "text-green-700 dark:text-green-400" : "text-red-600"}>
          {r.siteName}: {r.success ? "成功" : `失敗${r.errorMessage ? `(${r.errorMessage})` : ""}`}
        </li>
      ))}
    </ul>
  );
}

function MemberRow({ projectId, member }: { projectId: number; member: ProjectUser }) {
  const [isPending, startTransition] = useTransition();
  const [syncResults, setSyncResults] = useState<ProjectUserSyncSiteResult[] | null>(null);
  const [syncError, setSyncError] = useState<string | null>(null);
  const [roleError, setRoleError] = useState<string | null>(null);

  function handleRoleChange(newRole: string) {
    setRoleError(null);
    startTransition(async () => {
      const result = await updateProjectUserRoleAction(projectId, member.userId, newRole);
      if (result.error) {
        setRoleError(result.error);
      }
    });
  }

  function handleRemove() {
    if (!window.confirm("このユーザーをプロジェクトから削除しますか?(WordPress側のユーザーは残ります)")) {
      return;
    }
    startTransition(() => {
      removeProjectUserAction(projectId, member.userId);
    });
  }

  function handleSync() {
    setSyncError(null);
    startTransition(async () => {
      const result = await syncProjectUserAction(projectId, member.userId);
      if (result.error) {
        setSyncError(result.error);
        setSyncResults(null);
      } else {
        setSyncResults(result.results ?? []);
      }
    });
  }

  return (
    <tr className="border-b border-neutral-100 dark:border-neutral-800 last:border-0 cursor-pointer hover:bg-neutral-50 dark:hover:bg-neutral-800 hover:shadow-sm transition-colors">
      <td className="px-4 py-2">{member.email}</td>
      <td className="px-4 py-2 text-neutral-600 dark:text-neutral-400">{member.displayName}</td>
      <td className="px-4 py-2">
        <select
          value={member.wpRole}
          disabled={isPending}
          onChange={(e) => handleRoleChange(e.target.value)}
          className="rounded border border-neutral-300 dark:border-neutral-700 px-2 py-1 text-sm"
        >
          {WP_ROLES.map((role) => (
            <option key={role} value={role}>
              {role}
            </option>
          ))}
        </select>
        {roleError && (
          <p role="alert" className="mt-1 text-xs text-red-600">
            {roleError}
          </p>
        )}
      </td>
      <td className="px-4 py-2 text-right">
        <div className="flex items-center justify-end gap-3">
          <button
            type="button"
            onClick={handleSync}
            disabled={isPending}
            className="text-sm text-blue-600 hover:underline disabled:text-neutral-400"
          >
            {isPending ? "処理中…" : "ユーザー情報を同期"}
          </button>
          <button
            type="button"
            onClick={handleRemove}
            disabled={isPending}
            className="text-sm text-red-600 hover:underline disabled:text-neutral-400"
          >
            {isPending ? "処理中…" : "削除"}
          </button>
        </div>
        {syncError && <p className="mt-1 text-xs text-red-600">{syncError}</p>}
        {syncResults && <SyncResultList results={syncResults} />}
      </td>
    </tr>
  );
}

export function ProjectUserManager({ projectId, members }: { projectId: number; members: ProjectUser[] }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 text-neutral-500 dark:text-neutral-400">
          <tr>
            <th className="px-4 py-2">メールアドレス</th>
            <th className="px-4 py-2">表示名</th>
            <th className="px-4 py-2">ロール</th>
            <th className="px-4 py-2"></th>
          </tr>
        </thead>
        <tbody>
          {members.length === 0 && (
            <tr>
              <td colSpan={4} className="px-4 py-6 text-center text-neutral-600 dark:text-neutral-400">
                参加ユーザーはいません。下の「ユーザーを追加」から追加してください。
                サイトを紐付けていない場合は、先に概要タブの環境設定でサイトを紐付けてください。
              </td>
            </tr>
          )}
          {members.map((member) => (
            <MemberRow key={member.userId} projectId={projectId} member={member} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

export { WP_ROLES };
