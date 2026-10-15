'use strict';
/**
 * PlantUML サーバーのスタブ(issue #1802)。
 *
 *   GET /png/<encoded>    固定の PNG(1x1)を image/png で返す
 *
 * 受け入れテストは extensions のツールをすべてモックで受ける(docs/ACCEPTANCE_TESTING.md §9)。
 * 本物の plantuml-server(#1784)に代わり、lbs-net 上で別名 `lbx-plantuml`(ポート 8080)として届く
 * (docker-compose.e2e-stubs.yml の `plantuml-stub`)。media-service の PlantUmlClient が叩く
 * `GET /png/<符号化したソース>` だけを模す。画像は固定なので、描かれた内容は確かめられない。
 * 描かせた内容を確かめたいときは、`GET /__control/state` から受け取ったソースを読む。
 *
 * ## 受け取ったソース
 *
 * パスの符号化(PlantUML 独自の64文字 + raw deflate。services/media の PlantUmlEncoder.java と同じ)を
 * `zlib.inflateRawSync` で復号して記録する。`GET /__control/state` の
 *
 *   lastSource   直近に復号できたソース(無ければ null)
 *   sources      復号できたソースの記録(古い順、最新 100 件まで)
 *
 * から読める。応答には含めない(決定性)。`POST /__control/reset` で消える。
 *
 * ## 決定的なエラー
 *
 *   復号できないパス、復号したソースに `@startuml` が無い
 *       → 400 と `X-PlantUML-Diagram-Error`(と `-Line`)。本文は実物と同じく PNG。
 *   URI が 8192 バイトを超える(Jetty の既定の要求行の上限と同じ)
 *       → 414(復号も記録もしない)。Node の要求ヘッダー上限(16KB)を超える URI も 414(末尾の clientError)
 *
 * 画像の拒否と接続不能を区別する media-service の翻訳(#1776)を AT で確かめるための規則であり、
 * 本物の PlantUML の構文検査を再現するものではない。
 *
 * ## 制御(lib/stub.js の共通機能)
 *
 *   POST /__control/force  {"status": 503, "count": 1}   次の1回を失敗させる
 *   POST /__control/reset                                 注入・受信件数・記録を解除する
 *   GET  /__control/state                                 受信件数と、受け取ったソース
 */
const zlib = require('node:zlib');
const { createStub } = require('../lib/stub');

const PORT = Number(process.env.PORT || 8080);
const PNG_PREFIX = '/png/';
const MAX_URI_BYTES = 8192;
const MAX_RECORDED = 100;

/** 1x1 の透過 PNG。決定性のため定数で持つ。 */
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
);

const ALPHABET = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_';

/** PlantUML 独自の符号化を復号する。復号できなければ null。 */
function decode(encoded) {
  if (encoded.length === 0 || encoded.length % 4 !== 0) return null;
  const bytes = [];
  for (let i = 0; i < encoded.length; i += 4) {
    const v = [];
    for (let j = 0; j < 4; j += 1) {
      const idx = ALPHABET.indexOf(encoded[i + j]);
      if (idx < 0) return null;
      v.push(idx);
    }
    bytes.push((v[0] << 2) | (v[1] >> 4), ((v[1] & 0xf) << 4) | (v[2] >> 2), ((v[2] & 0x3) << 6) | v[3]);
  }
  try {
    return zlib.inflateRawSync(Buffer.from(bytes)).toString('utf8');
  } catch {
    return null;
  }
}

let sources = [];

function record(source) {
  sources.push(source);
  if (sources.length > MAX_RECORDED) sources = sources.slice(-MAX_RECORDED);
}

function sendPng(res, status, headers = {}) {
  res.writeHead(status, { 'Content-Type': 'image/png', 'Content-Length': PNG.length, ...headers });
  res.end(PNG);
}

const server = createStub({
  name: 'plantuml',
  port: PORT,
  onReset() {
    sources = [];
  },
  extraState: () => ({ lastSource: sources.length ? sources[sources.length - 1] : null, sources }),
  async handle({ req, res, method, pathname }) {
    if (method !== 'GET' || !pathname.startsWith(PNG_PREFIX) || pathname.length === PNG_PREFIX.length) {
      return false;
    }
    if (Buffer.byteLength(req.url) > MAX_URI_BYTES) {
      res.writeHead(414, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('URI Too Long\n');
      return true;
    }
    const source = decode(pathname.slice(PNG_PREFIX.length));
    if (source === null) {
      sendPng(res, 400, {
        'X-PlantUML-Diagram-Error': 'Cannot decode the encoded diagram',
        'X-PlantUML-Diagram-Error-Line': '1',
      });
      return true;
    }
    record(source);
    if (!source.includes('@startuml')) {
      sendPng(res, 400, {
        'X-PlantUML-Diagram-Error': 'Syntax Error?',
        'X-PlantUML-Diagram-Error-Line': '1',
      });
      return true;
    }
    sendPng(res, 200);
    return true;
  },
});

// Node は要求ヘッダー(要求行を含む)が 16KB を超えると、ハンドラへ届く前に 431 で切る。10000 要素の図の
// 符号化は約 33KB の URI になるため、そのままだと上の 414 の規則に届かない。実物の PlantUML(Jetty)も
// 要求行が長すぎれば 414 なので、ヘッダー超過だけを 414 へ写す(それ以外の不正な要求は Node の既定どおり 400)。
server.on('clientError', (err, socket) => {
  if (!socket.writable) {
    socket.destroy();
    return;
  }
  const status = err.code === 'HPE_HEADER_OVERFLOW' ? '414 URI Too Long' : '400 Bad Request';
  socket.end(`HTTP/1.1 ${status}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
});
