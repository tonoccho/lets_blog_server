'use strict';
/**
 * X API スタブ(issue #1573)の契約テスト。実行: node --test infra/e2e-stubs/x/server.test.js
 * letsblog プラグインが叩く 3 本(トークン更新・投稿・自分の情報)と、エラー注入・受信内容の確認を検証する。
 */
const test = require('node:test');
const assert = require('node:assert/strict');
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

const basic = 'Basic ' + Buffer.from('e2e-client-id:e2e-client-secret').toString('base64');
const tweet = (token, text = 'hello') =>
  fetch(`${base}/2/tweets`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ text }),
  });
const refresh = (refreshToken, auth = basic) =>
  fetch(`${base}/2/oauth2/token`, {
    method: 'POST',
    headers: { Authorization: auth, 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'refresh_token', refresh_token: refreshToken }).toString(),
  });
const state = async () => (await fetch(`${base}/__control/state`)).json();

test('有効なアクセストークンで投稿でき、決定的な ID を返す', async () => {
  await fetch(`${base}/__control/reset`, { method: 'POST' });
  const r = await tweet('e2e-x-access-valid', 'テスト投稿');
  assert.equal(r.status, 201);
  const body = await r.json();
  assert.equal(body.data.id, '1900000000000000001');
  assert.equal(body.data.text, 'テスト投稿');
  assert.deepEqual((await state()).tweets, ['テスト投稿']);
});

test('無効・期限切れのアクセストークンは 401', async () => {
  assert.equal((await tweet('e2e-x-access-expired')).status, 401);
  assert.equal((await tweet('nope')).status, 401);
});

test('refresh token で更新でき、トークンが入れ替わる', async () => {
  const r = await refresh('e2e-x-refresh-valid');
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.equal(body.access_token, 'e2e-x-access-refreshed');
  assert.equal(body.refresh_token, 'e2e-x-refresh-rotated');
  assert.equal(body.expires_in, 7200);
  assert.equal((await tweet(body.access_token)).status, 201);
  assert.equal((await refresh(body.refresh_token)).status, 200);
});

test('refresh: 失効した refresh token は invalid_grant、クライアント認証が無ければ 401', async () => {
  const bad = await refresh('revoked');
  assert.equal(bad.status, 400);
  assert.equal((await bad.json()).error, 'invalid_grant');
  assert.equal((await refresh('e2e-x-refresh-valid', 'Basic AAAA')).status, 401);
});

test('自分のアカウント情報を返す', async () => {
  const r = await fetch(`${base}/2/users/me`, { headers: { Authorization: 'Bearer e2e-x-access-valid' } });
  assert.equal(r.status, 200);
  assert.equal((await r.json()).data.username, 'lets_blog_e2e');
  assert.equal((await fetch(`${base}/2/users/me`, { headers: { Authorization: 'Bearer nope' } })).status, 401);
});

test('制御エンドポイントでエラーを注入できる', async () => {
  await fetch(`${base}/__control/force`, {
    method: 'POST',
    body: JSON.stringify({ status: 429, count: 1 }),
  });
  assert.equal((await tweet('e2e-x-access-valid')).status, 429);
  assert.equal((await tweet('e2e-x-access-valid')).status, 201);
});

test('未対応のパスは 404', async () => {
  assert.equal((await fetch(`${base}/2/unknown`)).status, 404);
});
