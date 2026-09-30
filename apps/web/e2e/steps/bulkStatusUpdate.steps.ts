import { execFileSync } from "node:child_process";
import path from "node:path";
import type { APIRequestContext } from "@playwright/test";
import { After, Given, Then, When } from "./fixtures";
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
} from "../support";

/**
 * 投稿ステータスの一括更新と一括操作の認可(issue #1182 / AT-7-6、AC-BULK-010、
 * 親 #933 のシナリオ12・13)のステップ定義。
 *
 * 兄弟 issue(#1177 の `bulkComparison.steps.ts`、#1178 の `bulkComparisonResources.steps.ts`)と
 * 同じファイルに相乗りしない。あちらのヘルパーは非公開なので、サイトの用意と wp-cli の
 * 呼び出しは必要な分だけをここへ持つ。ステップ文言は兄弟と重複させない
 * (playwright-bdd のステップ登録は全ファイル共通)。
 */

const REPO_ROOT = path.resolve(__dirname, "..", "..", "..", "..");

/** #1177・#1178 と同じ固定キーの比較用プロジェクトとサイト。冪等に再利用する。 */
const PROJECT_SLUG = "e2e-at7-cmp";
const MASTER_SITE_KEY = "at7cmpmaster";
const TARGET_SITE_KEY = "at7cmptarget";

const PROVISION_TIMEOUT_MS = 600_000;

interface SiteFixture {
  id: number;
  siteKey: string;
}

interface OperationLog {
  status: string;
  environment: string;
  errorMessage: string | null;
}

/** 比較APIの行のうち、存在判定に使う共通の形(値の型はリソースごとに違う)。 */
interface ComparisonRow {
  slug: string;
  local: {
    error: boolean;
    slug?: string | null;
    postId?: string | null;
    status?: string | null;
  };
  test: {
    error: boolean;
    slug?: string | null;
    postId?: string | null;
    status?: string | null;
  };
}

type Cleanup = () => void;

type DeleteResource = "categories" | "tags" | "posts" | "plugins" | "themes";

const DELETE_RESOURCES: DeleteResource[] = [
  "categories",
  "tags",
  "posts",
  "plugins",
  "themes",
];

function wpSlug(siteKey: string): string {
  return siteKey.toLowerCase().replace(/[^a-z0-9-]/g, "-");
}

async function adminHeaders(
  request: APIRequestContext,
): Promise<Record<string, string>> {
  return {
    Authorization: `Bearer ${await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD)}`,
  };
}

async function generalUserHeaders(
  request: APIRequestContext,
): Promise<Record<string, string>> {
  return {
    Authorization: `Bearer ${await fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD)}`,
  };
}

function wpShell(siteSlug: string, script: string): string {
  return execFileSync(
    "docker",
    [
      "compose",
      "exec",
      "-T",
      "wordpress",
      "sh",
      "-c",
      `cd /var/www/html/sites/${siteSlug} && ${script}`,
    ],
    { cwd: REPO_ROOT, encoding: "utf8", timeout: 180_000 },
  ).trim();
}

function wpCli(siteSlug: string, command: string): string {
  return wpShell(siteSlug, `wp --allow-root ${command}`);
}

function unique(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

function cleanups(ctx: Record<string, unknown>): Cleanup[] {
  return ctx.bsuCleanups as Cleanup[];
}

async function ensureProject(request: APIRequestContext): Promise<number> {
  const headers = await adminHeaders(request);
  const list = await request.get("/api/projects", { headers });
  expect(
    list.ok(),
    `プロジェクト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`,
  ).toBe(true);
  const existing = ((await list.json()) as { id: number; slug: string }[]).find(
    (project) => project.slug === PROJECT_SLUG,
  );
  if (existing) {
    return existing.id;
  }
  const created = await request.post("/api/projects", {
    headers,
    data: { name: "E2E AT7 Category Comparison", slug: PROJECT_SLUG },
  });
  expect(
    created.ok(),
    `プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`,
  ).toBe(true);
  return ((await created.json()) as { id: number }).id;
}

async function ensureManagedSite(
  request: APIRequestContext,
  siteKey: string,
): Promise<SiteFixture> {
  const headers = await adminHeaders(request);
  const list = await request.get("/api/sites", { headers });
  expect(
    list.ok(),
    `サイト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`,
  ).toBe(true);
  const existing = ((await list.json()) as SiteFixture[]).find(
    (site) => site.siteKey === siteKey,
  );
  if (existing) {
    return existing;
  }
  const created = await request.post("/api/sites/managed-wordpress", {
    headers,
    data: {
      name: `AT7 comparison ${siteKey}`,
      siteKey,
      title: `AT7 ${siteKey}`,
      adminUser: `${siteKey}admin`.slice(0, 30),
      adminEmail: `${siteKey}@letsblog.local`,
      adminPassword: "At7Comparison#Passw0rd1",
    },
    timeout: PROVISION_TIMEOUT_MS,
  });
  expect(
    created.ok(),
    `マネージドWordPressの構築に失敗しました (status=${created.status()}): ${await created.text()}`,
  ).toBe(true);
  return (await created.json()) as SiteFixture;
}

async function bindEnvironment(
  request: APIRequestContext,
  projectId: number,
  environment: "local" | "test",
  siteId: number,
): Promise<void> {
  const response = await request.post(
    `/api/projects/${projectId}/environments`,
    {
      headers: await adminHeaders(request),
      data: { environment, siteId },
    },
  );
  expect(
    response.ok(),
    `${environment}環境へのサイト紐付けに失敗しました (status=${response.status()}): ${await response.text()}`,
  ).toBe(true);
}

// ---- WordPress の実状態(wp-cli) ----

function createPost(siteSlug: string, slug: string, status: string): string {
  return wpCli(
    siteSlug,
    `post create --post_type=post --post_title='Post ${slug}' --post_name='${slug}' --post_status=${status} --porcelain`,
  );
}

/** 投稿の公開状態。存在しなければ null(`--name` は下書きを引けないので全件から絞る)。 */
function readPostStatus(siteSlug: string, slug: string): string | null {
  const listing = wpCli(
    siteSlug,
    "post list --post_type=post --post_status=any --fields=post_name,post_status --format=csv",
  );
  const line = listing.split("\n").find((l) => l.startsWith(`${slug},`));
  return line ? line.slice(slug.length + 1) : null;
}

function pluginDir(slug: string): string {
  return `$(wp --allow-root plugin path)/${slug}`;
}

function themeDir(slug: string): string {
  return `$(wp --allow-root theme path)/${slug}`;
}

/** 5種類のリソースそれぞれについて、WordPress 上に実在するか。 */
function existsInWordPress(
  siteSlug: string,
  resource: DeleteResource,
  slug: string,
): boolean {
  switch (resource) {
    case "categories":
      return (
        wpCli(siteSlug, `term list category --slug='${slug}' --field=slug`) ===
        slug
      );
    case "tags":
      return (
        wpCli(siteSlug, `term list post_tag --slug='${slug}' --field=slug`) ===
        slug
      );
    case "posts":
      return readPostStatus(siteSlug, slug) !== null;
    case "plugins":
      return (
        wpCli(siteSlug, `plugin list --name='${slug}' --field=name`) === slug
      );
    case "themes":
      return (
        wpCli(siteSlug, `theme list --name='${slug}' --field=name`) === slug
      );
  }
}

function deletePath(resource: DeleteResource): string {
  return resource === "posts"
    ? "posts/delete-all?postType=post"
    : `${resource}/delete-all`;
}

/** 比較APIの全ページを辿って、指定スラッグの行を集める。 */
async function fetchRows(
  request: APIRequestContext,
  projectId: number,
  resource: DeleteResource,
  slug: string,
): Promise<ComparisonRow | undefined> {
  const headers = await adminHeaders(request);
  const query = resource === "posts" ? "postType=post&" : "";
  for (let page = 0; ; page++) {
    const response = await request.get(
      `/api/projects/${projectId}/bulk-management/${resource}/comparison?${query}page=${page}`,
      { headers, timeout: 120_000 },
    );
    expect(
      response.ok(),
      `${resource}の比較に失敗しました (status=${response.status()}): ${await response.text()}`,
    ).toBe(true);
    const body = (await response.json()) as {
      items: ComparisonRow[];
      totalCount: number;
    };
    const hit = body.items.find((item) => item.slug === slug);
    if (hit) {
      return hit;
    }
    if (
      body.items.length === 0 ||
      (page + 1) * body.items.length >= body.totalCount
    ) {
      return undefined;
    }
  }
}

function presentIn(
  value: ComparisonRow["local"],
  resource: DeleteResource,
): boolean {
  if (resource === "posts") {
    return value.postId != null;
  }
  if (resource === "plugins" || resource === "themes") {
    return value.status != null && value.status !== "NOT_INSTALLED";
  }
  return value.slug != null;
}

Given(
  "testとlocalに2つのWordPressサイトを持つ一括操作用プロジェクトがある",
  async ({ ctx, request }) => {
    const projectId = await ensureProject(request);
    const master = await ensureManagedSite(request, MASTER_SITE_KEY);
    const target = await ensureManagedSite(request, TARGET_SITE_KEY);
    await bindEnvironment(request, projectId, "test", master.id);
    await bindEnvironment(request, projectId, "local", target.id);
    ctx.bsuProjectId = projectId;
    ctx.bsuSites = [wpSlug(MASTER_SITE_KEY), wpSlug(TARGET_SITE_KEY)];
    ctx.bsuCleanups = [] as Cleanup[];
  },
);

// ---- シナリオ12: 投稿ステータスの一括変更 ----

Given(
  "両環境に公開状態の投稿が2件と、変更対象ではない公開状態の投稿が1件ある",
  async ({ ctx }) => {
    const sites = ctx.bsuSites as string[];
    const targets = [unique("at7bsu-a"), unique("at7bsu-b")];
    const untouched = unique("at7bsu-c");
    for (const site of sites) {
      for (const slug of [...targets, untouched]) {
        const id = createPost(site, slug, "publish");
        cleanups(ctx).push(() => wpCli(site, `post delete ${id} --force`));
      }
    }
    ctx.bsuTargets = targets;
    ctx.bsuUntouched = untouched;
  },
);

When(
  "管理者が変更対象の2件の投稿を下書きにするステータス一括変更を実行する",
  async ({ ctx, request }) => {
    const headers = await adminHeaders(request);
    const logs: OperationLog[] = [];
    for (const slug of ctx.bsuTargets as string[]) {
      const response = await request.post(
        `/api/projects/${ctx.bsuProjectId}/bulk-management/posts/status-update?postType=post`,
        { headers, data: { slug, status: "draft" }, timeout: 120_000 },
      );
      expect(
        response.ok(),
        `ステータス一括変更に失敗しました (status=${response.status()}): ${await response.text()}`,
      ).toBe(true);
      logs.push(...((await response.json()) as OperationLog[]));
    }
    ctx.bsuLogs = logs;
  },
);

Then(
  "一括変更の結果は変更対象の全投稿・全環境で成功している",
  async ({ ctx }) => {
    const logs = ctx.bsuLogs as OperationLog[];
    // 変更対象2件 × 2環境(local / test)
    expect(logs, "変更対象の全投稿・全環境の結果が返っていません").toHaveLength(
      4,
    );
    for (const log of logs) {
      expect(
        log.status,
        `${log.environment} の変更が成功していません: ${log.errorMessage}`,
      ).toBe("SUCCESS");
    }
    expect(new Set(logs.map((log) => log.environment))).toEqual(
      new Set(["local", "test"]),
    );
  },
);

Then(
  "変更対象の2件の投稿は両環境のWordPressで下書きになっている",
  async ({ ctx }) => {
    for (const site of ctx.bsuSites as string[]) {
      for (const slug of ctx.bsuTargets as string[]) {
        expect(
          readPostStatus(site, slug),
          `${site} の ${slug} が下書きになっていません`,
        ).toBe("draft");
      }
    }
  },
);

Then(
  "変更対象ではない投稿は両環境のWordPressで公開のままである",
  async ({ ctx }) => {
    for (const site of ctx.bsuSites as string[]) {
      expect(
        readPostStatus(site, ctx.bsuUntouched as string),
        `${site} の対象外の投稿が変化しています`,
      ).toBe("publish");
    }
  },
);

// ---- シナリオ13: 一般ユーザーによる一括削除系の拒否 ----

Given(
  "両環境にカテゴリ・タグ・投稿・プラグイン・テーマが1件ずつある",
  async ({ ctx }) => {
    const slugs = {
      categories: unique("at7bsu-cat"),
      tags: unique("at7bsu-tag"),
      posts: unique("at7bsu-post"),
      plugins: unique("at7bsu-plg"),
      themes: unique("at7bsu-thm"),
    } as Record<DeleteResource, string>;
    for (const site of ctx.bsuSites as string[]) {
      const catId = wpCli(
        site,
        `term create category 'Cat ${slugs.categories}' --slug='${slugs.categories}' --porcelain`,
      );
      cleanups(ctx).push(() => wpCli(site, `term delete category ${catId}`));
      const tagId = wpCli(
        site,
        `term create post_tag 'Tag ${slugs.tags}' --slug='${slugs.tags}' --porcelain`,
      );
      cleanups(ctx).push(() => wpCli(site, `term delete post_tag ${tagId}`));
      const postId = createPost(site, slugs.posts, "publish");
      cleanups(ctx).push(() => wpCli(site, `post delete ${postId} --force`));
      wpShell(
        site,
        `mkdir -p ${pluginDir(slugs.plugins)} && printf '<?php\\n/*\\nPlugin Name: ${slugs.plugins}\\n*/\\n' > ${pluginDir(slugs.plugins)}/${slugs.plugins}.php`,
      );
      cleanups(ctx).push(() =>
        wpShell(site, `rm -rf ${pluginDir(slugs.plugins)}`),
      );
      wpShell(
        site,
        `mkdir -p ${themeDir(slugs.themes)} && printf '/*\\nTheme Name: ${slugs.themes}\\n*/\\n' > ${themeDir(slugs.themes)}/style.css && printf '<?php\\n' > ${themeDir(slugs.themes)}/index.php`,
      );
      cleanups(ctx).push(() =>
        wpShell(site, `rm -rf ${themeDir(slugs.themes)}`),
      );
    }
    ctx.bsuSlugs = slugs;
    // 前提が成り立っていること(用意したものが実在する)を、判定の前に確かめておく
    for (const site of ctx.bsuSites as string[]) {
      for (const resource of DELETE_RESOURCES) {
        expect(
          existsInWordPress(site, resource, slugs[resource]),
          `${site} に ${resource} を用意できていません`,
        ).toBe(true);
      }
    }
  },
);

When(
  "一般ユーザーが5種類の一括削除を実行しようとする",
  async ({ ctx, request }) => {
    const headers = await generalUserHeaders(request);
    const slugs = ctx.bsuSlugs as Record<DeleteResource, string>;
    const statuses: Record<string, number> = {};
    for (const resource of DELETE_RESOURCES) {
      const response = await request.post(
        `/api/projects/${ctx.bsuProjectId}/bulk-management/${deletePath(resource)}`,
        { headers, data: { slug: slugs[resource] } },
      );
      statuses[resource] = response.status();
    }
    ctx.bsuDeleteStatuses = statuses;
  },
);

Then("5種類の一括削除はいずれも403で拒否される", async ({ ctx }) => {
  const statuses = ctx.bsuDeleteStatuses as Record<string, number>;
  for (const resource of DELETE_RESOURCES) {
    expect(
      statuses[resource],
      `${resource}/delete-all が403で拒否されていません`,
    ).toBe(403);
  }
});

Then(
  "管理者トークンの比較結果で5種類の対象は両環境に残っている",
  async ({ ctx, request }) => {
    const slugs = ctx.bsuSlugs as Record<DeleteResource, string>;
    for (const resource of DELETE_RESOURCES) {
      const row = await fetchRows(
        request,
        ctx.bsuProjectId as number,
        resource,
        slugs[resource],
      );
      expect(
        row,
        `${resource} の比較結果から対象(${slugs[resource]})が消えています`,
      ).toBeTruthy();
      const found = row as ComparisonRow;
      expect(
        presentIn(found.local, resource),
        `${resource} が対象環境(local)から消えています`,
      ).toBe(true);
      expect(
        presentIn(found.test, resource),
        `${resource} がマスター環境(test)から消えています`,
      ).toBe(true);
    }
  },
);

Then(
  "両環境のWordPressの実状態でも5種類の対象は残っている",
  async ({ ctx }) => {
    const slugs = ctx.bsuSlugs as Record<DeleteResource, string>;
    for (const site of ctx.bsuSites as string[]) {
      for (const resource of DELETE_RESOURCES) {
        expect(
          existsInWordPress(site, resource, slugs[resource]),
          `${site} から ${resource} が消えています`,
        ).toBe(true);
      }
    }
  },
);

/** シナリオが作ったリソースを消す。サイトそのものは残す(構築に分単位かかるため)。 */
After({ tags: "@bulk" }, async ({ ctx }) => {
  for (const cleanup of (
    (ctx.bsuCleanups as Cleanup[] | undefined) ?? []
  ).reverse()) {
    try {
      cleanup();
    } catch {
      // 既に無いものを消そうとした場合は、後片付けの目的(残さない)を満たしている。
    }
  }
});
