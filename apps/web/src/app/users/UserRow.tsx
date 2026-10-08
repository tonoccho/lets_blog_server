"use client";

import { useState, type ReactNode } from "react";
import Link from "next/link";
import { DeleteUserButton } from "./DeleteUserButton";

/**
 * `/users` の1行。削除成功時はクライアント状態で自行を描画しなくなる(issue #1383)。
 * 登録日セル(サーバ側で解決する ViewerDateTime)は children として受け取る。
 */
export function UserRow({
  id,
  email,
  role,
  projectsText,
  canDelete,
  children,
}: {
  id: number;
  email: string;
  role: string;
  projectsText: string;
  canDelete: boolean;
  children: ReactNode;
}) {
  const [deleted, setDeleted] = useState(false);
  if (deleted) {
    return null;
  }
  return (
    <tr className="border-b border-neutral-100 dark:border-neutral-800 last:border-0 cursor-pointer hover:bg-neutral-50 dark:hover:bg-neutral-800 hover:shadow-sm transition-colors">
      <td className="px-4 py-2 text-neutral-500 dark:text-neutral-400">{projectsText}</td>
      <td className="px-4 py-2">{email}</td>
      <td className="px-4 py-2 font-mono">{role}</td>
      <td className="px-4 py-2 text-neutral-500 dark:text-neutral-400">{children}</td>
      <td className="px-4 py-2 text-right">
        <div className="flex justify-end gap-3">
          <Link href={`/users/${id}/edit`} className="text-sm text-neutral-600 dark:text-neutral-400 hover:underline">
            編集
          </Link>
          {canDelete && <DeleteUserButton id={id} onDeleted={() => setDeleted(true)} />}
        </div>
      </td>
    </tr>
  );
}
