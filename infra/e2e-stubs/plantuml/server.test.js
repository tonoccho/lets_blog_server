'use strict';
/**
 * PlantUML スタブ(issue #1802)の契約テスト。実行: node --test infra/e2e-stubs/plantuml/server.test.js
 * 固定の PNG、受け取ったソースの復号と記録、決定的な 400 / 414、制御エンドポイントを検証する。
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');
const path = require('node:path');
const zlib = require('node:zlib');
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
  child = spawn(process.execPath, [path.join(__dirname, 'server.js')], { env: { ...process.env, PORT: String(port) }, stdio: 'ignore' });
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

// services/media の PlantUmlEncoder.java と同じ符号化(raw deflate + 専用アルファベット)。
const ALPHABET = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_';
function encode(source) {
  const data = zlib.deflateRawSync(Buffer.from(source, 'utf8'), { level: 9 });
  let out = '';
  for (let i = 0; i < data.length; i += 3) {
    const b1 = data[i];
    const b2 = i + 1 < data.length ? data[i + 1] : 0;
    const b3 = i + 2 < data.length ? data[i + 2] : 0;
    out += ALPHABET[b1 >> 2] + ALPHABET[((b1 & 3) << 4) | (b2 >> 4)]
      + ALPHABET[((b2 & 0xf) << 2) | (b3 >> 6)] + ALPHABET[b3 & 0x3f];
  }
  return out;
}

const png = (source, headers = {}) => fetch(`${base}/png/${encode(source)}`, { headers });
const state = async () => (await fetch(`${base}/__control/state`)).json();
const reset = () => fetch(`${base}/__control/reset`, { method: 'POST' });
const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

test('妥当なソースには固定の PNG を image/png で返し、何度呼んでも同じバイト列', async () => {
  await reset();
  const a = await png('@startuml\nAlice -> Bob: Hello\n@enduml');
  assert.equal(a.status, 200);
  assert.equal(a.headers.get('content-type'), 'image/png');
  const first = Buffer.from(await a.arrayBuffer());
  assert.ok(first.subarray(0, 8).equals(SIGNATURE));
  const second = Buffer.from(await (await png('@startuml\nA -> B\nB -> C\n@enduml')).arrayBuffer());
  assert.ok(first.equals(second), '入力によらず同じ画像');
});

test('受け取ったソースを復号して state に記録する(日本語も)', async () => {
  await reset();
  await png('@startuml\nAlice -> Bob: こんにちは\n@enduml');
  const s = await state();
  assert.equal(s.lastSource, '@startuml\nAlice -> Bob: こんにちは\n@enduml');
  assert.deepEqual(s.sources, [s.lastSource]);
});

test('@startuml が無いソースは 400 と X-PlantUML-Diagram-Error を返す', async () => {
  await reset();
  const r = await png('これはPlantUMLではありません\n???');
  assert.equal(r.status, 400);
  assert.ok((r.headers.get('x-plantuml-diagram-error') ?? '').length > 0);
  assert.ok(r.headers.get('x-plantuml-diagram-error-line'));
  assert.equal(r.headers.get('content-type'), 'image/png');
  assert.equal((await state()).sources.length, 1, '拒否したソースも記録する');
});

test('復号できないパスは 400', async () => {
  const r = await fetch(`${base}/png/!!!not-encoded!!!`);
  assert.equal(r.status, 400);
  assert.ok(r.headers.get('x-plantuml-diagram-error'));
});

test('長い URI は 414', async () => {
  await reset();
  const r = await fetch(`${base}/png/${'A'.repeat(9000)}`);
  assert.equal(r.status, 414);
  assert.equal((await state()).sources.length, 0, '414 は復号しない');
});

test('Node の要求ヘッダー上限(16KB)を超える URI も 431 ではなく 414', async () => {
  await reset();
  const r = await fetch(`${base}/png/${'A'.repeat(40000)}`);
  assert.equal(r.status, 414);
  assert.equal((await state()).sources.length, 0);
  assert.equal((await fetch(`${base}/health`)).status, 200, '続けて要求を受けられる');
});

test('/png/ 以外は 404', async () => {
  assert.equal((await fetch(`${base}/uml/xxxx`)).status, 404);
  assert.equal((await fetch(`${base}/png`)).status, 404);
});

test('記録は最新 100 件までで、reset で消える', async () => {
  await reset();
  for (let i = 0; i < 105; i++) await png(`@startuml\nP${i}\n@enduml`);
  const s = await state();
  assert.equal(s.sources.length, 100);
  assert.equal(s.lastSource, '@startuml\nP104\n@enduml');
  assert.equal(s.sources[0], '@startuml\nP5\n@enduml');
  await reset();
  const cleared = await state();
  assert.deepEqual(cleared.sources, []);
  assert.equal(cleared.lastSource, null);
});

test('制御エンドポイントでエラーを注入でき、ヘッダでも単発で注入できる', async () => {
  await reset();
  await fetch(`${base}/__control/force`, { method: 'POST', body: JSON.stringify({ status: 503, count: 1 }) });
  assert.equal((await png('@startuml\nA\n@enduml')).status, 503);
  assert.equal((await png('@startuml\nA\n@enduml')).status, 200);
  assert.equal((await png('@startuml\nA\n@enduml', { 'X-E2E-Stub-Force-Status': '500' })).status, 500);
});
