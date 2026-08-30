import { notFound } from "next/navigation";
import { getProject, listPosts } from "@/lib/apiClient";
import { requireAdminSession, getViewerTimeZone } from "@/lib/session";
import { Breadcrumb } from "@/components/Breadcrumb";
import { ProjectSectionNav } from "../ProjectSectionNav";
import { PostsTable } from "../../../posts/PostsTable";

export default async function ProjectPostsPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  await requireAdminSession();
  const projectId = Number(id);

  const [project, allPosts, timezone] = await Promise.all([
    getProject(projectId).catch(() => null),
    listPosts().catch(() => []),
    getViewerTimeZone(),
  ]);

  if (!project) {
    notFound();
  }

  const projectSiteIds = new Set(
    [project.localSite, project.testSite, project.productionSite]
      .filter((site): site is NonNullable<typeof site> => site !== null)
      .map((site) => site.id)
  );
  const posts = allPosts.filter((post) => projectSiteIds.has(post.siteId));

  return (
    <div className="space-y-8">
      <Breadcrumb
        items={[
          { label: "ダッシュボード", href: "/" },
          { label: "プロジェクト", href: "/projects" },
          { label: project.name, href: `/projects/${projectId}` },
          { label: "投稿履歴" },
        ]}
      />
      <div>
        <h1 className="text-xl font-semibold">{project.name} — 投稿履歴</h1>
      </div>

      <ProjectSectionNav projectId={projectId} active="posts" />

      <div className="text-sm text-neutral-600 dark:text-neutral-400">
        全{posts.length}件を表示
      </div>

      <PostsTable posts={posts} timezone={timezone} />
    </div>
  );
}
