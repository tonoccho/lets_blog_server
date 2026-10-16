'use strict';
/**
 * 共通土台(lib/stub.js)の Authorization 記録(issue #1804)の契約テスト。
 * 実行: node --test infra/e2e-stubs/lib/stub.test.js
 *
 * lets_blog が extensions のツールを呼ぶときに付けるトークンを、受け入れテストが `/__control/state` から
 * 読めるようにする。スタブ自身は検証しない。**値はログにも応答の本文にも出さない**(制御の state だけが見せる)。
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');
const { createStub } = require('./stub');

function freePort() {
  return new Promise((resolve) => {
    const s = net.createServer();
    s.listen(0, '127.0.0.1', () => {
      const { port } = s.address();
      s.close(() => resolve(port));
    });
  });
}

let server;
let base;
const logged = [];
const realLog = console.log;

test.before(async () => {
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  console.log = (...args) => {
    logged.push(args.join(' '));
    realLog(...args);
  };
  server = createStub({
    name: 'auth-probe',
    port,
    async handle({ method, pathname, res, sendJson }) {
      if (method === 'GET' && pathname === '/ping') {
        sendJson(res, 200, { ok: true });
        return true;
      }
      return false;
    },
  });
  for (let i = 0; i < 50; i++) {
    try {
      if ((await fetch(`${base}/health`)).ok) return;
    } catch {
      /* 起動待ち */
    }
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error('stub did not start');
});

test.after(() => {
  console.log = realLog;
  server && server.close();
});

const state = async () => (await fetch(`${base}/__control/state`)).json();
const reset = () => fetch(`${base}/__control/reset`, { method: 'POST' });

test('受けた Authorization を METHOD /path ごとに state で読める', async () => {
  await reset();
  await fetch(`${base}/ping`, { headers: { Authorization: 'Bearer aaa' } });
  await fetch(`${base}/ping`, { headers: { Authorization: 'Bearer bbb' } });
  const s = await state();
  assert.deepEqual(s.authorizationsByPath['GET /ping'], ['Bearer aaa', 'Bearer bbb']);
});

test('Authorization が無い要求は (none) として記録する(付けなかったことも検証できる)', async () => {
  await reset();
  await fetch(`${base}/ping`);
  assert.deepEqual((await state()).authorizationsByPath['GET /ping'], ['(none)']);
});

test('応答の本文・ヘッダーに Authorization の値を出さない', async () => {
  await reset();
  const res = await fetch(`${base}/ping`, { headers: { Authorization: 'Bearer secret-value-xyz' } });
  const body = await res.text();
  assert.ok(!body.includes('secret-value-xyz'));
  assert.ok(![...res.headers.values()].some((v) => v.includes('secret-value-xyz')));
});

test('ログに Authorization の値を出さない', async () => {
  await reset();
  await fetch(`${base}/ping`, { headers: { Authorization: 'Bearer log-probe-123' } });
  assert.ok(!logged.some((line) => line.includes('log-probe-123')));
});

test('未対応のパス(404)の応答にも値を出さず、記録だけはする', async () => {
  await reset();
  const res = await fetch(`${base}/nope`, { headers: { Authorization: 'Bearer notfound-456' } });
  assert.equal(res.status, 404);
  assert.ok(!(await res.text()).includes('notfound-456'));
  assert.deepEqual((await state()).authorizationsByPath['GET /nope'], ['Bearer notfound-456']);
});

test('/health と /__control/** は記録しない', async () => {
  await reset();
  await fetch(`${base}/health`, { headers: { Authorization: 'Bearer h' } });
  await fetch(`${base}/__control/state`, { headers: { Authorization: 'Bearer c' } });
  assert.deepEqual((await state()).authorizationsByPath, {});
});

test('reset で消える', async () => {
  await fetch(`${base}/ping`, { headers: { Authorization: 'Bearer x' } });
  const after = await (await reset()).json();
  assert.deepEqual(after.authorizationsByPath, {});
  assert.deepEqual((await state()).authorizationsByPath, {});
});

test('エラー注入で失敗させた要求も記録する(401 を返すスタブの検証に使える)', async () => {
  await reset();
  await fetch(`${base}/ping`, { headers: { Authorization: 'Bearer forced', 'X-E2E-Stub-Force-Status': '500' } });
  assert.deepEqual((await state()).authorizationsByPath['GET /ping'], ['Bearer forced']);
});

test('1つのパスに記録するのは直近の100件まで(長く動かしてもメモリが増え続けない)', async () => {
  await reset();
  for (let i = 0; i < 105; i++) {
    await fetch(`${base}/ping`, { headers: { Authorization: `Bearer n${i}` } });
  }
  const list = (await state()).authorizationsByPath['GET /ping'];
  assert.equal(list.length, 100);
  assert.equal(list[0], 'Bearer n5');
  assert.equal(list[99], 'Bearer n104');
});
