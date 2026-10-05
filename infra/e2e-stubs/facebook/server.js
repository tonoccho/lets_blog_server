'use strict';
/**
 * Facebook(Graph API)のスタブ(issue #1580)。
 *
 * letsblog プラグイン(infra/wordpress/letsblog-plugin)の Facebook ページ送信処理と、project-service の接続処理が叩くのは以下だけ。
 *   GET  /dialog/oauth                認可画面。利用者の操作なしで redirect_uri へ code と state を付けて戻す
 *   GET  /oauth/access_token          code → 短期ユーザートークン / grant_type=fb_exchange_token → 長期ユーザートークン(約60日)
 *   GET  /me/accounts                 管理しているページと、そのページのトークン(長期ユーザートークンで呼ぶ)
 *   POST /{page_id}/feed              ページのフィードへ message / link を投稿(ページのトークンで呼ぶ)
 *
 * プラグインの API ベース URL は wp-config.php の定数 LETSBLOG_FACEBOOK_API_BASE_URL で差し替える
 * (アプリから送る設定では変えられない)。e2e ではこのスタブの URL を指す。
 *
 * 決定性: トークンは固定値で、状態は受信内容の記録だけ。
 *   アプリ                   e2e-facebook-app-id / e2e-facebook-app-secret
 *   認可コード               e2e-facebook-code
 *   短期ユーザートークン     e2e-facebook-user-short(長期トークン化にだけ使える)
 *   長期ユーザートークン     e2e-facebook-user-long(/me/accounts にだけ使える。フィードへは投稿できない=個人アカウントには投稿できない)
 *   管理しているページ       100000000000001 「Let's Blog E2E ページ」(トークン e2e-facebook-page-token。投稿できる)
 *                            100000000000002 「権限のないページ」(トークン e2e-facebook-page-token-noperm。pages_manage_posts が無く 403)
 * 受信した投稿は `GET /__control/state` の `posts`({page_id, message, link})で確認できる(応答には出さない)。
 */
const { createStub } = require('../lib/stub');

const APP_ID = 'e2e-facebook-app-id';
const APP_SECRET = 'e2e-facebook-app-secret';
const CODE = 'e2e-facebook-code';
const USER_SHORT = 'e2e-facebook-user-short';
const USER_LONG = 'e2e-facebook-user-long';
const LONG_EXPIRES_IN = 5184000;
const REQUIRED_SCOPES = ['pages_show_list', 'pages_manage_posts', 'pages_read_engagement'];
const PAGES = [
  { id: '100000000000001', name: "Let's Blog E2E ページ", access_token: 'e2e-facebook-page-token', canPost: true },
  { id: '100000000000002', name: '権限のないページ', access_token: 'e2e-facebook-page-token-noperm', canPost: false },
];

const posts = [];

function bearer(req) {
  const m = /^Bearer (.+)$/.exec(req.headers.authorization || '');
  return m ? m[1] : null;
}

function graphError(sendJson, res, status, message, code, type = 'OAuthException') {
  sendJson(res, status, { error: { message, type, code } });
}

createStub({
  name: 'facebook',
  port: Number(process.env.PORT || 8080),
  errorBody: (status, name) => ({ error: { message: `[${name}] forced ${status}`, type: 'Forced', code: status } }),
  onReset: () => {
    posts.length = 0;
  },
  extraState: () => ({ posts: [...posts] }),
  async handle({ method, pathname, query, req, res, body, sendJson }) {
    if (method === 'GET' && pathname === '/dialog/oauth') {
      const scopes = (query.get('scope') || '').split(/[ ,]/);
      const redirectUri = query.get('redirect_uri') || '';
      if (
        query.get('client_id') !== APP_ID ||
        query.get('response_type') !== 'code' ||
        !redirectUri ||
        !query.get('state') ||
        !REQUIRED_SCOPES.every((scope) => scopes.includes(scope))
      ) {
        graphError(sendJson, res, 400, 'Invalid authorization request.', 100);
        return true;
      }
      const location = new URL(redirectUri);
      location.searchParams.set('code', CODE);
      location.searchParams.set('state', query.get('state'));
      res.writeHead(302, { Location: location.toString() });
      res.end();
      return true;
    }
    if (method === 'GET' && pathname === '/oauth/access_token') {
      if (query.get('grant_type') === 'fb_exchange_token') {
        if (
          query.get('client_id') !== APP_ID ||
          query.get('client_secret') !== APP_SECRET ||
          query.get('fb_exchange_token') !== USER_SHORT
        ) {
          graphError(sendJson, res, 400, 'Invalid token exchange request.', 100);
          return true;
        }
        sendJson(res, 200, { access_token: USER_LONG, token_type: 'bearer', expires_in: LONG_EXPIRES_IN });
        return true;
      }
      if (
        query.get('client_id') !== APP_ID ||
        query.get('client_secret') !== APP_SECRET ||
        !query.get('redirect_uri') ||
        query.get('code') !== CODE
      ) {
        graphError(sendJson, res, 400, 'Invalid or expired verification code.', 100);
        return true;
      }
      sendJson(res, 200, { access_token: USER_SHORT, token_type: 'bearer', expires_in: 3600 });
      return true;
    }
    if (method === 'GET' && pathname === '/me/accounts') {
      if (bearer(req) !== USER_LONG) {
        graphError(sendJson, res, 401, 'Invalid OAuth access token.', 190);
        return true;
      }
      sendJson(res, 200, { data: PAGES.map(({ id, name, access_token }) => ({ id, name, access_token })) });
      return true;
    }
    const feedMatch = /^\/([^/]+)\/feed$/.exec(pathname);
    if (method === 'POST' && feedMatch) {
      const token = bearer(req) || '';
      if (token === USER_LONG) {
        // 個人アカウント(ユーザートークン)には投稿できない。
        graphError(sendJson, res, 403, '(#200) Posting to a personal account is not permitted. Use a Page access token.', 200);
        return true;
      }
      const page = PAGES.find((p) => p.access_token === token);
      if (!page) {
        graphError(sendJson, res, 400, 'Error validating access token: Session has expired', 190);
        return true;
      }
      if (feedMatch[1] !== page.id) {
        graphError(sendJson, res, 400, 'Error validating access token: the token does not belong to this page', 190);
        return true;
      }
      if (!page.canPost) {
        graphError(sendJson, res, 403, "(#200) If posting to a page, requires both pages_manage_posts and pages_read_engagement permission", 200);
        return true;
      }
      const form = new URLSearchParams(body || '');
      const message = form.get('message');
      const link = form.get('link');
      if (!message && !link) {
        graphError(sendJson, res, 400, '(#100) The message or link parameter is required.', 100, 'ApiException');
        return true;
      }
      posts.push({ page_id: page.id, message: message || null, link: link || null });
      sendJson(res, 200, { id: `${page.id}_${posts.length}` });
      return true;
    }
    return false;
  },
});
