import { listRoles, listUsers } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { loadOrReport, failedLabels } from "@/lib/loadOrReport";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";
import { RoleAssignmentPanel } from "./RoleAssignmentPanel";

export default async function AdminRolesPage() {
  await requireAdminSession();

  const [rolesResult, usersResult] = await Promise.all([
    loadOrReport("admin/roles", "ロール一覧", listRoles(), []),
    loadOrReport("admin/roles", "ユーザー一覧", listUsers(), []),
  ]);
  const roles = rolesResult.data;
  const users = usersResult.data;

  return (
    <div className="space-y-8">
      <div>
        <h1 className="mb-4 text-xl font-semibold">ロール管理</h1>
        <div className="mb-4">
          <FetchErrorNotice labels={failedLabels(rolesResult, usersResult)} />
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          {roles.map((role) => (
            <div key={role.roleName} className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4">
              <p className="font-medium">{role.displayName}</p>
              <p className="text-xs text-neutral-500 dark:text-neutral-400">{role.roleName}</p>
              {role.description && <p className="mt-2 text-sm text-neutral-600 dark:text-neutral-400">{role.description}</p>}
              <ul className="mt-3 flex flex-wrap gap-1">
                {role.permissions.map((permission) => (
                  <li key={permission} className="rounded bg-neutral-100 dark:bg-neutral-800 px-1.5 py-0.5 text-xs text-neutral-600 dark:text-neutral-400">
                    {permission}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      </div>

      <div>
        <h2 className="mb-4 text-lg font-semibold">ユーザーへのロール割り当て</h2>
        {!rolesResult.failed && !usersResult.failed && (
          <RoleAssignmentPanel
            users={users.map((user) => ({ id: user.id, email: user.email, roleNames: user.roleNames }))}
            roles={roles.map((role) => ({ roleName: role.roleName, displayName: role.displayName }))}
          />
        )}
      </div>
    </div>
  );
}
