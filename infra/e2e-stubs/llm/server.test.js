'use strict';
/**
 * LLM スタブの画像入力(issue #1600)の契約テスト。実行: node --test infra/e2e-stubs/llm/server.test.js
 * OpenAI 互換の content 配列に image_url(data URL)が含まれる要求へ固定タグを返し、
 * 受け取った画像の素性(モデル・MIME・バイト数・sha256・Exif/GPS の有無)を
 * /__control/state から読めること、文字だけの要求は従来どおりであることを検証する。
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const net = require('node:net');
const path = require('node:path');
const { spawn } = require('node:child_process');

function freePort() {
  return new Promise((resolve) => {
    const s = net.createServer();
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
  });
}

let child;
let base;

test.before(async () => {
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  child = spawn(process.execPath, [path.join(__dirname, 'server.js')], {
    env: { ...process.env, PORT: String(port) },
    stdio: 'ignore',
  });
  for (let i = 0; i < 50; i++) {
    try {
      const r = await fetch(`${base}/health`);
      if (r.ok) return;
    } catch {
      /* 起動待ち */
    }
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error('stub did not start');
});

test.after(() => child && child.kill());

const chat = (messages, model = 'llava:7b') =>
  fetch(`${base}/v1/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model, messages }),
  });
const state = async () => (await fetch(`${base}/__control/state`)).json();
const reset = () => fetch(`${base}/__control/reset`, { method: 'POST' });

const imageMessage = (bytes, mime = 'image/png', text = '説明して') => [
  {
    role: 'user',
    content: [
      { type: 'text', text },
      { type: 'image_url', image_url: { url: `data:${mime};base64,${bytes.toString('base64')}` } },
    ],
  },
];

test('画像入力付きの要求には固定のビジョン用タグを返す', async () => {
  await reset();
  const r = await chat(imageMessage(Buffer.from([1, 2, 3])));
  assert.equal(r.status, 200);
  const content = (await r.json()).choices[0].message.content;
  assert.deepEqual(JSON.parse(content), {
    tags: ['e2e-stub-vision-tag-a', 'e2e-stub-vision-tag-b', 'e2e-stub-vision-tag-c'],
  });
});

test('受け取った画像の素性を state の imageRequests に残す', async () => {
  await reset();
  const bytes = Buffer.from([1, 2, 3, 4, 5]);
  await chat(imageMessage(bytes, 'image/jpeg'), 'llava:7b-e2e-x');
  const { imageRequests } = await state();
  assert.equal(imageRequests.length, 1);
  assert.deepEqual(imageRequests[0], {
    model: 'llava:7b-e2e-x',
    mimeType: 'image/jpeg',
    bytes: 5,
    sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
    containsExif: false,
    containsGpsMarker: false,
  });
});

test('Exif と GPS の目印が画像に含まれていれば検出して記録する', async () => {
  await reset();
  const bytes = Buffer.concat([
    Buffer.from([0xff, 0xd8]),
    Buffer.from('Exif\0\0GPS-35.6586N-139.7454E', 'latin1'),
  ]);
  await chat(imageMessage(bytes, 'image/jpeg'));
  const { imageRequests } = await state();
  assert.equal(imageRequests[0].containsExif, true);
  assert.equal(imageRequests[0].containsGpsMarker, true);
});

test('reset で受信履歴を空にする', async () => {
  await chat(imageMessage(Buffer.from([1])));
  await reset();
  assert.deepEqual((await state()).imageRequests, []);
});

test('履歴は直近50件までに切り詰める', async () => {
  await reset();
  for (let i = 0; i < 52; i++) {
    await chat(imageMessage(Buffer.from([i])), `m-${i}`);
  }
  const { imageRequests } = await state();
  assert.equal(imageRequests.length, 50);
  assert.equal(imageRequests[0].model, 'm-2');
});

test('文字だけの要求は従来どおりで、imageRequests に残らない', async () => {
  await reset();
  const r = await chat([
    { role: 'user', content: '以下の画像生成プロンプト {"tags": ["タグ1", "タグ2", "タグ3"]} 猫' },
  ]);
  const content = (await r.json()).choices[0].message.content;
  assert.deepEqual(JSON.parse(content), {
    tags: ['e2e-stub-image-tag-a', 'e2e-stub-image-tag-b', 'e2e-stub-image-tag-c'],
  });
  assert.deepEqual((await state()).imageRequests, []);
});

test('data URL でない image_url は画像として数えるが素性は空として記録する', async () => {
  await reset();
  await chat([
    {
      role: 'user',
      content: [{ type: 'image_url', image_url: { url: 'https://example.invalid/a.png' } }],
    },
  ]);
  const { imageRequests } = await state();
  assert.equal(imageRequests.length, 1);
  assert.equal(imageRequests[0].bytes, 0);
  assert.equal(imageRequests[0].mimeType, null);
});

// ------------------------------------ Ollama の POST /api/pull(issue #1675)

const pull = (model) =>
  fetch(`${base}/api/pull`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model, stream: true }),
  });
const ndjson = async (response) =>
  (await response.text()).split('\n').filter((line) => line !== '').map((line) => JSON.parse(line));

test('pull: 通常のモデル名は進捗(completed/total)を流して最後に success を返し、受け取ったモデル名を state に残す', async () => {
  await reset();
  const r = await pull('e2e-pull-ok:1b');
  assert.equal(r.status, 200);
  assert.match(r.headers.get('content-type'), /ndjson/);
  const lines = await ndjson(r);
  assert.ok(lines.some((l) => l.total > 0 && l.completed > 0 && l.completed < l.total), JSON.stringify(lines));
  assert.deepEqual(lines[lines.length - 1], { status: 'success' });
  assert.deepEqual((await state()).pullRequests, ['e2e-pull-ok:1b']);
});

test('pull: 古い形式の name フィールドでもモデル名を受け取る', async () => {
  await reset();
  await fetch(`${base}/api/pull`, { method: 'POST', body: JSON.stringify({ name: 'legacy-name:1b' }) });
  assert.deepEqual((await state()).pullRequests, ['legacy-name:1b']);
});

test('pull: モデル名に fail を含むとストリームの途中で error 行を返して終わる', async () => {
  await reset();
  const lines = await ndjson(await pull('e2e-pull-fail:1b'));
  assert.equal(lines[0].status, 'pulling manifest');
  assert.ok(typeof lines[lines.length - 1].error === 'string' && lines[lines.length - 1].error !== '');
  assert.ok(!lines.some((l) => l.status === 'success'));
});

test('pull: モデル名に missing を含むとHTTP 404 と error を返す', async () => {
  await reset();
  const r = await pull('e2e-pull-missing:1b');
  assert.equal(r.status, 404);
  assert.match((await r.json()).error, /file does not exist/);
});

test('pull: モデル名に slow を含むと時間をかけて進捗を流す(画面で進捗を観測できる)', async () => {
  await reset();
  const started = Date.now();
  const lines = await ndjson(await pull('e2e-pull-slow:1b'));
  assert.ok(Date.now() - started >= 2500, `elapsed ${Date.now() - started}ms`);
  assert.ok(lines.filter((l) => l.total > 0).length >= 5);
  assert.deepEqual(lines[lines.length - 1], { status: 'success' });
});

test('pull: 本文が壊れていても空のモデル名として受け、success を返す', async () => {
  await reset();
  const r = await fetch(`${base}/api/pull`, { method: 'POST', body: 'not json' });
  assert.equal(r.status, 200);
  assert.deepEqual((await ndjson(r)).pop(), { status: 'success' });
});

test('pull: reset で受信したモデル名の履歴を空にする', async () => {
  await pull('e2e-pull-ok:1b');
  await reset();
  assert.deepEqual((await state()).pullRequests, []);
});

test('pull: 履歴は直近50件までに切り詰める', async () => {
  await reset();
  for (let i = 0; i < 52; i++) {
    await (await pull(`m-${i}`)).text();
  }
  const { pullRequests } = await state();
  assert.equal(pullRequests.length, 50);
  assert.equal(pullRequests[0], 'm-2');
});

// ---------------------------------------------------------------- モデル一覧(issue #1674)

test('models: GET /v1/models は OpenAI 形式で2件以上のモデルIDを返す', async () => {
  const r = await fetch(`${base}/v1/models`);
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.equal(body.object, 'list');
  const ids = body.data.map((m) => m.id);
  assert.ok(ids.length >= 2, `ids=${ids}`);
  assert.deepEqual(ids, ['e2e-stub-gpt-a', 'e2e-stub-gpt-b']);
});

test('models: GET /api/tags は Ollama 形式で2件以上のモデル名を返す', async () => {
  const r = await fetch(`${base}/api/tags`);
  assert.equal(r.status, 200);
  const names = (await r.json()).models.map((m) => m.name);
  assert.ok(names.length >= 2, `names=${names}`);
  assert.deepEqual(names, ['e2e-stub-ollama-a:1b', 'e2e-stub-ollama-b:1b']);
});

test('models: POST /v1/models は一覧ではない(404)', async () => {
  const r = await fetch(`${base}/v1/models`, { method: 'POST', body: '{}' });
  assert.equal(r.status, 404);
});
