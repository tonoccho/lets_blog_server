import { Given, Then, When } from './fixtures';
import { expect } from '../support';
import { PORTAL_STUB_URL, requirePortalStub } from '../support/stubs';

/**
 * extensions のポータルのスタブ(issue #1782)の受け入れテスト。スタブを直接叩く。
 *
 * 既定の応答の出典は seiji/lets_blog_server_extensions の b864179 の
 * apps/portal/src/lib/config.ts(SERVICE_DEFS)と connections.ts(buildConnections)。
 * BASE_DOMAIN=lbx.localhost、HTTPS_PORT=8443 の既定値で組んだ値を、ここに書き下ろしている。
 */

interface Connection {
  id: string;
  name: string;
  publicUrl: string;
  internalUrl: string;
  internalServiceUrl: string;
  openaiBaseUrl?: string;
}

const EXPECTED_CONNECTIONS: Connection[] = [
  {
    id: 'plantuml',
    name: 'PlantUML',
    publicUrl: 'https://plantuml.lbx.localhost:8443',
    internalUrl: 'http://lbx-plantuml:8080',
    internalServiceUrl: 'http://plantuml:8080',
  },
  {
    id: 'comfyui',
    name: 'ComfyUI',
    publicUrl: 'https://comfyui.lbx.localhost:8443',
    internalUrl: 'http://lbx-comfyui:8188',
    internalServiceUrl: 'http://comfyui:8188',
  },
  {
    id: 'ollama',
    name: 'Ollama',
    publicUrl: 'https://ollama.lbx.localhost:8443',
    internalUrl: 'http://lbx-ollama:11434',
    internalServiceUrl: 'http://ollama:11434',
    openaiBaseUrl: 'http://lbx-ollama:11434/v1',
  },
  {
    id: 'penpot',
    name: 'Penpot',
    publicUrl: 'https://penpot.lbx.localhost:8443',
    internalUrl: 'http://lbx-penpot-frontend:8080',
    internalServiceUrl: 'http://penpot-frontend:8080',
  },
  {
    id: 'gitlab',
    name: 'GitLab',
    publicUrl: 'https://gitlab.lbx.localhost:8443',
    internalUrl: 'http://lbx-gitlab',
    internalServiceUrl: 'http://gitlab',
  },
];

const IDS = EXPECTED_CONNECTIONS.map((c) => c.id);

interface Call {
  status: number;
  contentType: string;
  body: string;
}

let last: Call | null = null;
let calls: Call[] = [];
let delayed: { elapsedMs: number; rejected: boolean } | null = null;

async function getConnections(): Promise<Call> {
  const res = await fetch(`${PORTAL_STUB_URL}/api/connections`);
  return {
    status: res.status,
    contentType: res.headers.get('content-type') ?? '',
    body: await res.text(),
  };
}

function lastCall(): Call {
  expect(last, 'ポータルスタブを呼んでいない').not.toBeNull();
  return last!;
}

async function postControl(path: string, payload: unknown): Promise<void> {
  const res = await fetch(`${PORTAL_STUB_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  expect(res.ok, `ポータルスタブの ${path} が失敗した(${res.status})`).toBe(true);
}

Given('ポータルスタブが起動していて初期状態である', async () => {
  await requirePortalStub();
  const res = await fetch(`${PORTAL_STUB_URL}/__control/reset`, { method: 'POST' });
  expect(res.ok, 'ポータルスタブのリセットに失敗した').toBe(true);
  last = null;
  calls = [];
  delayed = null;
});

When(/^ポータルスタブの GET \/api\/connections を呼ぶ$/, async () => {
  last = await getConnections();
});

When(/^ポータルスタブの GET \/api\/connections を(\d+)回呼ぶ$/, async ({}, times: string) => {
  calls = [];
  for (let i = 0; i < Number(times); i += 1) calls.push(await getConnections());
  last = calls[calls.length - 1];
});

When(/^ポータルスタブの GET \/api\/connections を呼び応答を待つ$/, async () => {
  const started = Date.now();
  let rejected = false;
  try {
    await fetch(`${PORTAL_STUB_URL}/api/connections`);
  } catch {
    rejected = true;
  }
  delayed = { elapsedMs: Date.now() - started, rejected };
});

When('ポータルスタブをリセットする', async () => {
  const res = await fetch(`${PORTAL_STUB_URL}/__control/reset`, { method: 'POST' });
  expect(res.ok).toBe(true);
});

Given(
  /^ポータルスタブの応答を ollama の internalUrl を「(.+)」にした5要素へ差し替える$/,
  async ({}, url: string) => {
    const body = EXPECTED_CONNECTIONS.map((c) => (c.id === 'ollama' ? { ...c, internalUrl: url } : c));
    await postControl('/__control/response', { body });
  }
);

Given(/^ポータルスタブの応答を本文「(.+)」に差し替える$/, async ({}, text: string) => {
  await postControl('/__control/response', { body: text, contentType: 'text/plain; charset=utf-8' });
});

Given(/^ポータルスタブの応答を JSON「(.+)」に差し替える$/, async ({}, json: string) => {
  await postControl('/__control/response', { body: JSON.parse(json) });
});

Given(/^ポータルスタブへ次の1回を (\d+) にする指示を出す$/, async ({}, status: string) => {
  await postControl('/__control/force', { status: Number(status), count: 1 });
});

Given(/^ポータルスタブへ次の1回を (\d+) ミリ秒遅らせる指示を出す$/, async ({}, ms: string) => {
  await postControl('/__control/force', { delayMs: Number(ms), count: 1 });
});

Then('ステータスは {int} で Content-Type は application\\/json である', async ({}, status: number) => {
  const call = lastCall();
  expect(call.status).toBe(status);
  expect(call.contentType).toContain('application/json');
});

Then('ステータスは {int} である', async ({}, status: number) => {
  expect(lastCall().status).toBe(status);
});

Then('応答は plantuml・comfyui・ollama・penpot・gitlab の順の5要素である', async () => {
  const parsed = JSON.parse(lastCall().body) as Connection[];
  expect(parsed.map((c) => c.id)).toEqual(IDS);
});

Then('各要素は extensions の既定値と同じ項目と値を持つ', async () => {
  expect(JSON.parse(lastCall().body)).toEqual(EXPECTED_CONNECTIONS);
});

Then(/^ollama だけが openaiBaseUrl「(.+)」を持つ$/, async ({}, url: string) => {
  const parsed = JSON.parse(lastCall().body) as Connection[];
  const withOpenai = parsed.filter((c) => c.openaiBaseUrl !== undefined);
  expect(withOpenai.map((c) => c.id)).toEqual(['ollama']);
  expect(withOpenai[0].openaiBaseUrl).toBe(url);
});

Then('3回とも同一の応答である', async () => {
  expect(calls).toHaveLength(3);
  expect(calls[1]).toEqual(calls[0]);
  expect(calls[2]).toEqual(calls[0]);
});

Then(/^応答の ollama の internalUrl は「(.+)」である$/, async ({}, url: string) => {
  const parsed = JSON.parse(lastCall().body) as Connection[];
  expect(parsed.find((c) => c.id === 'ollama')?.internalUrl).toBe(url);
});

Then(/^応答本文は「(.+)」である$/, async ({}, text: string) => {
  expect(lastCall().body).toBe(text);
});

Then('応答本文は JSON として読めない', async () => {
  expect(() => JSON.parse(lastCall().body)).toThrow();
});

Then(/^応答本文は JSON「(.+)」である$/, async ({}, json: string) => {
  expect(JSON.parse(lastCall().body)).toEqual(JSON.parse(json));
});

Then(/^呼び出しは (\d+) ミリ秒以上経ってから応答なしで終わる$/, async ({}, ms: string) => {
  expect(delayed, '呼び出しを記録していない').not.toBeNull();
  expect(delayed!.rejected, '遅延の後に応答が返ってしまった').toBe(true);
  expect(delayed!.elapsedMs).toBeGreaterThanOrEqual(Number(ms) - 20);
});

Then(/^ポータルスタブの状態の受信件数は (\d+) である$/, async ({}, n: string) => {
  const state = (await (await fetch(`${PORTAL_STUB_URL}/__control/state`)).json()) as { requests: number };
  expect(state.requests).toBe(Number(n));
});

Then(/^ポータルスタブの状態の GET \/api\/connections の受信件数は (\d+) である$/, async ({}, n: string) => {
  const state = (await (await fetch(`${PORTAL_STUB_URL}/__control/state`)).json()) as {
    requestsByPath: Record<string, number>;
  };
  expect(state.requestsByPath['GET /api/connections']).toBe(Number(n));
});
