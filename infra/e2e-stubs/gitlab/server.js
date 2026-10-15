'use strict';
/**
 * GitLab のスタブの骨組み(issue #1796)。
 *
 *   GET /users/sign_in    ログイン画面(200。連携サービスの状況の到達性判定が叩く)
 *
 * `/-/readiness` は模さない(実物は localhost 以外からの要求に 404 を返すので、到達性判定には使わない)。
 * lbs-net 上では、同じ PC の extensions の GitLab と同じ名前 `lbx-gitlab`(ポート 80)で届く
 * (docker-compose.e2e-stubs.yml の `gitlab-stub`)。
 *
 * ## 制御
 *
 *   POST /__control/force  ステータス・遅延の注入(lib/stub.js の共通機能。`/users/sign_in` に効く)。
 *                          2xx 以外・タイムアウトの異常系は、これで起こす。
 *   POST /__control/reset  注入と受信件数を解除する
 *   GET  /__control/state  受信件数(requests / requestsByPath["GET /users/sign_in"])
 *
 * ## 決定性
 *
 * 応答にタイムスタンプ等は混ぜない。毎回同じバイト列を返す。
 */
const { createStub } = require('../lib/stub');

const PORT = Number(process.env.PORT || 80);
const HTML_TYPE = 'text/html; charset=utf-8';
const SIGN_IN_PAGE = '<!DOCTYPE html><html><head><title>Sign in · GitLab</title></head><body>GitLab (e2e stub)</body></html>\n';

createStub({
  name: 'gitlab',
  port: PORT,
  async handle({ method, pathname, res, sendText }) {
    if (method === 'GET' && pathname === '/users/sign_in') {
      sendText(res, 200, SIGN_IN_PAGE, HTML_TYPE);
      return true;
    }
    return false;
  },
});
