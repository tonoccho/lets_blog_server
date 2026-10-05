import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { Before, Given, Then, When } from './fixtures';
import { expect } from '../support';
import {
  ALL_STUBS,
  STUB_URLS,
  type StubName,
  comfyUiWorkflow,
  forceStatusHeader,
  requireStubs,
} from '../support/stubs';

/**
 * 外部依存スタブ自体の受け入れテスト(issue #928 / AT-2)。
 *
 * スタブは「受け入れテストの土台」であって製品機能ではない。だが土台が壊れると、
 * それに乗る AT-8 / AT-9 / AT-10 / AT-13 のシナリオが**理由の分からない形で**落ちる。
 * 決定性とエラー注入という2つの約束をここで守らせる。
 */

/**
 * `@stub` が付いたシナリオは、スタブが起動していなければスキップではなく失敗させる
 * (#843 の「恒常スキップで未検証」の再発防止)。全 `@stub` シナリオに効く。
 */
Before({ tags: '@stub' }, async () => {
  await requireStubs();
});

/** 直前の応答本文。決定性の比較に使う。 */
const responses = new Map<string, string[]>();

/** 各スタブの「読み取り専用で決定的な」代表リクエスト。 */
const PROBES: Record<StubName, { path: string; method: string; body?: unknown; headers?: Record<string, string> }> = {
  llm: {
    path: '/chat/completions',
    method: 'POST',
    body: { messages: [{ role: 'user', content: 'カスタムタグを作ってください' }] },
  },
  'google-analytics': {
    path: '/v1beta/properties/123456789:runReport',
    method: 'POST',
    body: { metrics: [{ name: 'activeUsers' }, { name: 'screenPageViews' }], dimensions: [{ name: 'date' }] },
    // #1231 以降、スタブは Bearer のアクセストークンを検証する
    headers: { Authorization: 'Bearer e2e-stub-ga-access-token' },
  },
  adsense: {
    path: '/v2/accounts/pub-e2e/reports:generate?dateRange=LAST_7_DAYS&dimensions=DATE',
    method: 'GET',
  },
  'brave-search': {
    path: '/res/v1/web/search?q=let%27s%20blog&count=3',
    method: 'GET',
    headers: { 'X-Subscription-Token': 'e2e-stub-key' },
  },
  'openai-image': {
    path: '/images/generations',
    method: 'POST',
    body: { model: 'gpt-image-1', prompt: 'a blue button', n: 2, size: '1024x1024' },
  },
  github: {
    path: '/repos/e2e-stub/acceptance/issues?state=open&per_page=100',
    method: 'GET',
    headers: { Authorization: 'Bearer e2e-stub-token' },
  },
  // ComfyUI(#1106)。prompt_id は投入したワークフローから決まるので、同じ形を3回投げれば
  // 3回とも同じ応答になる。client_id は実物では毎回変わるため、決定性の対象に含めない。
  comfyui: {
    path: '/prompt',
    method: 'POST',
    body: { prompt: comfyUiWorkflow({ seed: 1_106_000, batchSize: 1 }) },
  },
  // X API(#1573)。トークンは固定値で状態を持たないので、同じ呼び出しは常に同じ応答になる。
  x: {
    path: '/2/users/me',
    method: 'GET',
    headers: { Authorization: 'Bearer e2e-x-access-valid' },
  },
  // Threads API(#1579)。トークンは固定値で状態を持たないので、同じ呼び出しは常に同じ応答になる。
  threads: {
    path: '/v1.0/me?fields=id,username',
    method: 'GET',
    headers: { Authorization: 'Bearer e2e-threads-long' },
  },
};

async function callProbe(
  name: StubName,
  extraHeaders: Record<string, string> = {}
): Promise<{ status: number; body: string; headers: Headers }> {
  const probe = PROBES[name];
  const res = await fetch(`${STUB_URLS[name]}${probe.path}`, {
    method: probe.method,
    headers: {
      ...(probe.body ? { 'Content-Type': 'application/json' } : {}),
      ...(probe.headers ?? {}),
      ...extraHeaders,
    },
    body: probe.body ? JSON.stringify(probe.body) : undefined,
  });
  return { status: res.status, body: await res.text(), headers: res.headers };
}

Given('全ての外部依存スタブが起動している', async () => {
  await requireStubs();
  responses.clear();
});

When('各スタブへ同じリクエストを3回送る', async () => {
  for (const name of ALL_STUBS) {
    const bodies: string[] = [];
    for (let i = 0; i < 3; i += 1) {
      const { status, body } = await callProbe(name);
      expect(status, `${name} スタブが 200 を返さなかった`).toBe(200);
      bodies.push(body);
    }
    responses.set(name, bodies);
  }
});

Then('全てのスタブが3回とも同一の応答を返す', async () => {
  for (const name of ALL_STUBS) {
    const bodies = responses.get(name);
    expect(bodies, `${name} の応答が記録されていない`).toHaveLength(3);
    expect(bodies![1], `${name} スタブの応答が1回目と2回目で異なる`).toBe(bodies![0]);
    expect(bodies![2], `${name} スタブの応答が1回目と3回目で異なる`).toBe(bodies![0]);
  }
});

/** 注入した応答。ヘッダ経由なのでスタブの状態は変わらず、並列実行に干渉しない。 */
const injected = new Map<StubName, { status: number; headers: Headers }>();

When('各スタブに {int} を注入する', async ({}, status: number) => {
  injected.clear();
  for (const name of ALL_STUBS) {
    const res = await callProbe(name, forceStatusHeader(status));
    injected.set(name, { status: res.status, headers: res.headers });
  }
});

Then('各スタブが注入した {int} を返す', async ({}, status: number) => {
  for (const name of ALL_STUBS) {
    const res = injected.get(name);
    expect(res, `${name} スタブの応答が記録されていない`).toBeDefined();
    expect(res!.status, `${name} スタブが注入した ${status} を返さなかった`).toBe(status);
  }
});

Then('注入していないリクエストは正常に戻る', async () => {
  for (const name of ALL_STUBS) {
    const res = await callProbe(name);
    expect(res.status, `${name} スタブが注入なしのリクエストで 200 を返さなかった`).toBe(200);
  }
});

Then('レート制限のエラーには Retry-After が付く', async () => {
  for (const name of ALL_STUBS) {
    const res = injected.get(name);
    expect(res!.headers.get('retry-after'), `${name} スタブに Retry-After が無い`).toBe('1');
  }
});

Then('タイムアウトを注入すると呼び出しは応答を得られない', async () => {
  // 遅延中に接続を切る。呼び元(サービス)からはリードタイムアウトとして観測される。
  await expect(
    callProbe('llm', { 'X-E2E-Stub-Force-Delay': '300' })
  ).rejects.toThrow();
});

/* ------------------------------------------------------------------ *
 * ComfyUI スタブ(issue #1106)
 *
 * 実機の ComfyUI は GPU が要り、1枚あたり数十秒かかる。batch size 16 の枚数検証も、
 * 「リピートごとに seed が変わる」ことの確認も、実機では現実的な時間で回らない。
 * ここで検証するのはスタブ側の約束(枚数と seed の記録)だけで、画像の見た目は
 * 検証しない(#1106 Out of Scope)。#936 の実生成シナリオ(@slow)は実機のまま。
 * ------------------------------------------------------------------ */

const COMFYUI = STUB_URLS.comfyui;

interface ComfyUiImageRef {
  filename: string;
  subfolder: string;
  type: string;
}

interface ComfyUiPromptRecord {
  promptId: string;
  seed: number;
  batchSize: number;
}

/** 直近に投入したワークフローの prompt_id と、history から得た画像一覧。 */
const comfyUi: {
  promptIds: string[];
  seeds: number[];
  images: ComfyUiImageRef[];
  objectInfo: Record<string, string[]>;
  lastStatus: number;
} = { promptIds: [], seeds: [], images: [], objectInfo: {}, lastStatus: 0 };

/** ワークフローを投入し、history から画像一覧を取り出す。 */
async function submitComfyUiWorkflow(seed: number, batchSize: number): Promise<string> {
  const submit = await fetch(`${COMFYUI}/prompt`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt: comfyUiWorkflow({ seed, batchSize }), client_id: 'e2e-stub-client' }),
  });
  expect(submit.status, 'ComfyUIスタブがワークフローを受け付けなかった').toBe(200);
  const submitted = (await submit.json()) as { prompt_id?: string };
  expect(submitted.prompt_id, 'ComfyUIスタブが prompt_id を返さなかった').toBeTruthy();
  return submitted.prompt_id!;
}

async function fetchComfyUiImages(promptId: string): Promise<ComfyUiImageRef[]> {
  const res = await fetch(`${COMFYUI}/history/${promptId}`);
  expect(res.status, 'ComfyUIスタブの history が 200 を返さなかった').toBe(200);
  const history = (await res.json()) as Record<string, { outputs?: Record<string, { images?: ComfyUiImageRef[] }> }>;
  const entry = history[promptId];
  expect(entry, `ComfyUIスタブの history に ${promptId} が無い`).toBeDefined();
  const images: ComfyUiImageRef[] = [];
  for (const output of Object.values(entry!.outputs ?? {})) {
    for (const image of output.images ?? []) images.push(image);
  }
  return images;
}

async function comfyUiPrompts(): Promise<ComfyUiPromptRecord[]> {
  const res = await fetch(`${COMFYUI}/__control/state`);
  expect(res.status, 'ComfyUIスタブの制御エンドポイントが 200 を返さなかった').toBe(200);
  const state = (await res.json()) as { prompts?: ComfyUiPromptRecord[] };
  return state.prompts ?? [];
}

Given('ComfyUIスタブが起動している', async () => {
  await requireStubs(['comfyui']);
  comfyUi.promptIds = [];
  comfyUi.seeds = [];
  comfyUi.images = [];
  comfyUi.objectInfo = {};
  comfyUi.lastStatus = 0;
});

When(
  /^ComfyUIスタブへ batch size「(\d+)」・seed「(\d+)」のワークフローを投入する$/,
  async ({}, batchSize: string, seed: string) => {
    const promptId = await submitComfyUiWorkflow(Number(seed), Number(batchSize));
    comfyUi.promptIds = [promptId];
    comfyUi.seeds = [Number(seed)];
    comfyUi.images = await fetchComfyUiImages(promptId);
  }
);

Then(/^生成結果の画像は「(\d+)」枚である$/, async ({}, expected: string) => {
  expect(comfyUi.images.length, 'ComfyUIスタブが返した枚数が batch size と一致しない').toBe(Number(expected));
});

Then('生成結果の画像はすべてPNGとしてデコードできる', async () => {
  expect(comfyUi.images.length, '画像が1枚も無い').toBeGreaterThan(0);
  for (const image of comfyUi.images) {
    const url = `${COMFYUI}/view?filename=${encodeURIComponent(image.filename)}`
      + `&subfolder=${encodeURIComponent(image.subfolder ?? '')}`
      + `&type=${encodeURIComponent(image.type ?? 'output')}`;
    const res = await fetch(url);
    expect(res.status, `${image.filename} の取得が 200 にならなかった`).toBe(200);
    expect(res.headers.get('content-type'), `${image.filename} が image/png で返らない`).toContain('image/png');
    const bytes = Buffer.from(await res.arrayBuffer());
    // PNG シグネチャ(8バイト)と、末尾の IEND チャンクまで揃っていることを見る。
    // 「200 が返る」だけではデコードできない断片でも通ってしまう。
    expect(bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])),
      `${image.filename} が PNG シグネチャで始まっていない`).toBeTruthy();
    expect(bytes.subarray(bytes.length - 8).toString('latin1').includes('IEND'),
      `${image.filename} に IEND チャンクが無い(途中で切れている)`).toBeTruthy();
  }
});

Then(
  /^制御エンドポイントにその投入のseed「(\d+)」とbatch size「(\d+)」が記録されている$/,
  async ({}, seed: string, batchSize: string) => {
    const prompts = await comfyUiPrompts();
    const record = prompts.find((p) => p.promptId === comfyUi.promptIds[0]);
    expect(record, `制御エンドポイントに ${comfyUi.promptIds[0]} の記録が無い`).toBeDefined();
    expect(record!.seed, '記録された seed が投入した値と違う').toBe(Number(seed));
    expect(record!.batchSize, '記録された batch size が投入した値と違う').toBe(Number(batchSize));
  }
);

When(/^ComfyUIスタブへ seedを変えたワークフローを「(\d+)」回投入する$/, async ({}, times: string) => {
  comfyUi.promptIds = [];
  comfyUi.seeds = [];
  for (let i = 0; i < Number(times); i += 1) {
    // 実際の batch count と同じで、リピートごとに seed だけが変わる(#1102)。
    const seed = 1_106_100 + i;
    comfyUi.promptIds.push(await submitComfyUiWorkflow(seed, 1));
    comfyUi.seeds.push(seed);
  }
});

Then(/^制御エンドポイントに記録された「(\d+)」回分のseedは互いに異なる$/, async ({}, times: string) => {
  const prompts = await comfyUiPrompts();
  const recorded = comfyUi.promptIds.map((id) => {
    const record = prompts.find((p) => p.promptId === id);
    expect(record, `制御エンドポイントに ${id} の記録が無い`).toBeDefined();
    return record!.seed;
  });
  expect(recorded, '記録された投入回数が違う').toHaveLength(Number(times));
  expect(new Set(recorded).size, `リピートごとの seed が重複している: ${recorded.join(', ')}`)
    .toBe(Number(times));
});

When('ComfyUIスタブのobject_infoを問い合わせる', async () => {
  const targets: Array<[string, string, string]> = [
    ['CheckpointLoaderSimple', 'CheckpointLoaderSimple', 'ckpt_name'],
    ['sampler', 'KSampler', 'sampler_name'],
    ['scheduler', 'KSampler', 'scheduler'],
    ['LoraLoader', 'LoraLoader', 'lora_name'],
  ];
  for (const [key, node, field] of targets) {
    const res = await fetch(`${COMFYUI}/object_info/${node}`);
    expect(res.status, `object_info/${node} が 200 を返さなかった`).toBe(200);
    const info = (await res.json()) as Record<string, { input?: { required?: Record<string, unknown[]> } }>;
    // media-service は required.<field>[0] を一覧として読む(ComfyUiClient)。
    const values = info[node]?.input?.required?.[field]?.[0];
    comfyUi.objectInfo[key] = Array.isArray(values) ? (values as string[]) : [];
  }
});

Then('チェックポイント・サンプラー・スケジューラー・LoRAの一覧がそれぞれ1件以上返る', async () => {
  for (const key of ['CheckpointLoaderSimple', 'sampler', 'scheduler', 'LoraLoader']) {
    expect(comfyUi.objectInfo[key], `${key} の一覧が配列で返っていない`).toBeDefined();
    expect(comfyUi.objectInfo[key].length, `${key} の一覧が空`).toBeGreaterThan(0);
  }
});

When('ComfyUIスタブへメモリ解放を要求する', async () => {
  const res = await fetch(`${COMFYUI}/api/interrupt`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: '{}',
  });
  comfyUi.lastStatus = res.status;
});

Then('メモリ解放の要求は成功する', async () => {
  expect(comfyUi.lastStatus, 'ComfyUIスタブが /api/interrupt に応答しない').toBe(200);
});

/* ------------------------------------------------------------------ *
 * ドキュメントと support/stubs.ts の一致(issue #1620)
 *
 * docs/ACCEPTANCE_TESTING.md の「何をスタブ化しているか」表が挙げるスタブと、
 * ALL_STUBS / resetAllStubs の対象が食い違うと、表のスタブだけが決定性・エラー注入・
 * リセットの検証から漏れる。表の「ホスト公開」列(ポート)を正として突き合わせる。
 * ------------------------------------------------------------------ */

const docStubPorts: number[] = [];

When('docs\\/ACCEPTANCE_TESTING.md の外部依存スタブ表を読む', async () => {
  const doc = readFileSync(resolve(__dirname, '../../../../docs/ACCEPTANCE_TESTING.md'), 'utf8');
  docStubPorts.length = 0;
  for (const line of doc.split('\n')) {
    const m = /^\| `[a-z-]+-stub` \|.*\| (18\d{3}) \|$/.exec(line);
    if (m) docStubPorts.push(Number(m[1]));
  }
  expect(docStubPorts.length, 'ドキュメントのスタブ表から行を読めなかった').toBeGreaterThan(0);
});

Then('表の全てのスタブの公開ポートが STUB_URLS に含まれる', async () => {
  const ports = Object.values(STUB_URLS).map((u) => Number(new URL(u).port));
  for (const port of docStubPorts) {
    expect(ports, `ドキュメントのスタブ(ポート ${port})が STUB_URLS に無い`).toContain(port);
  }
});

Then('表の全てのスタブが ALL_STUBS と同じ名前で数えられる', async () => {
  expect(ALL_STUBS, 'ALL_STUBS の数がドキュメントの表と一致しない').toHaveLength(docStubPorts.length);
});
