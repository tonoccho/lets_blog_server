"use client";

import { useTransition } from "react";
import type { ProjectUser } from "@/lib/apiClient";
import { updateProjectUserRoleAction, removeProjectUserAction } from "./actions";

const WP_ROLES = ["administrator", "editor", "author", "contributor", "subscriber"];

function MemberRow({ projectId, member }: { projectId: number; member: ProjectUser }) {
  const [isPending, startTransition] = useTransition();

  function handleRoleChange(newRole: string) {
    startTransition(() => {
      updateProjectUserRoleAction(projectId, member.userId, newRole);
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

  return (
    <tr className="border-b border-neutral-100 last:border-0 cursor-pointer hover:bg-neutral-50 hover:shadow-sm transition-colors">
      <td className="px-4 py-2">{member.email}</td>
      <td className="px-4 py-2 text-neutral-600">{member.displayName}</td>
      <td className="px-4 py-2">
        <select
          value={member.wpRole}
          disabled={isPending}
          onChange={(e) => handleRoleChange(e.target.value)}
          className="rounded border border-neutral-300 px-2 py-1 text-sm"
        >
          {WP_ROLES.map((role) => (
            <option key={role} value={role}>
              {role}
            </option>
          ))}
        </select>
      </td>
      <td className="px-4 py-2 text-right">
        <button
          type="button"
          onClick={handleRemove}
          disabled={isPending}
          className="text-sm text-red-600 hover:underline disabled:text-neutral-400"
        >
          {isPending ? "処理中…" : "削除"}
        </button>
      </td>
    </tr>
  );
}

export function ProjectUserManager({ projectId, members }: { projectId: number; members: ProjectUser[] }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-neutral-200 bg-neutral-50 text-neutral-500">
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
              <td colSpan={4} className="px-4 py-6 text-center text-neutral-600">
                参加ユーザーはいません
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
