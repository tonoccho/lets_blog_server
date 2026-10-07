'use strict';
/**
 * はてなブックマーク スタブ(issue #1582)の契約テスト。実行: node --test infra/e2e-stubs/hatena/server.test.js
 * letsblog プラグインと project-service が叩くエンドポイント(OAuth 1.0a のリクエストトークン・認可画面・アクセストークン・
 * 自分の情報・ブックマークの追加)と、OAuth 1.0a(HMAC-SHA1)の署名を実際に検証していること、
 * 失効したトークンが 401 になること、コメントが100文字を超えると 400 になること、エラー注入・受信内容の確認を検証する。
 * 署名はこのテストの中で独立に計算する(スタブの実装を呼ばない)。
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

const CK = 'e2e-hatena-consumer-key';
const CS = 'e2e-hatena-consumer-secret';
const REQ_TOKEN = 'e2e-hatena-request-token';
const REQ_SECRET = 'e2e-hatena-request-secret';
const VERIFIER = 'e2e-hatena-verifier';
const TOKEN = 'e2e-hatena-token';
const TOKEN_SECRET = 'e2e-hatena-token-secret';
const REVOKED = 'e2e-hatena-revoked';
const REVOKED_SECRET = 'e2e-hatena-revoked-secret';

const enc = (s) => encodeURIComponent(s).replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);

/** OAuth 1.0a の Authorization ヘッダ。formParams は署名の対象になる本文のパラメータ。 */
function oauthHeader(method, url, formParams, { key = CK, secret = CS, token, tokenSecret = '', extra = {}, badSignature = false } = {}) {
  const oauth = {
    oauth_consumer_key: key,
    oauth_nonce: crypto.randomBytes(8).toString('hex'),
    oauth_signature_method: 'HMAC-SHA1',
    oauth_timestamp: String(Math.floor(Date.now() / 1000)),
    oauth_version: '1.0',
    ...extra,
  };
  if (token) oauth.oauth_token = token;
  const all = { ...formParams, ...oauth };
  const normalized = Object.keys(all)
    .sort()
    .map((k) => `${enc(k)}=${enc(all[k])}`)
    .join('&');
  const baseString = `${method}&${enc(url)}&${enc(normalized)}`;
  let signature = crypto.createHmac('sha1', `${enc(secret)}&${enc(tokenSecret)}`).update(baseString).digest('base64');
  if (badSignature) signature = 'AAAA' + signature.slice(4);
  oauth.oauth_signature = signature;
  return 'OAuth ' + Object.keys(oauth).sort().map((k) => `${k}="${enc(oauth[k])}"`).join(', ');
}

const form = (params) => new URLSearchParams(params).toString();

async function post(pathname, params, auth) {
  const url = `${base}${pathname}`;
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Authorization: oauthHeader('POST', url, params, auth) },
    body: form(params),
  });
}

const initiate = (overrides = {}, auth = {}) =>
  post('/oauth/initiate', { scope: 'read_public,write_public', ...overrides }, { extra: { oauth_callback: 'http://localhost/connect/hatena/callback?state=7.abc' }, ...auth });

test('リクエストトークンは署名・consumer・scope・callback が正しいときだけ発行される', async () => {
  await reset();
  const r = await initiate();
  assert.equal(r.status, 200);
  const body = new URLSearchParams(await r.text());
  assert.equal(body.get('oauth_token'), REQ_TOKEN);
  assert.equal(body.get('oauth_token_secret'), REQ_SECRET);
  assert.equal(body.get('oauth_callback_confirmed'), 'true');
});

test('リクエストトークン: 署名違い・consumer 違い・write_public なし・callback なしは 400/401', async () => {
  assert.equal((await initiate({}, { badSignature: true })).status, 401);
  assert.equal((await initiate({}, { key: 'other' })).status, 401);
  assert.equal((await initiate({}, { secret: 'wrong-secret' })).status, 401);
  assert.equal((await initiate({ scope: 'read_public' })).status, 400);
  assert.equal((await initiate({}, { extra: {} })).status, 400);
  assert.equal((await fetch(`${base}/oauth/initiate`, { method: 'POST', body: '' })).status, 401);
});

test('認可画面は利用者の操作なしで callback へ oauth_token と oauth_verifier を付けて戻す(state は保たれる)', async () => {
  await reset();
  await initiate();
  const r = await fetch(`${base}/oauth/authorize?oauth_token=${REQ_TOKEN}`, { redirect: 'manual' });
  assert.equal(r.status, 302);
  const location = new URL(r.headers.get('location'));
  assert.equal(location.origin + location.pathname, 'http://localhost/connect/hatena/callback');
  assert.equal(location.searchParams.get('state'), '7.abc');
  assert.equal(location.searchParams.get('oauth_token'), REQ_TOKEN);
  assert.equal(location.searchParams.get('oauth_verifier'), VERIFIER);
});

test('認可画面: 知らないリクエストトークンは 400', async () => {
  assert.equal((await fetch(`${base}/oauth/authorize?oauth_token=nope`, { redirect: 'manual' })).status, 400);
  assert.equal((await fetch(`${base}/oauth/authorize`, { redirect: 'manual' })).status, 400);
});

const token = (verifier = VERIFIER, auth = {}) =>
  post('/oauth/token', {}, { token: REQ_TOKEN, tokenSecret: REQ_SECRET, extra: { oauth_verifier: verifier }, ...auth });

test('verifier とリクエストトークンの秘密で署名すると、アクセストークンとその秘密に交換できる', async () => {
  const r = await token();
  assert.equal(r.status, 200);
  const body = new URLSearchParams(await r.text());
  assert.equal(body.get('oauth_token'), TOKEN);
  assert.equal(body.get('oauth_token_secret'), TOKEN_SECRET);
});

test('アクセストークンの取得: verifier 違い・秘密違い・署名違いは 401', async () => {
  assert.equal((await token('wrong')).status, 401);
  assert.equal((await token(VERIFIER, { tokenSecret: 'wrong' })).status, 401);
  assert.equal((await token(VERIFIER, { badSignature: true })).status, 401);
  assert.equal((await token(VERIFIER, { token: 'nope' })).status, 401);
});

async function get(pathname, auth) {
  const url = `${base}${pathname}`;
  return fetch(url, { headers: { Authorization: oauthHeader('GET', url, {}, auth) } });
}

test('自分の情報(my.json)は有効なアクセストークンのときだけ url_name と display_name を返す', async () => {
  const r = await get('/applications/my.json', { token: TOKEN, tokenSecret: TOKEN_SECRET });
  assert.equal(r.status, 200);
  const body = await r.json();
  assert.equal(body.url_name, 'e2e-hatena-user');
  assert.equal(body.display_name, "Let's Blog E2E");
  assert.equal((await get('/applications/my.json', { token: TOKEN, tokenSecret: 'wrong' })).status, 401);
  assert.equal((await get('/applications/my.json', { token: REVOKED, tokenSecret: REVOKED_SECRET })).status, 401);
  assert.equal((await fetch(`${base}/applications/my.json`)).status, 401);
});

const bookmark = (params, auth = { token: TOKEN, tokenSecret: TOKEN_SECRET }) => post('/rest/1/my/bookmark', params, auth);

test('署名の正しい POST でブックマークでき、url と comment が受信内容に残る', async () => {
  await reset();
  const r = await bookmark({ url: 'https://example.test/a?x=1&y=2', comment: '題名 & 記号 = + "引用"' });
  assert.equal(r.status, 200);
  assert.equal((await r.json()).url, 'https://example.test/a?x=1&y=2');
  assert.deepEqual((await state()).bookmarks, [{ url: 'https://example.test/a?x=1&y=2', comment: '題名 & 記号 = + "引用"' }]);
});

test('comment なしでもブックマークできる(comment は空)', async () => {
  await reset();
  assert.equal((await bookmark({ url: 'https://example.test/b' })).status, 200);
  assert.deepEqual((await state()).bookmarks, [{ url: 'https://example.test/b', comment: '' }]);
});

test('ブックマーク: 署名違い・秘密違い・失効トークン・Authorization なしは 401 で、何も記録されない', async () => {
  await reset();
  assert.equal((await bookmark({ url: 'https://example.test/a' }, { token: TOKEN, tokenSecret: TOKEN_SECRET, badSignature: true })).status, 401);
  assert.equal((await bookmark({ url: 'https://example.test/a' }, { token: TOKEN, tokenSecret: 'wrong' })).status, 401);
  assert.equal((await bookmark({ url: 'https://example.test/a' }, { token: REVOKED, tokenSecret: REVOKED_SECRET })).status, 401);
  assert.equal((await bookmark({ url: 'https://example.test/a' }, { token: TOKEN, tokenSecret: TOKEN_SECRET, secret: 'wrong' })).status, 401);
  assert.equal((await fetch(`${base}/rest/1/my/bookmark`, { method: 'POST', body: form({ url: 'https://example.test/a' }) })).status, 401);
  assert.deepEqual((await state()).bookmarks, []);
});

test('ブックマーク: url が無い・http(s) でない・comment が100文字を超えるときは 400', async () => {
  await reset();
  assert.equal((await bookmark({ comment: 'x' })).status, 400);
  assert.equal((await bookmark({ url: 'ftp://example.test/a' })).status, 400);
  assert.equal((await bookmark({ url: 'https://example.test/a', comment: 'あ'.repeat(101) })).status, 400);
  assert.equal((await bookmark({ url: 'https://example.test/a', comment: 'あ'.repeat(100) })).status, 200);
  assert.equal((await state()).bookmarks.length, 1);
});

test('制御エンドポイントでエラーを注入できる', async () => {
  await reset();
  await fetch(`${base}/__control/force`, { method: 'POST', body: JSON.stringify({ status: 429, count: 1 }) });
  assert.equal((await bookmark({ url: 'https://example.test/a' })).status, 429);
  assert.equal((await bookmark({ url: 'https://example.test/a' })).status, 200);
});

test('未対応のパスは 404', async () => {
  assert.equal((await fetch(`${base}/unknown/path/x`)).status, 404);
});
