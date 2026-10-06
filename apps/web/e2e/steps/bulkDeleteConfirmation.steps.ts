import { execFileSync } from "node:child_process";
import path from "node:path";
import type { APIRequestContext, Page } from "@playwright/test";
import { After, Given, Then, When } from "./fixtures";
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  expect,
  fetchAccessToken,
  loginAsAdmin,
} from "../support";

/**
 * 一括削除の確認に、削除される環境の数と環境名が示されること
 * (issue #1181 / AT-7-5、AC-BULK-014、親 #933 のシナリオ11)のステップ定義。
 *
 * 兄弟 issue(#1177 / #1178 / #1182)のステップ定義ファイルには相乗りしない。
 * サイトの用意と wp-cli の呼び出しは必要な分だけをここへ持つ。
 * 確認ダイアログ(window.confirm)は文言を読んで**承認せずに閉じる**ので、どの環境も削除されない。
 */

const REPO_ROOT = path.resolve(__dirname, "..", "..", "..", "..");

/** #1177・#1178・#1182 と同じ固定キーの比較用プロジェクトとサイト。冪等に再利用する。 */
const PROJECT_SLUG = "e2e-at7-cmp";
const MASTER_SITE_KEY = "at7cmpmaster";
const TARGET_SITE_KEY = "at7cmptarget";
const PROVISION_TIMEOUT_MS = 600_000;

type Cleanup = () => void;
type Resource = "category" | "tag" | "plugin" | "post";

const TAB_LABEL: Record<Resource, string> = {
  category: "カテゴリ",
  tag: "タグ",
  plugin: "プラグイン",
  post: "ポスト/ページ",
};

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
  return ctx.bdcCleanups as Cleanup[];
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
): Promise<number> {
  const headers = await adminHeaders(request);
  const list = await request.get("/api/sites", { headers });
  expect(
    list.ok(),
    `サイト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`,
  ).toBe(true);
  const existing = (
    (await list.json()) as { id: number; siteKey: string }[]
  ).find((site) => site.siteKey === siteKey);
  if (existing) {
    return existing.id;
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
  return ((await created.json()) as { id: number }).id;
}

async function bindEnvironment(
  request: APIRequestContext,
  projectId: number,
  environment: "local" | "test",
  siteId: number,
): Promise<void> {
  const response = await request.post(
    `/api/projects/${projectId}/environments`,
    { headers: await adminHeaders(request), data: { environment, siteId } },
  );
  expect(
    response.ok(),
    `${environment}環境へのサイト紐付けに失敗しました (status=${response.status()}): ${await response.text()}`,
  ).toBe(true);
}

// ---- 前提: 各環境へ項目を用意する ----

function createCategory(
  ctx: Record<string, unknown>,
  site: string,
  slug: string,
) {
  const id = wpCli(
    site,
    `term create category 'Cat ${slug}' --slug='${slug}' --porcelain`,
  );
  cleanups(ctx).push(() => wpCli(site, `term delete category ${id}`));
}

function createTag(ctx: Record<string, unknown>, site: string, slug: string) {
  const id = wpCli(
    site,
    `term create post_tag 'Tag ${slug}' --slug='${slug}' --porcelain`,
  );
  cleanups(ctx).push(() => wpCli(site, `term delete post_tag ${id}`));
}

function createPlugin(
  ctx: Record<string, unknown>,
  site: string,
  slug: string,
) {
  const dir = `$(wp --allow-root plugin path)/${slug}`;
  wpShell(
    site,
    `mkdir -p ${dir} && printf '<?php\\n/*\\nPlugin Name: ${slug}\\n*/\\n' > ${dir}/${slug}.php`,
  );
  cleanups(ctx).push(() => wpShell(site, `rm -rf ${dir}`));
}

function createPost(ctx: Record<string, unknown>, site: string, slug: string) {
  const id = wpCli(
    site,
    `post create --post_type=post --post_title='Post ${slug}' --post_name='${slug}' --post_status=publish --porcelain`,
  );
  cleanups(ctx).push(() => wpCli(site, `post delete ${id} --force`));
}

function categoryExists(site: string, slug: string): boolean {
  return (
    wpCli(site, `term list category --slug='${slug}' --field=slug`) === slug
  );
}

function prepare(
  ctx: Record<string, unknown>,
  resource: Resource,
  create: (ctx: Record<string, unknown>, site: string, slug: string) => void,
  sites: string[],
): void {
  const slug = unique(`at7bdc-${resource}`);
  for (const site of sites) {
    create(ctx, site, slug);
  }
  ctx.bdcResource = resource;
  ctx.bdcSlug = slug;
}

Given(
  "testとlocalに2つのWordPressサイトを持つ削除確認用プロジェクトがある",
  async ({ ctx, request }) => {
    const projectId = await ensureProject(request);
    const master = await ensureManagedSite(request, MASTER_SITE_KEY);
    const target = await ensureManagedSite(request, TARGET_SITE_KEY);
    await bindEnvironment(request, projectId, "test", master);
    await bindEnvironment(request, projectId, "local", target);
    ctx.bdcProjectId = projectId;
    ctx.bdcMasterSite = wpSlug(MASTER_SITE_KEY);
    ctx.bdcTargetSite = wpSlug(TARGET_SITE_KEY);
    ctx.bdcCleanups = [] as Cleanup[];
  },
);

function bothSites(ctx: Record<string, unknown>): string[] {
  return [ctx.bdcTargetSite as string, ctx.bdcMasterSite as string];
}

Given("両環境に存在するカテゴリがある", async ({ ctx }) => {
  prepare(ctx, "category", createCategory, bothSites(ctx));
});

Given("両環境に存在するタグがある", async ({ ctx }) => {
  prepare(ctx, "tag", createTag, bothSites(ctx));
});

Given("両環境にインストールされているプラグインがある", async ({ ctx }) => {
  prepare(ctx, "plugin", createPlugin, bothSites(ctx));
});

Given("両環境に存在する投稿がある", async ({ ctx }) => {
  prepare(ctx, "post", createPost, bothSites(ctx));
});

Given("テスト環境にだけ存在するカテゴリがある", async ({ ctx }) => {
  prepare(ctx, "category", createCategory, [ctx.bdcMasterSite as string]);
  expect(
    categoryExists(ctx.bdcTargetSite as string, ctx.bdcSlug as string),
    "ローカル環境には存在しないはずのカテゴリが存在します",
  ).toBe(false);
});

// ---- もし: 画面で削除を押し、確認を承認せずに閉じる ----

/** 一覧を「次へ」で辿り、slug を含む行を見つける。一覧の読み込み完了(ページ送りの表示)を待ってから探す。 */
async function findRow(page: Page, slug: string, resource: Resource) {
  const next = page.getByRole("button", { name: "次へ" });
  await next.waitFor({ state: "visible", timeout: 120_000 });
  for (let i = 0; i < 50; i++) {
    // カテゴリ・タグは項目ごとに1つの表(操作ボタンは表の最後の行)、
    // プラグイン・投稿は1項目が表の1行である。
    const row = (
      resource === "category" || resource === "tag"
        ? page.locator("div.overflow-x-auto", { hasText: slug })
        : page.locator("tbody tr", { hasText: slug })
    ).first();
    if (await row.isVisible()) {
      return row;
    }
    if (await next.isDisabled()) {
      break;
    }
    await next.click();
  }
  throw new Error(`一覧に ${slug} の行が見つかりません`);
}

async function dismissDeleteConfirmation(
  page: Page,
  ctx: Record<string, unknown>,
): Promise<void> {
  const resource = ctx.bdcResource as Resource;
  await loginAsAdmin(page);
  await page.goto(`/projects/${ctx.bdcProjectId}`);
  await page.getByRole("button", { name: "メンテナンス", exact: true }).click();
  if (resource !== "category") {
    await page
      .getByRole("button", { name: TAB_LABEL[resource], exact: true })
      .click();
  }
  const row = await findRow(page, ctx.bdcSlug as string, resource);
  let message: string | null = null;
  page.once("dialog", async (dialog) => {
    message = dialog.message();
    await dialog.dismiss();
  });
  await row.getByRole("button", { name: "削除", exact: true }).click();
  await expect
    .poll(() => message, "確認ダイアログが出ませんでした")
    .not.toBeNull();
  ctx.bdcDialogMessage = message;
}

for (const label of ["カテゴリ", "タグ", "プラグイン", "投稿"]) {
  When(
    `管理者が一括管理画面でその${label}の削除を押し、確認を承認せずに閉じる`,
    async ({ page, ctx }) => dismissDeleteConfirmation(page, ctx),
  );
}

// ---- ならば ----

Then(
  "削除の確認には存在する2つの環境としてローカルとテストが示される",
  async ({ ctx }) => {
    expect(ctx.bdcDialogMessage as string).toContain(
      "存在する2つの環境(ローカル・テスト)",
    );
  },
);

Then(
  "削除の確認には存在する1つの環境としてテストだけが示される",
  async ({ ctx }) => {
    expect(ctx.bdcDialogMessage as string).toContain(
      "存在する1つの環境(テスト)",
    );
  },
);

Then("削除の確認にローカルは含まれない", async ({ ctx }) => {
  expect(ctx.bdcDialogMessage as string).not.toContain("ローカル");
});

Then("両環境のWordPressにそのカテゴリが残っている", async ({ ctx }) => {
  for (const site of bothSites(ctx)) {
    expect(
      categoryExists(site, ctx.bdcSlug as string),
      `${site} のカテゴリが消えています(確認は承認していないのに)`,
    ).toBe(true);
  }
});

After({ tags: "@bulk" }, async ({ ctx }) => {
  for (const cleanup of (
    (ctx.bdcCleanups as Cleanup[] | undefined) ?? []
  ).reverse()) {
    try {
      cleanup();
    } catch {
      // 既に無いものを消そうとした場合は、後片付けの目的(残さない)を満たしている。
    }
  }
});
