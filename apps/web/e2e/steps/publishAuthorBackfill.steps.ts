import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { createFixtureProject, expect } from '../support';
import {
  adminHeaders,
  adminToken,
  ensureManagedSite,
  findWpUserByEmail,
  provisionLoginableKeycloakCredential,
  uniqueSuffix,
  wpCli,
  wpSlug,
} from './publishAuthor.steps';

/**
 * サイトを後から紐付けたときの既存メンバーのWordPressユーザー補填(issue #1324)のステップ定義。
 *
 * 補填はドメインイベント経由で非同期に行われるため、WordPress側の確認は繰り返し待つ
 * (`expect.poll`)。共有するヘルパーと後片付け(プロジェクト・利用者の削除)は
 * `publishAuthor.steps.ts`のものを使う(ctxの`author*`キーを同じ意味で使うため、
 * その`After`が走る)。このファイルの`After`は、2つ目のサイトに作られたWordPressユーザーだけを消す。
 */

const FIRST_SITE_KEY = 'at66authorprobe';
const SECOND_SITE_KEY = 'at1324authorprobe2';
const BACKFILL_TIMEOUT_MS = 90_000;
const SETTLE_MS = 15_000;

async function bind(
  request: APIRequestContext,
  projectId: number,
  environment: string,
  siteId: number
): Promise<void> {
  const headers = await adminHeaders(request);
  const bound = await request.post(`/api/projects/${projectId}/environments`, {
    headers,
    data: { environment, siteId },
  });
  expect(
    bound.ok(),
    `${environment}環境へのサイト紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`
  ).toBe(true);
}

Given('補填検証用の2つのWordPressサイトがある', async ({ ctx, request }) => {
  const first = await ensureManagedSite(request, FIRST_SITE_KEY);
  const second = await ensureManagedSite(request, SECOND_SITE_KEY);
  ctx.authorSiteSlug = wpSlug(first.siteKey);
  ctx.backfillFirstSiteId = first.id;
  ctx.backfillSecondSiteId = second.id;
  ctx.backfillSecondSiteSlug = wpSlug(second.siteKey);
});

Given('サイトが紐付いていないプロジェクトに、メンバーが追加されている', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const project = await createFixtureProject(request, token, 'at1324-backfill');
  ctx.authorProjectId = project.id;

  const suffix = uniqueSuffix();
  const email = `e2e-1324-member-${suffix}@example.com`;
  const password = `E2e1324Member!${suffix}`;
  const headers = await adminHeaders(request);
  const created = await request.post('/api/users', { headers, data: { email, password, role: 'user' } });
  expect(
    created.ok(),
    `検証用利用者の登録に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const userId = ((await created.json()) as { id: number }).id;
  provisionLoginableKeycloakCredential(email, password);

  const added = await request.post(`/api/projects/${project.id}/users`, {
    headers,
    data: { userId, wpRole: 'author' },
  });
  expect(
    added.ok(),
    `プロジェクトへの参加に失敗しました (status=${added.status()}): ${await added.text()}`
  ).toBe(true);

  ctx.authorMemberId = userId;
  ctx.authorMemberEmail = email;
});

When('そのプロジェクトのテスト環境へ1つ目のサイトを紐付ける', async ({ ctx, request }) => {
  await bind(request, ctx.authorProjectId as number, 'test', ctx.backfillFirstSiteId as number);
});

When('そのプロジェクトの本番環境へ2つ目のサイトを紐付ける', async ({ ctx, request }) => {
  await bind(request, ctx.authorProjectId as number, 'production', ctx.backfillSecondSiteId as number);
});

When('そのプロジェクトのローカル環境へ同じ1つ目のサイトを紐付ける', async ({ ctx, request }) => {
  await bind(request, ctx.authorProjectId as number, 'local', ctx.backfillFirstSiteId as number);
});

When('1つ目のサイトに、そのメンバーのWordPressユーザーが作られるのを待つ', async ({ ctx }) => {
  const slug = ctx.authorSiteSlug as string;
  const email = ctx.authorMemberEmail as string;
  await expect
    .poll(() => findWpUserByEmail(slug, email).length, { timeout: BACKFILL_TIMEOUT_MS, intervals: [2_000] })
    .toBe(1);
});

Then(
  '1つ目のサイトに、そのメンバーのメールアドレスに対応するWordPressユーザーが1人作られる',
  async ({ ctx }) => {
    const slug = ctx.authorSiteSlug as string;
    const email = ctx.authorMemberEmail as string;
    await expect
      .poll(() => findWpUserByEmail(slug, email).length, {
        message: `1つ目のサイトに ${email} のWordPressユーザーが作られません`,
        timeout: BACKFILL_TIMEOUT_MS,
        intervals: [2_000],
      })
      .toBe(1);
  }
);

Then(
  '2つ目のサイトに、そのメンバーのメールアドレスに対応するWordPressユーザーが1人作られる',
  async ({ ctx }) => {
    const slug = ctx.backfillSecondSiteSlug as string;
    const email = ctx.authorMemberEmail as string;
    await expect
      .poll(() => findWpUserByEmail(slug, email).length, {
        message: `2つ目のサイトに ${email} のWordPressユーザーが作られません`,
        timeout: BACKFILL_TIMEOUT_MS,
        intervals: [2_000],
      })
      .toBe(1);
  }
);

Then('1つ目のサイトには、そのメンバーのWordPressユーザーが1人のまま変わらない', async ({ ctx }) => {
  const slug = ctx.authorSiteSlug as string;
  const email = ctx.authorMemberEmail as string;
  // 再紐付けで発行されたイベントが処理される時間を置いてから、重複が無いことを確かめる。
  await new Promise((resolve) => setTimeout(resolve, SETTLE_MS));
  expect(findWpUserByEmail(slug, email), '重複してWordPressユーザーが作られました').toHaveLength(1);
});

After({ tags: '@author-backfill' }, async ({ ctx }) => {
  const slug = ctx.backfillSecondSiteSlug as string | undefined;
  const email = ctx.authorMemberEmail as string | undefined;
  if (slug && email) {
    try {
      for (const wpUser of findWpUserByEmail(slug, email)) {
        wpCli(slug, `user delete ${wpUser.ID} --yes`);
      }
    } catch {
      // 後片付けの失敗としては扱わない(publishAuthor.steps.tsと同じ方針)。
    }
  }
});
