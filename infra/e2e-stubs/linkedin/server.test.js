'use strict';
/**
 * LinkedIn スタブ(issue #1581)の契約テスト。実行: node --test infra/e2e-stubs/linkedin/server.test.js
 * letsblog プラグインと project-service が叩くエンドポイント(認可画面・トークン交換・userinfo・ugcPosts への投稿)と、
 * 期限切れのトークンが 401 になること、エラー注入・受信内容の確認を検証する。
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
const SUB = 'e2e-linkedin-sub';
const SCOPE = 'openid profile w_member_social';

const authorize = (overrides = {}) => {
  const q = new URLSearchParams({
    client_id: 'e2e-linkedin-app-id',
    redirect_uri: 'http://localhost/connect/linkedin/callback',
    scope: SCOPE,
    response_type: 'code',
    state: '7.abc',
    ...overrides,
  });
  return fetch(`${base}/oauth/v2/authorization?${q}`, { redirect: 'manual' });
};

test('認可画面は利用者の操作なしで redirect_uri へ code と state を付けて戻す', async () => {
  const r = await authorize();
  assert.equal(r.status, 302);
  const location = new URL(r.headers.get('location'));
  assert.equal(location.origin + location.pathname, 'http://localhost/connect/linkedin/callback');
  assert.equal(location.searchParams.get('code'), 'e2e-linkedin-code');
  assert.equal(location.searchParams.get('state'), '7.abc');
});

test('認可リクエストが不正(クライアント違い・スコープ不足・state なし)なら 400', async () => {
  assert.equal((await authorize({ client_id: 'other' })).status, 400);
  assert.equal((await authorize({ scope: 'openid profile' })).status, 400);
  assert.equal((await authorize({ scope: 'profile w_member_social' })).status, 400);
  assert.equal((await authorize({ state: '' })).status, 400);
  assert.equal((await authorize({ response_type: 'token' })).status, 400);
  assert.equal((await authorize({ redirect_uri: '' })).status, 400);
});

const exchange = (overrides = {}) =>
  fetch(`${base}/oauth/v2/accessToken`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: 'e2e-linkedin-app-id',
      client_secret: 'e2e-linkedin-app-secret',
      redirect_uri: 'http://localhost/connect/linkedin/callback',
      code: 'e2e-linkedin-code',
      ...overrides,
    }).toString(),
  });

test('認可コードをアクセストークン(60日)に交換できる。refresh token は返さない', async () => {
  const r = await exchange();
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.equal(body.access_token, 'e2e-linkedin-token');
  assert.equal(body.expires_in, 5184000);
  assert.equal(body.refresh_token, undefined);
});

test('トークン交換: クライアント・秘密・コード・redirect_uri・grant_type が違えば 400', async () => {
  for (const bad of [{ client_id: 'x' }, { client_secret: 'x' }, { code: 'x' }, { redirect_uri: '' }, { grant_type: 'refresh_token' }]) {
    const r = await exchange(bad);
    assert.equal(r.status, 400, JSON.stringify(bad));
    assert.equal((await r.json()).error, 'invalid_request');
  }
});

test('userinfo は有効なトークンのときだけ sub を返す', async () => {
  const r = await fetch(`${base}/v2/userinfo`, { headers: bearer('e2e-linkedin-token') });
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.equal(body.sub, SUB);
  assert.equal(body.name, "Let's Blog E2E");
  assert.equal((await fetch(`${base}/v2/userinfo`, { headers: bearer('e2e-linkedin-expired') })).status, 401);
  assert.equal((await fetch(`${base}/v2/userinfo`)).status, 401);
});

const ugc = (accessToken, overrides = {}, headers = {}) => {
  const payload = {
    author: `urn:li:person:${SUB}`,
    lifecycleState: 'PUBLISHED',
    specificContent: {
      'com.linkedin.ugc.ShareContent': {
        shareCommentary: { text: 'タイトル' },
        shareMediaCategory: 'ARTICLE',
        media: [{ status: 'READY', originalUrl: 'https://example.test/a' }],
      },
    },
    visibility: { 'com.linkedin.ugc.MemberNetworkVisibility': 'PUBLIC' },
    ...overrides,
  };
  return fetch(`${base}/v2/ugcPosts`, {
    method: 'POST',
    headers: { ...bearer(accessToken), 'Content-Type': 'application/json', 'X-Restli-Protocol-Version': '2.0.0', ...headers },
    body: JSON.stringify(payload),
  });
};

test('有効なトークンで ugcPosts へ投稿でき(201 と X-RestLi-Id)、投稿者・本文・リンクが受信内容に残る', async () => {
  await reset();
  const r = await ugc('e2e-linkedin-token');
  assert.equal(r.status, 201);
  assert.ok(r.headers.get('x-restli-id'));
  assert.ok((await r.json()).id);
  assert.deepEqual((await state()).posts, [
    { author: `urn:li:person:${SUB}`, text: 'タイトル', link: 'https://example.test/a', category: 'ARTICLE', visibility: 'PUBLIC' },
  ]);
});

test('リンクなし(NONE)の投稿は link が null で記録される', async () => {
  await reset();
  const r = await ugc('e2e-linkedin-token', {
    specificContent: { 'com.linkedin.ugc.ShareContent': { shareCommentary: { text: '本文だけ' }, shareMediaCategory: 'NONE' } },
  });
  assert.equal(r.status, 201);
  assert.deepEqual((await state()).posts, [
    { author: `urn:li:person:${SUB}`, text: '本文だけ', link: null, category: 'NONE', visibility: 'PUBLIC' },
  ]);
});

test('投稿: 期限切れ・無効なトークン・Authorization なしは 401 で、何も記録されない', async () => {
  await reset();
  assert.equal((await ugc('e2e-linkedin-expired')).status, 401);
  assert.equal((await ugc('nope')).status, 401);
  assert.equal((await fetch(`${base}/v2/ugcPosts`, { method: 'POST', body: '{}' })).status, 401);
  assert.deepEqual((await state()).posts, []);
});

test('投稿: X-Restli-Protocol-Version が無い・author が別人・PUBLIC でない・本文が空・壊れた JSON は 400', async () => {
  await reset();
  assert.equal((await ugc('e2e-linkedin-token', {}, { 'X-Restli-Protocol-Version': '1.0.0' })).status, 400);
  assert.equal((await ugc('e2e-linkedin-token', { author: 'urn:li:person:other' })).status, 400);
  assert.equal((await ugc('e2e-linkedin-token', { visibility: { 'com.linkedin.ugc.MemberNetworkVisibility': 'CONNECTIONS' } })).status, 400);
  assert.equal((await ugc('e2e-linkedin-token', { lifecycleState: 'DRAFT' })).status, 400);
  assert.equal(
    (await ugc('e2e-linkedin-token', { specificContent: { 'com.linkedin.ugc.ShareContent': { shareCommentary: { text: '' }, shareMediaCategory: 'NONE' } } })).status,
    400
  );
  assert.equal(
    (await ugc('e2e-linkedin-token', { specificContent: { 'com.linkedin.ugc.ShareContent': { shareCommentary: { text: 'x' }, shareMediaCategory: 'ARTICLE' } } })).status,
    400
  );
  const broken = await fetch(`${base}/v2/ugcPosts`, {
    method: 'POST',
    headers: { ...bearer('e2e-linkedin-token'), 'X-Restli-Protocol-Version': '2.0.0' },
    body: '{not json',
  });
  assert.equal(broken.status, 400);
  assert.deepEqual((await state()).posts, []);
});

test('制御エンドポイントでエラーを注入できる', async () => {
  await reset();
  await fetch(`${base}/__control/force`, { method: 'POST', body: JSON.stringify({ status: 429, count: 1 }) });
  assert.equal((await ugc('e2e-linkedin-token')).status, 429);
  assert.equal((await ugc('e2e-linkedin-token')).status, 201);
});

test('未対応のパスは 404', async () => {
  assert.equal((await fetch(`${base}/unknown/path/x`)).status, 404);
});
