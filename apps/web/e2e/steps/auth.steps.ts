import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import type { APIRequestContext, BrowserContext, Page } from '@playwright/test';
import { decode, encode } from 'next-auth/jwt';
import type { JWT } from 'next-auth/jwt';
import { After, Given, Step, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  createFixtureProject,
  expect,
  fetchAccessToken,
  getNextAuthSecret,
  loginViaKeycloak,
  withAccountLock,
} from '../support';
import {
  AUTH_GATED_PATHS,
  DOMAIN_SERVICES,
  requestServiceDirectly,
  type DomainService,
} from '../support/services';

/**
 * 認証・セッション・初回セットアップのステップ定義(issue #929 / AT-3)。
 *
 * 移行元は `apps/web/e2e/auth-flow.spec.ts`(削除済み)。
 */

const REALM_BASE = '/auth/realms/letsblog';
const DEVICE_CLIENT_ID = 'letsblog-vscode';
const E2E_CLIENT_ID = 'letsblog-e2e';

/** 初回セットアップで作る管理者。#945 の seed が資格情報を整える相手と同じにする。 */
const SETUP_ADMIN_EMAIL = process.env.E2E_PROVISION_ADMIN_EMAIL ?? E2E_ADMIN_EMAIL;
const SETUP_ADMIN_PASSWORD = process.env.E2E_PROVISION_ADMIN_PASSWORD ?? E2E_ADMIN_PASSWORD;

/**
 * issue #1295(利用者の決定(a)): 意図的に失敗させるログイン・意図的にトークンを失効させる
 * 検証は、共有のE2E固定アカウント(`E2E_TEST_EMAIL`/`E2E_ADMIN_EMAIL`)へ一切触れず、
 * このファイルが自分で作る使い捨てのKeycloakアカウントに対して行う。
 *
 * `apps/web/e2e/steps/bruteForceLockout.steps.ts`(#1056)が同じ理由で同じ手法
 * (`docker exec`で`kcadm.sh`を叩き、実際にログインできるユーザーを直接作る)を
 * 既に使っている。このファイルではローカルDB側のUserは不要(Keycloak側の資格情報と
 * ブルートフォース検知だけが対象)なため、同じくローカルDBを経由しない。
 *
 * `kcadm`/`kcadmLogin`/`KEYCLOAK_REALM` 自体は、このファイル内で issue #1053
 * (`revokeKeycloakSsoSession`)向けに既に定義済みのものを再利用する
 * (関数宣言はモジュール内で巻き上げられるため、定義順に依存しない)。
 */
function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

/** 実際にKeycloakへログインできる使い捨てユーザーを作る(bruteForceLockout.steps.tsと同型)。 */
function createLoginableKeycloakUser(email: string, password: string): string {
  kcadmLogin();
  kcadm([
    'create', 'users', '-r', KEYCLOAK_REALM,
    '-s', `username=${email}`,
    '-s', `email=${email}`,
    '-s', 'enabled=true',
    '-s', 'emailVerified=true',
    '-s', 'firstName=E2E',
    '-s', 'lastName=AuthDisposable',
    '-s', 'requiredActions=[]',
  ]);
  const usersJson = kcadm(['get', 'users', '-r', KEYCLOAK_REALM, '-q', `email=${email}`, '--fields', 'id']);
  const users = JSON.parse(usersJson) as { id: string }[];
  if (users.length === 0) {
    throw new Error(`Keycloakに ${email} を作成できませんでした`);
  }
  const keycloakUserId = users[0].id;
  kcadm(['set-password', '-r', KEYCLOAK_REALM, '--userid', keycloakUserId, '--new-password', password]);
  return keycloakUserId;
}

function deleteKeycloakUser(keycloakUserId: string): void {
  kcadmLogin();
  // 既に削除済みでも失敗を無視する(後片付けの冪等性)。
  try {
    kcadm(['delete', `users/${keycloakUserId}`, '-r', KEYCLOAK_REALM]);
  } catch {
    // no-op
  }
}

/**
 * `POST /api/users`(管理者によるユーザー作成)はローカルDBとKeycloak双方にユーザーを
 * 作るが、Keycloak側のパスワードまでは設定しない(userDeactivation.steps.ts冒頭の
 * コメント参照)。実際にログイン・トークン取得できるようKeycloak側の資格情報を整える。
 */
function provisionLoginableKeycloakCredential(email: string, password: string): void {
  kcadmLogin();
  const usersJson = kcadm(['get', 'users', '-r', KEYCLOAK_REALM, '-q', `email=${email}`, '--fields', 'id']);
  const users = JSON.parse(usersJson) as { id: string }[];
  if (users.length === 0) {
    throw new Error(`Keycloakに ${email} が見つかりません`);
  }
  const keycloakUserId = users[0].id;
  kcadm(['set-password', '-r', KEYCLOAK_REALM, '--userid', keycloakUserId, '--new-password', password]);
  // VERIFY_PROFILEが有効なレルムのため、firstName/lastNameが空だとパスワードグラントが
  // "Account is not fully set up" で失敗する(userDeactivation.steps.ts参照)。
  kcadm([
    'update', `users/${keycloakUserId}`, '-r', KEYCLOAK_REALM,
    '-s', 'requiredActions=[]',
    '-s', 'emailVerified=true',
    '-s', 'firstName=E2E',
    '-s', 'lastName=TokenLifecycle',
  ]);
}

// ----------------------------------------------------------------- ログイン

When('ログイン画面を開く', async ({ page }) => {
  await page.goto('/login');
});

Then('Keycloakのホスト型ログイン画面が表示される', async ({ page }) => {
  // /login はマウント時に signIn("keycloak") を呼ぶだけの画面で、Keycloak の
  // ホスト型ログイン画面(reverse-proxy 経由 /auth/realms/letsblog/...)へ遷移する。
  await page.waitForURL(new RegExp(`${REALM_BASE}/`), { timeout: 15000 });
  await expect(page.locator('#username')).toBeVisible();
  await expect(page.locator('#password')).toBeVisible();
});

/**
 * issue #1052: JS無効時に/loginから自力でログインを開始できることの検証。
 *
 * `javaScriptEnabled: false` の別コンテキストを使う(初回セットアップのJS無効シナリオ
 * (下の「JavaScriptを無効にして初回セットアップ画面から空白だけのパスワードで送信する」)と
 * 同じ手法)。共有の`page`フィクスチャ自体はJS有効のままにしておきたいため、別コンテキストに
 * する。
 */
When('JavaScriptを無効にしてログイン画面を開く', async ({ page, ctx }) => {
  const browser = page.context().browser();
  if (!browser) {
    throw new Error('ブラウザインスタンスを取得できない(JS無効コンテキストを作成できない)');
  }
  const noJsContext = await browser.newContext({ ignoreHTTPSErrors: true, javaScriptEnabled: false });
  const noJsPage = await noJsContext.newPage();
  await noJsPage.goto('/login');
  ctx.noJsLoginPage = noJsPage;
  ctx.noJsLoginContext = noJsContext;
});

Then('JavaScriptが必要である旨の案内とログインを開始する手段が表示される', async ({ ctx }) => {
  const noJsPage = ctx.noJsLoginPage as Page;
  // `<noscript>` の中身はブラウザのアクセシビリティツリーに含まれないため、それに依存する
  // getByText/text= エンジンでは(JS無効で実際に描画されていても)絶対にヒットしない。
  // DOM の textContent を見る CSS の :has-text() を使う。
  await expect(
    noJsPage.locator('p:has-text("JavaScript")'),
    'JS無効時にJavaScriptが必要である旨の案内が表示されない'
  ).toBeVisible({ timeout: 10000 });
  await expect(
    noJsPage.locator('[data-testid="nojs-login-submit"]'),
    'JS無効時にログインを開始する手段(フォーム/ボタン)が表示されない'
  ).toBeVisible();
});

Then('その手段からKeycloakのホスト型ログイン画面へ到達できる', async ({ ctx }) => {
  const noJsPage = ctx.noJsLoginPage as Page;
  try {
    await noJsPage.locator('[data-testid="nojs-login-submit"]').click();
    await noJsPage.waitForURL(new RegExp(`${REALM_BASE}/`), { timeout: 15000 });
    await expect(noJsPage.locator('#username')).toBeVisible();
    await expect(noJsPage.locator('#password')).toBeVisible();
  } finally {
    await (ctx.noJsLoginContext as BrowserContext).close();
  }
});

// 「前提」でも「もし」でも同じ意味なので Step で定義する(Given と When の両方に一致する)。
Step('一般ユーザーとしてログインする', async ({ page }) => {
  await loginViaKeycloak(page, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
});

Given('管理者としてログインする', async ({ page }) => {
  await loginViaKeycloak(page, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
});

Then('ログアウトボタンが表示される', async ({ page }) => {
  await expect(page.locator('button:has-text("ログアウト")')).toBeVisible({ timeout: 5000 });
});

Given('誤ったパスワード検証用の使い捨てアカウントを作成する', async ({ ctx }) => {
  const suffix = uniqueSuffix();
  const email = `e2e-1295-wrongpassword-${suffix}@example.com`;
  const password = `E2e1295WrongPw!${suffix}`;
  ctx.wrongPasswordEmail = email;
  ctx.wrongPasswordKeycloakUserId = createLoginableKeycloakUser(email, password);
});

When('使い捨てアカウントのメールアドレスと誤ったパスワードを入力して送信する', async ({ page, ctx }) => {
  await page.waitForURL(new RegExp(`${REALM_BASE}/`), { timeout: 15000 });
  await page.locator('#username').fill(ctx.wrongPasswordEmail as string);
  // e2e-login-guard:disposable — このアカウントは直前のステップで作った使い捨て
  // アカウント(issue #1295)であり、共有のE2E固定アカウントには一切触れない。
  await page.locator('#password').fill('WrongPassword123!');
  await page.locator('#kc-login').click();
});

Then('Keycloakのログイン画面に留まり、資格情報が不正である旨が表示される', async ({ page }) => {
  // Web のコールバックへは進まない。進んでしまうと、誤った資格情報でセッションが
  // 確立したことになる。
  await expect(page).toHaveURL(new RegExp(`${REALM_BASE}/`));
  await expect(page.getByText('Invalid username or password')).toBeVisible({ timeout: 5000 });
});

When('ログアウトする', async ({ page }) => {
  const logoutButton = page.locator('button:has-text("ログアウト")');
  await expect(logoutButton).toBeVisible();
  await logoutButton.click();
});

Then('ログイン画面へ戻される', async ({ page }) => {
  // 行き先は `/login` とは限らない。ログアウトで Keycloak 側のSSOセッションも終了するため、
  // `/login` を経由せず Keycloak の認可エンドポイントへ直行することがある
  // (どちらになるかは NextAuth のリダイレクトと Keycloak の応答のタイミング次第)。
  // 確かめたいのは「もう保護ページには居られず、認証を求められること」なので両方を受ける。
  await page.waitForURL(
    (url) => url.pathname.startsWith('/login') || url.pathname.startsWith('/auth/realms/letsblog'),
    { timeout: 15000 }
  );
});

Then('保護ページを開くと認証を求められる', async ({ page }) => {
  // events.signOut で Keycloak 側のSSOセッションも終了させているため、
  // 保護ページへ直接入ろうとすると再び資格情報を求められる。
  await page.goto('/');
  await page.waitForURL(new RegExp(`/(login|${REALM_BASE.slice(1)})`), { timeout: 10000 });
});

// ----------------------------------------------------------------- 権限

When('管理者専用ページを開く', async ({ page }) => {
  await page.goto('/users');
});

Then('トップページへ戻される', async ({ page }) => {
  // apps/web/src/proxy.ts の ADMIN_ONLY_PREFIXES が "/" へ戻す。
  await expect(page).toHaveURL('/', { timeout: 5000 });
});

Then('管理者専用ページが表示される', async ({ page }) => {
  await expect(page).toHaveURL(/\/users$/, { timeout: 5000 });
});

/**
 * 管理系の権限。Web の ADMIN_ONLY_PREFIXES(`/users` / `/admin`)が要求する操作の裏にある。
 * 一般ユーザー(ROLE_VIEWER)がこれを持っていたら、画面の出し分けとサーバーの権限が
 * 食い違っていることになる。
 *
 * `USER_READ` は含めない。ROLE_VIEWER も持つ設計だからである
 * (V2__seed_roles_and_permissions.sql。閲覧者はユーザー一覧を見られる)。
 * 「管理者専用ページに入れるか」と「ユーザーを閲覧できるか」は別の話である。
 */
const ADMIN_ONLY_PERMISSIONS = ['USER_CREATE', 'USER_DELETE', 'ROLE_MANAGE', 'SYSTEM_CONFIG'];

/** 誰でも持つ閲覧権限。これが欠けていたらロールの割り当て自体が壊れている。 */
const VIEWER_PERMISSIONS = ['POST_READ', 'SITE_READ'];

Then(
  '管理者だけが管理系の権限を持ち、一般ユーザーは閲覧権限だけを持つ',
  async ({ request }) => {
    const adminPermissions = await fetchPermissions(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
    const userPermissions = await fetchPermissions(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);

    for (const permission of ADMIN_ONLY_PERMISSIONS) {
      expect(adminPermissions, `管理者に ${permission} が無い`).toContain(permission);
      expect(userPermissions, `一般ユーザーに ${permission} が付いている`).not.toContain(permission);
    }
    for (const permission of VIEWER_PERMISSIONS) {
      expect(userPermissions, `一般ユーザーに ${permission} が無い`).toContain(permission);
    }
  }
);

/** Playwright の `request` フィクスチャの型。ステップ定義の引数から導出せず直接指定する。 */
type RequestFixture = APIRequestContext;

async function fetchPermissions(
  request: RequestFixture,
  email: string,
  password: string
): Promise<string[]> {
  const token = await fetchAccessToken(request, email, password);
  const response = await request.get('/api/identity/me/permissions', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(response.ok(), `権限の取得に失敗 (${email}, status=${response.status()})`).toBe(true);
  return ((await response.json()) as { permissions: string[] }).permissions;
}

// ----------------------------------------------------------------- 認証ゲート

/** 改竄したJWT。ヘッダとペイロードは本物の形だが、署名が別物。 */
const TAMPERED_JWT =
  'eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9'
  + '.eyJzdWIiOiJ0YW1wZXJlZCIsImlzcyI6Imh0dHBzOi8vbG9jYWxob3N0L2F1dGgvcmVhbG1zL2xldHNibG9nIn0'
  + '.aW52YWxpZC1zaWduYXR1cmUtZm9yLWFjY2VwdGFuY2UtdGVzdA';

/** 他レルム(letsblog ではない issuer)のJWT。署名以前に issuer が合わない。 */
const OTHER_REALM_JWT =
  'eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9'
  + '.eyJzdWIiOiJvdGhlciIsImlzcyI6Imh0dHBzOi8vbG9jYWxob3N0L2F1dGgvcmVhbG1zL290aGVyLXJlYWxtIn0'
  + '.b3RoZXItcmVhbG0tc2lnbmF0dXJlLWZvci1hY2NlcHRhbmNlLXRlc3Q';

function probeAllServices(authorization?: string): Record<DomainService, number> {
  const result = {} as Record<DomainService, number>;
  for (const service of DOMAIN_SERVICES) {
    result[service] = requestServiceDirectly(service, AUTH_GATED_PATHS[service], {
      rawAuthorization: authorization,
    }).status;
  }
  return result;
}

When('全ドメインサービスへJWT無しで直接アクセスする', async ({ ctx }) => {
  ctx.gateResults = probeAllServices();
});

When('全ドメインサービスへ改竄したJWTで直接アクセスする', async ({ ctx }) => {
  ctx.gateResults = probeAllServices(`Bearer ${TAMPERED_JWT}`);
});

When('全ドメインサービスへ他レルムのJWTで直接アクセスする', async ({ ctx }) => {
  ctx.gateResults = probeAllServices(`Bearer ${OTHER_REALM_JWT}`);
});

When('全ドメインサービスへ管理者のJWTで直接アクセスする', async ({ ctx, request }) => {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  ctx.gateResults = probeAllServices(`Bearer ${token}`);
});

Then('すべてのサービスが401を返す', async ({ ctx }) => {
  const results = ctx.gateResults as Record<DomainService, number>;
  const passedThrough = Object.entries(results).filter(([, status]) => status !== 401);
  expect(
    passedThrough,
    '認証ゲートを素通りしたサービスがある(ADR-0008)。'
      + `結果: ${JSON.stringify(results)}`
  ).toEqual([]);
});

Then('どのサービスも401を返さない', async ({ ctx }) => {
  const results = ctx.gateResults as Record<DomainService, number>;
  const rejected = Object.entries(results).filter(([, status]) => status === 401);
  expect(
    rejected,
    `有効なJWTを拒否したサービスがある。結果: ${JSON.stringify(results)}`
  ).toEqual([]);
});

// ----------------------------------------------------------------- 初回セットアップ

Given('システムにユーザーが1人も居ない', async ({ request }) => {
  const status = await fetchSetupStatus(request);
  // ここは「まだセットアップしていないこと」を確かめるだけで、状態を作りには行かない。
  // 状態を作るのは scripts/reset-acceptance-env.sh(#945)であり、その段取りを
  // シナリオ側に二重定義しないため。
  expect(
    status.needsSetup,
    'ユーザーが既に存在します。この段階(@stage:setup)はリセット直後にしか成立しません。'
      + ' `npm run test:at:clean` で実行してください(docs/ACCEPTANCE_TESTING.md §10)。'
  ).toBe(true);
});

Given('システムにユーザーが存在する', async ({ request }) => {
  const status = await fetchSetupStatus(request);
  expect(
    status.needsSetup,
    'ユーザーが1人も居ません。初回セットアップの再実行拒否は、誰か居る状態でしか検証できません。'
  ).toBe(false);
});

When('トップページを開く', async ({ page }) => {
  await page.goto('/');
});

Then('初回セットアップ画面へ誘導される', async ({ page }) => {
  await page.waitForURL(/\/setup/, { timeout: 15000 });
});

/**
 * issue #1280: このステップは以前、パスワード欄の`fill()`が「送信までに値を失った」ように
 * 見えるタイムアウト(ブラウザのHTML5必須入力検証「Please fill out this field.」)で落ちて
 * いた。スクリーンショットではメールアドレス欄は正しく埋まっているのに、パスワード欄だけが
 * 空だった。原因は `fill()` とハイドレーションの競合(製品側)ではなく、
 * `SETUP_ADMIN_PASSWORD`(`E2E_PROVISION_ADMIN_PASSWORD` / `E2E_ADMIN_PASSWORD` が未設定なら
 * 空文字列に解決される)を**そのまま**`fill()`していたこと(シナリオ側)だった。
 * `SETUP_ADMIN_EMAIL`はハードコードされた既定値へフォールバックする(helpers.ts の
 * `E2E_ADMIN_EMAIL`)ため常に埋まるが、パスワードには既定値が無く、空文字列で`fill('')`
 * すると入力欄は本当に空のままになり、必須入力検証が送信をブロックしたままステップが
 * 30秒でタイムアウトする。「値が失われた」ように見えたのは、実際には最初から空だった
 * ためである(CPUを6倍・ネットワークを400ms/50KB/sへ絞った条件下でも、埋めた値が
 * 勝手に消えることは確認できなかった)。
 * ここで早期に失敗させることで、後続の30秒タイムアウトと紛らわしいスクリーンショットの
 * 代わりに、原因がひと目で分かるメッセージを出す。
 */
When('初回セットアップ画面から最初の管理者を作成する', async ({ page, ctx }) => {
  expect(
    SETUP_ADMIN_PASSWORD,
    'E2E_PROVISION_ADMIN_PASSWORD / E2E_ADMIN_PASSWORD が未設定(または空文字列)のため、'
      + '初回セットアップの管理者パスワードが空文字列になっている。この状態で送信すると'
      + 'ブラウザの必須入力検証(HTML5 required)で送信がブロックされたまま30秒タイムアウト'
      + 'し、原因が分かりにくい(#1280)。資格情報を環境変数で設定してから再実行すること'
      + '(docs/e2e-testing.md §3.2)。'
  ).not.toBe('');
  await page.goto('/setup');
  await page.locator('input[type="email"]').fill(SETUP_ADMIN_EMAIL);
  // パスワード欄は「パスワード」と「確認用」の2つ。両方同じ値を入れる。
  const passwordInputs = page.locator('input[type="password"]');
  const count = await passwordInputs.count();
  for (let i = 0; i < count; i += 1) {
    await passwordInputs.nth(i).fill(SETUP_ADMIN_PASSWORD);
  }
  await page.locator('button[type="submit"]').click();
  ctx.setupAdminEmail = SETUP_ADMIN_EMAIL;
});

/**
 * issue #1051: `<input minlength="8">`はHTML5の制約検証(JS不要・ブラウザ組み込み)で守られて
 * いるため、それより短い値では送信自体がブロックされ、サーバーへ到達する前に終わってしまう。
 * 8文字の空白はminlengthを満たしたまま送信を通過し、サーバー側の`.trim()`で空文字列になって
 * バリデーションエラーを返す。この経路ならアカウントを作らずに(=「ユーザーが1人も居ない」
 * 前提を消費せずに)サーバーの応答まで確認できる。
 */
const NO_JS_BLANK_PASSWORD = '        ';

When('JavaScriptを無効にして初回セットアップ画面から空白だけのパスワードで送信する', async ({ page, ctx }) => {
  const browser = page.context().browser();
  if (!browser) {
    throw new Error('ブラウザインスタンスを取得できない(JS無効コンテキストを作成できない)');
  }
  const noJsContext = await browser.newContext({ ignoreHTTPSErrors: true, javaScriptEnabled: false });
  const noJsPage = await noJsContext.newPage();
  await noJsPage.goto('/setup');
  await noJsPage.locator('input[name="email"]').fill(`nojs-setup-${Date.now()}@letsblog.local`);
  await noJsPage.locator('input[name="password"]').fill(NO_JS_BLANK_PASSWORD);
  await noJsPage.locator('button[type="submit"]').click();
  await noJsPage.waitForLoadState('load');
  ctx.noJsPage = noJsPage;
  ctx.noJsContext = noJsContext;
});

Then('入力エラーが画面に表示される', async ({ ctx }) => {
  const noJsPage = ctx.noJsPage as Page;
  await expect(
    noJsPage.getByText('メールアドレスとパスワードを入力してください。'),
    'JS無効での送信後にサーバーのバリデーションエラーが表示されない(無反応な画面のままの疑い)'
  ).toBeVisible({ timeout: 15000 });
});

Then('送信後のURLにパスワードが含まれない', async ({ ctx }) => {
  const noJsPage = ctx.noJsPage as Page;
  try {
    const url = noJsPage.url();
    expect(
      url,
      `JS無効時の送信後URLにpassword=が含まれている(GETフォールバックでクエリ文字列に漏れた疑い): ${url}`
    ).not.toContain('password=');
  } finally {
    await (ctx.noJsContext as BrowserContext).close();
  }
});

Then('管理者アカウントを作成した旨が表示される', async ({ page }) => {
  await expect(
    page.getByText('管理者アカウントを作成しました'),
    'セットアップの成功表示が出ない。フォームのエラー表示を確認すること'
  ).toBeVisible({ timeout: 30000 });
});

Then('作成した管理者でログインすると管理者専用ページへ入れる', async ({ page }) => {
  // helpers.ts の loginViaKeycloak と同じ理由(issue #1017)で、goto を load 完了まで
  // 待たせない。/login からのクライアント側リダイレクトが進行中の goto を中断しうるため。
  await page.goto('/login', { waitUntil: 'commit' });
  await expect(page).toHaveURL(new RegExp(`${REALM_BASE}/`), { timeout: 30000 });
  await page.waitForLoadState('load');

  // issue #1295: SETUP_ADMIN_EMAIL(既定は共有E2E_ADMIN_EMAIL)へ実際にKeycloak認証を
  // 送る唯一の直書き経路。@stage:setup @mode:serialで他と並列には走らないが、
  // Requirement 3の検査に「恒久的な例外」を1件残さないため、fetchAccessToken/
  // loginViaKeycloakと同じアカウント単位ロックを経由させる。
  // e2e-login-guard:locked
  await withAccountLock(SETUP_ADMIN_EMAIL, async () => {
    await page.locator('#username').fill(SETUP_ADMIN_EMAIL);
    await page.locator('#password').fill(SETUP_ADMIN_PASSWORD);
    await page.locator('#kc-login').click();
  });

  await completeKeycloakProfileIfPrompted(page);

  // ログイン後のコールバック(Keycloak → /api/auth/callback/keycloak → /)が完了して
  // からでないと、直後の goto('/users') が進行中のクライアント側リダイレクトと競合し
  // /users ではなく / に着地することがある(#1078・#1017と同種の競合)。
  await expect(page).toHaveURL('/', { timeout: 30000 });
  await page.waitForLoadState('load');

  await page.goto('/users', { waitUntil: 'commit' });
  await expect(
    page,
    '初回セットアップで作った管理者が管理者専用ページへ入れない'
  ).toHaveURL(/\/users$/, { timeout: 15000 });
});

/**
 * Keycloak のプロフィール補完画面(VERIFY_PROFILE)が出たら埋める。
 *
 * このレルムでは required action の VERIFY_PROFILE が有効で、identity-service の
 * KeycloakAdminClient#createUser は firstName/lastName を送らない。そのため
 * 初回セットアップで作られた管理者は、**初回ログイン時にプロフィール補完を求められる**。
 * これは Keycloak の意図した挙動であり不具合ではないが、シナリオはここを通過できないと
 * 先へ進めないので、出たら埋める(#945 で seed 側が API 経由の同じ問題を回避しているのと対)。
 */
async function completeKeycloakProfileIfPrompted(page: Page): Promise<void> {
  const firstName = page.locator('#firstName');
  if (!(await firstName.isVisible({ timeout: 5000 }).catch(() => false))) {
    return;
  }
  await firstName.fill('E2E');
  await page.locator('#lastName').fill('Setup Admin');
  await page.locator('input[type="submit"], button[type="submit"]').first().click();
}

Then('初回セットアップが不要な状態になる', async ({ request }) => {
  const status = await fetchSetupStatus(request);
  expect(status.needsSetup, 'セットアップ後も needsSetup が true のまま').toBe(false);
});

When('未認証で初回セットアップAPIを呼ぶ', async ({ request, ctx }) => {
  // 認証ヘッダを付けない。ここが通ってしまうと、誰でも管理者を作れる。
  const email = `intruder-${Date.now()}@letsblog.local`;
  ctx.intruderEmail = email;
  const response = await request.post('/api/auth/setup', {
    headers: { 'Content-Type': 'application/json' },
    data: { email, password: 'IntruderPassword123!' },
  });
  ctx.setupResponseStatus = response.status();
  ctx.setupResponseBody = await response.text();
});

Then('初回セットアップは拒否される', async ({ ctx }) => {
  const status = ctx.setupResponseStatus as number;
  expect(
    status >= 400,
    'ユーザーが居る状態で POST /api/auth/setup が成功した。'
      + '未認証で管理者を作れる状態であり、重大な認可の穴である。'
      + ` status=${status} body=${String(ctx.setupResponseBody)}`
  ).toBe(true);
});

Then(
  '呼び出しに使ったメールアドレスのユーザーは作成されていない',
  async ({ request, ctx }) => {
    const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
    const response = await request.get('/api/users', {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(response.ok(), `ユーザー一覧の取得に失敗 (status=${response.status()})`).toBe(true);
    const body = (await response.json()) as unknown;
    const users = Array.isArray(body) ? body : ((body as { content?: unknown[] }).content ?? []);
    const emails = (users as { email?: string }[]).map((u) => u.email);
    expect(emails, '拒否されたはずのユーザーが作成されている').not.toContain(ctx.intruderEmail);
  }
);

async function fetchSetupStatus(request: RequestFixture): Promise<{ needsSetup: boolean }> {
  const response = await request.get('/api/auth/setup-status');
  expect(
    response.ok(),
    `GET /api/auth/setup-status が ${response.status()} を返した。`
      + 'このパスは未認証で到達できなければならない(ADR-0008 / #713)。'
      + 'gateway が古い転送先を掴んでいる可能性がある(#951)。'
  ).toBe(true);
  return (await response.json()) as { needsSetup: boolean };
}

// ----------------------------------------------------------------- Device Code

interface DeviceAuthorization {
  device_code: string;
  user_code: string;
  verification_uri: string;
  verification_uri_complete?: string;
  expires_in: number;
  interval: number;
}

async function requestDeviceAuthorization(request: RequestFixture): Promise<DeviceAuthorization> {
  const response = await request.post(
    `${REALM_BASE}/protocol/openid-connect/auth/device`,
    { form: { client_id: DEVICE_CLIENT_ID } }
  );
  expect(
    response.ok(),
    `デバイス認可要求に失敗 (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as DeviceAuthorization;
}

async function pollDeviceToken(
  request: RequestFixture,
  deviceCode: string
): Promise<{ status: number; body: Record<string, unknown> }> {
  const response = await request.post(`${REALM_BASE}/protocol/openid-connect/token`, {
    form: {
      grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
      device_code: deviceCode,
      client_id: DEVICE_CLIENT_ID,
    },
  });
  return {
    status: response.status(),
    body: (await response.json()) as Record<string, unknown>,
  };
}

Step('デバイス認可を要求する', async ({ request, ctx }) => {
  ctx.deviceAuthorization = await requestDeviceAuthorization(request);
});

Then('ユーザーコードと確認URLが返る', async ({ ctx }) => {
  const auth = ctx.deviceAuthorization as DeviceAuthorization;
  expect(auth.device_code, 'device_code が無い').toBeTruthy();
  expect(auth.user_code, 'user_code が無い').toBeTruthy();
  expect(auth.verification_uri, 'verification_uri が無い').toContain(REALM_BASE);
  expect(auth.expires_in, 'expires_in が正でない').toBeGreaterThan(0);
});

When('承認しないままトークンを要求する', async ({ request, ctx }) => {
  const auth = ctx.deviceAuthorization as DeviceAuthorization;
  ctx.deviceTokenResult = await pollDeviceToken(request, auth.device_code);
});

Then('authorization_pending が返る', async ({ ctx }) => {
  const result = ctx.deviceTokenResult as { status: number; body: Record<string, unknown> };
  // 拡張(apps/extension/src/deviceAuth.ts)はこの値を見てポーリングを続ける。
  // 別のエラーに変わると、拡張は待たずに失敗として扱う。
  expect(result.body.error, `期待と違うエラー: ${JSON.stringify(result.body)}`)
    .toBe('authorization_pending');
});

// issue #1266: 承認完了後に Keycloak が実際に遷移する成功画面のURL。
// (`/protocol/openid-connect/...` ではなく `/device/status`。認可コード付与後、
// 「Device Login Successful / You may close this browser window」を表示するだけの
// 静的な確認画面で、拡張はブラウザではなくポーリングでトークンを受け取るため、
// この画面自体をシナリオが操作することはない。)
const DEVICE_STATUS_URL_PATTERN = /\/protocol\/openid-connect\/auth\/device\/status(\?|$)|\/device\/status(\?|$)/;

async function loginIfPromptedForDeviceCode(page: Page): Promise<void> {
  const usernameField = page.locator('#username');
  if (await usernameField.isVisible({ timeout: 5000 }).catch(() => false)) {
    // issue #1295: helpers.tsのloginViaKeycloakを通らない直書きのブラウザログイン。
    // 同じアカウント単位ロックで囲み、機構1(quick login)への対処を経路によらず揃える。
    // e2e-login-guard:locked
    // レビュー差し戻し(note 8148): これも実フォーム送信を伴う対話ログインなので、
    // loginViaKeycloak(helpers.ts)と同じ120秒のロック待ちタイムアウトに揃える。
    await withAccountLock(
      E2E_ADMIN_EMAIL,
      async () => {
        await usernameField.fill(E2E_ADMIN_EMAIL);
        await page.locator('#password').fill(E2E_ADMIN_PASSWORD);
        // e2e-login-guard:locked
        await page.locator('#kc-login').click();
      },
      { timeoutMs: 120_000 }
    );
  }
}

When('管理者がブラウザでデバイス認可を承認する', async ({ page, ctx }) => {
  const auth = ctx.deviceAuthorization as DeviceAuthorization;
  // verification_uri_complete はユーザーコードを埋め込んだURL。拡張もこれを開く。
  const verificationUrl = auth.verification_uri_complete ?? auth.verification_uri;
  await page.goto(verificationUrl);

  // 未ログインならログイン画面が出る。ログイン済みなら承認画面へ直行する。
  await loginIfPromptedForDeviceCode(page);

  // ユーザーコードの入力を求められる場合(verification_uri を開いたとき)に備える。
  const codeField = page.locator('#device-user-code');
  if (await codeField.isVisible({ timeout: 3000 }).catch(() => false)) {
    await codeField.fill(auth.user_code);
    await page.locator('input[type="submit"], button[type="submit"]').first().click();
  }

  // 承認ボタン(Keycloak の同意画面。name="accept" が付く)。
  const approve = page.locator('#kc-login, input[name="accept"], button[name="accept"]').first();
  await expect(approve, 'デバイス認可の承認ボタンが見つからない').toBeVisible({ timeout: 15000 });
  await approve.click();

  // issue #1266: 原因は「画面遷移の誤認識」だった。承認完了を「承認ボタンが消えたこと」
  // (`toBeHidden()`)で判定していたが、この同意画面の承認ボタンは `#kc-login` にも
  // 一致する(通常のログイン送信ボタンと同じidを共有元セレクタに含めているため)。
  // 受け入れテストは e2e-admin という同一アカウントを並列実行の複数シナリオ間で共有して
  // おり、負荷が高い状況では承認クリック直後に Keycloak がセッション競合により
  // ログイン再認証画面(`/login-actions/authenticate?...`)へフローを差し戻すことが
  // 実際に観測された(#1266 調査ログ)。この再認証画面の "Sign In" ボタンも同じ
  // id="kc-login" を持つため、`toBeHidden()` は「まだ同じ承認ボタンが残っている」と
  // 誤認識し、実際には全く別の画面(ログイン画面)に戻っているのに15秒間ずっと
  // "visible" と判定してタイムアウトしていた。
  //
  // 完了判定を、承認ボタンという同意画面内のDOM要素の状態ではなく、Keycloakが実際に
  // 遷移する完了画面のURL(`/device/status`)で行うよう変更する。ログイン画面へ
  // 差し戻された場合は、そのURLへは決して遷移しないため誤検知が起きず、再ログインして
  // 承認をやり直すことでセッション競合を吸収できる。
  try {
    await page.waitForURL(DEVICE_STATUS_URL_PATTERN, { timeout: 15000 });
  } catch {
    // セッション競合でログイン画面へ差し戻された場合の救済(#1266 調査で確認済み)。
    // 再ログインし、承認ボタンをもう一度クリックしてから完了画面への遷移を待つ。
    await loginIfPromptedForDeviceCode(page);
    const approveAgain = page
      .locator('#kc-login, input[name="accept"], button[name="accept"]')
      .first();
    await expect(approveAgain, 'デバイス認可の承認ボタン(再ログイン後)が見つからない').toBeVisible({
      timeout: 15000,
    });
    await approveAgain.click();
    await page.waitForURL(DEVICE_STATUS_URL_PATTERN, { timeout: 15000 });
  }
});

When('トークンを要求する', async ({ request, ctx }) => {
  const auth = ctx.deviceAuthorization as DeviceAuthorization;
  ctx.deviceTokenResult = await pollDeviceToken(request, auth.device_code);
});

Then('アクセストークンが取得できる', async ({ ctx }) => {
  const result = ctx.deviceTokenResult as { status: number; body: Record<string, unknown> };
  expect(
    result.body.access_token,
    `アクセストークンが返らなかった: ${JSON.stringify(result.body)}`
  ).toBeTruthy();
});

When('存在しないデバイスコードでトークンを要求する', async ({ request, ctx }) => {
  ctx.deviceTokenResult = await pollDeviceToken(request, 'e2e-nonexistent-device-code');
});

Then('デバイスコードが無効である旨のエラーが返る', async ({ ctx }) => {
  const result = ctx.deviceTokenResult as { status: number; body: Record<string, unknown> };
  expect(result.status, 'エラーにならなかった').toBeGreaterThanOrEqual(400);
  // Keycloak は未知/期限切れのデバイスコードをまとめて invalid_grant で返す。
  // 拡張(deviceAuth.ts)は authorization_pending / slow_down 以外を失敗として扱うため、
  // ここで見たいのは「待ち続けずに失敗と分かること」である。
  expect(
    ['invalid_grant', 'expired_token'],
    `期待と違うエラー: ${JSON.stringify(result.body)}`
  ).toContain(result.body.error);
});

// ----------------------------------------------------------------- トークンのライフサイクル

Given('一般ユーザーのアクセストークンを取得する', async ({ request, ctx }) => {
  ctx.userToken = await fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
});

/**
 * issue #1295(利用者の決定(a)): 「無効化したユーザーの発行済みアクセストークンは
 * 拒否される」(token-lifecycle.feature、`@destructive`)は、共有アカウント自体を
 * 無効化・再有効化する(`auth.steps.ts`旧実装)ため、意図的失敗こそ無いものの
 * 共有アカウントに実害(再有効化漏れによる巻き添え)が及びうる経路だった。
 * `userDeactivation.steps.ts`(#1158)が既に確立している「使い捨てアカウントを作って
 * 無効化・振る舞いを確かめる」手法を、このシナリオにもそのまま適用する。
 */
Given(
  'トークンライフサイクル検証用の使い捨てアカウントを作成し、アクセストークンを取得する',
  async ({ request, ctx }) => {
    const suffix = uniqueSuffix();
    const email = `e2e-1295-tokenlifecycle-${suffix}@example.com`;
    const password = `E2e1295TokenLc!${suffix}`;

    const adminToken = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
    const created = await request.post('/api/users', {
      headers: { Authorization: `Bearer ${adminToken}` },
      data: { email, password, role: 'user' },
    });
    expect(
      created.ok(),
      `検証用アカウントの作成に失敗しました (status=${created.status()}): ${await created.text()}`
    ).toBe(true);

    // POST /api/users の password はローカルDBにしか反映されない(userDeactivation.steps.ts
    // 冒頭のコメント参照)ため、実際にログイン・トークン取得できるようKeycloak側の資格情報を
    // 別途整える。
    provisionLoginableKeycloakCredential(email, password);

    ctx.tokenLifecycleEmail = email;
    ctx.tokenLifecyclePassword = password;
    ctx.userId = ((await created.json()) as { id: number }).id;
    // e2e-login-guard:disposable — 直前で作った使い捨てアカウント(issue #1295)。
    ctx.userToken = await fetchAccessToken(request, email, password);
  }
);

Given('そのトークンで保護APIへアクセスできる', async ({ request, ctx }) => {
  const response = await request.get('/api/identity/me', {
    headers: { Authorization: `Bearer ${ctx.userToken as string}` },
  });
  expect(response.ok(), `無効化前のアクセスが失敗した (status=${response.status()})`).toBe(true);
  ctx.userId = ((await response.json()) as { id: number }).id;
});

When('管理者がそのユーザーを無効化する', async ({ request, ctx }) => {
  const adminToken = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  ctx.adminToken = adminToken;
  const response = await request.post(`/api/users/${ctx.userId as number}/deactivate`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  });
  expect(response.ok(), `無効化に失敗 (status=${response.status()}): ${await response.text()}`)
    .toBe(true);
});

Then('同じトークンでの保護APIアクセスが拒否される', async ({ request, ctx }) => {
  // #816: 無効化しても、発行済みアクセストークンが期限切れになるまで(最大5分)
  // 通り続けていた。無効化は「次のトークン発行を止める」だけでなく
  // 「発行済みトークンを無効にする」ことでなければならない。
  await expect(async () => {
    const response = await request.get('/api/identity/me', {
      headers: { Authorization: `Bearer ${ctx.userToken as string}` },
    });
    expect(
      response.status(),
      '無効化したユーザーの発行済みトークンがまだ通っている(#816)'
    ).toBeGreaterThanOrEqual(400);
  }).toPass({ timeout: 30_000, intervals: [1000, 2000, 3000] });
});

Then('無効化したユーザーは新しくトークンを取得できない', async ({ request, ctx }) => {
  const response = await request.post(
    `${REALM_BASE}/protocol/openid-connect/token`,
    {
      form: {
        // e2e-login-guard:disposable — issue #1295: このシナリオが自分で作った使い捨て
        // アカウント(ctx.tokenLifecycleEmail)。共有アカウントには一切触れない。
        grant_type: 'password',
        client_id: E2E_CLIENT_ID,
        username: ctx.tokenLifecycleEmail as string,
        password: ctx.tokenLifecyclePassword as string,
      },
    }
  );
  expect(
    response.status(),
    '無効化したユーザーが新しいトークンを取得できてしまった'
  ).toBeGreaterThanOrEqual(400);

  // issue #1295: このシナリオは使い捨てアカウントを使うため、共有アカウントを再有効化する
  // 後始末は不要になった。使い捨てアカウント自体の削除は After({tags: '@auth'}) で行う。
});

When('不正なセッションで保護ページを開く', async ({ page }) => {
  // NextAuth のセッションクッキーに壊れた値を入れる。復号できないセッションは
  // 「未認証」として扱われなければならない(復号失敗を無視して素通りさせない)。
  await page.context().addCookies([
    {
      name: 'next-auth.session-token',
      value: 'e2e-invalid-session-token',
      domain: 'localhost',
      path: '/',
    },
    {
      name: '__Secure-next-auth.session-token',
      value: 'e2e-invalid-session-token',
      domain: 'localhost',
      path: '/',
      secure: true,
    },
  ]);
  await page.goto('/');
});

Then('認証を求められる', async ({ page }) => {
  await page.waitForURL(new RegExp(`/(login|${REALM_BASE.slice(1)})`), { timeout: 15000 });
});

// ----------------------------------------------------------------- アクセストークンの寿命(issue #1053)

/**
 * Keycloakのrealm設定(infra/keycloak/realm-export.jsonのaccessTokenLifespan)と同じ値。
 * apps/web/src/lib/tokenRefreshPolicy.ts の ACCESS_TOKEN_LIFESPAN_SECONDS と揃えること
 * (揃っているかはJestの単体テスト tokenRefreshPolicy.test.ts が固定する。ここは
 * 「寿命を実時間で跨ぐ」ための待機時間としてのみ使うので、e2e からプロダクションコードを
 * importはしない)。
 */
const ACCESS_TOKEN_LIFESPAN_SECONDS = 300;

/**
 * 待機時間の安全マージン(秒)。gateway(Spring Security Resource Server)のJWT検証は
 * `JwtTimestampValidator` の既定クロックスキュー60秒を持つため、寿命ちょうどで待つと
 * 実際にはまだ許容範囲内で通ってしまい、シナリオが偽陰性(本来落ちるべきなのに通る)になる。
 * 実測(#1053): 30秒のマージンでは修正前のコードでもシナリオが通ってしまった。
 * 60秒のクロックスキューを確実に超えるよう倍のマージンを取る。
 */
const GATEWAY_CLOCK_SKEW_MARGIN_SECONDS = 120;

async function ensureTokenLifecycleProject(request: APIRequestContext, ctx: Record<string, unknown>): Promise<void> {
  if (ctx.tlcProjectId !== undefined) {
    return;
  }
  const adminToken = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  ctx.tlcAdminToken = adminToken;

  const project = await createFixtureProject(request, adminToken, 'at1053-tlc');
  ctx.tlcProjectId = project.id;
  ctx.tlcProjectName = project.name;

  const siteKey = `e2e-at1053-tlc-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  const siteResponse = await request.post('/api/sites', {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: {
      name: `E2E at1053 tlc site ${siteKey}`,
      siteKey,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'AGENT',
        baseUrl: 'http://wordpress',
        username: 'at1053-tlc-fixture',
        appPassword: 'at1053-tlc fixture app password',
      },
    },
  });
  expect(
    siteResponse.ok(),
    `フィクスチャのサイト登録に失敗しました (status=${siteResponse.status()}): ${await siteResponse.text()}`
  ).toBe(true);
  ctx.tlcSiteId = ((await siteResponse.json()) as { id: number }).id;
  ctx.tlcSiteKey = siteKey;
}

Given('プロジェクトとサイトが登録されている', async ({ request, ctx }) => {
  await ensureTokenLifecycleProject(request, ctx);
});

/**
 * 実時間でアクセストークンの寿命(ACCESS_TOKEN_LIFESPAN_SECONDS)を跨いで待つ(issue #1053)。
 *
 * 「同じタブで待機する」がこのシナリオの要である。SessionProvider(apps/web/src/app/
 * SessionProvider.tsx)の再取得間隔(ACCESS_TOKEN_REFETCH_INTERVAL_SECONDS)によるポーリングは
 * 開いているタブの中で動くため、タブを開いたまま実時間で待つことで、修正後の実装が実際に
 * 寿命前にCookieを更新し続けることを検証できる。修正前(develop)はこの猶予が無いため、
 * 寿命(300秒)を過ぎてから次の再取得(240秒周期)までの窓で失効済みトークンが残り、
 * 後続の操作が401になる。
 */
Step('アクセストークンの寿命を超える時間、同じタブで待機する', async ({ page }) => {
  await page.waitForTimeout((ACCESS_TOKEN_LIFESPAN_SECONDS + GATEWAY_CLOCK_SKEW_MARGIN_SECONDS) * 1000);
});

const TLC_ENVIRONMENT_LABEL = 'テスト環境に紐付けるサイト';

function tlcEnvironmentSlot(page: Page) {
  return page
    .locator('div.rounded-lg', { has: page.getByRole('heading', { name: 'テスト環境' }) })
    .first();
}

When('プロジェクトの環境にサイトを紐付ける', async ({ page, request, ctx }) => {
  await ensureTokenLifecycleProject(request, ctx);
  const projectId = ctx.tlcProjectId as number;
  const siteId = ctx.tlcSiteId as number;

  await page.goto(`/projects/${projectId}`);
  await page.waitForLoadState('load');

  // requireAdminSession()(apps/web/src/lib/session.ts)が再ログインへ誘導した場合、
  // /projects/{id}には留まらない(シナリオ3: リフレッシュトークンが使えない場合)。
  // その場合はフォームが存在しないため、ここでは操作せず終える
  // (再ログイン導線が出たことの検証はThenステップ側で行う)。
  if (!page.url().includes(`/projects/${projectId}`)) {
    return;
  }

  await page.locator(`select[aria-label="${TLC_ENVIRONMENT_LABEL}"]`).selectOption(String(siteId));
  await tlcEnvironmentSlot(page).locator('button:has-text("紐付ける")').click();
});

Then('紐付けは成功する', async ({ page, ctx }) => {
  const siteKey = ctx.tlcSiteKey as string;
  await expect(tlcEnvironmentSlot(page).getByText(siteKey, { exact: true })).toBeVisible({ timeout: 10000 });
});

Then('「APIエラー \\(401)」を含むメッセージは表示されない', async ({ page }) => {
  await expect(page.getByText('APIエラー (401)')).toHaveCount(0);
});

When('プロジェクト詳細を開く', async ({ page, request, ctx }) => {
  await ensureTokenLifecycleProject(request, ctx);
  const projectId = ctx.tlcProjectId as number;
  await page.goto(`/projects/${projectId}`);
});

Then('プロジェクト名とメンバー一覧が表示される', async ({ page, ctx }) => {
  const projectName = ctx.tlcProjectName as string;
  await expect(page.locator('input[name="name"]')).toHaveValue(projectName, { timeout: 10000 });

  await page.locator('button:has-text("メンバー")').click();
  await expect(page.getByText('メールアドレス')).toBeVisible({ timeout: 10000 });
});

/** NextAuthのセッションCookie名。webのNEXTAUTH_URLはhttps固定のためSecure prefix側を使う。 */
const SESSION_COOKIE_NAME = '__Secure-next-auth.session-token';

/**
 * next-authはセッションCookieが4096バイトを超えると `<name>.0` / `<name>.1` ... へ分割する
 * (`node_modules/next-auth/core/lib/cookie.js` の `SessionStore`)。このJWTはaccessToken/
 * refreshToken/idTokenを含み実際に複数チャンクへ分割される。読み書き両方でこの分割を
 * 再現する必要がある。
 */
function tokenLifecycleChunkSuffix(name: string): number {
  const last = name.split('.').pop() ?? '';
  return /^\d+$/.test(last) ? Number(last) : 0;
}

async function readTokenLifecycleSessionCookie(page: Page) {
  const cookies = await page.context().cookies();
  const chunks = cookies
    .filter((c) => c.name === SESSION_COOKIE_NAME || c.name.startsWith(`${SESSION_COOKIE_NAME}.`))
    .sort((a, b) => tokenLifecycleChunkSuffix(a.name) - tokenLifecycleChunkSuffix(b.name));
  if (chunks.length === 0) {
    throw new Error(
      `セッションCookieが見つからない(先にログインしていること)。実際のCookie名: ${cookies.map((c) => c.name).join(', ')}`
    );
  }
  const value = chunks.map((c) => c.value).join('');
  const secret = getNextAuthSecret();
  const token = await decode({ token: value, secret });
  if (!token) {
    throw new Error('セッションCookieを復号できなかった(NEXTAUTH_SECRETの取得元がwebコンテナと食い違っている疑い)');
  }
  return { template: chunks[0], chunkNames: chunks.map((c) => c.name), secret, token };
}

/** next-authのCHUNK_SIZE( `ALLOWED_COOKIE_SIZE(4096) - ESTIMATED_EMPTY_COOKIE_SIZE(163)` )と同じ値。 */
const SESSION_COOKIE_CHUNK_SIZE = 4096 - 163;

async function writeTokenLifecycleSessionCookie(
  page: Page,
  template: Awaited<ReturnType<typeof readTokenLifecycleSessionCookie>>['template'],
  chunkNames: string[],
  secret: string,
  token: JWT
): Promise<void> {
  const value = await encode({ token, secret });
  const chunkCount = Math.max(1, Math.ceil(value.length / SESSION_COOKIE_CHUNK_SIZE));
  const newCookies = Array.from({ length: chunkCount }, (_, i) => ({
    ...template,
    name: chunkCount === 1 ? SESSION_COOKIE_NAME : `${SESSION_COOKIE_NAME}.${i}`,
    value: value.substring(i * SESSION_COOKIE_CHUNK_SIZE, (i + 1) * SESSION_COOKIE_CHUNK_SIZE),
  }));
  // チャンク数が変わって古いチャンク名が余る場合に備え、既存のチャンクは一度すべて消してから書く。
  await page.context().clearCookies({ name: new RegExp(`^${SESSION_COOKIE_NAME.replace(/[.]/g, '\\.')}(\\.\\d+)?$`) });
  await page.context().addCookies(newCookies.map(({ name, value: v, ...rest }) => ({ ...rest, name, value: v })));
  void chunkNames;
}

const KEYCLOAK_CONTAINER = 'lbs-keycloak';
const KEYCLOAK_REALM = 'letsblog';
const KCADM_BIN = '/opt/keycloak/bin/kcadm.sh';
/** リポジトリルート(apps/web/e2e/steps から4階層上)。`.env`からKeycloakのmaster管理者資格情報を読む。 */
const KCADM_REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

/** userDeactivation.steps.ts と同じ形(docker exec で kcadm.sh を叩く)。ステップ定義ファイルは
 * 兄弟issueと相乗りしない方針のため、小さいこのヘルパーはここに複製する。 */
function readEnvValue(key: string): string {
  const envPath = path.join(KCADM_REPO_ROOT, '.env');
  const content = fs.readFileSync(envPath, 'utf-8');
  const match = content.match(new RegExp(`^${key}=(.*)$`, 'm'));
  if (!match) {
    throw new Error(`.env に ${key} が見つかりません`);
  }
  return match[1].trim();
}

const KCADM_LOCK_RETRY_ATTEMPTS = 5;
const KCADM_LOCK_RETRY_DELAY_MS = 200;

/**
 * issue #1295: `kcadm.sh`はコンテナ内の単一ファイル(`/opt/keycloak/.keycloak/kcadm.config`)
 * にセッションを保存しており、複数ワーカーから同時に`docker exec ... kcadm.sh`を呼ぶと
 * "Failed to get lock on ...kcadm.config"で失敗しうる。これはKeycloakのブルートフォース
 * 検知(本Issueが対象とするアカウント単位ロック)とは無関係な、kcadmコマンド自身の排他
 * である。このIssueで新設した使い捨てアカウント作成(`createLoginableKeycloakUser`・
 * `provisionLoginableKeycloakCredential`)が、既存の`revokeKeycloakSsoSession`(#1053)と
 * 同じ`kcadm`ヘルパーを共有しつつ並列ワーカーで同時に呼ばれるようになったため、
 * 短い再試行で吸収する(根本対策である「kcadm呼び出し自体の直列化」は#1056/#1158の
 * 既存ファイルにも及ぶため本Issueのスコープ外。別途 #1328 で追跡)。
 */
function kcadm(args: string[]): string {
  for (let attempt = 1; ; attempt += 1) {
    try {
      return execFileSync('docker', ['exec', KEYCLOAK_CONTAINER, KCADM_BIN, ...args], {
        encoding: 'utf-8',
        timeout: 30_000,
      });
    } catch (error) {
      const output = `${(error as { stdout?: string }).stdout ?? ''}${(error as { stderr?: string }).stderr ?? ''}`;
      if (attempt >= KCADM_LOCK_RETRY_ATTEMPTS || !output.includes('Failed to get lock')) {
        throw error;
      }
      execFileSync('sleep', [String(KCADM_LOCK_RETRY_DELAY_MS / 1000)]);
    }
  }
}

function kcadmLogin(): void {
  const username = readEnvValue('KEYCLOAK_ADMIN_USERNAME');
  const password = readEnvValue('KEYCLOAK_ADMIN_PASSWORD');
  kcadm([
    'config', 'credentials',
    '--server', 'http://localhost:8080/auth',
    '--realm', 'master',
    '--user', username,
    '--password', password,
  ]);
}

/**
 * KeycloakのSSOセッション自体を終了させる(issue #1053)。
 *
 * Cookieのrefresh_token値を壊すだけでは、KeycloakのSSOセッション自体は生きたままなので、
 * `/login`が呼ぶ`signIn("keycloak")`はKeycloak側で無言のまま自動的に再認証してしまい
 * (ブラウザはKeycloakの有効なSSO Cookieを持っている)、利用者には何も見えないまま新しい
 * セッションへすり替わる。これは実運用の`ssoSessionIdleTimeout`超過(Keycloak側のSSO
 * セッションも同時に失効している)とは違う状態であり、「再ログインを促される」の検証には
 * ならない。SSOセッション自体もここで終了させ、実際にKeycloakのホスト型ログイン画面が
 * 出ることを保証する。
 */
function revokeKeycloakSsoSession(email: string): void {
  kcadmLogin();
  const usersJson = kcadm(['get', 'users', '-r', KEYCLOAK_REALM, '-q', `email=${email}`, '--fields', 'id']);
  const users = JSON.parse(usersJson) as { id: string }[];
  if (users.length === 0) {
    throw new Error(`Keycloakに ${email} が見つかりません`);
  }
  kcadm(['create', `users/${users[0].id}/logout`, '-r', KEYCLOAK_REALM, '-b', '{}']);
}

/**
 * リフレッシュトークンをKeycloakが確実に拒否する値へ差し替える(issue #1053)。
 *
 * 実運用での典型例は `ssoSessionIdleTimeout` 超過だが、それを実時間で再現するのは
 * 非現実的(既定1800秒)なので、Cookie自体を直接decode/re-encodeして壊れたリフレッシュ
 * トークンへ差し替える。あわせてaccessTokenExpiresも過去へ書き換え、次のjwtコールバック
 * (requireAdminSession経由)で確実に更新が試行されるようにする。KeycloakのSSOセッション
 * 自体もここで終了させる(理由は{@link revokeKeycloakSsoSession}参照)。
 */
Given('リフレッシュトークンが使えない状態にする', async ({ page }) => {
  revokeKeycloakSsoSession(E2E_ADMIN_EMAIL);

  const { template, chunkNames, secret, token } = await readTokenLifecycleSessionCookie(page);
  const tampered: JWT = {
    ...token,
    accessTokenExpires: Date.now() - 1000,
    refreshToken: 'e2e-invalidated-refresh-token-1053',
  };
  await writeTokenLifecycleSessionCookie(page, template, chunkNames, secret, tampered);
});

Then('再ログインを促すメッセージが表示される', async ({ page }) => {
  // requireAdminSession()(apps/web/src/lib/session.ts)がsession.errorを見て/loginへ
  // リダイレクトする。/loginはマウント時にKeycloakのホスト型ログイン画面へ即座に遷移する
  // (apps/web/src/app/login/page.tsx)。
  await page.waitForURL(new RegExp(`/(login|${REALM_BASE.slice(1)})`), { timeout: 15000 });
});

Then('「APIエラー \\(401): Unauthorized」という文言は表示されない', async ({ page }) => {
  await expect(page.getByText('APIエラー (401): Unauthorized')).toHaveCount(0);
});

// ------------------------------------------------------ ソフト遷移(issue #1234)

/**
 * ヘッダーの<Link>、またはダッシュボードのカードの<Link>を、名前(可視テキスト)で
 * クリックする。`page.goto()` を使わないのが要点(issue #1234): 利用者の報告は
 * クリックによるクライアント側のソフト遷移では再ログインへ飛ばないというものであり、
 * `page.goto()` はページ全体を再読み込みするため症状を再現しない。
 *
 * ヘッダーのリンクは `title`属性と可視テキストが同じ(HeaderNav.tsx)、ダッシュボードの
 * カードのリンクは「ラベル+件数」を可視テキストに持つ(page.tsx)ため、部分一致の
 * アクセシブルネームで両方を1つのステップで扱える。
 */
/**
 * ヘッダーの`<Link>`はアクセシブルネームがラベルそのもの(`title`属性、HeaderNav.tsx)なので
 * 完全一致(`exact: true`)で特定する。ダッシュボードのカードの`<Link>`はラベルの後ろに
 * 件数が続く(「投稿数0」のように、`page.tsx`)ため部分一致にする必要があり、`exact`は
 * 呼び出し側で選ばせる。
 */
async function clickLinkByName(page: Page, label: string, exact: boolean): Promise<void> {
  await page.getByRole('link', { name: label, exact }).first().click();
}

Step('「ダッシュボード」のリンクをクリックする', async ({ page }) => clickLinkByName(page, 'ダッシュボード', true));
Step('「サイト」のリンクをクリックする', async ({ page }) => clickLinkByName(page, 'サイト', true));
Step('「投稿数」のリンクをクリックする', async ({ page }) => clickLinkByName(page, '投稿数', false));
Step('「生成画像ギャラリー」のリンクをクリックする', async ({ page }) => clickLinkByName(page, '生成画像ギャラリー', true));

Then('「登録サイト数」という文言は表示されない', async ({ page }) => {
  await expect(page.getByText('登録サイト数')).toHaveCount(0);
});

Then('「全0件を表示」という文言は表示されない', async ({ page }) => {
  await expect(page.getByText('全0件を表示')).toHaveCount(0);
});

Then('「生成画像がありません」という文言は表示されない', async ({ page }) => {
  await expect(page.getByText('生成画像がありません', { exact: false })).toHaveCount(0);
});

After({ tags: '@auth' }, async ({ ctx, request }) => {
  const projectId = ctx.tlcProjectId as number | undefined;
  const siteId = ctx.tlcSiteId as number | undefined;
  if (projectId === undefined && siteId === undefined) {
    return;
  }
  const adminToken = (ctx.tlcAdminToken as string | undefined) ?? (await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD));
  const headers = { Authorization: `Bearer ${adminToken}` };
  if (siteId !== undefined) {
    await request.delete(`/api/sites/${siteId}`, { headers });
  }
  if (projectId !== undefined) {
    await request.delete(`/api/projects/${projectId}`, { headers });
  }
});

// issue #1295: 意図的な失敗ログイン検証用に作った使い捨てKeycloakアカウントの後片付け。
After({ tags: '@auth' }, async ({ ctx }) => {
  const keycloakUserId = ctx.wrongPasswordKeycloakUserId as string | undefined;
  if (keycloakUserId === undefined) {
    return;
  }
  deleteKeycloakUser(keycloakUserId);
});

// issue #1295: トークンライフサイクル検証用に作った使い捨てアカウント(ローカルDB+Keycloak
// 双方)の後片付け。UserService#deleteが両方から削除する(userDeactivation.steps.tsと同型)。
After({ tags: '@auth' }, async ({ ctx, request }) => {
  const userId = ctx.userId as number | undefined;
  const email = ctx.tokenLifecycleEmail as string | undefined;
  if (email === undefined || userId === undefined) {
    return;
  }
  const adminToken = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  await request.delete(`/api/users/${userId}`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  });
});
