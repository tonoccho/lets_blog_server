'use strict';
/**
 * Google AdSense Management API v2 + Google OAuth のスタブ(issue #928 / AT-2)。
 *
 * analytics-service の AdSenseClient は次を叩く。
 *   1. OAuth トークン交換(POST {googleOauthTokenUri})
 *      - 認可コード → アクセストークン + リフレッシュトークン(初回連携)
 *      - リフレッシュトークン → アクセストークン(以降)
 *   2. レポート取得(GET {adsenseDataApiBaseUrl}/v2/accounts/{accountId}/reports:generate)
 *      - ディメンション無し(合計)/ DATE(日次)/ PLATFORM_TYPE_NAME(内訳)の3種
 *
 * 認可コード `e2e-stub-invalid-code` と リフレッシュトークン `e2e-stub-invalid-refresh` は
 * 401 を返す。「不正な資格情報を登録したらどうなるか」を制御エンドポイント無しで検証できる。
 */
const { createStub } = require('../lib/stub');

const ACCESS_TOKEN = 'e2e-stub-adsense-access-token';
const REFRESH_TOKEN = 'e2e-stub-adsense-refresh-token';

/** 合計。AdSenseClient#parseReport は totals.cells[0..2] を earnings/clicks/impressions として読む。 */
const TOTALS = { earnings: '12.34', clicks: '56', impressions: '7890' };

const DAILY = [
  { date: '2026-08-25', earnings: '1.11', clicks: '5', impressions: '900' },
  { date: '2026-08-26', earnings: '1.62', clicks: '7', impressions: '1010' },
  { date: '2026-08-27', earnings: '2.05', clicks: '9', impressions: '1180' },
  { date: '2026-08-28', earnings: '1.48', clicks: '6', impressions: '980' },
  { date: '2026-08-29', earnings: '2.31', clicks: '11', impressions: '1290' },
  { date: '2026-08-30', earnings: '1.77', clicks: '8', impressions: '1140' },
  { date: '2026-08-31', earnings: '2.00', clicks: '10', impressions: '1390' },
];

const PLATFORMS = [
  { name: 'Desktop', earnings: '7.10', clicks: '31', impressions: '4300' },
  { name: 'Mobile', earnings: '4.60', clicks: '21', impressions: '3100' },
  { name: 'Tablet', earnings: '0.64', clicks: '4', impressions: '490' },
];

const cells = (row) => [
  { value: row.earnings }, { value: row.clicks }, { value: row.impressions },
];

function report(dimension) {
  const headers = [
    { name: 'ESTIMATED_EARNINGS', type: 'METRIC_CURRENCY' },
    { name: 'CLICKS', type: 'METRIC_TALLY' },
    { name: 'IMPRESSIONS', type: 'METRIC_TALLY' },
  ];
  const base = {
    headers: dimension ? [{ name: dimension, type: 'DIMENSION' }, ...headers] : headers,
    totals: { cells: cells(TOTALS) },
    totalMatchedRows: '1',
    currencyCode: 'JPY',
    startDate: { year: 2026, month: 8, day: 25 },
    endDate: { year: 2026, month: 8, day: 31 },
  };
  if (!dimension) return { ...base, rows: [{ cells: cells(TOTALS) }] };

  const source = dimension === 'DATE' ? DAILY : PLATFORMS;
  return {
    ...base,
    totalMatchedRows: String(source.length),
    rows: source.map((row) => ({
      cells: [{ value: dimension === 'DATE' ? row.date : row.name }, ...cells(row)],
    })),
  };
}

function formValue(body, key) {
  const m = new RegExp(`(?:^|&)${key}=([^&]*)`).exec(body || '');
  return m ? decodeURIComponent(m[1].replace(/\+/g, ' ')) : null;
}

createStub({
  name: 'adsense',
  port: Number(process.env.PORT || 8080),
  async handle({ method, pathname, query, body, res, sendJson }) {
    // 1. OAuth トークン交換。
    if (method === 'POST' && (pathname === '/token' || pathname === '/oauth2/token')) {
      const grantType = formValue(body, 'grant_type');
      const code = formValue(body, 'code');
      const refresh = formValue(body, 'refresh_token');

      if (code === 'e2e-stub-invalid-code' || refresh === 'e2e-stub-invalid-refresh') {
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
        scope: 'https://www.googleapis.com/auth/adsense.readonly',
      };
      if (grantType === 'authorization_code') payload.refresh_token = REFRESH_TOKEN;
      sendJson(res, 200, payload);
      return true;
    }

    // 2. レポート取得。
    if (method === 'GET' && /^\/v2\/accounts\/[^/]+\/reports:generate$/.test(pathname)) {
      sendJson(res, 200, report(query.get('dimensions')));
      return true;
    }

    return false;
  },
});
