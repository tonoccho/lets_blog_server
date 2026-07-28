import { listRoles, listUsers } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";
import { RoleAssignmentPanel } from "./RoleAssignmentPanel";

export default async function AdminRolesPage() {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const [roles, users] = await Promise.all([
    listRoles(actor).catch(() => []),
    listUsers().catch(() => []),
  ]);

  return (
    <div className="space-y-8">
      <div>
        <h1 className="mb-4 text-xl font-semibold">ロール管理</h1>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          {roles.map((role) => (
            <div key={role.roleName} className="rounded-lg border border-neutral-200 bg-white p-4">
              <p className="font-medium">{role.displayName}</p>
              <p className="text-xs text-neutral-500">{role.roleName}</p>
              {role.description && <p className="mt-2 text-sm text-neutral-600">{role.description}</p>}
              <ul className="mt-3 flex flex-wrap gap-1">
                {role.permissions.map((permission) => (
                  <li key={permission} className="rounded bg-neutral-100 px-1.5 py-0.5 text-xs text-neutral-600">
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
        <RoleAssignmentPanel
          users={users.map((user) => ({ id: user.id, email: user.email, roleNames: user.roleNames }))}
          roles={roles.map((role) => ({ roleName: role.roleName, displayName: role.displayName }))}
        />
      </div>
    </div>
  );
}
