'use strict';
/**
 * Threads API のスタブ(issue #1579)。
 *
 * letsblog プラグイン(infra/wordpress/letsblog-plugin)の Threads 送信処理と、project-service の接続処理が叩くのは以下だけ。
 *   GET  /oauth/authorize          認可画面。利用者の操作なしで redirect_uri へ code と state を付けて戻す
 *   POST /oauth/access_token       grant_type=authorization_code(client_secret はフォームで送る)→ 短期トークンと user_id(JS の数値では精度が足りないので文字列で返す)
 *   GET  /access_token             grant_type=th_exchange_token → 長期トークン(約60日)
 *   GET  /refresh_access_token     grant_type=th_refresh_token → 更新した長期トークン
 *   POST /v1.0/{user_id}/threads          media_type=TEXT&text=...(コンテナの作成)
 *   POST /v1.0/{user_id}/threads_publish  creation_id=...(公開。ここで初めて投稿になる)
 *   GET  /v1.0/me                  自分の情報(id, username)
 *
 * プラグインの API ベース URL は wp-config.php の定数 LETSBLOG_THREADS_API_BASE_URL で差し替える
 * (アプリから送る設定では変えられない)。e2e ではこのスタブの URL を指す。
 *
 * 決定性: トークンは固定値で、状態は受信内容の記録だけ。
 *   アプリ                   e2e-threads-app-id / e2e-threads-app-secret
 *   認可コード               e2e-threads-code
 *   短期トークン             e2e-threads-short(長期トークン化にだけ使える)
 *   有効な長期トークン       e2e-threads-long / e2e-threads-refreshed(更新するとどちらも e2e-threads-refreshed を返す)
 *   期限切れのトークン       e2e-threads-expired(401 / 更新は 400)
 * 本番の Threads と同様に、更新は「期限前の長期トークン」だけが受けられる。発行から24時間以上たっているか
 * (本物はこれも検査する)は、時刻を持たないスタブでは判定せず、プラグインの側で守る。
 * 受信した投稿の本文は `GET /__control/state` の `posts`、更新に使われたトークンは `refreshes` で確認できる(応答には出さない)。
 */
const { createStub } = require('../lib/stub');

const APP_ID = 'e2e-threads-app-id';
const APP_SECRET = 'e2e-threads-app-secret';
const CODE = 'e2e-threads-code';
const SHORT = 'e2e-threads-short';
const VALID_LONG = new Set(['e2e-threads-long', 'e2e-threads-refreshed']);
const USER_ID = '17841400000000001';
const LONG_EXPIRES_IN = 5184000;
const REQUIRED_SCOPES = ['threads_basic', 'threads_content_publish'];
const TEXT_LIMIT = 500;

const posts = [];
const refreshes = [];
const containers = new Map();

function bearer(req) {
  const m = /^Bearer (.+)$/.exec(req.headers.authorization || '');
  return m ? m[1] : null;
}

function oauthError(sendJson, res, status, message) {
  sendJson(res, status, { error: { message, type: 'OAuthException', code: 190 } });
}

function apiError(sendJson, res, message, code = 100) {
  sendJson(res, 400, { error: { message, type: 'ApiException', code } });
}

/** 本物の Graph API と同様、パラメータはクエリでもフォーム本文でも受ける。 */
function params(query, body) {
  const merged = new URLSearchParams(query.toString());
  for (const [k, v] of new URLSearchParams(body || '')) merged.set(k, v);
  return merged;
}

createStub({
  name: 'threads',
  port: Number(process.env.PORT || 8080),
  errorBody: (status, name) => ({ error: { message: `[${name}] forced ${status}`, type: 'Forced', code: status } }),
  onReset: () => {
    posts.length = 0;
    refreshes.length = 0;
    containers.clear();
  },
  extraState: () => ({ posts: [...posts], refreshes: [...refreshes] }),
  async handle({ method, pathname, query, req, res, body, sendJson }) {
    if (method === 'GET' && pathname === '/oauth/authorize') {
      const scopes = (query.get('scope') || '').split(/[ ,]/);
      const redirectUri = query.get('redirect_uri') || '';
      if (
        query.get('client_id') !== APP_ID ||
        query.get('response_type') !== 'code' ||
        !redirectUri ||
        !query.get('state') ||
        !REQUIRED_SCOPES.every((scope) => scopes.includes(scope))
      ) {
        sendJson(res, 400, { error: { message: 'Invalid authorization request.', type: 'OAuthException', code: 100 } });
        return true;
      }
      const location = new URL(redirectUri);
      location.searchParams.set('code', CODE);
      location.searchParams.set('state', query.get('state'));
      res.writeHead(302, { Location: location.toString() });
      res.end();
      return true;
    }
    if (method === 'POST' && pathname === '/oauth/access_token') {
      const form = params(query, body);
      if (
        form.get('client_id') !== APP_ID ||
        form.get('client_secret') !== APP_SECRET ||
        form.get('grant_type') !== 'authorization_code' ||
        !form.get('redirect_uri') ||
        form.get('code') !== CODE
      ) {
        sendJson(res, 400, { error: { message: 'Invalid or expired authorization code.', type: 'OAuthException', code: 100 } });
        return true;
      }
      sendJson(res, 200, { access_token: SHORT, user_id: USER_ID });
      return true;
    }
    if (method === 'GET' && pathname === '/access_token') {
      if (
        query.get('grant_type') !== 'th_exchange_token' ||
        query.get('client_secret') !== APP_SECRET ||
        query.get('access_token') !== SHORT
      ) {
        oauthError(sendJson, res, 400, 'Invalid token exchange request.');
        return true;
      }
      sendJson(res, 200, { access_token: 'e2e-threads-long', token_type: 'bearer', expires_in: LONG_EXPIRES_IN });
      return true;
    }
    if (method === 'GET' && pathname === '/refresh_access_token') {
      const token = query.get('access_token') || '';
      if (query.get('grant_type') !== 'th_refresh_token' || !VALID_LONG.has(token)) {
        oauthError(sendJson, res, 400, 'Invalid OAuth access token.');
        return true;
      }
      refreshes.push(token);
      sendJson(res, 200, { access_token: 'e2e-threads-refreshed', token_type: 'bearer', expires_in: LONG_EXPIRES_IN });
      return true;
    }
    if (method === 'GET' && pathname === '/v1.0/me') {
      if (!VALID_LONG.has(bearer(req) || '')) {
        oauthError(sendJson, res, 401, 'Invalid OAuth access token.');
        return true;
      }
      sendJson(res, 200, { id: USER_ID, username: 'lets_blog_e2e' });
      return true;
    }
    const publishMatch = /^\/v1\.0\/([^/]+)\/(threads|threads_publish)$/.exec(pathname);
    if (method === 'POST' && publishMatch) {
      if (!VALID_LONG.has(bearer(req) || '')) {
        oauthError(sendJson, res, 401, 'Invalid OAuth access token.');
        return true;
      }
      if (publishMatch[1] !== USER_ID) {
        apiError(sendJson, res, 'Unknown user.');
        return true;
      }
      const form = params(query, body);
      if (publishMatch[2] === 'threads') {
        const text = form.get('text') || '';
        if (form.get('media_type') !== 'TEXT' || !text) {
          apiError(sendJson, res, 'media_type must be TEXT and text is required.');
          return true;
        }
        if ([...text].length > TEXT_LIMIT) {
          apiError(sendJson, res, `The text is longer than ${TEXT_LIMIT} characters.`);
          return true;
        }
        const id = `e2e-container-${containers.size + 1}`;
        containers.set(id, text);
        sendJson(res, 200, { id });
        return true;
      }
      const creationId = form.get('creation_id') || '';
      if (!containers.has(creationId)) {
        apiError(sendJson, res, 'Unknown creation_id.', 24);
        return true;
      }
      posts.push(containers.get(creationId));
      containers.delete(creationId);
      sendJson(res, 200, { id: `e2e-thread-${posts.length}` });
      return true;
    }
    return false;
  },
});
