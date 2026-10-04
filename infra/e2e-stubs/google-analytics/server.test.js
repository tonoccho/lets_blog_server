'use strict';
/**
 * Google Analytics スタブの契約テスト(issue #1576)。実行: node --test infra/e2e-stubs/google-analytics/server.test.js
 * letsblog プラグインが叩く runReport(date + pagePath + hostName、ホスト名での絞り込み、limit/offset)と、
 * 既存の呼び出し元(analytics-service)が使うディメンション指定が変わらないことを検証する。
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

async function accessToken() {
  const r = await fetch(`${base}/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'grant_type=refresh_token&refresh_token=e2e-stub-ga-refresh-token&client_id=c&client_secret=s',
  });
  return (await r.json()).access_token;
}

async function runReport(payload, token) {
  const t = token ?? (await accessToken());
  return fetch(`${base}/v1beta/properties/987654321:runReport`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

const hostFilter = (value) => ({ filter: { fieldName: 'hostName', stringFilter: { matchType: 'EXACT', value } } });
const pvPayload = (extra = {}) => ({
  dateRanges: [{ startDate: '2020-10-14', endDate: 'today' }],
  dimensions: [{ name: 'date' }, { name: 'pagePath' }, { name: 'hostName' }],
  metrics: [{ name: 'screenPageViews' }],
  ...extra,
});
const flat = (json) => json.rows.map((r) => [...r.dimensionValues.map((d) => d.value), r.metricValues[0].value]);

test('date + pagePath + hostName: 記事ごとの日別 PV を、依頼した順のディメンションで返す', async () => {
  const res = await runReport(pvPayload({ dimensionFilter: hostFilter('blog.example.test') }));
  assert.equal(res.status, 200);
  const json = await res.json();
  assert.deepEqual(json.dimensionHeaders.map((h) => h.name), ['date', 'pagePath', 'hostName']);
  assert.deepEqual(flat(json), [
    ['20260830', '/e2e-stub/first-post', 'blog.example.test', '120'],
    ['20260831', '/e2e-stub/first-post', 'blog.example.test', '200'],
    ['20260830', '/e2e-stub/second-post', 'blog.example.test', '60'],
    ['20260831', '/e2e-stub/second-post', 'blog.example.test', '90'],
    ['20260831', '/', 'blog.example.test', '40'],
    ['20260831', '/e2e-stub/missing', 'blog.example.test', '7'],
  ]);
  assert.equal(json.rowCount, 6);
});

test('hostName の絞り込みで他ホストの行が除かれ、絞り込みが無ければ他ホストの行も返る', async () => {
  const filtered = flat(await (await runReport(pvPayload({ dimensionFilter: hostFilter('blog.example.test') }))).json());
  assert.ok(!filtered.some((r) => r[2] === 'other.example.test'));
  const all = flat(await (await runReport(pvPayload())).json());
  assert.ok(all.some((r) => r[2] === 'other.example.test' && r[3] === '999'));
});

test('ディメンションの順を変えても値は同じ行に付く', async () => {
  const json = await (
    await runReport(pvPayload({ dimensions: [{ name: 'pagePath' }, { name: 'date' }], dimensionFilter: hostFilter('h.test') }))
  ).json();
  assert.deepEqual(flat(json)[0], ['/e2e-stub/first-post', '20260830', '120']);
});

test('レスポンスに GA プロパティのタイムゾーンが入る', async () => {
  const json = await (await runReport(pvPayload({ dimensionFilter: hostFilter('h.test') }))).json();
  assert.equal(json.metadata.timeZone, 'Asia/Tokyo');
});

test('limit と offset で行を区切って返し、rowCount は全件数', async () => {
  const filter = hostFilter('h.test');
  const first = await (await runReport(pvPayload({ dimensionFilter: filter, limit: 4, offset: 0 }))).json();
  const second = await (await runReport(pvPayload({ dimensionFilter: filter, limit: 4, offset: 4 }))).json();
  assert.equal(first.rows.length, 4);
  assert.equal(second.rows.length, 2);
  assert.equal(first.rowCount, 6);
  assert.equal(second.rowCount, 6);
});

test('Bearer が無効なら 401', async () => {
  const res = await runReport(pvPayload(), 'wrong');
  assert.equal(res.status, 401);
});

test('既存の呼び出し(date のみ / pagePath のみ)の応答は変わらない', async () => {
  const daily = await (await runReport({ dimensions: [{ name: 'date' }], metrics: [{ name: 'screenPageViews' }] })).json();
  assert.equal(daily.rows.length, 7);
  assert.equal(daily.rows[0].dimensionValues[0].value, '20260825');
  const pages = await (await runReport({ dimensions: [{ name: 'pagePath' }], metrics: [{ name: 'screenPageViews' }] })).json();
  assert.deepEqual(pages.rows.map((r) => [r.dimensionValues[0].value, r.metricValues[0].value]), [
    ['/e2e-stub/first-post', '820'],
    ['/e2e-stub/second-post', '410'],
    ['/', '260'],
  ]);
});
