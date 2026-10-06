'use strict';
/**
 * LinkedIn API のスタブ(issue #1581)。
 *
 * letsblog プラグイン(infra/wordpress/letsblog-plugin)の LinkedIn 送信処理と、project-service の接続処理が叩くのは以下だけ。
 *   GET  /oauth/v2/authorization   認可画面。利用者の操作なしで redirect_uri へ code と state を付けて戻す
 *   POST /oauth/v2/accessToken     grant_type=authorization_code(client_secret はフォームで送る)→ アクセストークン(60日)。refresh token は返さない
 *   GET  /v2/userinfo              自分の情報(sub, name)。Bearer
 *   POST /v2/ugcPosts              ugcPosts への投稿(ARTICLE + originalUrl、または NONE)。Bearer と X-Restli-Protocol-Version: 2.0.0。201 と X-RestLi-Id
 *
 * プラグインの API ベース URL は wp-config.php の定数 LETSBLOG_LINKEDIN_API_BASE_URL で差し替える
 * (アプリから送る設定では変えられない)。e2e ではこのスタブの URL を指す。
 *
 * 決定性: トークンは固定値で、状態は受信内容の記録だけ。
 *   アプリ                 e2e-linkedin-app-id / e2e-linkedin-app-secret
 *   認可コード             e2e-linkedin-code
 *   有効なアクセストークン e2e-linkedin-token
 *   拒否されるトークン     e2e-linkedin-expired(userinfo・ugcPosts とも 401)
 *   メンバー(sub)          e2e-linkedin-sub(名前 "Let's Blog E2E")
 * 受信した投稿は `GET /__control/state` の `posts`(author / text / link / category / visibility)で確認できる(応答には出さない)。
 */
const { createStub } = require('../lib/stub');

const APP_ID = 'e2e-linkedin-app-id';
const APP_SECRET = 'e2e-linkedin-app-secret';
const CODE = 'e2e-linkedin-code';
const VALID_TOKEN = 'e2e-linkedin-token';
const SUB = 'e2e-linkedin-sub';
const NAME = "Let's Blog E2E";
const EXPIRES_IN = 5184000;
const REQUIRED_SCOPES = ['openid', 'profile', 'w_member_social'];
const SHARE = 'com.linkedin.ugc.ShareContent';
const VISIBILITY = 'com.linkedin.ugc.MemberNetworkVisibility';

const posts = [];

function bearer(req) {
  const m = /^Bearer (.+)$/.exec(req.headers.authorization || '');
  return m ? m[1] : null;
}

function apiError(sendJson, res, status, message) {
  sendJson(res, status, { status, message, serviceErrorCode: status === 401 ? 65601 : 100 });
}

createStub({
  name: 'linkedin',
  port: Number(process.env.PORT || 8080),
  errorBody: (status, name) => ({ status, message: `[${name}] forced ${status}`, serviceErrorCode: 0 }),
  onReset: () => {
    posts.length = 0;
  },
  extraState: () => ({ posts: [...posts] }),
  async handle({ method, pathname, query, req, res, body, sendJson }) {
    if (method === 'GET' && pathname === '/oauth/v2/authorization') {
      const scopes = (query.get('scope') || '').split(/[ ,]+/);
      const redirectUri = query.get('redirect_uri') || '';
      if (
        query.get('client_id') !== APP_ID ||
        query.get('response_type') !== 'code' ||
        !redirectUri ||
        !query.get('state') ||
        !REQUIRED_SCOPES.every((scope) => scopes.includes(scope))
      ) {
        sendJson(res, 400, { error: 'invalid_request', error_description: 'Invalid authorization request.' });
        return true;
      }
      const location = new URL(redirectUri);
      location.searchParams.set('code', CODE);
      location.searchParams.set('state', query.get('state'));
      res.writeHead(302, { Location: location.toString() });
      res.end();
      return true;
    }
    if (method === 'POST' && pathname === '/oauth/v2/accessToken') {
      const form = new URLSearchParams(body || '');
      for (const [k, v] of query) if (!form.has(k)) form.set(k, v);
      if (
        form.get('client_id') !== APP_ID ||
        form.get('client_secret') !== APP_SECRET ||
        form.get('grant_type') !== 'authorization_code' ||
        !form.get('redirect_uri') ||
        form.get('code') !== CODE
      ) {
        sendJson(res, 400, { error: 'invalid_request', error_description: 'Invalid or expired authorization code.' });
        return true;
      }
      sendJson(res, 200, {
        access_token: VALID_TOKEN,
        expires_in: EXPIRES_IN,
        scope: REQUIRED_SCOPES.join(','),
        token_type: 'Bearer',
      });
      return true;
    }
    if (method === 'GET' && pathname === '/v2/userinfo') {
      if (bearer(req) !== VALID_TOKEN) {
        apiError(sendJson, res, 401, 'Invalid access token');
        return true;
      }
      sendJson(res, 200, { sub: SUB, name: NAME, given_name: 'Let\'s', family_name: 'Blog' });
      return true;
    }
    if (method === 'POST' && pathname === '/v2/ugcPosts') {
      if (bearer(req) !== VALID_TOKEN) {
        apiError(sendJson, res, 401, 'Invalid access token');
        return true;
      }
      if (req.headers['x-restli-protocol-version'] !== '2.0.0') {
        apiError(sendJson, res, 400, 'X-Restli-Protocol-Version must be 2.0.0');
        return true;
      }
      let payload;
      try {
        payload = JSON.parse(body || '');
      } catch {
        apiError(sendJson, res, 400, 'Invalid JSON body');
        return true;
      }
      const content = (payload.specificContent || {})[SHARE] || {};
      const text = (content.shareCommentary || {}).text || '';
      const category = content.shareMediaCategory;
      const link = category === 'ARTICLE' && Array.isArray(content.media) && content.media[0] ? content.media[0].originalUrl || null : null;
      if (
        payload.author !== `urn:li:person:${SUB}` ||
        payload.lifecycleState !== 'PUBLISHED' ||
        (payload.visibility || {})[VISIBILITY] !== 'PUBLIC' ||
        !text ||
        (category !== 'NONE' && category !== 'ARTICLE') ||
        (category === 'ARTICLE' && !link)
      ) {
        apiError(sendJson, res, 400, 'Invalid ugcPost payload.');
        return true;
      }
      posts.push({ author: payload.author, text, link, category, visibility: 'PUBLIC' });
      const id = `urn:li:share:e2e-${posts.length}`;
      sendJson(res, 201, { id }, { 'X-RestLi-Id': id });
      return true;
    }
    return false;
  },
});
