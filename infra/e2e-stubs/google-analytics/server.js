'use strict';
/**
 * Google Analytics Admin API v1beta / Data API v1beta / Google OAuth のスタブ(issue #928 / AT-2、
 * issue #1231でサービスアカウントJWTからユーザーOAuthへ移行)。
 *
 * analytics-service の GoogleAnalyticsClient は次を叩く。
 *   1. OAuth トークン交換(POST {GOOGLE_ANALYTICS_OAUTH_TOKEN_URI})
 *      - authorization_code: 認可コード → アクセストークン + リフレッシュトークン(初回連携)
 *      - refresh_token: リフレッシュトークン → アクセストークン(以降)
 *   2. プロパティ一覧(GET {GOOGLE_ANALYTICS_ADMIN_API_BASE_URL}/v1beta/accountSummaries、2ページ)
 *   3. レポート取得(POST {dataApiBaseUrl}/v1beta/properties/{propertyId}:runReport)
 *
 * 資格情報不正の再現(制御エンドポイント無し):
 *   - 認可コード `e2e-stub-invalid-code` はトークン交換を401にする。
 *   - 認可コード `e2e-stub-ga-expired-code` は「後で失効するリフレッシュトークン」
 *     `e2e-stub-ga-invalid-refresh` を返し、そのリフレッシュトークンでのアクセストークン取得は401になる。
 *
 * Admin API / Data API はBearerのアクセストークンを検証する(保存済みリフレッシュトークンから
 * 取得したトークンで呼ばれたことを受け入れテストで確かめるため)。
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

/**
 * 記事ごとの日別 PV(issue #1576。letsblog プラグインの date + pagePath + hostName)。
 * host が null の行は「問い合わせたサイトのホスト」の行(絞り込みの値で返す)。他ホストの行は絞り込みで除かれる。
 */
const PAGE_DAILY_ROWS = [
  { date: '20260830', path: '/e2e-stub/first-post', host: null, views: '120' },
  { date: '20260831', path: '/e2e-stub/first-post', host: null, views: '200' },
  { date: '20260830', path: '/e2e-stub/second-post', host: null, views: '60' },
  { date: '20260831', path: '/e2e-stub/second-post', host: null, views: '90' },
  { date: '20260831', path: '/', host: null, views: '40' },
  { date: '20260831', path: '/e2e-stub/missing', host: null, views: '7' },
  { date: '20260831', path: '/e2e-stub/first-post', host: 'other.example.test', views: '999' },
];
const DEFAULT_HOST = 'e2e-site.test';
/** GA プロパティのタイムゾーン。実 API は runReport の metadata.timeZone で返す。 */
const PROPERTY_TIME_ZONE = 'Asia/Tokyo';

/** hostName の完全一致フィルタの値。無ければ null。 */
function hostFilterValue(payload) {
  const f = payload.dimensionFilter && payload.dimensionFilter.filter;
  if (f && f.fieldName === 'hostName' && f.stringFilter) return f.stringFilter.value;
  return null;
}

function pageDailyReport(payload, metrics, dimensions) {
  const filterHost = hostFilterValue(payload);
  const rows = PAGE_DAILY_ROWS.map((r) => ({ ...r, host: r.host ?? filterHost ?? DEFAULT_HOST })).filter(
    (r) => filterHost === null || r.host === filterHost,
  );
  const offset = Number(payload.offset) || 0;
  const limit = Number(payload.limit) || 10000;
  const dimValue = { date: (r) => r.date, pagePath: (r) => r.path, hostName: (r) => r.host };
  return {
    dimensionHeaders: dimensions.map((name) => ({ name })),
    metricHeaders: metricHeaders(metrics),
    rows: rows.slice(offset, offset + limit).map((r) => ({
      dimensionValues: dimensions.map((d) => ({ value: (dimValue[d] || (() => ''))(r) })),
      metricValues: metrics.map((m) => ({ value: m === 'screenPageViews' ? r.views : (METRIC_VALUES[m] ?? '0') })),
    })),
    rowCount: rows.length,
    metadata: { timeZone: PROPERTY_TIME_ZONE },
    kind: 'analyticsData#runReport',
  };
}

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

  if (dimensions.includes('date') && dimensions.includes('pagePath')) {
    return pageDailyReport(payload, metrics, dimensions);
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

const ACCESS_TOKEN = 'e2e-stub-ga-access-token';
const REFRESH_TOKEN = 'e2e-stub-ga-refresh-token';
const EXPIRED_REFRESH_TOKEN = 'e2e-stub-ga-invalid-refresh';

/** accountSummaries.list は2ページに分けて返し、クライアントが全ページを取得することを確かめる。 */
const ACCOUNT_SUMMARY_PAGES = {
  '': {
    accountSummaries: [
      {
        name: 'accountSummaries/1001',
        account: 'accounts/1001',
        displayName: 'E2E Stub Account',
        propertySummaries: [
          { property: 'properties/987654321', displayName: 'E2E Stub Site', propertyType: 'PROPERTY_TYPE_ORDINARY' },
        ],
      },
    ],
    nextPageToken: 'e2e-stub-page-2',
  },
  'e2e-stub-page-2': {
    accountSummaries: [
      {
        name: 'accountSummaries/1002',
        account: 'accounts/1002',
        displayName: 'E2E Stub Second Account',
        propertySummaries: [
          { property: 'properties/555000111', displayName: 'E2E Stub Second Site', propertyType: 'PROPERTY_TYPE_ORDINARY' },
        ],
      },
    ],
  },
};

function formValue(body, key) {
  const m = new RegExp(`(?:^|&)${key}=([^&]*)`).exec(body || '');
  return m ? decodeURIComponent(m[1].replace(/\+/g, ' ')) : null;
}

function hasValidAccessToken(req) {
  return req.headers.authorization === `Bearer ${ACCESS_TOKEN}`;
}

createStub({
  name: 'google-analytics',
  port: Number(process.env.PORT || 8080),
  async handle({ req, method, pathname, query, body, res, sendJson }) {
    // 1. OAuth トークン交換。
    if (method === 'POST' && (pathname === '/token' || pathname === '/oauth2/token')) {
      const grantType = formValue(body, 'grant_type');
      const code = formValue(body, 'code');
      const refresh = formValue(body, 'refresh_token');

      if (grantType !== 'authorization_code' && grantType !== 'refresh_token') {
        sendJson(res, 400, { error: 'unsupported_grant_type', error_description: '[stub] 未対応のグラント種別です' });
        return true;
      }
      if (code === 'e2e-stub-invalid-code' || refresh === EXPIRED_REFRESH_TOKEN) {
        sendJson(res, 401, {
          error: 'invalid_grant',
          error_description: '[stub] 認可コードまたはリフレッシュトークンが不正です',
        });
        return true;
      }
      // 認可コードフロー(初回)だけ refresh_token を返す。実APIと同じ挙動。
      const payload = {
        access_token: ACCESS_TOKEN,
        token_type: 'Bearer',
        expires_in: 3599,
        scope: 'https://www.googleapis.com/auth/analytics.readonly',
      };
      if (grantType === 'authorization_code') {
        payload.refresh_token = code === 'e2e-stub-ga-expired-code' ? EXPIRED_REFRESH_TOKEN : REFRESH_TOKEN;
      }
      sendJson(res, 200, payload);
      return true;
    }

    // 2. プロパティ一覧(Admin API)。
    if (method === 'GET' && pathname === '/v1beta/accountSummaries') {
      if (!hasValidAccessToken(req)) {
        sendJson(res, 401, { error: { code: 401, message: '[stub] invalid access token' } });
        return true;
      }
      const page = ACCOUNT_SUMMARY_PAGES[query.get('pageToken') || ''];
      if (!page) {
        sendJson(res, 400, { error: { code: 400, message: '[stub] invalid pageToken' } });
        return true;
      }
      sendJson(res, 200, page);
      return true;
    }

    // 2. レポート取得。
    if (method === 'POST' && /^\/v1beta\/properties\/[^/]+:runReport$/.test(pathname)) {
      if (!hasValidAccessToken(req)) {
        sendJson(res, 401, { error: { code: 401, message: '[stub] invalid access token' } });
        return true;
      }
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
