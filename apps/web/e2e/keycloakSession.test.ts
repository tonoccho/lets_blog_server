/**
 * issue #1329: `revokeKeycloakSsoSession`(auth.steps.ts、#1053)は
 * `kcadm create users/{id}/logout`でユーザーIDに紐づく**全SSOセッション**を終了させて
 * いたため、同じ`E2E_ADMIN_EMAIL`を使う別の同時実行中シナリオ(既定の並列度では
 * `token-lifecycle.feature`内の複数シナリオが同時に走りうる)のセッションまで
 * 巻き添えにしていた(2026-09-16 AC1検証で再現)。
 *
 * 対策(issue #1329本文の案A): KeycloakはOIDCのセッション管理仕様により、発行する
 * アクセストークン/IDトークンに発行元セッションのID(`sid`クレーム)を含める。この値は
 * Admin REST APIの`GET /admin/realms/{realm}/users/{id}/sessions`が返す各セッションの
 * `id`と同じであり、`DELETE /admin/realms/{realm}/sessions/{session}`
 * (kcadm経由では`kcadm.sh delete sessions/{sid}`)で個別セッションだけを終了できる。
 * 実機(Keycloak 26.7.2、`docker exec lbs-keycloak`)で以下を確認済み:
 *   - 既に認証済みのkcadmセッションが持つアクセストークンを復号すると`sid`クレームが
 *     実際に含まれている。
 *   - `kcadm.sh delete sessions/<存在しないid>`は"Resource not found for url:
 *     .../sessions/<id>"を返す(エンドポイント自体は生きており、指定したidの
 *     セッションが無いという応答)。
 *
 * このテストは、そのトークンから`sid`を取り出す純粋関数`extractKeycloakSessionId`
 * (`./support/keycloakSession`)を検証する。`sid`さえ取り出せれば、
 * `kcadm.sh delete sessions/{sid}`は個別セッションのログアウトそのもの
 * (実機で存在確認済み)なので、ここより先はdocker/Keycloakに依存しない。
 *
 * `apps/web/e2e/**` は通常jestの対象外(jest.config.tsのtestMatch、#994)なので、
 * kcadm.test.ts / retryClick.test.ts と同様にCLI引数で明示的に上書きして実行する
 * (実装報告に実行結果を記録):
 *
 *   npx jest --config jest.config.ts \
 *     --testPathIgnore(略・対象除外オプション名)='/node_modules/|/\.next/' \
 *     --testMatch='**\/e2e/keycloakSession.test.ts' \
 *     e2e/keycloakSession.test.ts
 *
 * `keycloakSession.ts`自体は`apps/web/e2e/**`にあるため、CLAUDE.md → Test-First
 * Implementationのコミット分類上は「テストコード」であり(.claude/hooks/paths.pyの
 * TEST_PATTERNS)、C1/C2カバレッジの数値目標(90%)の対象外(90%目標はプロダクション
 * コードにのみ課される)。
 *
 * このファイルを`e2e/support/`ではなく`e2e/`直下(kcadm.test.ts / retryClick.test.tsと
 * 同じ場所)に置くのは、`playwright.config.ts`の`steps: ['e2e/steps/**\/*.ts',
 * 'e2e/support/**\/*.ts']`が`e2e/support/**`配下の全`.ts`を無条件にステップ定義として
 * `require`するため。`e2e/support/`に置くと`bddgen`が本ファイルをロードしようとして
 * `ReferenceError: describe is not defined`で落ちる(jestのグローバルはbddgenの実行時に
 * 存在しない)。実装対象の`keycloakSession.ts`自体は`e2e/support/`に置いて構わない
 * (他のsupportモジュールと同様、副作用なくrequireできるプレーンなモジュールのため)。
 */

import { extractKeycloakSessionId } from './support/keycloakSession';

/** 署名検証を伴わない、ペイロードだけが本物と同じ形のJWT文字列を組み立てる。 */
function fakeJwt(payload: Record<string, unknown>): string {
  const base64url = (obj: Record<string, unknown>) => Buffer.from(JSON.stringify(obj)).toString('base64url');
  return `${base64url({ alg: 'RS256', typ: 'JWT' })}.${base64url(payload)}.fake-signature-for-unit-test`;
}

describe('extractKeycloakSessionId(issue #1329: 個別セッションログアウトのためのsid抽出)', () => {
  test('sidクレームを持つトークンから、そのままの値を取り出す', () => {
    const token = fakeJwt({ sub: 'user-1', sid: '4AZOr5G5QTvi36s6qqHicEs6' });

    expect(extractKeycloakSessionId(token)).toBe('4AZOr5G5QTvi36s6qqHicEs6');
  });

  test('sidクレームが無いトークンでは例外を投げる(ユーザー全体へフォールバックしない)', () => {
    const token = fakeJwt({ sub: 'user-1' });

    expect(() => extractKeycloakSessionId(token)).toThrow(/sid/);
  });

  test('sidクレームが空文字のトークンでも例外を投げる', () => {
    const token = fakeJwt({ sub: 'user-1', sid: '' });

    expect(() => extractKeycloakSessionId(token)).toThrow(/sid/);
  });

  test('sidクレームが文字列でないトークンでも例外を投げる', () => {
    const token = fakeJwt({ sub: 'user-1', sid: 12345 });

    expect(() => extractKeycloakSessionId(token)).toThrow(/sid/);
  });
});
