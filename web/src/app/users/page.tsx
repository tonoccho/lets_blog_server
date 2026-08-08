import Link from "next/link";
import { listUsers, listProjects, listAllProjectUsers } from "@/lib/apiClient";
import { requireAdminSession, getViewerTimeZone } from "@/lib/session";
import { formatDateTime } from "@/lib/formatDate";
import { UserForm } from "./UserForm";
import { DeleteUserButton } from "./DeleteUserButton";

export default async function UsersPage() {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };
  const [users, projects, projectUsers, timezone] = await Promise.all([
    listUsers().catch(() => []),
    listProjects().catch(() => []),
    listAllProjectUsers(actor).catch(() => []),
    getViewerTimeZone(),
  ]);

  const userToProjects = new Map<number, string[]>();
  for (const pu of projectUsers) {
    const project = projects.find((p) => p.id === pu.projectId);
    if (!project) continue;
    if (!userToProjects.has(pu.userId)) userToProjects.set(pu.userId, []);
    userToProjects.get(pu.userId)!.push(project.name);
  }

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">ユーザー管理</h1>

      <div className="overflow-x-auto rounded-lg border border-neutral-200 bg-white">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 bg-neutral-50 text-neutral-500">
            <tr>
              <th className="px-4 py-2">参加プロジェクト</th>
              <th className="px-4 py-2">メールアドレス</th>
              <th className="px-4 py-2">権限</th>
              <th className="px-4 py-2">登録日</th>
              <th className="px-4 py-2"></th>
            </tr>
          </thead>
          <tbody>
            {users.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-8 text-center">
                  <div className="flex flex-col items-center gap-4">
                    <p className="text-neutral-600">登録済みユーザーはありません</p>
                    <a
                      href="#user-form"
                      className="rounded bg-neutral-900 px-4 py-2 text-sm text-white hover:bg-neutral-800"
                    >
                      ユーザーを招待する
                    </a>
                  </div>
                </td>
              </tr>
            )}
            {users.map((user) => {
              const joinedProjects = userToProjects.get(user.id) ?? [];
              return (
                <tr key={user.id} className="border-b border-neutral-100 last:border-0">
                  <td className="px-4 py-2 text-neutral-500">
                    {joinedProjects.length > 0 ? joinedProjects.join(", ") : "-"}
                  </td>
                  <td className="px-4 py-2">{user.email}</td>
                  <td className="px-4 py-2 font-mono">{user.role}</td>
                  <td className="px-4 py-2 text-neutral-500">{formatDateTime(user.createdAt, timezone)}</td>
                  <td className="px-4 py-2 text-right">
                    <div className="flex justify-end gap-3">
                      <Link href={`/users/${user.id}/edit`} className="text-sm text-neutral-600 hover:underline">
                        編集
                      </Link>
                      {String(user.id) !== session.user.id && <DeleteUserButton id={user.id} />}
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      <div id="user-form">
        <UserForm />
      </div>
    </div>
  );
}
