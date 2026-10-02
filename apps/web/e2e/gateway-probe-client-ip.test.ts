/**
 * @jest-environment node
 *
 * issue #1420: `nextProbeClientIp`(`support/gateway.ts`)の採番を固定する。
 *
 * ## なぜこのテストが要るのか
 *
 * この仕組みは #995 で「異なるWorkerが同じ合成IPを選び、無関係なシナリオが429で落ちる」
 * (#943のQAで2回観測)を解決するために作られたが、**単体テストが無かった。**
 *
 * #1420 で、レート制限のシナリオが使っていた独自の採番(`process.pid % 250` 起点、#1132)を
 * 捨ててこちらへ一本化した。同じ問題に2つの仕組みを置かないためだが、その結果として
 * **この関数の正しさに依存する範囲が広がった**ので、ここで固定する。
 *
 * pid起点の採番が不十分だった理由(実測、#1420 の note_9566):
 *
 *   pid=3883333 → pid%250=83 → 203.0.113.84 から
 *   pid=3883339 → pid%250=89 → 203.0.113.90 から
 *   pid=3883345 → pid%250=95 → 203.0.113.96 から
 *
 * **Playwrightのワーカーのpidは6しか離れていない。**6回呼べば隣のワーカーの開始位置に
 * 到達し、pid差が250の倍数なら1回目から衝突する。`nextProbeClientIp` は状態ファイルと
 * ロックで全プロセスの払い出しを直列化するため、この問題を構造的に持たない。
 *
 * `apps/web/e2e/**` は jest の既定実行から除外されている(`jest.config.ts` の
 * 対象除外オプション)ため、他のe2e単体テストと同様に明示的な上書きで実行する:
 *
 *   npx jest --config jest.config.ts \
 *     --testPathIgnore(略・対象除外オプション名)='/node_modules/|/\.next/' \
 *     --testMatch='**\/e2e/gateway-probe-client-ip.test.ts'
 *
 * これらが自動実行されていないこと自体は **#1421** として別途起票した。
 */

import { existsSync, readFileSync, unlinkSync, utimesSync, writeFileSync } from 'node:fs';
import { nextProbeClientIp, probeStateFilePath } from './support/gateway';

const STATE_FILE = probeStateFilePath(process.ppid);
/** 1回の実行での払い出し総数(issue #1422)。連番(STATE_FILE)と違い、一周しても戻らない。 */
const COUNT_FILE = `${STATE_FILE}.count`;

function removeState(): void {
  for (const path of [STATE_FILE, `${STATE_FILE}.lock`, COUNT_FILE]) {
    if (existsSync(path)) {
      unlinkSync(path);
    }
  }
}

describe('nextProbeClientIp(issue #1420 で採番を一本化した)', () => {
  afterEach(() => {
    removeState();
  });

  test('呼ぶたびに別のIPを返す', () => {
    writeFileSync(STATE_FILE, '0');
    const ips = Array.from({ length: 50 }, () => nextProbeClientIp());
    expect(new Set(ips).size).toBe(50);
  });

  /**
   * 本Issueの本題。**別プロセスが払い出した分を引き継ぐ**ので、同じIPを二重に出さない。
   * 状態ファイルへ直接書くことで「別のWorkerが既に120番まで使った」状況を再現する。
   */
  test('別プロセスが進めた連番を引き継ぎ、同じIPを二度出さない', () => {
    writeFileSync(STATE_FILE, '120');
    const first = nextProbeClientIp();
    expect(first).toBe('198.51.100.122');

    // 別プロセスがさらに進めた、という状況。
    writeFileSync(STATE_FILE, '200');
    expect(nextProbeClientIp()).toBe('198.51.100.202');
  });

  test('生成されるのはTEST-NET-2(198.51.100.0/24、RFC 5737)の範囲だけ', () => {
    writeFileSync(STATE_FILE, '0');
    for (let i = 0; i < 60; i += 1) {
      const ip = nextProbeClientIp();
      expect(ip).toMatch(/^198\.51\.100\.\d{1,3}$/);
      const host = Number(ip.split('.')[3]);
      expect(host).toBeGreaterThanOrEqual(1);
      expect(host).toBeLessThanOrEqual(250);
    }
  });

  /**
   * `CONTENT_CACHE_PROBE_CLIENT_IP`(`198.51.100.251`)は連番と重ならない固定値として
   * `cross-cutting.steps.ts` が使っている。連番が251に達すると衝突する。
   */
  test('連番は251に達しない(固定で使う198.51.100.251と衝突しない)', () => {
    writeFileSync(STATE_FILE, '248');
    const ips = [nextProbeClientIp(), nextProbeClientIp(), nextProbeClientIp()];
    expect(ips).not.toContain('198.51.100.251');
    // 250で一周して先頭へ戻る。
    expect(ips).toEqual(['198.51.100.250', '198.51.100.1', '198.51.100.2']);
  });

  test('ロックファイルを残さない', () => {
    writeFileSync(STATE_FILE, '0');
    nextProbeClientIp();
    expect(existsSync(`${STATE_FILE}.lock`)).toBe(false);
  });

  /**
   * issue #1422: 連番は250で一周して黙って使用中のIPを再配布していた。払い出し総数を別ファイルに
   * 数え、プールを使い切ったら例外で止める(#1420の自作案と同じ設計)。
   */
  test('払い出し総数を数える', () => {
    writeFileSync(STATE_FILE, '0');
    nextProbeClientIp();
    nextProbeClientIp();
    expect(readFileSync(COUNT_FILE, 'utf8').trim()).toBe('2');
  });

  test('プールの250件を使い切った次の払い出しは、黙って一周せず例外で止まる', () => {
    writeFileSync(STATE_FILE, '10');
    writeFileSync(COUNT_FILE, '250');
    expect(() => nextProbeClientIp()).toThrow(/250/);
    // 連番は進めない(使用中のIPを配らない)。
    expect(readFileSync(STATE_FILE, 'utf8').trim()).toBe('10');
  });

  test('249件目までは払い出せる', () => {
    writeFileSync(STATE_FILE, '10');
    writeFileSync(COUNT_FILE, '249');
    expect(nextProbeClientIp()).toBe('198.51.100.12');
  });

  test('一周で例外になってもロックファイルを残さない', () => {
    writeFileSync(STATE_FILE, '10');
    writeFileSync(COUNT_FILE, '250');
    expect(() => nextProbeClientIp()).toThrow();
    expect(existsSync(`${STATE_FILE}.lock`)).toBe(false);
  });

  test('数えたファイルが壊れていても0件として扱う', () => {
    writeFileSync(STATE_FILE, '10');
    writeFileSync(COUNT_FILE, 'garbage');
    expect(nextProbeClientIp()).toBe('198.51.100.12');
    expect(readFileSync(COUNT_FILE, 'utf8').trim()).toBe('1');
  });

  test('2時間前に更新された古い総数(PID再利用の残り)は0件として扱う', () => {
    writeFileSync(STATE_FILE, '10');
    writeFileSync(COUNT_FILE, '250');
    const old = new Date(Date.now() - 2 * 60 * 60 * 1000);
    utimesSync(COUNT_FILE, old, old);
    expect(nextProbeClientIp()).toBe('198.51.100.12');
    expect(readFileSync(COUNT_FILE, 'utf8').trim()).toBe('1');
  });
});
