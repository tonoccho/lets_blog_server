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

// ---- issue #1574: 認可コードフロー(アプリが接続に使う) ----

const crypto = require('node:crypto');
const VERIFIER = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
const challengeOf = (verifier) => crypto.createHash('sha256').update(verifier).digest('base64url');
const authorize = (overrides = {}) => {
  const params = new URLSearchParams({
    response_type: 'code',
    client_id: 'e2e-client-id',
    redirect_uri: 'https://localhost/connect/x/callback',
    scope: 'tweet.read tweet.write users.read offline.access',
    state: '7.abc',
    code_challenge: challengeOf(VERIFIER),
    code_challenge_method: 'S256',
    ...overrides,
  });
  for (const [k, v] of [...params]) if (v === undefined || v === '') params.delete(k);
  return fetch(`${base}/i/oauth2/authorize?${params.toString()}`, { redirect: 'manual' });
};
const exchange = (code, { verifier = VERIFIER, auth = basic, redirect = 'https://localhost/connect/x/callback' } = {}) =>
  fetch(`${base}/2/oauth2/token`, {
    method: 'POST',
    headers: { Authorization: auth, 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      code,
      redirect_uri: redirect,
      ...(verifier ? { code_verifier: verifier } : {}),
    }).toString(),
  });

test('認可画面は redirect_uri へ code と state を付けて戻す', async () => {
  const r = await authorize();
  assert.equal(r.status, 302);
  const location = new URL(r.headers.get('location'));
  assert.equal(`${location.origin}${location.pathname}`, 'https://localhost/connect/x/callback');
  assert.equal(location.searchParams.get('state'), '7.abc');
  assert.ok(location.searchParams.get('code'));
});

test('認可コードは PKCE の検証子から決まる(同じチャレンジなら同じコード)', async () => {
  const a = new URL((await authorize()).headers.get('location')).searchParams.get('code');
  const b = new URL((await authorize()).headers.get('location')).searchParams.get('code');
  const c = new URL((await authorize({ code_challenge: challengeOf('another-verifier-value-another-verifier-value') })).headers.get('location')).searchParams.get('code');
  assert.equal(a, b);
  assert.notEqual(a, c);
});

test('認可画面: 不正なリクエストは 400(クライアント・response_type・PKCE・redirect_uri・スコープ)', async () => {
  for (const bad of [
    { client_id: 'other' },
    { response_type: 'token' },
    { code_challenge: '' },
    { code_challenge_method: 'plain' },
    { redirect_uri: '' },
    { scope: 'tweet.read' },
    { state: '' },
  ]) {
    const r = await authorize(bad);
    assert.equal(r.status, 400, JSON.stringify(bad));
  }
});

test('認可コードをトークンに交換でき、そのトークンで投稿できる', async () => {
  const code = new URL((await authorize()).headers.get('location')).searchParams.get('code');
  const r = await exchange(code);
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.equal(body.access_token, 'e2e-x-access-valid');
  assert.equal(body.refresh_token, 'e2e-x-refresh-valid');
  assert.equal(body.expires_in, 7200);
  assert.equal((await tweet(body.access_token)).status, 201);
});

test('認可コードの交換: 検証子の不一致・コード不正・クライアント認証なしは拒否する', async () => {
  const code = new URL((await authorize()).headers.get('location')).searchParams.get('code');
  const wrongVerifier = await exchange(code, { verifier: 'wrong-verifier-wrong-verifier-wrong-verifier-123' });
  assert.equal(wrongVerifier.status, 400);
  assert.equal((await wrongVerifier.json()).error, 'invalid_grant');
  assert.equal((await exchange('bogus')).status, 400);
  assert.equal((await exchange(code, { verifier: '' })).status, 400);
  assert.equal((await exchange(code, { redirect: '' })).status, 400);
  assert.equal((await exchange(code, { auth: 'Basic AAAA' })).status, 401);
});
