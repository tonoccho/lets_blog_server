/**
 * @jest-environment node
 *
 * issue #1328: `apps/web/e2e/steps/`の10ファイル(`adminSelfGuard.steps.ts`・
 * `auth.steps.ts`・`avatarUpload.steps.ts`・`bruteForceLockout.steps.ts`・
 * `projectMember.steps.ts`・`publishAuthor.steps.ts`・`roleManagement.steps.ts`・
 * `timezoneOverride.steps.ts`・`userDeactivation.steps.ts`・`userManagement.steps.ts`)が、
 * `kcadm`/`kcadmLogin`/`KCADM_BIN`をそれぞれ独立に複製していた(相互にimportし合っていない)。
 * `kcadm.sh`はコンテナ内の単一ファイル(`/opt/keycloak/.keycloak/kcadm.config`)に
 * セッションを保存するため、複数のPlaywrightワーカーがほぼ同時に`docker exec ... kcadm.sh`を
 * 呼ぶと"Failed to get lock on ...kcadm.config"で失敗しうる。
 *
 * `apps/web/e2e/**` は通常jestの対象外(jest.config.tsのtestMatchと対象除外設定、#994)なので、
 * `account-lock.test.ts`と同様にCLI引数で明示的に上書きして実行する
 * (実装報告に実行結果を記録):
 *
 *   npx jest --config jest.config.ts \
 *     --testPathIgnore(略・対象除外オプション名)='/node_modules/|/\.next/' \
 *     --testMatch='**\/e2e/kcadm.test.ts' \
 *     e2e/kcadm.test.ts
 *
 * `kcadm.ts`自体は`apps/web/e2e/**`にあるため、CLAUDE.md → Test-First Implementationの
 * コミット分類上は「テストコード」であり(.claude/hooks/paths.tsのTEST_PATTERNS)、
 * C1/C2カバレッジの数値目標(90%)の対象外。
 *
 * AC1は静的検査(ソース走査)、AC2は複数プロセスをまたいだ実際の競合再現で検証する。
 * AC2は実際のdocker/Keycloakには依存しない — `docker`をテストが用意する偽スクリプト
 * (コンテナ内`kcadm.config`のファイルロック競合をflockで模す)に差し替えたPATH環境で
 * 子プロセスを動かす(`token-cross-process.test.ts`と同じ、esbuildで子プロセスをバンドルして
 * 実際に複数のNodeプロセスとして同時起動する手法)。
 *
 * 【2026-09-22 差し戻し】レビューの差し戻しに従い、ホスト側`flock`によるクロスプロセス
 * 直列化は不採用とした(issue #1328のRequirements 2は元々任意、Out of Scopeにも
 * 「必須要件としないこと」と明記されている)。呼び出し元が実機で計測したところ
 * (`identity/`配下29シナリオ、既定の並列度4ワーカー)、直列化はシナリオの所要時間を
 * 軒並み押し上げ、`@mode:serial`の`project-members.feature`のように連鎖的なタイムアウトを
 * 新たに引き起こした一方、`Failed to get lock`自体は直列化の有無に関わらず0件だった。
 * 対策はRequirements 1(#1295の再試行を10ファイル分の呼び出しに揃える。`execKcadmWithRetry`)
 * のみとし、以下の「ホスト側flockは実装しない」静的検査と、AC2の「対策後」再現テストの
 * 期待値(再試行だけで吸収される)で検証する。
 */

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import * as esbuild from 'esbuild';

const STEPS_DIR = path.join(__dirname, 'steps');

describe('AC1: kcadmの実装が共有モジュールへ一本化されている(issue #1328)', () => {
  function stepsFiles(): string[] {
    return fs.readdirSync(STEPS_DIR).filter((name) => name.endsWith('.steps.ts'));
  }

  test('steps配下のどのファイルも、ローカルなKCADM_BIN宣言・kcadm/kcadmLogin関数の複製を持たない', () => {
    const files = stepsFiles();
    // 将来ファイルが増えても効くよう、10という数をハードコードせず「1件以上ある」ことだけ確かめる。
    expect(files.length).toBeGreaterThan(0);

    const offenders: string[] = [];
    for (const file of files) {
      const content = fs.readFileSync(path.join(STEPS_DIR, file), 'utf-8');
      const hasLocalConst = /\bconst KCADM_BIN\b/.test(content);
      const hasLocalKcadmFn = /\bfunction kcadm\(/.test(content);
      const hasLocalKcadmLoginFn = /\bfunction kcadmLogin\(/.test(content);
      if (hasLocalConst || hasLocalKcadmFn || hasLocalKcadmLoginFn) {
        offenders.push(file);
      }
    }
    expect(offenders).toEqual([]);
  });

  test('kcadm/kcadmLoginを使うファイルは共有モジュール(../kcadm)からimportしている', () => {
    const files = stepsFiles();
    const offenders: string[] = [];
    for (const file of files) {
      const content = fs.readFileSync(path.join(STEPS_DIR, file), 'utf-8');
      const usesKcadm = /\bkcadm\(|\bkcadmLogin\(/.test(content);
      const importsSharedModule = /from ['"]\.\.\/kcadm['"]/.test(content);
      if (usesKcadm && !importsSharedModule) {
        offenders.push(file);
      }
    }
    expect(offenders).toEqual([]);
  });
});

describe('kcadm.tsはホスト側flockによるクロスプロセス直列化を実装しない(2026-09-22 差し戻し、issue #1328)', () => {
  test('kcadm.tsのソースに、ホスト側flock獲得/解放やロックファイルパス解決が存在しない', () => {
    const content = fs.readFileSync(path.join(__dirname, 'kcadm.ts'), 'utf-8');
    // 経緯を説明するコメント中の"flock"という語自体は許容し(不採用の理由を書き残すため)、
    // 実際に`flock`コマンドを起動する呼び出しと、専用のロックファイル/関数だけを狙い撃ちする。
    expect(content).not.toMatch(/execFileSync\(\s*['"]flock['"]/);
    expect(content).not.toMatch(/KCADM_LOCK_FILE/);
    expect(content).not.toMatch(/acquireKcadmHostLock|releaseKcadmHostLock/);
  });
});

describe('AC2: 複数プロセスから同時にkcadmを呼んでもロック競合("Failed to get lock")で失敗しない(issue #1328)', () => {
  const FAKE_DOCKER_HOLD_SECONDS = '0.3';
  const PROCESS_COUNT = 6;

  /** コンテナ内`kcadm.config`のファイルロック競合を模す偽の`docker`実行ファイルを作る。 */
  function writeFakeDockerBin(binDir: string): void {
    const dockerPath = path.join(binDir, 'docker');
    fs.writeFileSync(
      dockerPath,
      [
        '#!/usr/bin/env bash',
        'set -uo pipefail',
        'exec 9>>"${FAKE_KCADM_LOCK_FILE}"',
        'if flock -n 9; then',
        '  sleep "${FAKE_KCADM_HOLD_SECONDS:-0.3}"',
        '  flock -u 9',
        '  echo ok',
        '  exit 0',
        'else',
        '  echo "Failed to get lock on /opt/keycloak/.keycloak/kcadm.config" >&2',
        '  exit 1',
        'fi',
        '',
      ].join('\n')
    );
    fs.chmodSync(dockerPath, 0o755);
  }

  function bundleFixture(entry: string, outfile: string): void {
    esbuild.buildSync({
      entryPoints: [entry],
      outfile,
      bundle: true,
      platform: 'node',
      format: 'cjs',
    });
  }

  interface ProcessResult {
    code: number;
    stdout: string;
    stderr: string;
  }

  function runFixtureProcess(
    bundlePath: string,
    coordDir: string,
    index: number,
    env: NodeJS.ProcessEnv
  ): Promise<ProcessResult> {
    return new Promise((resolve) => {
      const child = spawn(process.execPath, [bundlePath, coordDir, String(index)], {
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
        resolve({ code: code ?? -1, stdout, stderr });
      });
    });
  }

  async function waitForAllReady(coordDir: string, count: number, timeoutMs: number): Promise<void> {
    const started = Date.now();
    for (;;) {
      const ready = Array.from({ length: count }, (_, i) => fs.existsSync(path.join(coordDir, `ready-${i}`)));
      if (ready.every(Boolean)) return;
      if (Date.now() - started > timeoutMs) {
        throw new Error(`${count}個のready印を${timeoutMs}ms以内に確認できませんでした`);
      }
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
  }

  async function runScenario(
    fixtureEntry: string,
    extraEnv: Record<string, string>,
    processCount: number = PROCESS_COUNT
  ): Promise<ProcessResult[]> {
    const workDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lbs-e2e-kcadm-xprocess-'));
    const binDir = path.join(workDir, 'bin');
    fs.mkdirSync(binDir);
    writeFakeDockerBin(binDir);
    const coordDir = path.join(workDir, 'coord');
    fs.mkdirSync(coordDir);
    const bundlePath = path.join(__dirname, `.kcadm-xprocess-bundle-${path.basename(fixtureEntry)}-${process.pid}.js`);
    bundleFixture(fixtureEntry, bundlePath);

    try {
      const env: NodeJS.ProcessEnv = {
        ...process.env,
        PATH: `${binDir}:${process.env.PATH}`,
        FAKE_KCADM_LOCK_FILE: path.join(workDir, 'fake-kcadm-config.lock'),
        FAKE_KCADM_HOLD_SECONDS: FAKE_DOCKER_HOLD_SECONDS,
        ...extraEnv,
      };

      const runs = Array.from({ length: processCount }, (_, i) => runFixtureProcess(bundlePath, coordDir, i, env));
      await waitForAllReady(coordDir, processCount, 10_000);
      fs.writeFileSync(path.join(coordDir, 'go'), '');
      return await Promise.all(runs);
    } finally {
      fs.rmSync(workDir, { recursive: true, force: true });
      fs.rmSync(bundlePath, { force: true });
    }
  }

  test(
    '対策前(ホスト側の直列化も再試行も無い旧実装)は、同時実行で"Failed to get lock"が実際に発生する',
    async () => {
      const results = await runScenario(path.join(__dirname, 'kcadm-cross-process-fixture-old.ts'), {});

      const lockFailures = results.filter((r) => r.code !== 0 && r.stderr.includes('Failed to get lock'));
      expect(lockFailures.length).toBeGreaterThan(0);
    },
    30_000
  );

  // 対策後(再試行のみ)は、KCADM_LOCK_RETRY_ATTEMPTS(5)×KCADM_LOCK_RETRY_DELAY_MS(200ms)という
  // 有限の予算で吸収する。ホスト側flock(無制限にポーリングし続ける)と異なり、この予算を
  // 超える競合は原理的に吸収しきれない: PROCESS_COUNT個が同時にFAKE_DOCKER_HOLD_SECONDS秒ずつ
  // 順番にロックを取り合うと、最後の1個が確保できるまで最悪(PROCESS_COUNT-1)×HOLDかかるが、
  // 個々のプロセスの再試行機会は最大5回・800ms分の間隔しかない。6プロセス×0.3秒(実測で
  // 試したところ確実に失敗する)は、この予算を超える人為的な最悪ケースであり、実機で計測された
  // 実際の競合(4ワーカー、`Failed to get lock`は再試行ありのファイルでは0件)より遥かに過酷。
  // ここでは「有限の再試行予算内に収まる現実的な競合」を代表する3プロセスで検証する。
  const RETRY_ABSORBABLE_PROCESS_COUNT = 3;

  test(
    '対策後(共有モジュール`kcadm.ts`、"Failed to get lock"検知時の再試行のみ)は、' +
      '再試行予算内の競合であれば、同時実行しても最終的に失敗しない(2026-09-22差し戻し: ホスト側直列化は無い)',
    async () => {
      const results = await runScenario(
        path.join(__dirname, 'kcadm-cross-process-fixture-new.ts'),
        {},
        RETRY_ABSORBABLE_PROCESS_COUNT
      );

      expect(results.every((r) => r.code === 0)).toBe(true);
    },
    30_000
  );
});
