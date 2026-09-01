'use strict';
/**
 * Google Analytics Data API v1beta のスタブ(issue #928 / AT-2)。
 *
 * analytics-service の GoogleAnalyticsClient は次の2つを叩く。
 *   1. サービスアカウントJWT → アクセストークン交換(POST {tokenUri})
 *   2. レポート取得(POST {dataApiBaseUrl}/v1beta/properties/{propertyId}:runReport)
 *
 * トークン交換の向き先はサービスアカウントJSONの token_uri が優先され、
 * 未設定のときだけ GOOGLE_ANALYTICS_OAUTH_TOKEN_URI が使われる。
 * したがってスタブ用のサービスアカウントJSONには token_uri を書かないこと
 * (docs/ACCEPTANCE_TESTING.md に明記した)。
 *
 * 資格情報不正の再現: サービスアカウントJSONの client_email が
 * `invalid@` で始まるときトークン交換を401にする。制御エンドポイントを使わずに
 * 「不正な資格情報を登録したらどうなるか」を検証できるようにするため。
 */
const { createStub } = require('../lib/stub');

/** 決定的な指標。シナリオはこの値をそのままアサートできる。 */
const METRIC_VALUES = {
  activeUsers: '1234',
  screenPageViews: '5678',
  sessions: '2345',
  bounceRate: '0.42',
  averageSessionDuration: '123.45',
};

const DAILY_ROWS = [
  { date: '20260825', activeUsers: '100', screenPageViews: '410' },
  { date: '20260826', activeUsers: '180', screenPageViews: '760' },
  { date: '20260827', activeUsers: '210', screenPageViews: '880' },
  { date: '20260828', activeUsers: '150', screenPageViews: '620' },
  { date: '20260829', activeUsers: '240', screenPageViews: '990' },
  { date: '20260830', activeUsers: '190', screenPageViews: '810' },
  { date: '20260831', activeUsers: '164', screenPageViews: '1208' },
];

const PAGE_ROWS = [
  { path: '/e2e-stub/first-post', views: '820' },
  { path: '/e2e-stub/second-post', views: '410' },
  { path: '/', views: '260' },
];

/** リクエストが要求した指標名の配列を取り出す。 */
function requestedMetrics(payload) {
  return (payload.metrics || []).map((m) => m.name).filter(Boolean);
}

function requestedDimensions(payload) {
  return (payload.dimensions || []).map((d) => d.name).filter(Boolean);
}

function metricHeaders(names) {
  return names.map((name) => ({ name, type: 'TYPE_INTEGER' }));
}

function runReport(payload) {
  const metrics = requestedMetrics(payload);
  const dimensions = requestedDimensions(payload);

  // ディメンション無し = 集計値のみ。
  if (dimensions.length === 0) {
    return {
      dimensionHeaders: [],
      metricHeaders: metricHeaders(metrics),
      rows: [{ dimensionValues: [], metricValues: metrics.map((m) => ({ value: METRIC_VALUES[m] ?? '0' })) }],
      rowCount: 1,
      kind: 'analyticsData#runReport',
    };
  }

  const source = dimensions[0] === 'date' ? DAILY_ROWS : PAGE_ROWS;
  return {
    dimensionHeaders: dimensions.map((name) => ({ name })),
    metricHeaders: metricHeaders(metrics),
    rows: source.map((row) => ({
      dimensionValues: dimensions.map((d) => ({ value: d === 'date' ? row.date : row.path })),
      metricValues: metrics.map((m) => {
        if (m === 'activeUsers') return { value: row.activeUsers ?? row.views ?? '0' };
        if (m === 'screenPageViews') return { value: row.screenPageViews ?? row.views ?? '0' };
        return { value: METRIC_VALUES[m] ?? '0' };
      }),
    })),
    rowCount: source.length,
    kind: 'analyticsData#runReport',
  };
}

createStub({
  name: 'google-analytics',
  port: Number(process.env.PORT || 8080),
  async handle({ method, pathname, body, res, sendJson }) {
    // 1. サービスアカウントJWT → アクセストークン。
    if (method === 'POST' && (pathname === '/token' || pathname === '/oauth2/token')) {
      // JWTのペイロードに invalid@ の issuer が入っていたら資格情報不正として扱う。
      if (/invalid%40|invalid@/.test(body) || decodedIssuerIsInvalid(body)) {
        sendJson(res, 401, {
          error: 'invalid_grant',
          error_description: '[stub] サービスアカウントの資格情報が不正です',
        });
        return true;
      }
      sendJson(res, 200, {
        access_token: 'e2e-stub-ga-access-token',
        token_type: 'Bearer',
        expires_in: 3599,
      });
      return true;
    }

    // 2. レポート取得。
    if (method === 'POST' && /^\/v1beta\/properties\/[^/]+:runReport$/.test(pathname)) {
      let payload = {};
      try {
        payload = JSON.parse(body || '{}');
      } catch {
        sendJson(res, 400, { error: { code: 400, message: '[stub] invalid JSON' } });
        return true;
      }
      sendJson(res, 200, runReport(payload));
      return true;
    }

    return false;
  },
});

/**
 * form-urlencoded の assertion=<JWT> からペイロードを覗き、iss が invalid@ かを判定する。
 * 署名は検証しない(スタブなので鍵を持たない)。
 */
function decodedIssuerIsInvalid(body) {
  const m = /assertion=([^&]+)/.exec(body || '');
  if (!m) return false;
  const parts = decodeURIComponent(m[1]).split('.');
  if (parts.length < 2) return false;
  try {
    const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
    return typeof payload.iss === 'string' && payload.iss.startsWith('invalid@');
  } catch {
    return false;
  }
}
