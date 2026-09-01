import Link from "next/link";

export type ProjectSection = "dashboard" | "detail" | "plan" | "tags" | "posts";

const SECTIONS: { id: ProjectSection; label: string; hrefSuffix: string }[] = [
  { id: "dashboard", label: "ダッシュボード", hrefSuffix: "/dashboard" },
  { id: "detail", label: "詳細", hrefSuffix: "" },
  { id: "plan", label: "計画", hrefSuffix: "/plan" },
  { id: "tags", label: "タグ", hrefSuffix: "/tags" },
  { id: "posts", label: "投稿履歴", hrefSuffix: "/posts" },
];

/** プロジェクトの詳細・計画・タグページ間を行き来するための共通ナビゲーション(issue #182)。 */
export function ProjectSectionNav({ projectId, active }: { projectId: number; active: ProjectSection }) {
  return (
    <nav aria-label="プロジェクトセクション" className="flex gap-2 border-b border-neutral-200 dark:border-neutral-800">
      {SECTIONS.map((section) => {
        const isActive = section.id === active;
        return (
          <Link
            key={section.id}
            href={`/projects/${projectId}${section.hrefSuffix}`}
            aria-current={isActive ? "page" : undefined}
            className={`px-4 py-3 text-sm font-medium whitespace-nowrap border-b-2 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500 ${
              isActive
                ? "border-neutral-900 dark:border-neutral-50 text-neutral-900 dark:text-neutral-50"
                : "border-transparent text-neutral-600 dark:text-neutral-400 hover:text-neutral-900 dark:hover:text-neutral-50"
            }`}
          >
            {section.label}
          </Link>
        );
      })}
    </nav>
  );
}
