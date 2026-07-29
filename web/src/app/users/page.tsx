import Link from "next/link";
import { listUsers } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { UserForm } from "./UserForm";
import { DeleteUserButton } from "./DeleteUserButton";

export default async function UsersPage() {
  const session = await requireAdminSession();
  const users = await listUsers().catch(() => []);

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">ユーザー管理</h1>

      <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 bg-neutral-50 text-neutral-500">
            <tr>
              <th className="px-4 py-2">メールアドレス</th>
              <th className="px-4 py-2">権限</th>
              <th className="px-4 py-2">登録日</th>
              <th className="px-4 py-2"></th>
            </tr>
          </thead>
          <tbody>
            {users.length === 0 && (
              <tr>
                <td colSpan={4} className="px-4 py-6 text-center text-neutral-600">
                  登録済みユーザーはありません
                </td>
              </tr>
            )}
            {users.map((user) => (
              <tr key={user.id} className="border-b border-neutral-100 last:border-0">
                <td className="px-4 py-2">{user.email}</td>
                <td className="px-4 py-2 font-mono">{user.role}</td>
                <td className="px-4 py-2 text-neutral-500">{new Date(user.createdAt).toLocaleString("ja-JP")}</td>
                <td className="px-4 py-2 text-right">
                  <div className="flex justify-end gap-3">
                    <Link href={`/users/${user.id}/edit`} className="text-sm text-neutral-600 hover:underline">
                      編集
                    </Link>
                    {String(user.id) !== session.user.id && <DeleteUserButton id={user.id} />}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <UserForm />
    </div>
  );
}
