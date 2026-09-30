import Link from "next/link";
import type { ProjectUser } from "@/lib/apiClient";
import { FetchErrorNotice } from "@/components/FetchErrorNotice";

function memberName(member: ProjectUser): string {
  return member.displayName ?? member.email ?? `ユーザー#${member.userId}`;
}

/**
 * ダッシュボードの「メンバー」ウィジェット(issue #1502)。閲覧専用。
 * ロール変更・削除・追加は詳細ページの「メンバー」タブ(ProjectUserManager)で行う。
 */
export function MembersWidget({
  projectId,
  members,
  fetchFailed = false,
}: {
  projectId: number;
  members: ProjectUser[];
  /** メンバー一覧の取得に失敗したとき true。「0人」と区別して失敗を示す。 */
  fetchFailed?: boolean;
}) {
  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5 space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="font-medium">メンバー</h2>
        <Link href={`/projects/${projectId}?tab=members`} className="text-sm underline">
          メンバーを管理
        </Link>
      </div>
      {fetchFailed ? (
        <FetchErrorNotice labels={["メンバー"]} />
      ) : members.length === 0 ? (
        <p className="text-sm text-neutral-600 dark:text-neutral-400">0人</p>
      ) : (
        <>
          <p className="text-sm text-neutral-600 dark:text-neutral-400">{members.length}人</p>
          <ul className="divide-y divide-neutral-200 dark:divide-neutral-800 text-sm">
            {members.map((member) => (
              <li key={member.userId} className="flex items-center justify-between py-2">
                <span>{memberName(member)}</span>
                <span className="text-neutral-600 dark:text-neutral-400">{member.wpRole}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
