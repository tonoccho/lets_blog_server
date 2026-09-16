import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { expect } from '../support';

/**
 * Keycloak自身のbrute force detectionが、パスワードを検証する唯一の経路
 * (`/auth/realms/letsblog/...`)を守っていることを検証するステップ定義(issue #1056)。
 *
 * gatewayの`RateLimitWebFilter`はこの経路を一度も見ない
 * (`infra/nginx/conf.d/default.conf`の`location /auth/`がKeycloakへ直接転送するため)。
 * 対して`infra/keycloak/realm-export.json`の`bruteForceProtected`は、Keycloakのログイン
 * フォームだけでなくこのシナリオが使うResource Owner Password Credentialsグラント
 * (`grant_type=password`)にも等しく効く——両方とも同じKeycloakの認証処理を通るため。
 *
 * ## なぜ使い捨てアカウントを使うか(userDeactivation.steps.tsと同型)
 *
 * ロックするのはこのシナリオが自分で作った使い捨てアカウントだけであり、共有のE2E固定
 * アカウント(`e2e-*@letsblog.local`)には一切触れない。Keycloakのbrute force detectionは
 * アカウント単位(IPやクライアント単位ではない)なので、他のシナリオが使っている
 * アカウントのログインを妨げることはない。
 *
 * ## なぜ@destructiveを付けるか
 *
 * それでも、このシナリオは実行中に「使い捨てアカウントがロックされた」状態を作る。
 * issue #1056の実装ノートが`@destructive`段階への配置を提案しており、
 * `apps/web/playwright.config.ts`の`at-destructive`のコメントが説明する「共有資源に
 * 一時的な異常状態を作る検証は隔離して最後にまとめて実行する」という方針に従う
 * (`e2e/features/auth/token-lifecycle.feature`の無効化シナリオと同じ考え方)。
 */

const KEYCLOAK_CONTAINER = 'lbs-keycloak';
const KEYCLOAK_REALM = 'letsblog';
const KCADM_BIN = '/opt/keycloak/bin/kcadm.sh';
const E2E_CLIENT_ID = 'letsblog-e2e';

/**
 * `infra/keycloak/realm-export.json`の`failureFactor`と一致させること。
 * ずれると、このシナリオが「設定した回数」より少ない/多い試行でロックを期待してしまい、
 * 実装と無関係な理由で赤/緑が入れ替わる。
 */
const BRUTE_FORCE_FAILURE_FACTOR = 5;

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

function readEnvValue(key: string): string {
  const envPath = path.join(REPO_ROOT, '.env');
  const content = fs.readFileSync(envPath, 'utf-8');
  const match = content.match(new RegExp(`^${key}=(.*)$`, 'm'));
  if (!match) {
    throw new Error(`.env に ${key} が見つかりません`);
  }
  return match[1].trim();
}

function kcadm(args: string[]): string {
  return execFileSync('docker', ['exec', KEYCLOAK_CONTAINER, KCADM_BIN, ...args], {
    encoding: 'utf-8',
    timeout: 30_000,
  });
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

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

/**
 * 実際にKeycloakへログインできる使い捨てユーザーを作る。identity-service経由の
 * `POST /api/users`は使わない(このシナリオはローカルDBのUserを必要としない、
 * Keycloak側の資格情報とbrute force検出だけが対象のため)。
 */
function createLoginableKeycloakUser(email: string, password: string): string {
  kcadmLogin();
  kcadm([
    'create', 'users', '-r', KEYCLOAK_REALM,
    '-s', `username=${email}`,
    '-s', `email=${email}`,
    '-s', 'enabled=true',
    '-s', 'emailVerified=true',
    '-s', 'firstName=E2E',
    '-s', 'lastName=BruteForce',
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

async function attemptLogin(
  request: APIRequestContext,
  email: string,
  password: string
): Promise<{ ok: boolean; status: number; body: { access_token?: string; error?: string; error_description?: string } }> {
  // e2e-login-guard:already-disposable — このファイル全体が使い捨てアカウント専用
  // (issue #1295。ファイル冒頭のコメント「なぜ使い捨てアカウントを使うか」参照)。
  const response = await request.post('/auth/realms/letsblog/protocol/openid-connect/token', {
    form: {
      grant_type: 'password',
      client_id: E2E_CLIENT_ID,
      username: email,
      password,
    },
  });
  return {
    ok: response.ok(),
    status: response.status(),
    body: (await response.json()) as { access_token?: string; error?: string; error_description?: string },
  };
}

// --------------------------------------------------------------- セットアップ

Given('ブルートフォース検証用の使い捨てアカウントを作成する', async ({ ctx }) => {
  const suffix = uniqueSuffix();
  const email = `e2e-1056-bruteforce-${suffix}@example.com`;
  const password = `E2e1056Brute!${suffix}`;
  const keycloakUserId = createLoginableKeycloakUser(email, password);

  ctx.bruteForceEmail = email;
  ctx.bruteForcePassword = password;
  ctx.bruteForceKeycloakUserId = keycloakUserId;
});

Given('そのアカウントは正しいパスワードでログインできる', async ({ request, ctx }) => {
  const email = ctx.bruteForceEmail as string;
  const password = ctx.bruteForcePassword as string;
  const result = await attemptLogin(request, email, password);
  expect(
    result.ok,
    `使い捨てアカウントの初回ログインに失敗しました(前提が崩れています): ${JSON.stringify(result.body)}`
  ).toBe(true);
  expect(result.body.access_token).toBeTruthy();
});

// --------------------------------------------------------------- 実行

When(
  'そのアカウントに対して、設定した失敗回数に達するまで誤ったパスワードで認証を試みる',
  async ({ request, ctx }) => {
    const email = ctx.bruteForceEmail as string;
    for (let attempt = 1; attempt <= BRUTE_FORCE_FAILURE_FACTOR; attempt += 1) {
      const result = await attemptLogin(request, email, 'DefinitelyWrongPassword1!');
      expect(
        result.ok,
        `誤ったパスワードなのにログインが成功しました(${attempt}回目): ${JSON.stringify(result.body)}`
      ).toBe(false);
    }
  }
);

// --------------------------------------------------------------- 検証

Then('正しいパスワードで認証してもアカウントが一時ロックされていて拒否される', async ({ request, ctx }) => {
  const email = ctx.bruteForceEmail as string;
  const password = ctx.bruteForcePassword as string;
  const result = await attemptLogin(request, email, password);
  expect(
    result.ok,
    `設定した回数(${BRUTE_FORCE_FAILURE_FACTOR}回)の失敗の後も、正しいパスワードでログインできてしまいました。` +
      `Keycloakのbrute force detectionが有効になっていないか、閾値が想定と違います: ${JSON.stringify(result.body)}`
  ).toBe(false);
  expect(result.status).toBeGreaterThanOrEqual(400);
  expect(result.body.access_token).toBeUndefined();
});

// --------------------------------------------------------------- 後片付け

After({ tags: '@destructive' }, async ({ ctx }) => {
  const keycloakUserId = ctx.bruteForceKeycloakUserId as string | undefined;
  if (keycloakUserId === undefined) {
    return;
  }
  deleteKeycloakUser(keycloakUserId);
});
