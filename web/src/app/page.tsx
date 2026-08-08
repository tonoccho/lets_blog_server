import Link from "next/link";
import { listSites, listPosts, listGenerationJobs } from "@/lib/apiClient";

export default async function DashboardPage() {
  const [sites, posts, jobs] = await Promise.all([
    listSites().catch(() => []),
    listPosts().catch(() => []),
    listGenerationJobs().catch(() => []),
  ]);

  const cards = [
    { label: "登録サイト数", value: sites.length, href: "/sites" },
    { label: "投稿数", value: posts.length, href: "/posts" },
    { label: "AIジョブ数", value: jobs.length, href: "/ai-jobs" },
  ];

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">ダッシュボード</h1>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        {cards.map((card) => (
          <Link
            key={card.href}
            href={card.href}
            className="rounded-lg border border-neutral-200 bg-white p-5 shadow-sm hover:shadow focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500 dark:border-neutral-800 dark:bg-neutral-900"
          >
            <div className="text-sm text-neutral-700 dark:text-neutral-300">{card.label}</div>
            <div className="mt-1 text-3xl font-semibold">{card.value}</div>
          </Link>
        ))}
      </div>
    </div>
  );
}
