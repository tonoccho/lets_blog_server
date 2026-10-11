import { Given, Then, When } from './fixtures';
import { E2E_TEST_EMAIL, expect } from '../support';

/**
 * Keycloak のパスワードリセットメールが `mailcatch` に届くことの検証(issue #1748)。
 *
 * メールキャッチャーの HTTP API(`GET /messages`)はホストの 1080 番に公開されている。
 * 別の場所へ向けたいときは `MAILCATCH_URL` で上書きする。
 */
const MAILCATCH_URL = process.env.MAILCATCH_URL ?? 'http://localhost:1080';

type CaughtMessage = { id: number; recipients: string[]; subject: string };

async function listMessages(): Promise<CaughtMessage[]> {
  const res = await fetch(`${MAILCATCH_URL}/messages`);
  if (!res.ok) {
    throw new Error(`メールキャッチャーの /messages が ${res.status} を返した`);
  }
  return (await res.json()) as CaughtMessage[];
}

Given('メールキャッチャーの受信メールを確認できる', async ({ ctx }) => {
  ctx.knownMessageIds = (await listMessages()).map((m) => m.id);
});

When('ログイン画面からE2E一般ユーザーのパスワードリセットを要求する', async ({ page }) => {
  await page.goto('/login', { waitUntil: 'commit' });
  await page.locator('a[href*="reset-credentials"]').click({ timeout: 30000 });
  await page.locator('#username').fill(E2E_TEST_EMAIL);
  await page.locator('input[type="submit"], button[type="submit"]').first().click();
});

Then('リセットメールがメールキャッチャーに届く', async ({ ctx }) => {
  const known = new Set(ctx.knownMessageIds as number[]);
  await expect
    .poll(
      async () =>
        (await listMessages()).some(
          (m) =>
            !known.has(m.id) &&
            m.recipients.some((r) => r.includes(E2E_TEST_EMAIL)),
        ),
      { timeout: 30000 },
    )
    .toBe(true);
});
