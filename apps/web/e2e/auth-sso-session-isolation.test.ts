/**
 * @jest-environment node
 *
 * issue #1329: `auth.steps.ts`の`revokeKeycloakSsoSession`(#1053)の実装が、
 * 「ユーザーID全体のSSOセッションを終了させる」旧実装(`kcadm create
 * users/{id}/logout`)から、「そのシナリオ自身のセッションだけを終了させる」新実装
 * (`kcadm delete sessions/{sid}`。`sid`は`./support/keycloakSession`の
 * `extractKeycloakSessionId`で取り出す)へ切り替わっていることをソース走査で確かめる。
 *
 * kcadm.test.ts(#1328)と同型: `revokeKeycloakSsoSession`はPlaywright実行コンテキスト
 * (実ブラウザ・実docker)に依存する関数であり、この単体テストの範囲では実際に
 * Keycloakへ到達させない。個別セッションのログアウトAPI自体が実機で到達可能なことは
 * `keycloakSession.test.ts`の冒頭コメントと実装報告に記録済み。ここではその呼び出し
 * パターンが実際のソースに存在すること(=旧実装へ後戻りしていないこと)だけを保証する。
 *
 * `apps/web/e2e/**` は通常jestの対象外(jest.config.tsのtestMatch、#994)なので、
 * 他のe2e単体テストと同様にCLI引数で明示的に上書きして実行する(実装報告に実行結果を記録):
 *
 *   npx jest --config jest.config.ts \
 *     --testPathIgnore(略・対象除外オプション名)='/node_modules/|/\.next/' \
 *     --testMatch='**\/e2e/auth-sso-session-isolation.test.ts' \
 *     e2e/auth-sso-session-isolation.test.ts
 */

import fs from 'node:fs';
import path from 'node:path';

const AUTH_STEPS_PATH = path.join(__dirname, 'steps', 'auth.steps.ts');

function readAuthSteps(): string {
  return fs.readFileSync(AUTH_STEPS_PATH, 'utf-8');
}

describe('revokeKeycloakSsoSessionがユーザー全体ではなく個別セッションだけを終了させる(issue #1329)', () => {
  test('ユーザーID全体を終了させる旧実装(users/{id}/logout)が残っていない', () => {
    const content = readAuthSteps();
    expect(content).not.toMatch(/[`'"]users\/\$\{[^}]*\}\/logout[`'"]/);
  });

  test('個別セッションを終了させる新実装(sessions/{sid}へのdelete)が存在する', () => {
    const content = readAuthSteps();
    expect(content).toMatch(/kcadm\(\[\s*['"]delete['"]\s*,\s*`sessions\/\$\{[^}]*\}`/);
  });

  test('sidの抽出を共有ヘルパー(../support/keycloakSession)からimportしている', () => {
    const content = readAuthSteps();
    expect(content).toMatch(/extractKeycloakSessionId/);
    expect(content).toMatch(/from ['"]\.\.\/support\/keycloakSession['"]/);
  });
});
