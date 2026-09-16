/**
 * @jest-environment node
 *
 * issue #1295フォローアップ(レビュー指摘、note 7363): `fetchAccessToken`のアカウント単位
 * キャッシュ/同時呼び出しの合流(#1295の最初の実装)はNodeのモジュール単位の状態であり、
 * Playwrightのワーカー(別OSプロセス)をまたいでは共有されない。実際の障害は「複数ワーカー
 * プロセスが起動直後、同じアカウントへほぼ同時にトークンを要求する」ことで起きるため、
 * 単一プロセス内の検証(helpers.test.ts)ではこのギャップを実証できない。
 *
 * このテストは実際に複数のNode**プロセス**(`child_process.spawn`)を同時に起動し、それぞれが
 * 独立に`fetchAccessToken`を呼んでも、モックKeycloakサーバへの実HTTPリクエストが合計で
 * 1本だけになることを検証する。子プロセス側の実処理は`token-cross-process-fixture.ts`
 * (esbuildでバンドルして実行する。TypeScriptのまま`node`へ直接渡せないため)。
 *
 * `apps/web/e2e/**`はhelpers.test.tsと同様にjestの対象外なので、明示的なCLI呼び出しで走らせる
 * (実装報告に実行結果を記録):
 *
 *   npx jest --config jest.config.ts \
 *     --testPathIgnore(略・対象除外オプション名)='/node_modules/|/\.next/' \
 *     --testMatch='**\/e2e/token-cross-process.test.ts' \
 *     e2e/token-cross-process.test.ts
 *
 * この検証は実際のdocker compose / Keycloakを必要としない(ローカルにhttp.Serverでモックを
 * 立てる)。実物のKeycloakに対する確認(AC1)は実装報告に別途記載する。
 */

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import * as esbuild from 'esbuild';

const FIXTURE_ENTRY = path.join(__dirname, 'token-cross-process-fixture.ts');

/** フィクスチャをCJSへバンドルする(子プロセスはNodeへ直接渡すため、TSのままでは実行できない)。 */
function bundleFixture(outfile: string): void {
  esbuild.buildSync({
    entryPoints: [FIXTURE_ENTRY],
    outfile,
    bundle: true,
    platform: 'node',
    format: 'cjs',
    // フィクスチャは`./token-cache`のみに依存する(`@playwright/test`はAPIRequestContextの
    // 型としてしか参照されず、`import type`なので実行時importは無い)。そのため
    // externalの指定なしで自己完結したバンドルにできる。
  });
}

/** 遅延つきでトークンを返し、受けたリクエスト数を数えるモックKeycloakトークンエンドポイント。 */
function startMockKeycloak(delayMs: number): Promise<{ url: string; requestCount: () => number; close: () => Promise<void> }> {
  let count = 0;
  const server = http.createServer((req, res) => {
    if (req.method === 'POST') {
      count += 1;
      setTimeout(() => {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ access_token: `token-from-mock-${count}`, expires_in: 300 }));
      }, delayMs);
    } else {
      res.writeHead(404);
      res.end();
    }
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      const port = typeof address === 'object' && address ? address.port : 0;
      resolve({
        url: `http://127.0.0.1:${port}`,
        requestCount: () => count,
        close: () => new Promise((r) => server.close(() => r())),
      });
    });
  });
}

function runFixtureProcess(
  bundlePath: string,
  input: { baseURL: string; email: string; password: string },
  env: NodeJS.ProcessEnv
): Promise<string> {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [bundlePath, JSON.stringify(input)], {
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => {
      stdout += chunk.toString();
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk.toString();
    });
    child.on('close', (code) => {
      if (code === 0) {
        resolve(stdout);
      } else {
        reject(new Error(`子プロセスが失敗しました(code=${code}): ${stderr}`));
      }
    });
  });
}

describe('fetchAccessTokenの複数プロセスをまたいだ排他(issue #1295フォローアップ)', () => {
  test(
    '4つの独立したNodeプロセスが同時に同じアカウントのトークンを要求しても、実HTTPリクエストは1本だけになる',
    async () => {
      const workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lbs-e2e-token-xprocess-'));
      // バンドル出力はapps/web配下に置く必要がある。子プロセスのrequire解決は
      // 要求元ファイルの位置からnode_modulesを辿るため(cwdではない)、/tmp配下では
      // `@playwright/test`(externalにした依存)を解決できない。テスト終了時に必ず削除する。
      const bundlePath = path.join(__dirname, `.token-cross-process-bundle-${process.pid}.js`);
      bundleFixture(bundlePath);

      // Keycloakの応答を意図的に遅延させ、複数プロセスの起動タイミングが重なるようにする
      // (起動直後にワーカーが一斉にトークンを要求する、実際の障害モードを模す)。
      const mock = await startMockKeycloak(300);
      try {
        const env = { ...process.env, E2E_TOKEN_CACHE_DIR: workDir };
        const input = { baseURL: mock.url, email: 'e2e-test@letsblog.local', password: 'dummy' };

        const results = await Promise.all(
          Array.from({ length: 4 }, () => runFixtureProcess(bundlePath, input, env))
        );

        expect(mock.requestCount()).toBe(1);
        const distinctTokens = new Set(results);
        expect(distinctTokens.size).toBe(1);
      } finally {
        await mock.close();
        fs.rmSync(workDir, { recursive: true, force: true });
        fs.rmSync(bundlePath, { force: true });
      }
    },
    30_000
  );
});
