"use client";

import { useState, useTransition } from "react";
import { assignRoleAction, removeRoleAction } from "./actions";

interface UserRow {
  id: number;
  email: string;
  roleNames: string[];
}

interface RoleOption {
  roleName: string;
  displayName: string;
}

export function RoleAssignmentPanel({ users, roles }: { users: UserRow[]; roles: RoleOption[] }) {
  return (
    <div className="overflow-x-auto rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
      <table className="w-full text-sm">
        <thead className="bg-neutral-50 dark:bg-neutral-800 text-neutral-500 dark:text-neutral-400">
          <tr>
            <th className="px-4 py-2 text-left font-medium">ユーザー</th>
            <th className="px-4 py-2 text-left font-medium">割り当て済みロール</th>
            <th className="px-4 py-2 text-left font-medium">ロールを追加</th>
          </tr>
        </thead>
        <tbody>
          {users.length === 0 && (
            <tr>
              <td colSpan={3} className="px-4 py-6 text-center text-neutral-500 dark:text-neutral-400">
                ユーザーがいません。
              </td>
            </tr>
          )}
          {users.map((user) => (
            <UserRoleRow key={user.id} user={user} roles={roles} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function UserRoleRow({ user, roles }: { user: UserRow; roles: RoleOption[] }) {
  const [selected, setSelected] = useState(roles[0]?.roleName ?? "");
  const [isPending, startTransition] = useTransition();
  const [error, setError] = useState<string | null>(null);

  function handleAssign() {
    if (!selected) return;
    setError(null);
    startTransition(async () => {
      try {
        await assignRoleAction(user.id, selected);
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err));
      }
    });
  }

  function handleRemove(roleName: string) {
    setError(null);
    startTransition(async () => {
      try {
        await removeRoleAction(user.id, roleName);
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err));
      }
    });
  }

  return (
    <tr className="border-b border-neutral-100 dark:border-neutral-800 align-top last:border-0 cursor-pointer hover:bg-neutral-50 dark:hover:bg-neutral-800 hover:shadow-sm transition-colors">
      <td className="px-4 py-2">{user.email}</td>
      <td className="px-4 py-2">
        <div className="flex flex-wrap gap-1">
          {user.roleNames.length === 0 && <span className="text-neutral-400">なし</span>}
          {user.roleNames.map((roleName) => (
            <span
              key={roleName}
              className="inline-flex items-center gap-1 rounded bg-neutral-100 dark:bg-neutral-800 px-2 py-0.5 text-xs text-neutral-700 dark:text-neutral-300"
            >
              {roleName}
              <button
                type="button"
                onClick={() => handleRemove(roleName)}
                disabled={isPending}
                className="text-red-600 hover:underline disabled:text-neutral-400"
                aria-label={`${roleName}を解除`}
              >
                ×
              </button>
            </span>
          ))}
        </div>
      </td>
      <td className="px-4 py-2">
        <div className="flex items-center gap-2">
          <select
            value={selected}
            onChange={(e) => setSelected(e.target.value)}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-2 py-1 text-sm"
          >
            {roles.map((role) => (
              <option key={role.roleName} value={role.roleName}>
                {role.displayName}
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={handleAssign}
            disabled={isPending}
            className="rounded bg-neutral-900 px-3 py-1 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {isPending ? "処理中…" : "割り当て"}
          </button>
        </div>
        {error && <p className="mt-1 text-xs text-red-600">{error}</p>}
      </td>
    </tr>
  );
}
