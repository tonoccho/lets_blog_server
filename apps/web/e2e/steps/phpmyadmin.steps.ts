import { execFileSync } from 'node:child_process';
import { Given, Then, When } from './fixtures';
import { expect } from '../support';

/**
 * phpMyAdmin(`/phpmyadmin/`)の認証のステップ定義(issue #978)。
 *
 * reverse-proxy が中継する経路のうち、ここはアプリの認証ゲート(ADR-0008)を通らない。
 * 無認証で到達できると Keycloak も各サービスの SecurityConfig も迂回して MySQL を
 * 直接読み書きできてしまうため、phpMyAdmin 自身のログインを必ず経由することを確かめる。
 */

/** phpMyAdmin のトップ。reverse-proxy のサブパス配信(PMA_ABSOLUTE_URI)経由で見る。 */
const PHPMYADMIN_TOP = '/phpmyadmin/';

/** サーバー上のデータベース一覧。認証済みセッションでのみ中身が描画される画面。 */
const PHPMYADMIN_DATABASE_LIST = '/phpmyadmin/index.php?route=/server/databases';

/**
 * 「MySQL に接続済みのセッションが与えられている」ときにだけ応答へ現れる印。
 * どちらも実際に MySQL へ接続した結果を描画しないと出てこないため、1つでも
 * 含まれていれば無認証でデータベースを覗けたことになる。
 *   - `db=information_schema` : サーバー上に実在するデータベースへのリンク
 *   - `id="pma_navigation"`   : データベースを操作する管理画面の枠
 *
 * `route=/database/structure` は印にできない。ログイン画面でも JS の
 * `CommonParams.setAll({... opendb_url:"index.php?route=/database/structure" ...})`
 * として出力されるため、未認証でも必ず含まれてしまう。
 */
const DATABASE_LISTING_MARKERS = ['db=information_schema', 'id="pma_navigation"'];

/** ログインフォーム(templates/login/form.twig)のユーザー名入力。 */
const LOGIN_FORM_MARKER = 'name="pma_username"';

interface PhpMyAdminResponse {
  status: number;
  body: string;
  url: string;
}

/** MySQL コンテナが実際に使っている資格情報。運用者が手で入力するものと同じ。 */
function mysqlCredentials(): { user: string; password: string } {
  const read = (name: string): string =>
    execFileSync('docker', ['exec', 'lbs-mysql', 'printenv', name], {
      encoding: 'utf8',
      timeout: 30_000,
    }).trim();
  return { user: read('MYSQL_USER'), password: read('MYSQL_PASSWORD') };
}

/** ログインフォームの hidden 入力を name=value の組で取り出す。 */
function hiddenInputs(html: string): Record<string, string> {
  const fields: Record<string, string> = {};
  for (const [, tag] of html.matchAll(/<input\s+([^>]*type="hidden"[^>]*)>/g)) {
    const name = /name="([^"]*)"/.exec(tag)?.[1];
    if (name) {
      fields[name] = /value="([^"]*)"/.exec(tag)?.[1] ?? '';
    }
  }
  return fields;
}

function seen(ctx: Record<string, unknown>): PhpMyAdminResponse {
  const response = ctx.phpmyadminResponse as PhpMyAdminResponse | undefined;
  if (!response) {
    throw new Error('先に phpMyAdmin へアクセスするステップを実行すること');
  }
  return response;
}

When('未認証で phpMyAdmin のデータベース一覧を開く', async ({ ctx, request }) => {
  const response = await request.get(PHPMYADMIN_DATABASE_LIST);
  ctx.phpmyadminResponse = {
    status: response.status(),
    body: await response.text(),
    url: PHPMYADMIN_DATABASE_LIST,
  } satisfies PhpMyAdminResponse;
});

When('未認証で phpMyAdmin のトップページを開く', async ({ ctx, request }) => {
  const response = await request.get(PHPMYADMIN_TOP);
  ctx.phpmyadminResponse = {
    status: response.status(),
    body: await response.text(),
    url: PHPMYADMIN_TOP,
  } satisfies PhpMyAdminResponse;
});

Then('データベース一覧は返らない', async ({ ctx }) => {
  const { body, url } = seen(ctx);
  for (const marker of DATABASE_LISTING_MARKERS) {
    expect(body, `${url} が未認証でデータベースを描画している(${marker})`).not.toContain(marker);
  }
});

Then('phpMyAdmin のログインを求められる', async ({ ctx }) => {
  const { status, body, url } = seen(ctx);
  // AC は「ログイン要求・403・到達不能」のいずれでもよいとしている。
  const rejected = status === 401 || status === 403 || body.includes(LOGIN_FORM_MARKER);
  expect(rejected, `${url} が認証を求めていない (status=${status})`).toBe(true);
});

Given('phpMyAdmin のログイン画面を開く', async ({ ctx, request }) => {
  const response = await request.get(PHPMYADMIN_TOP);
  const body = await response.text();
  expect(body, 'phpMyAdmin がログインフォームを出していない').toContain(LOGIN_FORM_MARKER);
  ctx.phpmyadminLoginForm = body;
});

When('MySQLの資格情報でログインする', async ({ ctx, request }) => {
  const { user, password } = mysqlCredentials();
  const form = ctx.phpmyadminLoginForm as string;
  // CSRF トークン(token)やセッションIDはフォームの hidden から取り、
  // 画面が渡してきたものをそのまま送り返す(利用者がフォームを送信するのと同じ)。
  const response = await request.post(`${PHPMYADMIN_TOP}index.php?route=/`, {
    form: { ...hiddenInputs(form), pma_username: user, pma_password: password },
  });
  ctx.phpmyadminResponse = {
    status: response.status(),
    body: await response.text(),
    url: `${PHPMYADMIN_TOP}index.php?route=/`,
  } satisfies PhpMyAdminResponse;
});

Then('phpMyAdmin の管理画面が表示される', async ({ ctx }) => {
  const { status, body } = seen(ctx);
  expect(status, 'ログインが失敗している').toBeLessThan(400);
  expect(body, 'ログイン後も認証を求められている').not.toContain(LOGIN_FORM_MARKER);
  expect(body, '管理画面(ナビゲーション枠)が出ていない').toContain('id="pma_navigation"');
});
