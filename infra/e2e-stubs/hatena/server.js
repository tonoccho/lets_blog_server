'use strict';
/**
 * はてなブックマーク(はてな OAuth 1.0a + ブックマーク REST API)のスタブ(issue #1582)。
 *
 * letsblog プラグイン(infra/wordpress/letsblog-plugin)のブックマーク送信処理と、project-service の接続処理が叩くのは以下だけ。
 * どれも OAuth 1.0a の署名(HMAC-SHA1)を**実際に検証する**(署名できていなければ 401)。
 *   POST /oauth/initiate           リクエストトークンの取得(scope に write_public と oauth_callback が要る)。form で oauth_token / oauth_token_secret を返す
 *   GET  /oauth/authorize          認可画面。利用者の操作なしで oauth_callback へ oauth_token と oauth_verifier を付けて戻す
 *   POST /oauth/token              oauth_verifier とリクエストトークンの秘密で署名 → アクセストークンとその秘密(form)
 *   GET  /applications/my.json     自分の情報(url_name, display_name)。read_public 相当。アクセストークンで署名
 *   POST /rest/1/my/bookmark       ブックマークの追加(url と comment)。comment は100文字まで(超えると 400)
 *
 * プラグインの API ベース URL は wp-config.php の定数 LETSBLOG_HATENA_API_BASE_URL で差し替える
 * (アプリから送る設定では変えられない)。e2e ではこのスタブの URL を指す。署名の対象 URL は、リクエストの Host ヘッダから組み立てる。
 *
 * 決定性: トークンは固定値で、状態は受信内容の記録だけ(認可画面の戻り先は、直前のリクエストトークン取得で渡された callback)。
 *   consumer                 e2e-hatena-consumer-key / e2e-hatena-consumer-secret
 *   リクエストトークン       e2e-hatena-request-token / e2e-hatena-request-secret
 *   verifier                 e2e-hatena-verifier
 *   有効なアクセストークン   e2e-hatena-token / e2e-hatena-token-secret
 *   失効したアクセストークン e2e-hatena-revoked / e2e-hatena-revoked-secret(署名が正しくても 401)
 *   ユーザー                 url_name e2e-hatena-user、display_name "Let's Blog E2E"
 * 受信したブックマークは `GET /__control/state` の `bookmarks`(url / comment)で確認できる(応答には出さない)。
 */
const crypto = require('node:crypto');
const { createStub } = require('../lib/stub');

const CONSUMER_KEY = 'e2e-hatena-consumer-key';
const CONSUMER_SECRET = 'e2e-hatena-consumer-secret';
const REQUEST_TOKEN = 'e2e-hatena-request-token';
const REQUEST_SECRET = 'e2e-hatena-request-secret';
const VERIFIER = 'e2e-hatena-verifier';
const ACCESS_TOKEN = 'e2e-hatena-token';
const ACCESS_SECRET = 'e2e-hatena-token-secret';
const REVOKED_TOKEN = 'e2e-hatena-revoked';
const REVOKED_SECRET = 'e2e-hatena-revoked-secret';
const URL_NAME = 'e2e-hatena-user';
const DISPLAY_NAME = "Let's Blog E2E";
const COMMENT_LIMIT = 100;

/** アクセストークン → その秘密。失効したトークンも署名の検証までは通す(その後で 401 にする)。 */
const ACCESS_SECRETS = { [ACCESS_TOKEN]: ACCESS_SECRET, [REVOKED_TOKEN]: REVOKED_SECRET };

let callback = null;
const bookmarks = [];

const enc = (s) => encodeURIComponent(s).replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);

/** Authorization: OAuth k="v", ... を { k: 値 } にする。OAuth でなければ null。 */
function parseOAuth(header) {
  if (!header || !header.startsWith('OAuth ')) return null;
  const params = {};
  for (const m of header.matchAll(/(oauth_[a-z_]+)="([^"]*)"/g)) {
    try {
      params[m[1]] = decodeURIComponent(m[2]);
    } catch {
      return null;
    }
  }
  return params;
}

/** 署名を検証する。tokenSecret は、そのリクエストが使うトークンの秘密(リクエストトークン取得では空)。 */
function signatureValid(req, method, pathname, formParams, oauth, tokenSecret) {
  if (!oauth || !oauth.oauth_signature || oauth.oauth_signature_method !== 'HMAC-SHA1' || oauth.oauth_version !== '1.0') return false;
  if (!oauth.oauth_nonce || !/^\d+$/.test(oauth.oauth_timestamp || '')) return false;
  const { oauth_signature: given, ...signed } = oauth;
  const all = { ...formParams, ...signed };
  const normalized = Object.keys(all)
    .map((k) => [enc(k), enc(all[k])])
    .sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : a[1] < b[1] ? -1 : a[1] > b[1] ? 1 : 0))
    .map(([k, v]) => `${k}=${v}`)
    .join('&');
  const url = `http://${req.headers.host}${pathname}`;
  const base = `${method}&${enc(url)}&${enc(normalized)}`;
  const expected = crypto.createHmac('sha1', `${enc(CONSUMER_SECRET)}&${enc(tokenSecret)}`).update(base).digest('base64');
  const a = Buffer.from(expected);
  const b = Buffer.from(given);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function formResponse(res, status, params) {
  res.writeHead(status, { 'Content-Type': 'application/x-www-form-urlencoded' });
  res.end(new URLSearchParams(params).toString());
}

function problem(res, status, name) {
  formResponse(res, status, { oauth_problem: name });
}

/** アクセストークンで署名された API リクエストか。違えば応答を返して null。 */
function authenticate(req, res, method, pathname, formParams) {
  const oauth = parseOAuth(req.headers.authorization);
  if (!oauth || oauth.oauth_consumer_key !== CONSUMER_KEY || !ACCESS_SECRETS[oauth.oauth_token]) {
    problem(res, 401, 'parameter_absent');
    return null;
  }
  if (!signatureValid(req, method, pathname, formParams, oauth, ACCESS_SECRETS[oauth.oauth_token])) {
    problem(res, 401, 'signature_invalid');
    return null;
  }
  if (oauth.oauth_token === REVOKED_TOKEN) {
    problem(res, 401, 'token_revoked');
    return null;
  }
  return oauth;
}

createStub({
  name: 'hatena',
  port: Number(process.env.PORT || 8080),
  errorBody: (status, name) => ({ message: `[${name}] forced ${status}` }),
  onReset: () => {
    callback = null;
    bookmarks.length = 0;
  },
  extraState: () => ({ bookmarks: [...bookmarks] }),
  async handle({ req, res, method, pathname, query, body, sendJson }) {
    if (method === 'POST' && pathname === '/oauth/initiate') {
      const form = Object.fromEntries(new URLSearchParams(body || ''));
      const oauth = parseOAuth(req.headers.authorization);
      if (!oauth || oauth.oauth_consumer_key !== CONSUMER_KEY || !signatureValid(req, method, pathname, form, oauth, '')) {
        problem(res, 401, 'signature_invalid');
        return true;
      }
      if (!(form.scope || '').split(',').includes('write_public') || !oauth.oauth_callback) {
        problem(res, 400, 'parameter_rejected');
        return true;
      }
      callback = oauth.oauth_callback;
      formResponse(res, 200, {
        oauth_token: REQUEST_TOKEN,
        oauth_token_secret: REQUEST_SECRET,
        oauth_callback_confirmed: 'true',
      });
      return true;
    }
    if (method === 'GET' && pathname === '/oauth/authorize') {
      if (query.get('oauth_token') !== REQUEST_TOKEN || !callback) {
        sendJson(res, 400, { message: 'invalid oauth_token' });
        return true;
      }
      const location = new URL(callback);
      location.searchParams.set('oauth_token', REQUEST_TOKEN);
      location.searchParams.set('oauth_verifier', VERIFIER);
      res.writeHead(302, { Location: location.toString() });
      res.end();
      return true;
    }
    if (method === 'POST' && pathname === '/oauth/token') {
      const form = Object.fromEntries(new URLSearchParams(body || ''));
      const oauth = parseOAuth(req.headers.authorization);
      if (
        !oauth ||
        oauth.oauth_consumer_key !== CONSUMER_KEY ||
        oauth.oauth_token !== REQUEST_TOKEN ||
        oauth.oauth_verifier !== VERIFIER ||
        !signatureValid(req, method, pathname, form, oauth, REQUEST_SECRET)
      ) {
        problem(res, 401, 'verifier_invalid');
        return true;
      }
      formResponse(res, 200, {
        oauth_token: ACCESS_TOKEN,
        oauth_token_secret: ACCESS_SECRET,
        url_name: URL_NAME,
        display_name: DISPLAY_NAME,
      });
      return true;
    }
    if (method === 'GET' && pathname === '/applications/my.json') {
      if (!authenticate(req, res, method, pathname, Object.fromEntries(query))) return true;
      sendJson(res, 200, { url_name: URL_NAME, display_name: DISPLAY_NAME, profile_image_url: '' });
      return true;
    }
    if (method === 'POST' && pathname === '/rest/1/my/bookmark') {
      const form = Object.fromEntries(new URLSearchParams(body || ''));
      if (!authenticate(req, res, method, pathname, form)) return true;
      const url = form.url || '';
      const comment = form.comment || '';
      if (!/^https?:\/\/\S+$/.test(url) || [...comment].length > COMMENT_LIMIT) {
        sendJson(res, 400, { message: 'Invalid url or comment (comment must be at most 100 characters).' });
        return true;
      }
      bookmarks.push({ url, comment });
      sendJson(res, 200, { url, comment, user: URL_NAME, eid: bookmarks.length });
      return true;
    }
    return false;
  },
});
