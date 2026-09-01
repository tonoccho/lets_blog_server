import type { APIRequestContext, Page } from '@playwright/test';
import { Given, Step, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
  loginViaKeycloak,
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

When('一般ユーザーのメールアドレスと誤ったパスワードを入力して送信する', async ({ page }) => {
  await page.waitForURL(new RegExp(`${REALM_BASE}/`), { timeout: 15000 });
  await page.locator('#username').fill(E2E_TEST_EMAIL);
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

When('初回セットアップ画面から最初の管理者を作成する', async ({ page, ctx }) => {
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

Then('管理者アカウントを作成した旨が表示される', async ({ page }) => {
  await expect(
    page.getByText('管理者アカウントを作成しました'),
    'セットアップの成功表示が出ない。フォームのエラー表示を確認すること'
  ).toBeVisible({ timeout: 30000 });
});

Then('作成した管理者でログインすると管理者専用ページへ入れる', async ({ page }) => {
  await page.goto('/login');
  await page.waitForURL(new RegExp(`${REALM_BASE}/`), { timeout: 15000 });
  await page.locator('#username').fill(SETUP_ADMIN_EMAIL);
  await page.locator('#password').fill(SETUP_ADMIN_PASSWORD);
  await page.locator('#kc-login').click();

  await completeKeycloakProfileIfPrompted(page);

  await page.goto('/users');
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

When('管理者がブラウザでデバイス認可を承認する', async ({ page, ctx }) => {
  const auth = ctx.deviceAuthorization as DeviceAuthorization;
  // verification_uri_complete はユーザーコードを埋め込んだURL。拡張もこれを開く。
  const verificationUrl = auth.verification_uri_complete ?? auth.verification_uri;
  await page.goto(verificationUrl);

  // 未ログインならログイン画面が出る。ログイン済みなら承認画面へ直行する。
  const usernameField = page.locator('#username');
  if (await usernameField.isVisible({ timeout: 5000 }).catch(() => false)) {
    await usernameField.fill(E2E_ADMIN_EMAIL);
    await page.locator('#password').fill(E2E_ADMIN_PASSWORD);
    await page.locator('#kc-login').click();
  }

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

  // 承認完了の表示を待つ。文言はロケール依存なので、承認ボタンが消えたことで判断する。
  await expect(approve).toBeHidden({ timeout: 15000 });
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
        grant_type: 'password',
        client_id: E2E_CLIENT_ID,
        username: E2E_TEST_EMAIL,
        password: E2E_TEST_PASSWORD,
      },
    }
  );
  expect(
    response.status(),
    '無効化したユーザーが新しいトークンを取得できてしまった'
  ).toBeGreaterThanOrEqual(400);

  // 後片付け: 以降のシナリオが一般ユーザーを使えるよう元へ戻す。
  // 受け入れテストは毎回リセットから始まる(#945)が、同じ実行の中の他シナリオは
  // このユーザーを使うため、ここで戻さないと後続が巻き添えで落ちる。
  const reactivate = await request.post(`/api/users/${ctx.userId as number}/reactivate`, {
    headers: { Authorization: `Bearer ${ctx.adminToken as string}` },
  });
  expect(reactivate.ok(), `再有効化に失敗 (status=${reactivate.status()})`).toBe(true);
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
