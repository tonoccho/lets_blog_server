/**
 * issue #1295フォローアップ: `fetchAccessToken`のワーカープロセスをまたいだ排他を検証するための
 * 子プロセス側エントリ。
 *
 * `token-cross-process.test.ts` がesbuildでこのファイルをバンドルし、実際に複数のNode
 * プロセスとして同時起動する。Playwrightのワーカーはそれぞれ別プロセスであり、
 * `fetchAccessToken`内のモジュール変数(メモリキャッシュ・進行中リクエストのMap)は
 * プロセスごとに独立しているため、単一プロセス内のテストではこの排他は検証できない
 * (レビュー指摘、issue #1295 note 7363)。これは実プロセスを複数立てて検証するための
 * 専用フィクスチャであり、jestのテストスイートそのものではない
 * (`*.test.ts`ではないため、jestからは拾われない)。
 *
 * 標準入力からJSON({ baseURL, email, password })を受け取り、`fetchAccessToken`が
 * 返したトークン文字列を標準出力へそのまま書き出す。
 */
import type { APIRequestContext } from '@playwright/test';
import { fetchAccessToken } from './token-cache';

interface FixtureInput {
  baseURL: string;
  email: string;
  password: string;
}

/**
 * `fetchAccessToken`が要求するのは`request.post(path, { form })`だけなので、
 * fetchでその最小限を満たす簡易実装を作る(APIRequestContext型注釈のみ、実際に
 * Playwright本体を実行時に読み込むわけではない)。
 */
function buildMinimalRequestContext(baseURL: string): APIRequestContext {
  return {
    post: async (urlPath: string, options: { form: Record<string, string> }) => {
      const response = await fetch(`${baseURL}${urlPath}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams(options.form).toString(),
      });
      const text = await response.text();
      return {
        ok: () => response.ok,
        status: () => response.status,
        json: async () => JSON.parse(text) as unknown,
        text: async () => text,
      };
    },
  } as unknown as APIRequestContext;
}

async function main(): Promise<void> {
  const input = JSON.parse(process.argv[2]) as FixtureInput;
  const request = buildMinimalRequestContext(input.baseURL);
  const token = await fetchAccessToken(request, input.email, input.password);
  process.stdout.write(token);
}

main().catch((error) => {
  process.stderr.write(String(error));
  process.exit(1);
});
