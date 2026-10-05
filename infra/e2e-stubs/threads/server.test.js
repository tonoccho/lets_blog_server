'use strict';
/**
 * Threads API スタブ(issue #1579)の契約テスト。実行: node --test infra/e2e-stubs/threads/server.test.js
 * letsblog プラグインと project-service が叩くエンドポイント(認可画面・トークン交換・長期トークン化・更新・
 * 投稿の作成と公開・自分の情報)と、エラー注入・受信内容の確認を検証する。
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

const state = async () => (await fetch(`${base}/__control/state`)).json();
const form = (params) => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams(params).toString(),
});
const bearer = (token) => ({ Authorization: `Bearer ${token}` });
const USER = '17841400000000001';

const authorize = (overrides = {}) => {
  const q = new URLSearchParams({
    client_id: 'e2e-threads-app-id',
    redirect_uri: 'http://localhost/connect/threads/callback',
    scope: 'threads_basic,threads_content_publish',
    response_type: 'code',
    state: '7.abc',
    ...overrides,
  });
  return fetch(`${base}/oauth/authorize?${q}`, { redirect: 'manual' });
};

test('認可画面は利用者の操作なしで redirect_uri へ code と state を付けて戻す', async () => {
  const r = await authorize();
  assert.equal(r.status, 302);
  const location = new URL(r.headers.get('location'));
  assert.equal(location.origin + location.pathname, 'http://localhost/connect/threads/callback');
  assert.equal(location.searchParams.get('code'), 'e2e-threads-code');
  assert.equal(location.searchParams.get('state'), '7.abc');
});

test('認可リクエストが不正(クライアント違い・スコープ不足・state なし)なら 400', async () => {
  assert.equal((await authorize({ client_id: 'other' })).status, 400);
  assert.equal((await authorize({ scope: 'threads_basic' })).status, 400);
  assert.equal((await authorize({ state: '' })).status, 400);
  assert.equal((await authorize({ response_type: 'token' })).status, 400);
});

const exchange = (overrides = {}) =>
  fetch(
    `${base}/oauth/access_token`,
    form({
      client_id: 'e2e-threads-app-id',
      client_secret: 'e2e-threads-app-secret',
      grant_type: 'authorization_code',
      redirect_uri: 'http://localhost/connect/threads/callback',
      code: 'e2e-threads-code',
      ...overrides,
    })
  );

test('認可コードを短期トークンと user_id に交換できる', async () => {
  const r = await exchange();
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.equal(body.access_token, 'e2e-threads-short');
  assert.equal(String(body.user_id), USER);
});

test('トークン交換: クライアントの秘密・コード・redirect_uri が違えば 400', async () => {
  for (const bad of [{ client_secret: 'x' }, { code: 'x' }, { redirect_uri: '' }, { grant_type: 'refresh_token' }]) {
    const r = await exchange(bad);
    assert.equal(r.status, 400, JSON.stringify(bad));
    assert.ok((await r.json()).error);
  }
});

test('短期トークンを長期トークン(約60日)に交換できる', async () => {
  const q = new URLSearchParams({ grant_type: 'th_exchange_token', client_secret: 'e2e-threads-app-secret', access_token: 'e2e-threads-short' });
  const r = await fetch(`${base}/access_token?${q}`);
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.equal(body.access_token, 'e2e-threads-long');
  assert.equal(body.token_type, 'bearer');
  assert.equal(body.expires_in, 5184000);
});

test('長期トークン化: 秘密が違う・短期トークンでないなら 400', async () => {
  const bad1 = new URLSearchParams({ grant_type: 'th_exchange_token', client_secret: 'x', access_token: 'e2e-threads-short' });
  assert.equal((await fetch(`${base}/access_token?${bad1}`)).status, 400);
  const bad2 = new URLSearchParams({ grant_type: 'th_exchange_token', client_secret: 'e2e-threads-app-secret', access_token: 'nope' });
  assert.equal((await fetch(`${base}/access_token?${bad2}`)).status, 400);
});

const refresh = (token) =>
  fetch(`${base}/refresh_access_token?${new URLSearchParams({ grant_type: 'th_refresh_token', access_token: token })}`);

test('長期トークンを更新でき、新しいトークンが使え、更新した回数が受信内容に残る', async () => {
  await fetch(`${base}/__control/reset`, { method: 'POST' });
  const r = await refresh('e2e-threads-long');
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.equal(body.access_token, 'e2e-threads-refreshed');
  assert.equal(body.expires_in, 5184000);
  assert.equal((await refresh('e2e-threads-refreshed')).status, 200);
  assert.deepEqual((await state()).refreshes, ['e2e-threads-long', 'e2e-threads-refreshed']);
});

test('更新: 期限切れ・未知のトークンは OAuthException(code 190)で 400', async () => {
  for (const token of ['e2e-threads-expired', 'nope']) {
    const r = await refresh(token);
    assert.equal(r.status, 400);
    assert.equal((await r.json()).error.code, 190);
  }
});

const createContainer = (token, text, userId = USER) =>
  fetch(`${base}/v1.0/${userId}/threads`, { ...form({ media_type: 'TEXT', text }), headers: { ...bearer(token), 'Content-Type': 'application/x-www-form-urlencoded' } });
const publish = (token, creationId, userId = USER) =>
  fetch(`${base}/v1.0/${userId}/threads_publish`, { ...form({ creation_id: creationId }), headers: { ...bearer(token), 'Content-Type': 'application/x-www-form-urlencoded' } });

test('投稿は「作成」と「公開」の2段階で、公開したときに受信内容に残る', async () => {
  await fetch(`${base}/__control/reset`, { method: 'POST' });
  const created = await createContainer('e2e-threads-long', 'テスト投稿 https://example.test/a');
  assert.equal(created.status, 200);
  const { id } = await created.json();
  assert.ok(id);
  assert.deepEqual((await state()).posts, [], '公開前は投稿にならない');
  const published = await publish('e2e-threads-long', id);
  assert.equal(published.status, 200);
  assert.ok((await published.json()).id);
  assert.deepEqual((await state()).posts, ['テスト投稿 https://example.test/a']);
});

test('投稿: 無効なトークンは 401、未知の creation_id と TEXT 以外・本文なしは 400、user_id 違いは 400', async () => {
  assert.equal((await createContainer('e2e-threads-expired', 'x')).status, 401);
  assert.equal((await publish('e2e-threads-expired', 'x')).status, 401);
  assert.equal((await publish('e2e-threads-long', 'unknown')).status, 400);
  assert.equal((await createContainer('e2e-threads-long', '')).status, 400);
  assert.equal((await createContainer('e2e-threads-long', 'x', '999')).status, 400);
  const notText = await fetch(`${base}/v1.0/${USER}/threads`, { ...form({ media_type: 'IMAGE', text: 'x' }), headers: { ...bearer('e2e-threads-long'), 'Content-Type': 'application/x-www-form-urlencoded' } });
  assert.equal(notText.status, 400);
});

test('500 文字を超える本文は 400(Threads の上限)', async () => {
  const r = await createContainer('e2e-threads-long', 'あ'.repeat(501));
  assert.equal(r.status, 400);
});

test('自分のアカウント情報を返す', async () => {
  const r = await fetch(`${base}/v1.0/me?fields=id,username`, { headers: bearer('e2e-threads-long') });
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.equal(body.username, 'lets_blog_e2e');
  assert.equal(String(body.id), USER);
  assert.equal((await fetch(`${base}/v1.0/me`, { headers: bearer('nope') })).status, 401);
});

test('制御エンドポイントでエラーを注入できる', async () => {
  await fetch(`${base}/__control/force`, { method: 'POST', body: JSON.stringify({ status: 429, count: 1 }) });
  assert.equal((await createContainer('e2e-threads-long', 'x')).status, 429);
  assert.equal((await createContainer('e2e-threads-long', 'x')).status, 200);
});

test('未対応のパスは 404', async () => {
  assert.equal((await fetch(`${base}/v1.0/unknown/path/x`)).status, 404);
});
