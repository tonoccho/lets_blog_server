'use strict';
/**
 * lets_blog_server_extensions のポータルのスタブ(issue #1782)。
 *
 *   GET /api/connections    extensions の接続先一覧(認証なし)
 *
 * LBS の `GET /api/connections` を読む機能(#1783)を、実物の extensions なしで
 * 受け入れテストにかけるための置き換えである。lbs-net 上で `lbx-portal:3100` として届く
 * (docker-compose.e2e-stubs.yml の `portal-stub`)。
 *
 * ## 既定の応答の出典
 *
 * seiji/lets_blog_server_extensions の `b864179`:
 *   apps/portal/src/app/api/connections/route.ts   Response.json(buildConnections(process.env))
 *   apps/portal/src/lib/connections.ts             各要素の形(openaiBaseUrl は Ollama だけ)
 *   apps/portal/src/lib/config.ts                  SERVICE_DEFS の順序と値
 * extensions の既定値(BASE_DOMAIN=lbx.localhost、HTTPS_PORT=8443)で組んだ値を書き下ろしている。
 * `/api/connections` は契約ではない(ADR-0011 §決定 5)ので、extensions 側が変わるとこの既定値は
 * 古くなりうる。食い違いは手で追う(自動検出はしない。#1782 Open Questions 2)。
 *
 * ## 制御
 *
 *   POST /__control/response  {"body": <JSON 値または文字列>, "contentType"?: "..."}
 *       以降の GET /api/connections の本文を差し替える。body が文字列ならそのまま、
 *       それ以外は JSON.stringify して返す(JSON でない本文は文字列で渡す)。
 *       contentType の既定は application/json; charset=utf-8。
 *   POST /__control/reset     本文を既定の5要素へ戻す(受信件数と注入も解除する)
 *   POST /__control/force     ステータス・遅延の注入(lib/stub.js の共通機能。/api/connections に効く)
 *   GET  /__control/state     受信件数(requests / requestsByPath["GET /api/connections"])と、
 *                             差し替え中かどうか(overridden)
 *
 * ## 決定性
 *
 * 既定の応答にタイムスタンプ等は混ぜない。毎回同じバイト列を返す。
 */
const { createStub } = require('../lib/stub');

const PORT = Number(process.env.PORT || 3100);
const JSON_TYPE = 'application/json; charset=utf-8';

const BASE_DOMAIN = 'lbx.localhost';
const HTTPS_PORT = '8443';

/** config.ts の SERVICE_DEFS の順序。internalServiceUrl は同ファイルの値(接頭辞 lbx- なしのベース URL)。 */
const SERVICE_DEFS = [
  { id: 'plantuml', name: 'PlantUML', internalUrl: 'http://lbx-plantuml:8080', internalServiceUrl: 'http://plantuml:8080' },
  { id: 'comfyui', name: 'ComfyUI', internalUrl: 'http://lbx-comfyui:8188', internalServiceUrl: 'http://comfyui:8188' },
  { id: 'ollama', name: 'Ollama', internalUrl: 'http://lbx-ollama:11434', internalServiceUrl: 'http://ollama:11434' },
  {
    id: 'penpot',
    name: 'Penpot',
    internalUrl: 'http://lbx-penpot-frontend:8080',
    internalServiceUrl: 'http://penpot-frontend:8080',
  },
  { id: 'gitlab', name: 'GitLab', internalUrl: 'http://lbx-gitlab', internalServiceUrl: 'http://gitlab' },
];

/** connections.ts の buildConnections と同じ形。OpenAI 互換のツール(Ollama)だけ openaiBaseUrl を持つ。 */
function defaultConnections() {
  return SERVICE_DEFS.map((def) => {
    const connection = {
      id: def.id,
      name: def.name,
      publicUrl: `https://${def.id}.${BASE_DOMAIN}:${HTTPS_PORT}`,
      internalUrl: def.internalUrl,
      internalServiceUrl: def.internalServiceUrl,
    };
    if (def.id === 'ollama') connection.openaiBaseUrl = `${def.internalUrl}/v1`;
    return connection;
  });
}

let override = null;

createStub({
  name: 'portal',
  port: PORT,
  onReset() {
    override = null;
  },
  extraState: () => ({ overridden: override !== null }),
  async control({ req, res, method, pathname, readBody, sendJson }) {
    if (method !== 'POST' || pathname !== '/__control/response') return false;
    let parsed;
    try {
      parsed = JSON.parse((await readBody(req)) || '{}');
    } catch {
      sendJson(res, 400, { error: { message: 'body must be JSON' } });
      return true;
    }
    if (parsed === null || typeof parsed !== 'object' || !('body' in parsed)) {
      sendJson(res, 400, { error: { message: 'body を指定すること' } });
      return true;
    }
    const text = typeof parsed.body === 'string' ? parsed.body : JSON.stringify(parsed.body);
    override = { text, contentType: parsed.contentType || JSON_TYPE };
    sendJson(res, 200, { name: 'portal', overridden: true });
    return true;
  },
  async handle({ method, pathname, res, sendText }) {
    if (method === 'GET' && pathname === '/api/connections') {
      if (override) {
        sendText(res, 200, override.text, override.contentType);
      } else {
        sendText(res, 200, JSON.stringify(defaultConnections()), JSON_TYPE);
      }
      return true;
    }
    return false;
  },
});
