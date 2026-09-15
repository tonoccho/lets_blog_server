/**
 * `nextProbeClientIp`(`./gateway.ts`)の並行呼び出し回帰テスト(issue #995)。
 *
 * AC1(`CI` 未設定・Playwright既定の並列Worker数で `--project=at-main --grep "@cross-cutting"`
 * を繰り返し実行しても、Worker間IP衝突による429が起きないこと)を、実際のdockerスタックを
 * 起動せずに検証するための永続化された回帰テスト。
 *
 * jest はこのファイルを含む `e2e/` 配下をテスト対象から除外する設定になっている
 * (`jest.config.ts`、issue #994)。この除外は「playwright-bddの生成物を二重に拾わない」
 * ための意図的な設計であり、対象を広げるには `jest.config.ts` の変更が要る。本Issueのスコープは
 * `gateway.ts`(と`nextProbeClientIp`の利用箇所)に限られ、`jest.config.ts` は対象外なので、
 * 既存の自動テストスイートに配線する代わりに、このファイル自身をエントリポイントとして
 * 直接実行できる自己完結したセルフテストとしてここに置く(コミットされ、下記の通り再実行可能)。
 *
 * ## なぜ `gateway.ts` から分けたか(issue #995 QA対応)
 *
 * 当初このセルフテストは `gateway.ts` 自身の末尾に置き、`import.meta.url` で「直接実行された
 * 場合のみ動く」ガードを付けていた。`import.meta` は**トップレベルで参照しただけでも**構文として
 * ESM専用であり、CommonJS向けにコンパイル/ロードされる経路では**ガードの条件が評価される前に
 * パースの時点で** `SyntaxError` になる。`playwright-bdd` の `bddgen` はステップ定義
 * (`e2e/steps/**`)から辿れるモジュール(`gateway.ts` を含む)をCJS互換の `require()` 経路で
 * 読み込むため、`gateway.ts` に `import.meta` が残っていると `bddgen` 自体が丸ごと壊れ、
 * `npm run test:at` 等の受け入れテスト系スクリプトが全滅する(QAで発覚)。
 *
 * `playwright.config.ts` の `bddConfig.steps` は `e2e/steps` 配下と `e2e/support` 配下の
 * 全 `.ts` ファイルを対象にしたグロブであり、`e2e/support` 配下は**ステップ定義から import
 * されているかに関わらず** `bddgen` の
 * 読み込み対象になる(グロブそのものが対象を決めるのであって、requireグラフの到達可否ではない)。
 * そのため、`e2e/support/gateway.ts` に置いたままでは `import.meta` が残る限りいずれにせよ
 * `bddgen` を壊す。このファイルをそのグロブの外側 —`e2e/support/` でも `e2e/steps/` でもない
 * `e2e/dev-scripts/` — に置くことで、`bddgen` の読み込み対象から完全に外れ、`import.meta.url`
 * を自由に使える。`gateway.ts` 側は `nextProbeClientIp` と `probeStateFilePath` をexportする
 * だけの、`import.meta` を含まない純粋なCJS互換モジュールに戻す。
 *
 * `Worker` プロセスを模した複数の**子プロセス**を同時に起動し、それぞれが `nextProbeClientIp`
 * を繰り返し呼ぶ。全ての子プロセスの `process.ppid` はこのセルフテスト自身のPIDで揃うため、
 * `gateway.ts` の `probeStateFilePath` により実際のPlaywright実行と同じく状態ファイル・ロックを
 * 共有することになり、Workerプロセスをまたいだ排他制御を実プロセス間で検証できる
 * (呼び出しを直接ループで済ませると同一プロセス内の話になり、#943で実際に起きた
 * 「別プロセスが同時に読み書きする」状況を再現できない)。
 *
 * 実行方法(リポジトリルートから):
 *   node --experimental-strip-types apps/web/e2e/dev-scripts/gatewayProbeIpConcurrencySelfTest.ts
 *
 * 払い出された `PROBE_SELF_TEST_WORKERS × PROBE_SELF_TEST_CALLS_PER_WORKER` 個のIPに重複が
 * 無ければ 0 で終了し、重複があれば非0で終了して重複したIPを標準エラーへ出力する。
 * ロック処理をリファクタする際はこれを実行して回帰していないことを確かめること。
 */
import { spawn } from 'node:child_process';
import { unlinkSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

/**
 * `../support/gateway.ts` を動的 import で読み込む。
 *
 * リテラル文字列の静的 import だと、`tsconfig.json` の `moduleResolution: "bundler"` の下では
 * 拡張子付き specifier(`.ts`)が `TS5097` で拒否される一方、拡張子無しの specifier は
 * `node --experimental-strip-types` の厳密なESM解決で `ERR_MODULE_NOT_FOUND` になる
 * (拡張子の省略はNode ESMの specifier 解決規則では許されない)。文字列連結で specifier を
 * 組み立てることで静的解析の対象から外し、両者を同時に満たす。
 */
async function loadGatewaySupport(): Promise<
  typeof import('../support/gateway.ts')
> {
  return import('../support/gateway' + '.ts');
}

const PROBE_SELF_TEST_WORKERS = 8;
const PROBE_SELF_TEST_CALLS_PER_WORKER = 20;
/** 子プロセスへ渡す目印。このファイル自身を子として再実行する際の分岐に使う。 */
const PROBE_SELF_TEST_WORKER_FLAG = '--probe-self-test-worker';

/** 子プロセス側: `nextProbeClientIp` を指定回数呼び、1行1IPで標準出力へ書き出す。 */
async function runProbeSelfTestWorker(): Promise<void> {
  const { nextProbeClientIp } = await loadGatewaySupport();
  const ips: string[] = [];
  for (let i = 0; i < PROBE_SELF_TEST_CALLS_PER_WORKER; i += 1) {
    ips.push(nextProbeClientIp());
  }
  process.stdout.write(`${ips.join('\n')}\n`);
}

/** 親プロセス側: 子プロセスを並行起動し、払い出されたIPに重複が無いことを検証する。 */
async function runProbeSelfTestOrchestrator(): Promise<void> {
  const { probeStateFilePath } = await loadGatewaySupport();
  // 前回までの実行の残骸(同じPIDが再利用されることは通常ないが、念のため)を掃除し、
  // このセルフテスト単体で完結させる。
  const scopedStateFile = probeStateFilePath(process.pid);
  for (const file of [scopedStateFile, `${scopedStateFile}.lock`]) {
    try {
      unlinkSync(file);
    } catch {
      // 元々存在しないなら何もしない。
    }
  }

  const thisFile = fileURLToPath(import.meta.url);
  const workerRuns = Array.from(
    { length: PROBE_SELF_TEST_WORKERS },
    () => new Promise<string[]>((resolve, reject) => {
      const child = spawn(
        process.execPath, ['--experimental-strip-types', thisFile, PROBE_SELF_TEST_WORKER_FLAG],
        { stdio: ['ignore', 'pipe', 'inherit'] }
      );
      let stdout = '';
      child.stdout.on('data', (chunk: Buffer) => {
        stdout += chunk.toString('utf8');
      });
      child.on('error', reject);
      child.on('close', (code) => {
        if (code !== 0) {
          reject(new Error(`probe self-test worker exited with code ${code}`));
          return;
        }
        resolve(stdout.split('\n').map((line) => line.trim()).filter(Boolean));
      });
    })
  );

  const allIps = (await Promise.all(workerRuns)).flat();
  const expectedCount = PROBE_SELF_TEST_WORKERS * PROBE_SELF_TEST_CALLS_PER_WORKER;
  const uniqueIps = new Set(allIps);
  const duplicates = allIps.filter((ip, index) => allIps.indexOf(ip) !== index);

  if (allIps.length !== expectedCount || uniqueIps.size !== expectedCount) {
    process.stderr.write(
      `probeClientIp並行呼び出しの回帰テストが失敗しました: ${PROBE_SELF_TEST_WORKERS}個の子`
      + `プロセスが計${expectedCount}回呼び出したが、得られたIPは${allIps.length}件`
      + `(うちユニーク${uniqueIps.size}件)でした。重複: ${[...new Set(duplicates)].join(', ')}\n`
    );
    process.exitCode = 1;
    return;
  }
  process.stdout.write(
    `OK: ${PROBE_SELF_TEST_WORKERS}子プロセス × ${PROBE_SELF_TEST_CALLS_PER_WORKER}回の`
    + `nextProbeClientIp呼び出しで重複なし(計${expectedCount}件)\n`
  );
}

const run = process.argv.includes(PROBE_SELF_TEST_WORKER_FLAG)
  ? runProbeSelfTestWorker()
  : runProbeSelfTestOrchestrator();
run.catch((error: unknown) => {
  process.stderr.write(`probe self-test failed: ${String(error)}\n`);
  process.exitCode = 1;
});
