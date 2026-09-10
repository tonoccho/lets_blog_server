import fs from 'node:fs';
import path from 'node:path';
import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * managed WordPress のメディアアップロード上限(issue #1243)のステップ定義。
 *
 * 兄弟の受け入れシナリオと同じ「ステップ定義ファイルは相乗りしない」方針
 * (siteAdoption.steps.ts の冒頭コメント)に倣い、必要なヘルパーはこのファイル内に閉じて持つ。
 */

/** リポジトリルート(apps/web/e2e/steps から4階層上)。 */
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

/** 生成した巨大フィクスチャの置き場。`.gitignore` 済みで、Playwright が実行開始時にクリアする。 */
const FIXTURE_DIR = path.join(REPO_ROOT, 'apps', 'web', 'test-results');

/**
 * 受入基準は「100MB を超えるファイル」。110MB にしているのは、
 * nginx の旧上限 100M を確実に超え、かつ 1GB の上限には余裕をもって収まるため。
 */
const LARGE_UPLOAD_BYTES = 110 * 1024 * 1024;

/** 期待する上限。WP は `min(upload_max_filesize, post_max_size)` を `size_format()` で表示する。 */
const EXPECTED_MAX_UPLOAD_LABEL = '1 GB';

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

/** issue #765: フィクスチャ名は並列実行時の衝突・孤児サイト対策でタイムスタンプ+乱数にする。 */
function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

interface ManagedWordPressFixture {
  siteId: number;
  adminUser: string;
  adminPassword: string;
  /** provision-agent 側の実体を指すディレクトリ名(`normalizeSlug(siteKey)` と同じ規則)。 */
  slug: string;
}

/**
 * `POST /api/sites/managed-wordpress` で ManagedWordPress サイトを構築する(API直叩き。
 * UIのフォームを経由しない。adminUser/adminPassword を自分で把握しておく必要があるため)。
 */
async function createManagedWordPress(
  request: APIRequestContext,
  headers: Record<string, string>
): Promise<ManagedWordPressFixture> {
  const unique = uniqueSuffix();
  // siteKey は英数字とハイフンのみ(normalizeSlug と同じ文字種)にしておく。
  const siteKey = `e2e-1243-upload-${unique}`;
  const name = `E2E 1243 upload ${unique}`;
  const adminUser = `e2e1243${unique}`.replace(/[^a-zA-Z0-9]/g, '').slice(0, 30);
  const adminPassword = 'E2eUpload#Passw0rd1';

  const response = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name,
      siteKey,
      title: name,
      adminUser,
      adminEmail: `e2e-1243-${unique}@letsblog.local`,
      adminPassword,
      locale: 'ja',
    },
  });
  expect(
    response.ok(),
    `ManagedWordPressの構築に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const body = (await response.json()) as { id: number };
  return { siteId: body.id, adminUser, adminPassword, slug: siteKey };
}

/** WordPress自身のログイン画面からCookie認証する(project-serviceの認証とは独立)。 */
async function loginToWordPressAdmin(
  page: Page,
  slug: string,
  adminUser: string,
  adminPassword: string
): Promise<void> {
  await page.goto(`/sites/${slug}/wp-login.php`);
  const user = page.locator('#user_login');
  const pass = page.locator('#user_pass');

  // wp-login.php は読み込み完了後に `#user_login` へ自動でフォーカスを移す。
  // その割り込みが2つの fill の間に入ると、2つ目の入力がユーザー名欄へ流れ込み、
  // 「ユーザー名欄にパスワードが入り、パスワード欄が空のまま送信される」形で落ちる(実測)。
  // 送信前に両欄の値を確認し、崩れていたら入れ直す。
  await expect(async () => {
    await user.fill(adminUser);
    await pass.fill(adminPassword);
    await expect(user).toHaveValue(adminUser);
    await expect(pass).toHaveValue(adminPassword);
  }).toPass({ timeout: 30000 });

  await page.locator('#wp-submit').click();
  await page.waitForURL(`**/sites/${slug}/wp-admin/**`, { timeout: 30000 });
}

/**
 * 110MB のテキストファイルを生成する。
 *
 * 巨大ファイルをリポジトリに置かないため、実行時に作る。テキストにしているのは、
 * WordPress が画像に対して行うサムネイル生成(GD/Imagick による再エンコード)を挟まず、
 * **転送経路の上限だけ**を検証対象にするため。受入基準が問うているのは経路のサイズ上限であり、
 * 画像処理の可否ではない。
 */
function createLargeUploadFixture(): string {
  fs.mkdirSync(FIXTURE_DIR, { recursive: true });
  const filePath = path.join(FIXTURE_DIR, `e2e-1243-large-${uniqueSuffix()}.txt`);
  const chunk = Buffer.alloc(1024 * 1024, 'a');
  const handle = fs.openSync(filePath, 'w');
  try {
    for (let written = 0; written < LARGE_UPLOAD_BYTES; written += chunk.length) {
      fs.writeSync(handle, chunk);
    }
  } finally {
    fs.closeSync(handle);
  }
  return filePath;
}

Given('ManagedWordPressサイトにWordPress管理者としてログインしている', async ({ ctx, page, request }) => {
  const headers = await adminHeaders(request);
  const fixture = await createManagedWordPress(request, headers);
  ctx.uploadLimitSiteId = fixture.siteId;
  ctx.uploadLimitSlug = fixture.slug;
  await loginToWordPressAdmin(page, fixture.slug, fixture.adminUser, fixture.adminPassword);
});

When('メディアの新規追加画面を開く', async ({ ctx, page }) => {
  await page.goto(`/sites/${ctx.uploadLimitSlug}/wp-admin/media-new.php`);
});

Then('最大アップロードサイズとして1GBが表示される', async ({ page }) => {
  // WP は `<p class="max-upload-size">最大アップロードサイズ: 1 GB。</p>` を出す。
  // 表示文言はロケール依存だが、`size_format()` が返す数値表記は共通。
  await expect(page.locator('.max-upload-size').first()).toContainText(EXPECTED_MAX_UPLOAD_LABEL, {
    timeout: 30000,
  });
});

When('110MBのファイルをメディアの新規追加画面からアップロードする', async ({ ctx, page }) => {
  const filePath = createLargeUploadFixture();
  ctx.uploadLimitFixturePath = filePath;
  ctx.uploadLimitFileName = path.basename(filePath);

  // plupload(既定のドラッグ&ドロップUI)ではなく、WP標準の代替アップローダーを使う。
  // こちらは `async-upload.php` へ直接 multipart POST する素のHTMLフォームで、
  // 経路(nginx → Apache → PHP)にそのまま乗る。JavaScript側の分割送信が挟まらないため、
  // 「1リクエストが各層の上限を通過できるか」をそのまま検証できる。
  await page.goto(`/sites/${ctx.uploadLimitSlug}/wp-admin/media-new.php?browser-uploader=1`);
  await page.locator('input[type="file"][name="async-upload"]').setInputFiles(filePath);
  await page.locator('#html-upload').click();
});

Then('アップロードが成功しメディアライブラリに登録される', async ({ ctx, page }) => {
  // 失敗時のWPは同じ画面にエラー(`.notice-error` / 「ファイルサイズ」)を出し、
  // nginxで弾かれた場合は413ページになる。成功時のみメディアライブラリへ遷移する。
  await page.waitForURL(`**/sites/${ctx.uploadLimitSlug}/wp-admin/upload.php**`, { timeout: 300000 });
  await expect(page.locator('body')).not.toContainText('413');

  const listing = page.locator(`text=${ctx.uploadLimitFileName as string}`).first();
  await expect(listing).toBeVisible({ timeout: 60000 });
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  const fixturePath = ctx.uploadLimitFixturePath as string | undefined;
  if (fixturePath !== undefined && fs.existsSync(fixturePath)) {
    fs.rmSync(fixturePath, { force: true });
  }

  const siteId = ctx.uploadLimitSiteId as number | undefined;
  if (siteId !== undefined) {
    const headers = await adminHeaders(request);
    await request.delete(`/api/sites/${siteId}`, { headers });
  }
});
