import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { Given, Then, When } from './fixtures';
import { expect } from '../support';
import { STUB_URLS, requireStubs, resetStub } from '../support/stubs';

/**
 * GitHubスタブの Pull Request / contents / comments API の受け入れテスト(issue #1334)。
 * スタブを直接叩く。応答の形が GitHub REST API と同じであることだけを検証する。
 */

const GITHUB = STUB_URLS.github;
const REPO = '/repos/e2e-stub/acceptance';
const DEFAULT_TOKEN = 'e2e-stub-token';

interface StubResponse {
  status: number;
  headers: Headers;
  body: any;
}

async function call(
  method: string,
  pathname: string,
  options: { token?: string; body?: unknown } = {}
): Promise<StubResponse> {
  const res = await fetch(`${GITHUB}${pathname}`, {
    method,
    headers: {
      Authorization: `Bearer ${options.token ?? DEFAULT_TOKEN}`,
      ...(options.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    },
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
  });
  const text = await res.text();
  return { status: res.status, headers: res.headers, body: text ? JSON.parse(text) : null };
}

const state: {
  last?: StubResponse;
  created?: StubResponse;
  head: string;
  files: string[];
  sha?: string;
  compose?: any;
} = { head: '', files: [] };

Given('GitHubスタブが起動している', async () => {
  await requireStubs(['github']);
  state.last = undefined;
  state.created = undefined;
  state.files = [];
  state.sha = undefined;
});

async function createPull(head: string, base: string, token?: string): Promise<StubResponse> {
  return call('POST', `${REPO}/pulls`, {
    token,
    body: { title: `E2Eスタブ: ${head}`, head, base, body: 'スタブの受け入れテスト' },
  });
}

When(/^head「(.+)」・base「(.+)」でPRを作成する$/, async ({}, head: string, base: string) => {
  // 同じ head を再利用しても衝突しないよう、実行ごとに一意な名前へする。
  state.head = `article/e2e-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  state.created = await createPull(state.head, base);
  state.last = state.created;
  void head;
});

When('コンフリクトするheadでPRを作成する', async () => {
  state.head = `article/e2e-conflict-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  state.created = await createPull(state.head, 'main');
  state.last = state.created;
});

function createdNumber(): number {
  const n = state.created?.body?.number;
  expect(n, 'PR作成の応答に number が無い').toBeDefined();
  return n as number;
}

Then(
  'PR作成の応答は 201 で、番号・head.ref・head.sha・base.ref・state「open」を持つ',
  async () => {
    const res = state.created!;
    expect(res.status).toBe(201);
    expect(typeof res.body.number).toBe('number');
    expect(res.body.head.ref).toBe(state.head);
    expect(res.body.head.sha).toMatch(/^[0-9a-f]{40}$/);
    expect(res.body.base.ref).toBe('main');
    expect(res.body.state).toBe('open');
  }
);

Then('open のPR一覧に作成したPRが含まれる', async () => {
  const res = await call('GET', `${REPO}/pulls?state=open`);
  expect(res.status).toBe(200);
  expect((res.body as any[]).map((p) => p.number)).toContain(createdNumber());
});

Then('作成したPRの詳細は mergeable が true で merged が false である', async () => {
  const res = await call('GET', `${REPO}/pulls/${createdNumber()}`);
  expect(res.status).toBe(200);
  expect(res.body.mergeable).toBe(true);
  expect(res.body.merged).toBe(false);
  expect(res.body.head.sha).toBe(state.created!.body.head.sha);
});

Then('作成したPRの詳細は mergeable が false で merged が false である', async () => {
  const res = await call('GET', `${REPO}/pulls/${createdNumber()}`);
  expect(res.status).toBe(200);
  expect(res.body.mergeable).toBe(false);
  expect(res.body.merged).toBe(false);
});

Then('作成したPRの変更ファイル一覧は配列で返る', async () => {
  const res = await call('GET', `${REPO}/pulls/${createdNumber()}/files`);
  expect(res.status).toBe(200);
  expect(Array.isArray(res.body)).toBe(true);
});

When('作成したPRをマージする', async () => {
  state.last = await call('PUT', `${REPO}/pulls/${createdNumber()}/merge`, { body: {} });
});

Then('マージの応答は 200 で merged が true である', async () => {
  expect(state.last!.status).toBe(200);
  expect(state.last!.body.merged).toBe(true);
});

Then(/^マージの応答は 405 である$/, async () => {
  expect(state.last!.status).toBe(405);
});

Then('作成したPRの詳細は merged が true で state が「closed」である', async () => {
  const res = await call('GET', `${REPO}/pulls/${createdNumber()}`);
  expect(res.body.merged).toBe(true);
  expect(res.body.state).toBe('closed');
});

When('作成したPRのブランチを削除する', async () => {
  state.last = await call('DELETE', `${REPO}/git/refs/heads/${state.head}`);
});

Then('ブランチ削除の応答は 204 である', async () => {
  expect(state.last!.status).toBe(204);
});

When(/^シードのPR「(\d+)」の変更ファイル一覧を取得する$/, async ({}, n: string) => {
  const res = await call('GET', `${REPO}/pulls/${n}/files`);
  expect(res.status).toBe(200);
  state.files = (res.body as any[]).map((f) => f.filename);
});

Then(/^変更ファイルに「(.+)」が含まれる$/, async ({}, file: string) => {
  expect(state.files).toContain(file);
});

When('リポジトリ情報を取得する', async () => {
  state.last = await call('GET', REPO);
});

Then(/^default_branch は「(.+)」である$/, async ({}, branch: string) => {
  expect(state.last!.status).toBe(200);
  expect(state.last!.body.default_branch).toBe(branch);
});

When(
  /^ref「(.+)」の「(.+)」を contents API で取得する$/,
  async ({}, ref: string, file: string) => {
    state.last = await call('GET', `${REPO}/contents/${file}?ref=${encodeURIComponent(ref)}`);
  }
);

Then('content は base64 でデコードでき、記事本文で始まっている', async () => {
  expect(state.last!.status).toBe(200);
  expect(state.last!.body.encoding).toBe('base64');
  const text = Buffer.from(state.last!.body.content, 'base64').toString('utf8');
  expect(text.startsWith('# ')).toBe(true);
});

Then('sha が付いている', async () => {
  expect(state.last!.body.sha).toMatch(/^[0-9a-f]{40}$/);
});

Then('content は null で、sha が付いている', async () => {
  expect(state.last!.status).toBe(200);
  expect(state.last!.body.content).toBeNull();
  expect(state.last!.body.sha).toMatch(/^[0-9a-f]{40}$/);
  expect(state.last!.body.size).toBeGreaterThan(1024 * 1024);
  state.sha = state.last!.body.sha;
});

When(/^その sha で git\/blobs を取得する$/, async () => {
  state.last = await call('GET', `${REPO}/git/blobs/${state.sha}`);
});

Then('blob は base64 でデコードでき、1MBを超えるサイズである', async () => {
  expect(state.last!.status).toBe(200);
  expect(state.last!.body.encoding).toBe('base64');
  const bytes = Buffer.from(state.last!.body.content, 'base64');
  expect(bytes.length).toBeGreaterThan(1024 * 1024);
});

When(/^PR「(\d+)」へコメント「(.+)」を投稿する$/, async ({}, n: string, text: string) => {
  const res = await call('POST', `${REPO}/issues/${n}/comments`, { body: { body: text } });
  expect(res.status).toBe(201);
});

Then(
  /^PR「(\d+)」のコメント一覧の末尾は「(.+)」「(.+)」の順である$/,
  async ({}, n: string, first: string, second: string) => {
    const res = await call('GET', `${REPO}/issues/${n}/comments`);
    expect(res.status).toBe(200);
    const bodies = (res.body as any[]).map((c) => c.body);
    expect(bodies.slice(-2)).toEqual([first, second]);
  }
);

When(/^トークン「(.+)」でPR一覧を取得する$/, async ({}, token: string) => {
  state.last = await call('GET', `${REPO}/pulls`, { token });
});

When(/^トークン「(.+)」でPRを作成する$/, async ({}, token: string) => {
  state.last = await createPull('article/e2e-auth', 'main', token);
});

Then(/^応答ステータスは (\d+) である$/, async ({}, status: string) => {
  expect(state.last!.status).toBe(Number(status));
});

Then(/^X-RateLimit-Remaining ヘッダは「(.+)」である$/, async ({}, value: string) => {
  expect(state.last!.headers.get('x-ratelimit-remaining')).toBe(value);
});

When('e2eスタブのcompose設定を展開する', async () => {
  const root = path.resolve(__dirname, '../../../..');
  const out = execFileSync(
    'docker',
    ['compose', '-f', 'docker-compose.yml', '-f', 'docker-compose.e2e-stubs.yml', 'config', '--format', 'json'],
    { cwd: root, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }
  );
  state.compose = JSON.parse(out);
});

Then('publishing サービスの GITHUB_API_BASE_URL は github-stub を指している', async () => {
  expect(state.compose.services.publishing.environment.GITHUB_API_BASE_URL).toBe('http://github-stub:8080');
});

Then('publishing サービスは github-stub に depends_on している', async () => {
  expect(Object.keys(state.compose.services.publishing.depends_on)).toContain('github-stub');
});

/* ---- リセット(github-stub-reset.feature) ---- */

When(/^シードのPR「(\d+)」をマージする$/, async ({}, n: string) => {
  const res = await call('PUT', `${REPO}/pulls/${n}/merge`, { body: {} });
  expect(res.status).toBe(200);
});

When('GitHubスタブをリセットする', async () => {
  await resetStub('github');
});

Then(/^PR一覧の番号はシードの「(\d+)」「(\d+)」だけである$/, async ({}, a: string, b: string) => {
  const res = await call('GET', `${REPO}/pulls?state=all`);
  expect((res.body as any[]).map((p) => p.number)).toEqual([Number(a), Number(b)]);
});

Then(/^PR「(\d+)」のコメント一覧は空である$/, async ({}, n: string) => {
  const res = await call('GET', `${REPO}/issues/${n}/comments`);
  expect(res.body).toEqual([]);
});

Then(/^シードのPR「(\d+)」は merged が false である$/, async ({}, n: string) => {
  const res = await call('GET', `${REPO}/pulls/${n}`);
  expect(res.body.merged).toBe(false);
  expect(res.body.state).toBe('open');
});

Then(/^ref「(.+)」に「(.+)」は存在しない$/, async ({}, ref: string, file: string) => {
  const res = await call('GET', `${REPO}/contents/${file}?ref=${encodeURIComponent(ref)}`);
  expect(res.status).toBe(404);
});
