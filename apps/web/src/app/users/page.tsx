import { listUsers, listProjects, listAllProjectUsers } from "@/lib/apiClient";
import { requireAdminSession, getViewerTimeZone, getViewerProfile } from "@/lib/session";
import { loadOrReport, failedLabels } from "@/lib/loadOrReport";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";
import { ViewerDateTime } from "@/components/ViewerDateTime";
import { UserForm } from "./UserForm";
import { UserRow } from "./UserRow";

export default async function UsersPage() {
  await requireAdminSession();
  // viewerはログイン中ユーザー自身のローカルプロフィール(issue #784)。session.user.idは
  // Keycloakのsub(UUID)なので、自分の行かどうかの判定にはこちらの数値idを使う。
  const [usersResult, projectsResult, projectUsersResult, timezone, viewer] = await Promise.all([
    loadOrReport("users", "ユーザー一覧", listUsers(), []),
    loadOrReport("users", "プロジェクト一覧", listProjects(), []),
    loadOrReport("users", "プロジェクトメンバー一覧", listAllProjectUsers(), []),
    getViewerTimeZone(),
    getViewerProfile(),
  ]);
  const users = usersResult.data;
  const projects = projectsResult.data;
  const projectUsers = projectUsersResult.data;

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

      <FetchErrorNotice labels={failedLabels(usersResult, projectsResult, projectUsersResult)} />

      {!usersResult.failed && (
        <>
      <div className="text-sm text-neutral-600 dark:text-neutral-400">
        全{users.length}件を表示
      </div>

      <div className="overflow-x-auto rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 text-neutral-500 dark:text-neutral-400">
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
                    <p className="text-neutral-600 dark:text-neutral-400">登録済みユーザーはありません</p>
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
                <UserRow
                  key={user.id}
                  id={user.id}
                  email={user.email}
                  role={user.role}
                  projectsText={joinedProjects.length > 0 ? joinedProjects.join(", ") : "-"}
                  canDelete={viewer != null && viewer.id !== user.id}
                >
                  <ViewerDateTime iso={user.createdAt} personalTimeZone={timezone} />
                </UserRow>
              );
            })}
          </tbody>
        </table>
      </div>
        </>
      )}

      <div id="user-form">
        <UserForm />
      </div>
    </div>
  );
}
