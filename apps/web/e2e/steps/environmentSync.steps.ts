import { execFileSync } from 'node:child_process';
import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { clickUntilVisible } from '../support/retryClick';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  createFixtureProject,
  deleteFixtureProject,
  expect,
  fetchAccessToken,
  loginAsAdmin,
} from '../support';

/**
 * 環境同期(managed→managed)でテーブルプレフィックスが異なる場合のDB同期のステップ定義
 * (issue #1075)。
 *
 * `@stage:provision`のfeatureが本Issue追加時点で0件だったため、managedサイトを2つ用意する
 * 最小限の足場をこのファイル自身が持つ(`environment-sync.feature`冒頭コメント参照)。
 *
 * WordPressコンテナ内へのdocker exec経由でのwp-cli呼び出しは`apps/web/e2e/steps/phpmyadmin.steps.ts:42-50`・
 * `articlePlan.steps.ts`のパターンを踏襲する。
 */

const WORDPRESS_CONTAINER = 'lbs-wordpress';
const MYSQL_CONTAINER = 'lbs-mysql';

/** WordPressコンテナ内で対象サイトのwp-cliを実行する。 */
function wpCli(siteKey: string, args: string[]): string {
  return execFileSync(
    'docker',
    ['exec', WORDPRESS_CONTAINER, 'wp', '--allow-root', `--path=/var/www/html/sites/${siteKey}`, ...args],
    { encoding: 'utf8', timeout: 120_000 }
  ).trim();
}

/** サイトのDB名(wp-configのDB_NAME)。生SQLを流すのに使う。 */
function siteDbName(siteKey: string): string {
  return wpCli(siteKey, ['config', 'get', 'DB_NAME']);
}

let cachedRootPassword: string | null = null;

/** mysqlコンテナのMYSQL_ROOT_PASSWORD(`phpmyadmin.steps.ts`と同じ、docker execで直接読む手段)。 */
function mysqlRootPassword(): string {
  if (cachedRootPassword === null) {
    cachedRootPassword = execFileSync('docker', ['exec', MYSQL_CONTAINER, 'printenv', 'MYSQL_ROOT_PASSWORD'], {
      encoding: 'utf8',
      timeout: 30_000,
    }).trim();
  }
  return cachedRootPassword;
}

/**
 * `wp db query`はコンテナのmysqlクライアント既定(SSL優先)の影響を受け自己署名証明書で
 * 失敗し、`--defaults`(`/root/.my.cnf`のskip-ssl)を指定してもwp-cliの実装上
 * (sql_mode取得の内部呼び出し)反映されないため使えない。`lbs-mysql`コンテナ自身の`mysql`
 * クライアントは`--skip-ssl`を知らない別バージョンのため、`index.php`と同じく
 * `lbs-wordpress`コンテナ(mariadbクライアントを持つ)からネットワーク越しに
 * `mysql --skip-ssl -h mysql ...`でシェルアウトする(`index.php`の各`mysql -e`呼び出しと同じ経路)。
 */
function dbQuery(siteKey: string, sql: string): string {
  const dbName = siteDbName(siteKey);
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

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await adminToken(request);
  return { Authorization: `Bearer ${token}` };
}

interface ManagedSiteFixture {
  id: number;
  siteKey: string;
  adminUser: string;
  adminPassword: string;
}

/** `POST /api/sites/managed-wordpress`でmanagedサイトを作る(`articlePlan.steps.ts`のcreateTaxonomySiteを踏襲)。 */
async function createManagedSite(
  request: APIRequestContext,
  headers: Record<string, string>,
  prefix: string
): Promise<ManagedSiteFixture> {
  const suffix = uniqueSuffix();
  const siteKey = `e2e1075${prefix}${suffix}`.toLowerCase().replace(/[^a-z0-9]/g, '');
  const adminUser = `e2e1075admin${prefix}`.slice(0, 30);
  const adminPassword = `E2e1075#Sync${suffix}`;
  const response = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: `E2E 1075 ${prefix} ${suffix}`,
      siteKey,
      title: `E2E 1075 ${prefix}`,
      adminUser,
      adminEmail: `e2e-1075-${prefix}-${suffix}@letsblog.local`,
      adminPassword,
      locale: 'ja',
    },
    timeout: 120_000,
  });
  expect(
    response.ok(),
    `managedサイト(${prefix})の作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const id = ((await response.json()) as { id: number }).id;
  return { id, siteKey, adminUser, adminPassword };
}

async function bindEnvironment(
  request: APIRequestContext,
  headers: Record<string, string>,
  projectId: number,
  environment: 'local' | 'test',
  siteId: number
): Promise<void> {
  const response = await request.post(`/api/projects/${projectId}/environments`, {
    headers,
    data: { environment, siteId },
  });
  expect(
    response.ok(),
    `${environment}環境への紐付けに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

/** サイトの現在のtable_prefixを読む(既存コード`index.php`と同じ取得方法)。 */
function readTablePrefix(siteKey: string): string {
  return wpCli(siteKey, ['config', 'get', 'table_prefix']);
}

/**
 * 「同期先のテーブルを一括改名してwp config set table_prefixを変える」ことで、
 * `/db-import`(#511)が残すのと等価な、自己無矛盾なプレフィックス食い違い状態を作る
 * (issue #1075 Open Questions)。単純なRENAME TABLEだけではoption_name/meta_keyの値
 * (データ)は古いプレフィックスのまま残ってしまうため、user_rolesの行とusermetaの
 * meta_key(`/db-import`側が実際に行っているのと同じ付け替え、issue #511)も値ごと
 * 付け替えて「改名直後は正常に動くサイト」にしてから検証対象の同期を実行する。
 * このサイト自身の管理者アカウント(この時点では同期の影響を受けない)がこの後も
 * 正しくログインできることの前提になる。
 */
function renameSitePrefix(siteKey: string, fromPrefix: string, toPrefix: string): void {
  const tables = nonEmptyLines(dbQuery(siteKey, `SHOW TABLES LIKE '${fromPrefix}%'`));
  expect(tables.length > 0, `${siteKey}に${fromPrefix}始まりのテーブルが見つかりません`).toBe(true);
  for (const table of tables) {
    const newTable = `${toPrefix}${table.slice(fromPrefix.length)}`;
    dbQuery(siteKey, `RENAME TABLE \`${table}\` TO \`${newTable}\``);
  }
  wpCli(siteKey, ['config', 'set', 'table_prefix', toPrefix]);
  dbQuery(siteKey, `DELETE FROM \`${toPrefix}options\` WHERE option_name='${toPrefix}user_roles'`);
  dbQuery(
    siteKey,
    `UPDATE \`${toPrefix}options\` SET option_name='${toPrefix}user_roles' WHERE option_name='${fromPrefix}user_roles'`
  );
  dbQuery(
    siteKey,
    `UPDATE \`${toPrefix}usermeta\` SET meta_key = CONCAT('${toPrefix}', SUBSTRING(meta_key, LENGTH('${fromPrefix}')+1)) ` +
      `WHERE LEFT(meta_key, LENGTH('${fromPrefix}')) = '${fromPrefix}'`
  );
}

/**
 * transient/site-transientはWordPress自身がリクエストのたびに再生成しうるキャッシュで、
 * ハッシュ付きの名前(テーマファイルパターン等)を含み、同じダンプから作った2つのサイトでも
 * その後のアクセス次第で内容が変わりうる(プレフィックス不整合の不具合とは無関係)。
 * options集合の一致比較からは除外する。
 */
function isVolatileTransientOption(optionName: string): boolean {
  return optionName.startsWith('_transient_') || optionName.startsWith('_site_transient_');
}

function optionNames(siteKey: string, prefix: string): string[] {
  return nonEmptyLines(dbQuery(siteKey, `SELECT option_name FROM \`${prefix}options\``)).filter(
    (name) => !isVolatileTransientOption(name)
  );
}

function roleNames(siteKey: string): string[] {
  return nonEmptyLines(wpCli(siteKey, ['role', 'list', '--fields=role', '--format=csv'])).filter(
    (line) => line !== 'role'
  );
}

/** WordPress自身のログイン画面からCookie認証する(`siteAdoption.steps.ts`のloginToWordPressAdminを踏襲)。 */
async function loginToWordPressAdmin(page: Page, slug: string, adminUser: string, adminPassword: string): Promise<void> {
  await page.goto(`/sites/${slug}/wp-login.php`);
  await page.locator('#user_login').fill(adminUser);
  await page.locator('#user_pass').fill(adminPassword);
  await page.locator('#wp-submit').click();
  await page.waitForURL(`**/sites/${slug}/wp-admin/**`, { timeout: 30000 });
}

// --------------------------------------------------------------- 前提

Given('環境同期検証用のプロジェクトと2つのmanagedサイトがある', async ({ request, ctx }) => {
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const project = await createFixtureProject(request, token, 'e2e1075-envsync');
  ctx.esProjectId = project.id;

  const fromSite = await createManagedSite(request, headers, 'from');
  const toSite = await createManagedSite(request, headers, 'to');
  ctx.esFromSite = fromSite;
  ctx.esToSite = toSite;

  await bindEnvironment(request, headers, project.id, 'test', fromSite.id);
  await bindEnvironment(request, headers, project.id, 'local', toSite.id);

  ctx.esFromPrefix = readTablePrefix(fromSite.siteKey);

  // `wp_calendar_block_has_published_posts`等は新規インストール直後は存在せず、遅延生成される
  // (実際に投稿を公開した場合等)。存在有無をWordPressのバージョン・タイミングに委ねず、
  // 同期元へ確定的に仕込んでおく(AC5の「改名されず残っている」を安定して検証するため)。
  for (const name of CORE_OPTIONS_NOT_PREFIX_DATA) {
    wpCli(fromSite.siteKey, ['option', 'update', name, '1']);
  }
});

Given('プロジェクトへwpRole=editorのメンバーを追加している', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  const suffix = uniqueSuffix();
  const email = `e2e-1075-member-${suffix}@example.com`;
  const created = await request.post('/api/users', {
    headers,
    data: { email, password: `E2e1075Member!${suffix}`, role: 'user' },
  });
  expect(
    created.ok(),
    `検証用メンバーの登録に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const userId = ((await created.json()) as { id: number }).id;
  ctx.esMemberUserId = userId;
  ctx.esMemberLogin = email.slice(0, email.indexOf('@'));

  const projectId = ctx.esProjectId as number;
  const response = await request.post(`/api/projects/${projectId}/users`, {
    headers,
    data: { userId, wpRole: 'editor' },
  });
  expect(
    response.ok(),
    `プロジェクトメンバーの追加に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

// --------------------------------------------------------------- 同期の実行

/**
 * 1シナリオ内で環境同期を2回行う(プレフィックス一致→不一致)ため、2回目でも同じ`page`を使う。
 * 同一セッションでのKeycloakへの再ログインはSSOの即時リダイレクトと競合しうる
 * (`site-provisioning.feature`の既存コメントと同種、#1017/#1078)ため、既にログイン済みなら
 * 再ログインせずナビゲーションだけ行う。
 */
async function performSync(page: Page, ctx: Record<string, unknown>, projectId: number): Promise<void> {
  if (!ctx.esLoggedIn) {
    await loginAsAdmin(page);
    ctx.esLoggedIn = true;
  }
  await page.goto(`/projects/${projectId}`);
  const panel = page
    .locator('div.rounded-lg', { has: page.getByRole('heading', { name: '環境同期' }) })
    .first();
  // issue #1386: goto直後はハイドレーション未完了で「設定」タブのクリックが空振りしうる。タブは
  // 活性タブの内容だけを描画するので、環境同期パネルの出現を期待値に、べき等なタブ切り替えを
  // 再試行する。「同期する」は非べき等なので再試行せず、パネル(クライアント描画)の出現後に1回だけ押す。
  await clickUntilVisible(page.locator('button:has-text("設定")'), panel);

  await panel.locator('select[name="from"]').selectOption('test');
  await panel.locator('select[name="to"]').selectOption('local');
  await panel.locator('input[name="targets"][value="db"]').check();

  page.once('dialog', (dialog) => dialog.accept());
  await panel.locator('button:has-text("同期する")').click();
  await expect(panel.getByText('同期しました。')).toBeVisible({ timeout: 60000 });
}

When('プレフィックスが同一のまま同期元から同期先へDBを同期する', async ({ page, ctx }) => {
  await performSync(page, ctx, ctx.esProjectId as number);
});

Then('同期先のoptionsの構成は同期元と一致している', async ({ ctx }) => {
  const fromSite = ctx.esFromSite as ManagedSiteFixture;
  const toSite = ctx.esToSite as ManagedSiteFixture;
  const fromPrefix = ctx.esFromPrefix as string;
  const toPrefix = readTablePrefix(toSite.siteKey);
  expect(toPrefix).toBe(fromPrefix);

  const fromOptions = new Set(optionNames(fromSite.siteKey, fromPrefix));
  const toOptions = new Set(optionNames(toSite.siteKey, toPrefix));
  expect([...toOptions].sort()).toEqual([...fromOptions].sort());
});

When('同期先のテーブルプレフィックスを同期元と異なる値へ変更する', async ({ ctx }) => {
  const toSite = ctx.esToSite as ManagedSiteFixture;
  const fromPrefix = ctx.esFromPrefix as string;
  const toPrefix = fromPrefix === 'wp_' ? 'xy7_' : 'wp_';
  renameSitePrefix(toSite.siteKey, fromPrefix, toPrefix);
  ctx.esToPrefix = toPrefix;

  // 改名直後、同期先は自己無矛盾(ロール定義あり)であることを前提として確認しておく。
  expect(roleNames(toSite.siteKey).length).toBeGreaterThan(0);
});

When('環境同期パネルから同期元のDBを同期先へ同期する', async ({ page, ctx }) => {
  await performSync(page, ctx, ctx.esProjectId as number);
});

Then(
  '同期先のoptionsに同期先プレフィックスのuser_rolesが1件存在し同期元プレフィックスのuser_rolesは存在しない',
  async ({ ctx }) => {
    const toSite = ctx.esToSite as ManagedSiteFixture;
    const fromPrefix = ctx.esFromPrefix as string;
    const toPrefix = ctx.esToPrefix as string;
    const options = optionNames(toSite.siteKey, toPrefix);
    expect(options.filter((name) => name === `${toPrefix}user_roles`).length).toBe(1);
    expect(options.includes(`${fromPrefix}user_roles`)).toBe(false);
  }
);

Then('同期先のロール一覧は同期元と同じロール集合である', async ({ ctx }) => {
  const fromSite = ctx.esFromSite as ManagedSiteFixture;
  const toSite = ctx.esToSite as ManagedSiteFixture;
  const fromRoles = roleNames(fromSite.siteKey).sort();
  const toRoles = roleNames(toSite.siteKey).sort();
  expect(toRoles).toEqual(fromRoles);
});

Then('同期先のテーブル名に同期元プレフィックスのものが残っていない', async ({ ctx }) => {
  const toSite = ctx.esToSite as ManagedSiteFixture;
  const fromPrefix = ctx.esFromPrefix as string;
  const leftoverTables = nonEmptyLines(dbQuery(toSite.siteKey, `SHOW TABLES LIKE '${fromPrefix}%'`));
  expect(leftoverTables).toEqual([]);
});

const CORE_OPTIONS_NOT_PREFIX_DATA = [
  'wp_page_for_privacy_policy',
  'wp_attachment_pages_enabled',
  'wp_calendar_block_has_published_posts',
  'wp_force_deactivated_plugins',
];

Then('同期先のプライバシーポリシー等のコアオプション名は改名されず残っている', async ({ ctx }) => {
  const toSite = ctx.esToSite as ManagedSiteFixture;
  const toPrefix = ctx.esToPrefix as string;
  const options = new Set(optionNames(toSite.siteKey, toPrefix));
  for (const name of CORE_OPTIONS_NOT_PREFIX_DATA) {
    expect(options.has(name), `${name}が同期先optionsに見つかりません(改名されてしまった可能性)`).toBe(true);
  }
});

// --------------------------------------------------------------- メンバーの権限・wp-adminログイン

When('そのメンバーの役割をadministratorへ変更する', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  const projectId = ctx.esProjectId as number;
  const userId = ctx.esMemberUserId as number;
  const response = await request.put(`/api/projects/${projectId}/users/${userId}`, {
    headers,
    data: { wpRole: 'administrator' },
  });
  expect(
    response.ok(),
    `役割変更に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

Then('そのメンバーの同期先での権限はadministratorただ1つである', async ({ ctx }) => {
  const toSite = ctx.esToSite as ManagedSiteFixture;
  const toPrefix = ctx.esToPrefix as string;
  const login = ctx.esMemberLogin as string;
  const capabilities = dbQuery(
    toSite.siteKey,
    `SELECT meta_value FROM \`${toPrefix}usermeta\` WHERE meta_key='${toPrefix}capabilities' ` +
      `AND user_id=(SELECT ID FROM \`${toPrefix}users\` WHERE user_login='${login}')`
  ).trim();
  expect(capabilities).toBe('a:1:{s:13:"administrator";b:1;}');
});

Then('同期先サイトの管理者アカウントでダッシュボードが表示される', async ({ page, ctx }) => {
  const toSite = ctx.esToSite as ManagedSiteFixture;
  await loginToWordPressAdmin(page, toSite.siteKey, toSite.adminUser, toSite.adminPassword);
  // ロール定義が失われた場合、wp-login.phpはcurrent_user_can('read')がfalseになるため
  // wp-adminへリダイレクトせずホーム(フロント)へ飛ばす、またはwp-admin自体が
  // 「このページへアクセスする十分な権限がありません」で拒否する(修正前のRED)。
  // 管理メニュー(`#adminmenu`、権限のあるユーザーにのみ描画される)の表示で判定する
  // (ダッシュボードウィジェット自体は本環境ではネットワーク到達性の都合で無関係に
  // 描画エラーになりうるため、権限判定に無関係なそちらは見ない)。
  await expect(page.locator('#adminmenu')).toBeVisible({ timeout: 15000 });
  await expect(page.getByText('このページにアクセスする十分な権限がありません。')).toHaveCount(0);
});

// --------------------------------------------------------------- 後片付け

After({ tags: '@project' }, async ({ ctx, request }) => {
  const fromSite = ctx.esFromSite as ManagedSiteFixture | undefined;
  const toSite = ctx.esToSite as ManagedSiteFixture | undefined;
  const projectId = ctx.esProjectId as number | undefined;
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}` };

  if (fromSite) {
    await request.delete(`/api/sites/${fromSite.id}`, { headers });
  }
  if (toSite) {
    await request.delete(`/api/sites/${toSite.id}`, { headers });
  }
  if (projectId !== undefined) {
    await deleteFixtureProject(request, token, projectId);
  }
});
