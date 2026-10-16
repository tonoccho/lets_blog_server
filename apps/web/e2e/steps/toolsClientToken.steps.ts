import { execFileSync } from 'node:child_process';
import { Given, Then, When } from './fixtures';
import { expect } from '../support';
import { PLANTUML_STUB_URL, PORTAL_STUB_URL, STUB_URLS, requirePortalStub, requirePlantUmlStub, requireStubs, resetStub } from '../support/stubs';
import { verifyBearer, type Jwks } from '../support/toolsJwt';

/**
 * extensions のツールへ送るトークン(issue #1804)の受け入れテスト。
 *
 * - `auth/tools-client-token.feature`: 専用クライアントの client credentials のトークンの性質(Admin REST API が 403、
 *   audience 固定、ロール無し)。シークレットはコンテナの環境変数にだけ置き、Node 側の argv・出力には出さない
 *   (`internalTools.steps.ts` の `fetchEffectiveComfyUiBaseUrl` と同じ流儀)。
 * - `stubs/tools-client-token.feature`: スタブが記録した Authorization の検証(署名・iss・azp・aud)。
 */

const TOOLS_CLIENT_ID = 'letsblog-tools-client';
const TOOLS_AUDIENCE = 'lbx-tools';
const TOKEN_URI = 'http://keycloak:8080/auth/realms/letsblog/protocol/openid-connect/token';
const ADMIN_USERS_URI = 'http://keycloak:8080/auth/admin/realms/letsblog/users?max=1';
const REALM_PATH = '/auth/realms/letsblog';
const APPLICATION_ROLES = ['admin', 'editor', 'viewer'];

/** ai-service のコンテナの中でトークンを取る。シークレットは `$KEYCLOAK_TOOLS_CLIENT_SECRET` をそのシェルに展開させる。 */
function fetchToolsAccessToken(): string {
  const response = execFileSync('docker', [
    'exec', 'lbs-ai', 'sh', '-c',
    'curl --silent --fail --max-time 30 ' +
      '--data-urlencode grant_type=client_credentials ' +
      `--data-urlencode client_id=${TOOLS_CLIENT_ID} ` +
      '--data-urlencode "client_secret=$KEYCLOAK_TOOLS_CLIENT_SECRET" ' +
      TOKEN_URI,
  ], { encoding: 'utf8', timeout: 60_000 });
  return (JSON.parse(response) as { access_token: string }).access_token;
}

function payloadOf(token: string): Record<string, unknown> {
  return JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8')) as Record<string, unknown>;
}

When('専用クライアントの client credentials でトークンを取得して Admin REST API のユーザー一覧を呼ぶ', async ({ ctx }) => {
  const token = fetchToolsAccessToken();
  // トークンは Node 側の argv に載せず、標準入力のヘッダー設定ファイル(curl -H @-)で渡す。
  const status = execFileSync('docker', [
    'exec', '-i', 'lbs-ai', 'sh', '-c',
    `curl --silent --output /dev/null --write-out '%{http_code}' --max-time 30 -H @- ${ADMIN_USERS_URI}`,
  ], { encoding: 'utf8', timeout: 60_000, input: `Authorization: Bearer ${token}\n` });
  ctx.toolsAdminStatus = Number(status);
});

Then(/^Admin REST API の応答は (\d+) である$/, async ({ ctx }, status: string) => {
  expect(ctx.toolsAdminStatus, '専用クライアントのトークンで Admin REST API を呼べてしまう').toBe(Number(status));
});

When('専用クライアントの client credentials でトークンを取得してクレームを読む', async ({ ctx }) => {
  ctx.toolsTokenPayload = payloadOf(fetchToolsAccessToken());
});

Then(/^トークンの azp は「(.+)」で、audience に「(.+)」を含む$/, async ({ ctx }, azp: string, audience: string) => {
  const payload = ctx.toolsTokenPayload as Record<string, unknown>;
  expect(payload.azp).toBe(azp);
  const aud = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
  expect(aud).toContain(audience);
});

Then('トークンに admin・editor・viewer のロールが無い', async ({ ctx }) => {
  const payload = ctx.toolsTokenPayload as { realm_access?: { roles?: string[] } };
  const roles = payload.realm_access?.roles ?? [];
  for (const role of APPLICATION_ROLES) {
    expect(roles, `専用クライアントのトークンに ${role} がある`).not.toContain(role);
  }
});

Then('トークンに realm-management のロールが無い', async ({ ctx }) => {
  const payload = ctx.toolsTokenPayload as { resource_access?: Record<string, unknown> };
  expect(Object.keys(payload.resource_access ?? {})).not.toContain('realm-management');
});

// ------------------------------------------------------------------ スタブが受けた Authorization

type StubKind = 'LLM' | 'ComfyUI' | 'PlantUML' | 'ポータル';

const STUB_BASE: Record<StubKind, string> = {
  LLM: STUB_URLS.llm,
  ComfyUI: STUB_URLS.comfyui,
  PlantUML: PLANTUML_STUB_URL,
  'ポータル': PORTAL_STUB_URL,
};

Given('extensions のツールのスタブの受信履歴が空である', async () => {
  await requireStubs(['llm', 'comfyui']);
  await requirePlantUmlStub();
  await requirePortalStub();
  await resetStub('llm');
  await resetStub('comfyui');
  for (const base of [PLANTUML_STUB_URL, PORTAL_STUB_URL]) {
    const res = await fetch(`${base}/__control/reset`, { method: 'POST' });
    expect(res.ok, `${base} のリセットに失敗した`).toBe(true);
  }
});

async function recordedAuthorizations(stub: StubKind, method: string, pathPrefix: string): Promise<string[]> {
  const res = await fetch(`${STUB_BASE[stub]}/__control/state`);
  const state = (await res.json()) as { authorizationsByPath?: Record<string, string[]> };
  expect(state.authorizationsByPath, `${stub} スタブが authorizationsByPath を返さない`).toBeDefined();
  const prefix = `${method} ${pathPrefix}`;
  return Object.entries(state.authorizationsByPath!)
    .filter(([key]) => key.startsWith(prefix))
    .flatMap(([, values]) => values);
}

Then(
  /^(LLM|ComfyUI|PlantUML|ポータル)スタブが受けた「(GET|POST) (\/\S*)」の Authorization は専用クライアントのトークンである$/,
  async ({ request }, stub: StubKind, method: string, path: string) => {
    // 呼び出しは非同期のことがある(pull・連携サービスの状況)。受信が現れるまで待つ。
    let values: string[] = [];
    await expect(async () => {
      values = await recordedAuthorizations(stub, method, path);
      expect(values.length, `${stub} スタブが ${method} ${path} を受けていない`).toBeGreaterThan(0);
    }).toPass({ timeout: 60_000 });

    const jwks = (await (await request.get(`${REALM_PATH}/protocol/openid-connect/certs`)).json()) as Jwks;
    const issuer = ((await (await request.get(`${REALM_PATH}/.well-known/openid-configuration`)).json()) as { issuer: string }).issuer;
    for (const value of values) {
      expect(value, `${stub} スタブが Authorization 無しの ${method} ${path} を受けた`).not.toBe('(none)');
      // 失敗時のメッセージにトークンの値は載らない(verifyBearer は理由だけを投げる)。
      verifyBearer(value, jwks, { issuer, azp: TOOLS_CLIENT_ID, audience: TOOLS_AUDIENCE });
    }
  }
);
