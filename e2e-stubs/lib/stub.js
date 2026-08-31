'use strict';
/**
 * 受け入れテスト用の外部依存スタブの共通土台(issue #928 / AT-2)。
 *
 * 設計上の約束は2つだけ。
 *
 * 1. **決定的であること。** 同じ入力には常に同じ応答を返す。タイムスタンプ・乱数・
 *    カウンタを応答に混ぜない。混ぜるとシナリオ側が値をアサートできず、
 *    「呼べたこと」しか確認できないテストに退化する。
 * 2. **エラーを注入できること。** 異常系(401/429/500/タイムアウト)は、実サービスでは
 *    再現できない。制御エンドポイントから明示的に起こせるようにする。
 *
 * ## エラー注入の2つの経路
 *
 * ### 1. リクエストヘッダ(スタブを直接叩くとき)
 *
 *   X-E2E-Stub-Force-Status: 429     そのリクエストだけを429にする
 *   X-E2E-Stub-Force-Delay: 30000    そのリクエストだけを遅延させる
 *
 * スタブの状態を変えないので、**並列に走る他のシナリオへ影響しない**。
 * スタブ自身の受け入れテスト(external-stubs.feature)はこちらを使う。
 *
 * ### 2. 制御エンドポイント(サービス越しに呼ばせるとき)
 *
 * 実際の異常系シナリオでは、スタブを呼ぶのはサービスであってテストではない。
 * テストはヘッダを差し込めないので、**事前に**スタブへ状態を仕込む。
 *
 *   POST /__control/force  {"status": 429, "count": 1}       次の1回を429にする
 *   POST /__control/force  {"delayMs": 30000, "count": 1}    次の1回を遅延させる(タイムアウト誘発)
 *   POST /__control/reset                                     仕込みを解除する
 *   GET  /__control/state                                     現在の仕込みと受信件数
 *   GET  /health                                              死活(compose の healthcheck 用)
 *
 * `count` を省略すると解除するまで継続する。`/health` と `/__control/**` 自身は
 * 注入の対象外なので、仕込んだまま健全性を確認できる。
 *
 * **この経路はスタブ全体の状態を変える。** 同じスタブへ注入するシナリオを並列に走らせると
 * 互いの仕込みを奪い合う。該当シナリオには `@mode:serial` を付け、同じスタブを触る
 * feature を分散させないこと(docs/ACCEPTANCE_TESTING.md に明記した)。
 *
 * 受信件数(`requests`)はシナリオが「サービスが実際に外部を呼んだか」を確かめるために使う。
 * 応答には含めない(決定性を壊すため)。`/__control/state` からのみ読める。
 */
const http = require('node:http');

const CONTROL_PREFIX = '/__control';

function readBody(req, limitBytes = 2_000_000) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.on('data', (chunk) => {
      raw += chunk;
      if (raw.length > limitBytes) {
        req.destroy();
        reject(new Error('request body too large'));
      }
    });
    req.on('end', () => resolve(raw));
    req.on('error', reject);
  });
}

function sendJson(res, status, payload, headers = {}) {
  const body = JSON.stringify(payload);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', ...headers });
  res.end(body);
}

function sendText(res, status, text, contentType = 'text/plain; charset=utf-8') {
  res.writeHead(status, { 'Content-Type': contentType });
  res.end(text);
}

function sendBinary(res, status, buffer, contentType) {
  res.writeHead(status, { 'Content-Type': contentType, 'Content-Length': buffer.length });
  res.end(buffer);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * @param {object} options
 * @param {string} options.name        ログとエラー本文に使う識別子
 * @param {number} options.port        待ち受けポート
 * @param {(ctx) => Promise<boolean>} options.handle
 *        スタブ固有の処理。処理したら true、未対応なら false を返す(呼び元が404にする)。
 *        ctx = { req, res, method, pathname, query, body, state }
 * @param {(status, name) => object} [options.errorBody]
 *        注入したエラーの本文。実サービスの形に寄せたいときに上書きする。
 * @param {() => void} [options.onReset]
 *        `POST /__control/reset` で呼ばれる。状態を持つスタブ(github)が初期シードへ戻すために使う。
 */
function createStub({ name, port, handle, errorBody, onReset }) {
  const state = {
    /** 注入中のエラー。{ status?, delayMs?, remaining } */
    forced: null,
    /** 制御・死活を除いた受信件数。 */
    requests: 0,
  };

  const defaultErrorBody = (status) => ({
    error: { code: status, message: `[${name}] forced ${status} for acceptance test`, status: 'FORCED' },
  });

  const server = http.createServer(async (req, res) => {
    let url;
    try {
      url = new URL(req.url, `http://localhost:${port}`);
    } catch {
      sendJson(res, 400, { error: { message: 'malformed request line' } });
      return;
    }
    const pathname = url.pathname;

    if (req.method === 'GET' && pathname === '/health') {
      sendText(res, 200, 'ok\n');
      return;
    }

    if (pathname.startsWith(CONTROL_PREFIX)) {
      await handleControl(req, res, pathname, state, name, onReset);
      return;
    }

    state.requests += 1;

    // 経路1: ヘッダによる単発注入。状態を変えないので並列実行に干渉しない。
    const headerStatus = req.headers['x-e2e-stub-force-status'];
    const headerDelay = req.headers['x-e2e-stub-force-delay'];
    if (headerStatus || headerDelay) {
      if (headerDelay) {
        await sleep(Number(headerDelay));
        res.destroy();
        return;
      }
      const status = Number(headerStatus);
      const headers = status === 429 ? { 'Retry-After': '1' } : {};
      const body = errorBody ? errorBody(status, name) : defaultErrorBody(status);
      sendJson(res, status, body, headers);
      return;
    }

    // 経路2: 制御エンドポイントで仕込まれた注入。スタブ全体の状態を消費する。
    if (state.forced) {
      const forced = state.forced;
      if (forced.remaining !== null) {
        forced.remaining -= 1;
        if (forced.remaining <= 0) state.forced = null;
      }
      if (forced.delayMs) {
        // 呼び元のリードタイムアウトを誘発する。応答自体は返さずに切る。
        await sleep(forced.delayMs);
        res.destroy();
        return;
      }
      const status = forced.status ?? 500;
      const headers = status === 429 ? { 'Retry-After': '1' } : {};
      const body = errorBody ? errorBody(status, name) : defaultErrorBody(status);
      sendJson(res, status, body, headers);
      return;
    }

    let body = '';
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      try {
        body = await readBody(req);
      } catch {
        sendJson(res, 413, { error: { message: 'request body too large' } });
        return;
      }
    }

    const handled = await handle({
      req, res, method: req.method, pathname, query: url.searchParams, body, state,
      sendJson, sendText, sendBinary,
    });
    if (!handled) {
      sendJson(res, 404, {
        error: { message: `[${name}] not implemented: ${req.method} ${pathname}` },
      });
    }
  });

  server.listen(port, '0.0.0.0', () => {
    console.log(`[e2e-stub:${name}] listening on ${port}`);
  });
  return server;
}

async function handleControl(req, res, pathname, state, name, onReset) {
  if (req.method === 'GET' && pathname === `${CONTROL_PREFIX}/state`) {
    sendJson(res, 200, { name, forced: state.forced, requests: state.requests });
    return;
  }
  if (req.method === 'POST' && pathname === `${CONTROL_PREFIX}/reset`) {
    state.forced = null;
    state.requests = 0;
    if (onReset) onReset();
    sendJson(res, 200, { name, forced: null, requests: 0 });
    return;
  }
  if (req.method === 'POST' && pathname === `${CONTROL_PREFIX}/force`) {
    let parsed;
    try {
      parsed = JSON.parse((await readBody(req)) || '{}');
    } catch {
      sendJson(res, 400, { error: { message: 'body must be JSON' } });
      return;
    }
    const status = parsed.status === undefined ? null : Number(parsed.status);
    const delayMs = parsed.delayMs === undefined ? null : Number(parsed.delayMs);
    if (status === null && delayMs === null) {
      sendJson(res, 400, { error: { message: 'status または delayMs のどちらかを指定すること' } });
      return;
    }
    state.forced = {
      status,
      delayMs,
      remaining: parsed.count === undefined ? null : Number(parsed.count),
    };
    sendJson(res, 200, { name, forced: state.forced });
    return;
  }
  sendJson(res, 404, { error: { message: `[${name}] unknown control: ${req.method} ${pathname}` } });
}

module.exports = { createStub, sendJson, sendText, sendBinary, readBody };
