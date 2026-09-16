import { execFileSync } from 'node:child_process';
import type { APIRequestContext, Page } from '@playwright/test';
import { Given, Then, When, After } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  expect,
  fetchAccessToken,
} from '../support';

/**
 * provision-agentの`/db-import`(issue #511)のusermeta meta_key改名が、旧プレフィックスの
 * 前方一致(LEFT match)ではなく`capabilities`/`user_level`の完全一致だけを対象にすることを
 * 固定するステップ定義(issue #1074)。
 *
 * 受け入れテスト環境にはSSH管理WordPressコンテナが無く`/db-import`をUIから起動する経路が
 * 無いため(issue #1074 Open Questions)、provision-agentのエンドポイント(ポート9000、
 * `lbs-wordpress`コンテナ内部限定)へ`docker exec`経由のcurlで直接POSTする。
 * docker exec経由でのwp-cli/生SQL呼び出しは`environmentSync.steps.ts`のパターンを踏襲する。
 */

const WORDPRESS_CONTAINER = 'lbs-wordpress';

/** WordPressコンテナ内で対象サイトのwp-cliを実行する(`environmentSync.steps.ts`と同じ)。 */
function wpCli(siteKey: string, args: string[]): string {
  return execFileSync(
    'docker',
    ['exec', WORDPRESS_CONTAINER, 'wp', '--allow-root', `--path=/var/www/html/sites/${siteKey}`, ...args],
    { encoding: 'utf8', timeout: 120_000 }
  ).trim();
}

function siteDbName(siteKey: string): string {
  return wpCli(siteKey, ['config', 'get', 'DB_NAME']);
}

let cachedRootPassword: string | null = null;

/** mysqlコンテナのMYSQL_ROOT_PASSWORD(`phpmyadmin.steps.ts`/`environmentSync.steps.ts`と同じ)。 */
function mysqlRootPassword(): string {
  if (cachedRootPassword === null) {
    cachedRootPassword = execFileSync('docker', ['exec', 'lbs-mysql', 'printenv', 'MYSQL_ROOT_PASSWORD'], {
      encoding: 'utf8',
      timeout: 30_000,
    }).trim();
  }
  return cachedRootPassword;
}

let cachedProvisionToken: string | null = null;

/** provision-agentの共有シークレット(`X-Provision-Token`)。lbs-wordpressコンテナの環境変数から直接読む。 */
function provisionToken(): string {
  if (cachedProvisionToken === null) {
    cachedProvisionToken = execFileSync('docker', ['exec', WORDPRESS_CONTAINER, 'printenv', 'WP_PROVISION_TOKEN'], {
      encoding: 'utf8',
      timeout: 30_000,
    }).trim();
  }
  return cachedProvisionToken;
}

/** `index.php`の各`mysql -e`呼び出しと同じ経路(lbs-wordpressコンテナから`mysql --skip-ssl`でシェルアウト)。 */
function dbQuery(dbName: string, sql: string): string {
  return execFileSync(
    'docker',
    [
      'exec', WORDPRESS_CONTAINER, 'mysql', '--skip-ssl', '-hmysql', '-uroot', `-p${mysqlRootPassword()}`,
      dbName, '-e', sql, '--skip-column-names',
    ],
    { encoding: 'utf8', timeout: 60_000 }
  ).trim();
}

function nonEmptyLines(output: string): string[] {
  return output
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

interface ManagedSiteFixture {
  id: number;
  siteKey: string;
  adminUser: string;
  adminPassword: string;
}

/** `POST /api/sites/managed-wordpress`でmanagedサイトを作る(`environmentSync.steps.ts`のcreateManagedSiteを踏襲)。 */
async function createManagedSite(request: APIRequestContext, headers: Record<string, string>): Promise<ManagedSiteFixture> {
  const suffix = uniqueSuffix();
  const siteKey = `e2e1074${suffix}`.toLowerCase().replace(/[^a-z0-9]/g, '');
  const adminUser = 'e2e1074admin'.slice(0, 30);
  const adminPassword = `E2e1074#Db${suffix}`;
  const response = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: `E2E 1074 ${suffix}`,
      siteKey,
      title: 'E2E 1074',
      adminUser,
      adminEmail: `e2e-1074-${suffix}@letsblog.local`,
      adminPassword,
      locale: 'ja',
    },
    timeout: 120_000,
  });
  expect(
    response.ok(),
    `managedサイトの作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const id = ((await response.json()) as { id: number }).id;
  return { id, siteKey, adminUser, adminPassword };
}

/**
 * 「別の(自己無矛盾な)テーブルプレフィックスを持つサイト」を作る(`environmentSync.steps.ts`の
 * renameSitePrefixを踏襲)。単純にダンプのテーブル名だけをsedで書き換えると、
 * option_name='wp_user_roles'のようなデータ側の値まではプレフィックスに追随しないため
 * ロール定義が見つからなくなり、実運用の`/db-import`(呼び出し元は実在の別サイトの
 * ダンプをそのままアップロードするだけで、ダンプ自体を書き換えない)とは異なる状態に
 * なってしまう。テーブル名・ロール定義・usermetaのcapabilities/user_levelを一括で
 * 新プレフィックスへ揃えることで、「新プレフィックスで実際に動くサイト」を作ってから
 * そのDBをダンプする。
 */
function renameSitePrefix(siteKey: string, dbName: string, fromPrefix: string, toPrefix: string): void {
  const tables = nonEmptyLines(dbQuery(dbName, `SHOW TABLES LIKE '${fromPrefix}%'`));
  expect(tables.length > 0, `${siteKey}に${fromPrefix}始まりのテーブルが見つかりません`).toBe(true);
  for (const table of tables) {
    const newTable = `${toPrefix}${table.slice(fromPrefix.length)}`;
    dbQuery(dbName, `RENAME TABLE \`${table}\` TO \`${newTable}\``);
  }
  wpCli(siteKey, ['config', 'set', 'table_prefix', toPrefix]);
  dbQuery(dbName, `DELETE FROM \`${toPrefix}options\` WHERE option_name='${toPrefix}user_roles'`);
  dbQuery(
    dbName,
    `UPDATE \`${toPrefix}options\` SET option_name='${toPrefix}user_roles' WHERE option_name='${fromPrefix}user_roles'`
  );
  dbQuery(
    dbName,
    `UPDATE \`${toPrefix}usermeta\` SET meta_key = CONCAT('${toPrefix}', SUBSTRING(meta_key, LENGTH('${fromPrefix}')+1)) ` +
      `WHERE LEFT(meta_key, LENGTH('${fromPrefix}')) = '${fromPrefix}'`
  );
}

/** WordPress自身のログイン画面からCookie認証する(`environmentSync.steps.ts`のloginToWordPressAdminを踏襲)。 */
async function loginToWordPressAdmin(page: Page, slug: string, adminUser: string, adminPassword: string): Promise<void> {
  await page.goto(`/sites/${slug}/wp-login.php`);
  await page.locator('#user_login').fill(adminUser);
  await page.locator('#user_pass').fill(adminPassword);
  await page.locator('#wp-submit').click();
  await page.waitForURL(`**/sites/${slug}/wp-admin/**`, { timeout: 30000 });
}

const NON_PREFIX_DERIVED_META_KEY_SUFFIX = 'notes_notify';

Given('usermetaにプレフィックス由来ではないキーを持つmanagedサイトがある', async ({ request, ctx }) => {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const headers = { Authorization: `Bearer ${token}` };
  const site = await createManagedSite(request, headers);
  ctx.dbImportSite = site;

  const originalPrefix = wpCli(site.siteKey, ['config', 'get', 'table_prefix']);
  expect(originalPrefix).toBe('wp_');
  ctx.dbImportOriginalPrefix = originalPrefix;

  const dbName = siteDbName(site.siteKey);
  ctx.dbImportDbName = dbName;

  const adminUserId = wpCli(site.siteKey, ['user', 'get', site.adminUser, '--field=ID']);

  // `wp_notes_notify`のように旧プレフィックス文字列で「たまたま」始まるだけの、
  // プレフィックス由来ではないusermetaキー(例: 何らかのプラグインが持つ独自キー)を仕込む。
  // `LEFT(meta_key, LENGTH(旧prefix)) = 旧prefix`の前方一致改名は、このキーまで
  // 巻き込んで改名してしまう(issue #1074の再現条件)。
  const nonPrefixDerivedKey = `${originalPrefix}${NON_PREFIX_DERIVED_META_KEY_SUFFIX}`;
  ctx.dbImportNonPrefixDerivedKey = nonPrefixDerivedKey;
  dbQuery(
    dbName,
    `INSERT INTO \`${originalPrefix}usermeta\` (user_id, meta_key, meta_value) VALUES ` +
      `(${adminUserId}, '${nonPrefixDerivedKey}', '1')`
  );
});

When('異なるテーブルプレフィックスのDBダンプをそのサイトへdb-importする', async ({ request, ctx }) => {
  const site = ctx.dbImportSite as ManagedSiteFixture;
  const originalPrefix = ctx.dbImportOriginalPrefix as string;
  const dbName = ctx.dbImportDbName as string;
  const newPrefix = originalPrefix === 'wp_' ? 'zz9_' : 'wp_';
  ctx.dbImportNewPrefix = newPrefix;

  // アップロードするダンプは、実運用(SSH管理サイトからの実際のダンプ)を模して、
  // 「新プレフィックスで自己無矛盾に動く別サイト」から取得する(`renameSitePrefix`参照)。
  // 同期先自身のusers/usermetaはダンプに含めない(実運用のexportDatabaseと同じ、
  // コメント参照)。users/usermetaは`/db-import`自身がインポート前に別途退避・復元し、
  // そこでmeta_key改名の対象になる。
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const headers = { Authorization: `Bearer ${token}` };
  const sourceSite = await createManagedSite(request, headers);
  ctx.dbImportSourceSite = sourceSite;
  const sourceDbName = siteDbName(sourceSite.siteKey);
  renameSitePrefix(sourceSite.siteKey, sourceDbName, originalPrefix, newPrefix);

  const exportPath = '/tmp/e2e1074-dump.sql';
  wpCli(sourceSite.siteKey, [
    'db', 'export', exportPath,
    '--defaults',
    `--exclude_tables=${newPrefix}users,${newPrefix}usermeta`,
  ]);
  execFileSync('docker', ['exec', WORDPRESS_CONTAINER, 'cp', exportPath, '/tmp/e2e1074-upload.sql'], {
    timeout: 30_000,
  });

  const siteUrl = `https://localhost/sites/${sourceSite.siteKey}`;
  const responseText = execFileSync(
    'docker',
    [
      'exec', WORDPRESS_CONTAINER, 'curl', '-s', '-X', 'POST', 'http://localhost:9000/db-import',
      '-H', `X-Provision-Token: ${provisionToken()}`,
      '-F', `slug=${site.siteKey}`,
      '-F', `dbName=${dbName}`,
      '-F', `fromUrl=${siteUrl}`,
      '-F', `fromPrefix=${newPrefix}`,
      '-F', 'file=@/tmp/e2e1074-upload.sql',
    ],
    { encoding: 'utf8', timeout: 120_000 }
  );
  ctx.dbImportResponse = responseText;
  const parsed = JSON.parse(responseText) as { status?: string; error?: string };
  expect(parsed.status, `/db-importが失敗しました: ${responseText}`).toBe('ok');
});

Then('新プレフィックスのusermetaにcapabilitiesとuser_levelが存在し旧プレフィックスのものは残っていない', async ({ ctx }) => {
  const dbName = ctx.dbImportDbName as string;
  const originalPrefix = ctx.dbImportOriginalPrefix as string;
  const newPrefix = ctx.dbImportNewPrefix as string;
  const keys = nonEmptyLines(dbQuery(dbName, `SELECT meta_key FROM \`${newPrefix}usermeta\``));
  expect(keys.includes(`${newPrefix}capabilities`)).toBe(true);
  expect(keys.includes(`${newPrefix}user_level`)).toBe(true);
  expect(keys.includes(`${originalPrefix}capabilities`)).toBe(false);
  expect(keys.includes(`${originalPrefix}user_level`)).toBe(false);
});

Then('プレフィックス由来ではないusermetaキーは改名されずそのまま残っている', async ({ ctx }) => {
  const dbName = ctx.dbImportDbName as string;
  const newPrefix = ctx.dbImportNewPrefix as string;
  const nonPrefixDerivedKey = ctx.dbImportNonPrefixDerivedKey as string;
  const renamedVariant = `${newPrefix}${NON_PREFIX_DERIVED_META_KEY_SUFFIX}`;
  const keys = nonEmptyLines(dbQuery(dbName, `SELECT meta_key FROM \`${newPrefix}usermeta\``));
  expect(
    keys.includes(nonPrefixDerivedKey),
    `${nonPrefixDerivedKey}が見つかりません(改名されてしまった可能性): ${keys.join(',')}`
  ).toBe(true);
  expect(keys.includes(renamedVariant), `${renamedVariant}へ誤って改名されています`).toBe(false);
});

Then('そのサイトの管理者アカウントでダッシュボードが表示される', async ({ page, ctx }) => {
  const site = ctx.dbImportSite as ManagedSiteFixture;
  await loginToWordPressAdmin(page, site.siteKey, site.adminUser, site.adminPassword);
  await expect(page.locator('#adminmenu')).toBeVisible({ timeout: 15000 });
  await expect(page.getByText('このページにアクセスする十分な権限がありません。')).toHaveCount(0);
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  const site = ctx.dbImportSite as ManagedSiteFixture | undefined;
  const sourceSite = ctx.dbImportSourceSite as ManagedSiteFixture | undefined;
  if (!site && !sourceSite) {
    return;
  }
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const headers = { Authorization: `Bearer ${token}` };
  if (site) {
    await request.delete(`/api/sites/${site.id}`, { headers });
  }
  if (sourceSite) {
    await request.delete(`/api/sites/${sourceSite.id}`, { headers });
  }
});
