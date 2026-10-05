'use strict';
/**
 * Facebook(Graph API)スタブ(issue #1580)の契約テスト。実行: node --test infra/e2e-stubs/facebook/server.test.js
 * letsblog プラグインと project-service が叩くエンドポイント(認可画面・コード交換・長期ユーザートークン化・
 * 管理ページの一覧・ページのフィードへの投稿)と、個人アカウントへは投稿できないこと、エラー注入・受信内容の確認を検証する。
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
const reset = () => fetch(`${base}/__control/reset`, { method: 'POST' });
const bearer = (token) => ({ Authorization: `Bearer ${token}` });
const PAGE = '100000000000001';
const NOPERM_PAGE = '100000000000002';
const SCOPE = 'pages_show_list,pages_manage_posts,pages_read_engagement';

const authorize = (overrides = {}) => {
  const q = new URLSearchParams({
    client_id: 'e2e-facebook-app-id',
    redirect_uri: 'http://localhost/connect/facebook/callback',
    scope: SCOPE,
    response_type: 'code',
    state: '7.abc',
    ...overrides,
  });
  return fetch(`${base}/dialog/oauth?${q}`, { redirect: 'manual' });
};

test('認可画面は利用者の操作なしで redirect_uri へ code と state を付けて戻す', async () => {
  const r = await authorize();
  assert.equal(r.status, 302);
  const location = new URL(r.headers.get('location'));
  assert.equal(location.origin + location.pathname, 'http://localhost/connect/facebook/callback');
  assert.equal(location.searchParams.get('code'), 'e2e-facebook-code');
  assert.equal(location.searchParams.get('state'), '7.abc');
});

test('認可リクエストが不正(クライアント違い・スコープ不足・state なし)なら 400', async () => {
  assert.equal((await authorize({ client_id: 'other' })).status, 400);
  assert.equal((await authorize({ scope: 'pages_show_list' })).status, 400);
  assert.equal((await authorize({ scope: 'pages_show_list,pages_read_engagement' })).status, 400);
  assert.equal((await authorize({ state: '' })).status, 400);
  assert.equal((await authorize({ response_type: 'token' })).status, 400);
  assert.equal((await authorize({ redirect_uri: '' })).status, 400);
});

const token = (params) => fetch(`${base}/oauth/access_token?${new URLSearchParams(params)}`);
const codeExchange = (overrides = {}) =>
  token({
    client_id: 'e2e-facebook-app-id',
    client_secret: 'e2e-facebook-app-secret',
    redirect_uri: 'http://localhost/connect/facebook/callback',
    code: 'e2e-facebook-code',
    ...overrides,
  });

test('認可コードを短期のユーザートークンに交換できる', async () => {
  const r = await codeExchange();
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.equal(body.access_token, 'e2e-facebook-user-short');
  assert.equal(body.token_type, 'bearer');
});

test('コード交換: クライアント・秘密・コード・redirect_uri が違えば OAuthException(100)で 400', async () => {
  for (const bad of [{ client_id: 'x' }, { client_secret: 'x' }, { code: 'x' }, { redirect_uri: '' }]) {
    const r = await codeExchange(bad);
    assert.equal(r.status, 400, JSON.stringify(bad));
    assert.equal((await r.json()).error.code, 100);
  }
});

const longLived = (overrides = {}) =>
  token({
    grant_type: 'fb_exchange_token',
    client_id: 'e2e-facebook-app-id',
    client_secret: 'e2e-facebook-app-secret',
    fb_exchange_token: 'e2e-facebook-user-short',
    ...overrides,
  });

test('短期のユーザートークンを長期(約60日)に交換できる', async () => {
  const r = await longLived();
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.equal(body.access_token, 'e2e-facebook-user-long');
  assert.equal(body.expires_in, 5184000);
});

test('長期トークン化: クライアント・秘密・短期トークンが違えば 400', async () => {
  for (const bad of [{ client_id: 'x' }, { client_secret: 'x' }, { fb_exchange_token: 'nope' }]) {
    assert.equal((await longLived(bad)).status, 400, JSON.stringify(bad));
  }
});

test('管理しているページとそのトークンを返す(長期ユーザートークンのときだけ)', async () => {
  const r = await fetch(`${base}/me/accounts?fields=id,name,access_token`, { headers: bearer('e2e-facebook-user-long') });
  assert.equal(r.status, 200);
  const { data } = await r.json();
  assert.equal(data.length, 2);
  assert.deepEqual(data[0], { id: PAGE, name: "Let's Blog E2E ページ", access_token: 'e2e-facebook-page-token' });
  assert.equal(data[1].id, NOPERM_PAGE);
  assert.equal(data[1].access_token, 'e2e-facebook-page-token-noperm');
  assert.equal((await fetch(`${base}/me/accounts`, { headers: bearer('e2e-facebook-user-short') })).status, 401);
  assert.equal((await fetch(`${base}/me/accounts`)).status, 401);
});

const feed = (accessToken, params, pageId = PAGE) =>
  fetch(`${base}/${pageId}/feed`, {
    method: 'POST',
    headers: { ...bearer(accessToken), 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(params).toString(),
  });

test('ページのトークンでページのフィードへ message と link を投稿でき、受信内容に残る', async () => {
  await reset();
  const r = await feed('e2e-facebook-page-token', { message: 'タイトル', link: 'https://example.test/a' });
  assert.equal(r.status, 200);
  assert.ok((await r.json()).id);
  assert.deepEqual((await state()).posts, [{ page_id: PAGE, message: 'タイトル', link: 'https://example.test/a' }]);
});

test('message だけ・link だけでも投稿できるが、どちらも無ければ 400', async () => {
  await reset();
  assert.equal((await feed('e2e-facebook-page-token', { message: 'm' })).status, 200);
  assert.equal((await feed('e2e-facebook-page-token', { link: 'https://example.test/b' })).status, 200);
  assert.equal((await feed('e2e-facebook-page-token', {})).status, 400);
  assert.deepEqual((await state()).posts, [
    { page_id: PAGE, message: 'm', link: null },
    { page_id: PAGE, message: null, link: 'https://example.test/b' },
  ]);
});

test('投稿: 個人アカウント(ユーザートークン・/me)には投稿できない', async () => {
  await reset();
  const asUser = await feed('e2e-facebook-user-long', { message: 'm' });
  assert.equal(asUser.status, 403);
  assert.equal((await asUser.json()).error.code, 200);
  const toMe = await feed('e2e-facebook-page-token', { message: 'm' }, 'me');
  assert.equal(toMe.status, 400);
  assert.deepEqual((await state()).posts, []);
});

test('投稿: 無効・期限切れのトークンは OAuthException(190)で 400、別のページのトークンも拒否', async () => {
  await reset();
  const bad = await feed('nope', { message: 'm' });
  assert.equal(bad.status, 400);
  assert.equal((await bad.json()).error.code, 190);
  assert.equal((await feed('e2e-facebook-page-token', { message: 'm' }, NOPERM_PAGE)).status, 400);
  assert.deepEqual((await state()).posts, []);
});

test('投稿: pages_manage_posts の権限が無いページは 403(code 200)で、理由に権限名が出る', async () => {
  await reset();
  const r = await feed('e2e-facebook-page-token-noperm', { message: 'm' }, NOPERM_PAGE);
  assert.equal(r.status, 403);
  const { error } = await r.json();
  assert.equal(error.code, 200);
  assert.match(error.message, /pages_manage_posts/);
  assert.deepEqual((await state()).posts, []);
});

test('制御エンドポイントでエラーを注入できる', async () => {
  await reset();
  await fetch(`${base}/__control/force`, { method: 'POST', body: JSON.stringify({ status: 429, count: 1 }) });
  assert.equal((await feed('e2e-facebook-page-token', { message: 'x' })).status, 429);
  assert.equal((await feed('e2e-facebook-page-token', { message: 'x' })).status, 200);
});

test('未対応のパスは 404', async () => {
  assert.equal((await fetch(`${base}/unknown/path/x`)).status, 404);
});
