'use strict';
/**
 * X API(v2)のスタブ(issue #1573)。
 *
 * letsblog プラグイン(infra/wordpress/letsblog-plugin)の X 送信処理が叩くのは以下だけ。
 *   POST /2/oauth2/token   grant_type=refresh_token(confidential client: Authorization: Basic base64(client_id:client_secret))
 *   POST /2/tweets         {"text": "..."}(Authorization: Bearer <access token>)
 *   GET  /2/users/me       (Authorization: Bearer <access token>)
 *
 * プラグインの API ベース URL は wp-config.php の定数 LETSBLOG_X_API_BASE_URL で差し替える
 * (アプリから送る設定では変えられない)。e2e ではこのスタブの URL を指す。
 *
 * 決定性: トークンは固定値で、状態を持たない(refresh はどちらの有効な refresh token でも同じ組を返す)。
 *   有効なアクセストークン   e2e-x-access-valid / e2e-x-access-refreshed
 *   期限切れのアクセストークン e2e-x-access-expired(401)
 *   有効な refresh token     e2e-x-refresh-valid / e2e-x-refresh-rotated
 *   クライアント             e2e-client-id / e2e-client-secret
 * 受信した投稿の本文は `GET /__control/state` の `tweets` で確認できる(応答には出さない)。
 */
const { createStub } = require('../lib/stub');

const VALID_ACCESS = new Set(['e2e-x-access-valid', 'e2e-x-access-refreshed']);
const VALID_REFRESH = new Set(['e2e-x-refresh-valid', 'e2e-x-refresh-rotated']);
const CLIENT_BASIC = 'Basic ' + Buffer.from('e2e-client-id:e2e-client-secret').toString('base64');

const tweets = [];

function bearer(req) {
  const m = /^Bearer (.+)$/.exec(req.headers.authorization || '');
  return m ? m[1] : null;
}

function unauthorized(res, sendJson) {
  sendJson(res, 401, { title: 'Unauthorized', type: 'about:blank', status: 401, detail: 'Unauthorized' });
}

createStub({
  name: 'x',
  port: Number(process.env.PORT || 8080),
  errorBody: (status, name) => ({ title: 'Forced', type: 'about:blank', status, detail: `[${name}] forced ${status}` }),
  onReset: () => {
    tweets.length = 0;
  },
  extraState: () => ({ tweets: [...tweets] }),
  async handle({ method, pathname, req, res, body, sendJson }) {
    if (method === 'POST' && pathname === '/2/oauth2/token') {
      if (req.headers.authorization !== CLIENT_BASIC) {
        sendJson(res, 401, { error: 'unauthorized_client', error_description: 'Missing or invalid client credentials.' });
        return true;
      }
      const form = new URLSearchParams(body);
      if (form.get('grant_type') !== 'refresh_token' || !VALID_REFRESH.has(form.get('refresh_token') || '')) {
        sendJson(res, 400, { error: 'invalid_grant', error_description: 'Value passed for the token was invalid.' });
        return true;
      }
      sendJson(res, 200, {
        token_type: 'bearer',
        expires_in: 7200,
        access_token: 'e2e-x-access-refreshed',
        scope: 'tweet.read tweet.write users.read offline.access',
        refresh_token: 'e2e-x-refresh-rotated',
      });
      return true;
    }
    if (method === 'POST' && pathname === '/2/tweets') {
      if (!VALID_ACCESS.has(bearer(req) || '')) {
        unauthorized(res, sendJson);
        return true;
      }
      let text = '';
      try {
        text = String(JSON.parse(body || '{}').text ?? '');
      } catch {
        sendJson(res, 400, { title: 'Invalid Request', status: 400, detail: 'body must be JSON' });
        return true;
      }
      tweets.push(text);
      sendJson(res, 201, { data: { id: '1900000000000000001', text } });
      return true;
    }
    if (method === 'GET' && pathname === '/2/users/me') {
      if (!VALID_ACCESS.has(bearer(req) || '')) {
        unauthorized(res, sendJson);
        return true;
      }
      sendJson(res, 200, { data: { id: '1900000000000000000', name: 'Lets Blog E2E', username: 'lets_blog_e2e' } });
      return true;
    }
    return false;
  },
});
